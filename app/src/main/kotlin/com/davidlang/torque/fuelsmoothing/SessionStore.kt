package com.davidlang.torque.fuelsmoothing

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class Sample(
    val t: Long,
    val rawPct: Double,
    val smoothedPct: Double,
    val fuelUsedGal: Double,
    val speed: Double,
    val fill: Boolean = false,
    val ok: Boolean = true,
    val why: String? = null,
    val spdUnit: String = "",
    val mode: String = "",
    val ecu: Boolean = true,
    val fuelRaw: Double? = null,
    val fuelUnit: String = "",
    val lvlSrc: String = "",
    val lvlL: Double? = null,
)

internal class SessionStore(context: Context, private val tank: Int = 1, rawDir: File? = null) {
    private val dir = (rawDir ?: File(
        context.filesDir,
        if (tank <= 1) "sessions" else "sessions-t$tank",
    )).apply { mkdirs() }
    private val snapshotFile = File(
        if (rawDir != null) rawDir.parentFile ?: context.filesDir else context.filesDir,
        if (tank <= 1) "last-session-end.json" else "last-session-end-t$tank.json",
    )

    init {
        if (snapshotFile.isFile) {
            FsLog.i(TAG, "last session-end snapshot (debug only): ${snapshotFile.readText()}")
        }
    }
    private var file: File? = null
    private var rawLog = RawLog()
    var sessionId: String? = null
        private set
    var fuelUsedOriginGal: Double? = null
        private set
    var profileId: String = "default"
        private set
    val samples = mutableListOf<Sample>()

    val isOpen: Boolean get() = file != null

    /**
     * True after a "gap" entry until a later sample shows fuel burned. Derived from the
     * session file itself on open, so a restart that reuses a file cannot stack gap entries.
     */
    var gapOpen: Boolean = false
        private set

    /** Committed JSONL lines not yet appended to [file]; flushed every FLUSH_MS and on close. */
    private val pending = StringBuilder()
    private var lastFlushMs = 0L

    fun open(
        t0: Long = System.currentTimeMillis(),
        profileId: String = this.profileId,
        app: String = "",
        sources: String = "{}",
    ): String {
        if (isOpen) close(t0)
        val id = "${t0}-${UUID.randomUUID().toString().take(8)}"
        sessionId = id
        this.profileId = profileId
        fuelUsedOriginGal = null
        samples.clear()
        rawLog = RawLog()
        val f = File(dir, "$id.jsonl")
        gapOpen = if (f.isFile) gapOpenFromLog(f.readLines()) else false
        file = f
        pending.setLength(0)
        lastFlushMs = 0L
        rawLog.openLine(id, t0, profileId, tank, app, sources)
        drain(t0, force = true)
        return id
    }

    fun relativeFuelUsed(absoluteGal: Double): Double {
        val origin = fuelUsedOriginGal
        if (origin == null) {
            fuelUsedOriginGal = absoluteGal
            return 0.0
        }
        val rel = absoluteGal - origin
        return if (rel < 0.0) 0.0 else rel
    }

    fun append(sample: Sample) {
        if (file == null) return
        val prev = samples.lastOrNull()?.fuelUsedGal
        val dF = if (prev == null) {
            if (sample.fuelUsedGal.isFinite() && sample.fuelUsedGal > 0.0) sample.fuelUsedGal else 0.0
        } else {
            val d = sample.fuelUsedGal - prev
            if (d.isFinite() && d > 0.0) d else 0.0
        }
        rawLog.addDelta(dF)
        if (dF > 0.0) gapOpen = false
        rawLog.sample(
            t = sample.t,
            lvl = sample.rawPct,
            lvlSrc = sample.lvlSrc,
            fuelRaw = sample.fuelRaw,
            lvlL = sample.lvlL,
            fuelUnit = sample.fuelUnit,
            spd = sample.speed,
            spdUnit = sample.spdUnit,
            ecu = sample.ecu,
            ok = sample.ok,
            why = sample.why,
            mode = if (sample.fill && sample.mode.isEmpty()) "FILL" else sample.mode,
            sm = sample.smoothedPct,
        )
        drain(sample.t, force = false)
        samples.add(sample)
    }

    /** Writes one gap entry unless one is already open (no burn since). Returns true if written. */
    fun markGap(t: Long, dtSec: Double): Boolean {
        if (file == null || gapOpen) return false
        mark("gap", t, "\"dtSec\":${"%.1f".format(java.util.Locale.ROOT, dtSec)}")
        gapOpen = true
        return true
    }

    /** Marker only. fuelGal stays where the last sample left it. */
    fun mark(event: String, t: Long, extra: String = "") {
        if (file == null) return
        rawLog.event(event, t, extra)
        drain(t, force = false)
    }

    /**
     * Append-only writer. Only lines RawLog has committed are written (takeNew); the open
     * identical-level run stays in RawLog's memory until it closes or latches as frozen, so
     * no line on disk is ever rewritten. Appends at most every FLUSH_MS, and always when forced.
     */
    /** Writes buffered samples now (fill-time chunk update reads the file). */
    fun flush() = drain(System.currentTimeMillis(), true)

    private fun drain(nowMs: Long, force: Boolean) {
        val f = file ?: return
        for (line in rawLog.takeNew()) pending.append(line).append('\n')
        if (pending.isEmpty()) return
        if (!force && nowMs - lastFlushMs < FLUSH_MS) return
        runCatching { f.appendText(pending.toString()) }
            .onSuccess {
                pending.setLength(0)
                lastFlushMs = nowMs
            }
            .onFailure { FsLog.w(TAG, "append ${f.name}: ${it.message}") }
    }

    fun close(t: Long = System.currentTimeMillis()): List<Sample> {
        if (file == null) return emptyList()
        val id = sessionId
        val copy = samples.toList()
        rawLog.closeLine(t, copy.size)
        drain(t, force = true)
        if (copy.isNotEmpty()) {
            val last = copy.last()
            snapshotFile.writeText(
                JSONObject()
                    .put("sessionId", id)
                    .put("tank", tank)
                    .put("t", last.t)
                    .put("rawPct", last.rawPct)
                    .put("fuelUsedGal", last.fuelUsedGal)
                    .put("speed", last.speed)
                    .toString(),
            )
        }
        file = null
        sessionId = null
        fuelUsedOriginGal = null
        samples.clear()
        return copy
    }

    fun sessionCount(): Int = dir.listFiles { _, name -> name.endsWith(".jsonl") }?.size ?: 0

    companion object {
        private const val TAG = "FuelSmooth"
        private const val FLUSH_MS = 10_000L
        private val FUEL_GAL = Regex("\"fuelGal\":([-+0-9.eE]+)")

        /** Gap still open at the end of [lines]: a gap event with no fuelGal increase after it. */
        fun gapOpenFromLog(lines: List<String>): Boolean {
            var open = false
            var prev: Double? = null
            for (line in lines) {
                if (line.contains("\"event\":\"gap\"")) {
                    open = true
                    continue
                }
                val fg = FUEL_GAL.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                if (prev != null && fg > prev) open = false
                prev = fg
            }
            return open
        }
    }
}

internal object Units {
    const val GAL_PER_LITER = 0.264172052
    const val LITERS_PER_GAL = 3.785411784

    fun toGallons(value: Double, unit: String): Double {
        val u = unit.lowercase().replace(" ", "")
        return when {
            u.contains("gal") -> value
            u.contains("cc") || u.contains("ml") -> value * 0.000264172052
            else -> value * GAL_PER_LITER
        }
    }

    fun prefersLiters(preferred: String): Boolean {
        val u = preferred.lowercase().replace(" ", "")
        if (u.contains("gal")) return false
        return u == "l" || u.contains("liter") || u.contains("litre") || u.contains("lit")
    }
}
