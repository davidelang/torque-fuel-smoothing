package org.dlang.torque.fordfuel

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
            Log.i(TAG, "last session-end snapshot (debug only): ${snapshotFile.readText()}")
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
        file = f
        rawLog.openLine(id, t0, profileId, tank, app, sources)
        rewrite()
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
        rawLog.sample(
            t = sample.t,
            lvl = sample.rawPct,
            lvlSrc = sample.lvlSrc,
            fuelRaw = sample.fuelRaw,
            fuelUnit = sample.fuelUnit,
            spd = sample.speed,
            spdUnit = sample.spdUnit,
            ecu = sample.ecu,
            ok = sample.ok,
            why = sample.why,
            mode = if (sample.fill && sample.mode.isEmpty()) "FILL" else sample.mode,
            sm = sample.smoothedPct,
        )
        rewrite()
        samples.add(sample)
    }

    /** Marker only. fuelGal stays where the last sample left it. */
    fun mark(event: String, t: Long, extra: String = "") {
        if (file == null) return
        rawLog.event(event, t, extra)
        rewrite()
    }

    private fun rewrite() {
        val f = file ?: return
        f.writeText(rawLog.rendered())
    }

    fun close(t: Long = System.currentTimeMillis()): List<Sample> {
        if (file == null) return emptyList()
        val id = sessionId
        val copy = samples.toList()
        rawLog.closeLine(t, copy.size)
        rewrite()
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
        private const val TAG = "FordFuel"
        private const val LITER_GPH = 0.264172052
        private const val LITER_MEAN_GPH = 8.0

        fun loadSmoothedSamples(file: File): List<Sample>? {
            if (!file.isFile) return null
            val samples = mutableListOf<Sample>()
            var sawSmoothed = false
            runCatching {
                file.forEachLine { line ->
                    if (line.isBlank()) return@forEachLine
                    val o = JSONObject(line)
                    if (o.has("event") || !o.has("t")) return@forEachLine
                    if (o.has("smoothedPct")) sawSmoothed = true
                    val raw = o.optDouble("rawPct", Double.NaN)
                    val sm = o.optDouble("smoothedPct", raw)
                    if (!sm.isFinite()) return@forEachLine
                    samples.add(
                        Sample(
                            t = o.optLong("t"),
                            rawPct = if (raw.isFinite()) raw else sm,
                            smoothedPct = sm,
                            fuelUsedGal = o.optDouble("fuelUsedGal", 0.0),
                            speed = o.optDouble("speed", 0.0),
                            fill = o.optBoolean("fill", false),
                        ),
                    )
                }
            }.onFailure { Log.w(TAG, "jsonl ${file.name}: ${it.message}") }
            if (!sawSmoothed || samples.size < 2) return null
            return scaleIfLiterSession(samples)
        }

        private fun scaleIfLiterSession(samples: List<Sample>): List<Sample> {
            val first = samples.first()
            val last = samples.last()
            val hours = (last.t - first.t) / 3_600_000.0
            if (hours < 1.0 / 60.0) return samples
            val used = last.fuelUsedGal - first.fuelUsedGal
            if (used <= 0.0) return samples
            val gph = used / hours
            if (gph <= LITER_MEAN_GPH) return samples
            Log.i(TAG, "jsonl liter-scale meanGph=${"%.2f".format(gph)} × $LITER_GPH")
            return samples.map { it.copy(fuelUsedGal = it.fuelUsedGal * LITER_GPH) }
        }
    }
}

internal object Units {
    const val GAL_PER_LITER = 0.264172052
    const val LITERS_PER_GAL = 3.785411784
    const val IMPLAUSIBLE_GPH = 8.0

    fun toGallons(value: Double, unit: String): Double {
        val u = unit.lowercase().replace(" ", "")
        return when {
            u.contains("gal") -> value
            u.contains("cc") || u.contains("ml") -> value * 0.000264172052
            else -> value * GAL_PER_LITER
        }
    }

    fun looksLikeGallons(unit: String): Boolean {
        return unit.lowercase().replace(" ", "").contains("gal")
    }

    fun prefersLiters(preferred: String): Boolean {
        val u = preferred.lowercase().replace(" ", "")
        if (u.contains("gal")) return false
        return u == "l" || u.contains("liter") || u.contains("litre") || u.contains("lit")
    }
}
