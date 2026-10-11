package com.davidlang.torque.fuelsmoothing

/**
 * One JSON object per second. fuelGal only increases by a positive dF.
 * A fill event does not change it. Held rows are ok:false. A frozen run
 * (identical level, length >= 60, at least 30 samples with speed > 5)
 * is rewritten why:"frozen" and one event line.
 * Speed is compared as given unless spdUnit contains mph or km.
 */
internal class RawLog {
    var fuelGal: Double = 0.0
        private set

    private val out = ArrayList<String>()
    private var taken = 0
    private val buf = ArrayList<Pend>()
    private var runLvl: Double? = null
    private var latched = false
    private var eventSent = false

    fun addDelta(dF: Double) {
        if (dF.isFinite() && dF > 0.0) fuelGal += dF
    }

    fun openLine(sessionId: String, t0: Long, profileId: String, tank: Int, app: String, sources: String): String {
        val line = buildString {
            append("{\"v\":2,\"event\":\"open\",\"sessionId\":")
            append(jsonString(sessionId))
            append(",\"t0\":")
            append(t0)
            append(",\"profileId\":")
            append(jsonString(profileId))
            append(",\"tank\":")
            append(tank)
            append(",\"app\":")
            append(jsonString(app))
            append(",\"sources\":")
            append(if (sources.isBlank()) "{}" else sources)
            append("}")
        }
        out.add(line)
        return line
    }

    fun sample(
        t: Long,
        lvl: Double,
        lvlSrc: String,
        fuelRaw: Double?,
        fuelUnit: String,
        spd: Double,
        spdUnit: String,
        ecu: Boolean,
        ok: Boolean,
        why: String?,
        mode: String,
        sm: Double,
        lvlL: Double? = null,
    ) {
        val pend = Pend(t, lvl, lvlSrc, fuelRaw, fuelUnit, spd, spdUnit, ecu, ok, why, mode, sm, fuelGal, lvlL)
        if (runLvl != null && pend.lvl != runLvl) closeRun()
        if (runLvl == null) runLvl = pend.lvl
        if (latched) {
            out.add(sampleLine(pend, false, "frozen"))
            return
        }
        buf.add(pend)
        if (buf.size >= FuelV2.FROZEN_S && fastCount(buf) >= FuelV2.FROZEN_S / 2) {
            for (row in buf) out.add(sampleLine(row, false, "frozen"))
            buf.clear()
            if (!eventSent) {
                out.add("{\"event\":\"frozen\",\"t\":$t,\"why\":\"frozen\"}")
                eventSent = true
            }
            latched = true
        }
    }

    /** Fill end, anchor, and other markers. Does not change fuelGal. */
    fun event(name: String, t: Long, extra: String = "") {
        closeRun()
        val tail = if (extra.isBlank()) "" else ",$extra"
        out.add("{\"event\":${jsonString(name)},\"t\":$t$tail}")
    }

    fun closeLine(t: Long, n: Int): String {
        closeRun()
        val line = "{\"event\":\"close\",\"t\":$t,\"n\":$n,\"fuelGal\":${LiterFix.javaDouble(fuelGal)}}"
        out.add(line)
        return line
    }

    fun takeNew(): List<String> {
        if (taken >= out.size) return emptyList()
        val slice = out.subList(taken, out.size).toList()
        taken = out.size
        return slice
    }

    fun finish(): List<String> {
        closeRun()
        return out.toList()
    }

    private fun closeRun() {
        if (!latched) {
            for (row in buf) out.add(sampleLine(row, row.ok, row.why))
        }
        buf.clear()
        latched = false
        eventSent = false
        runLvl = null
    }

    private fun fastCount(rows: List<Pend>): Int {
        var n = 0
        for (row in rows) if (gateSpeed(row.spd, row.spdUnit) > 5.0) n++
        return n
    }

    private fun sampleLine(row: Pend, ok: Boolean, why: String?): String {
        return buildString {
            append("{\"t\":")
            append(row.t)
            append(",\"lvl\":")
            // -1 is the explicit missing-level flag (never 0): not ok, or no finite reading.
            append(if (!ok || !row.lvl.isFinite() || row.lvl < 0.0) "-1" else LiterFix.javaDouble(row.lvl))
            if (row.lvlL != null && row.lvlL.isFinite()) {
                // Raw litres from a litre-reporting source (Toyota 2129), kept so history can be rescaled.
                append(",\"lvlL\":")
                append(LiterFix.javaDouble(row.lvlL))
            }
            append(",\"lvlSrc\":")
            append(jsonString(row.lvlSrc))
            append(",\"fuelRaw\":")
            append(if (row.fuelRaw == null || !row.fuelRaw.isFinite()) "null" else LiterFix.javaDouble(row.fuelRaw))
            append(",\"fuelUnit\":")
            append(jsonString(row.fuelUnit))
            append(",\"fuelGal\":")
            append(LiterFix.javaDouble(row.fuelGal))
            append(",\"spd\":")
            append(numOrNull(row.spd))
            append(",\"spdUnit\":")
            append(jsonString(row.spdUnit))
            append(",\"ecu\":")
            append(if (row.ecu) "true" else "false")
            append(",\"ok\":")
            append(if (ok) "true" else "false")
            append(",\"why\":")
            append(if (why == null) "null" else jsonString(why))
            append(",\"mode\":")
            append(jsonString(row.mode))
            append(",\"sm\":")
            append(numOrNull(row.sm))
            append("}")
        }
    }

    private class Pend(
        val t: Long,
        val lvl: Double,
        val lvlSrc: String,
        val fuelRaw: Double?,
        val fuelUnit: String,
        val spd: Double,
        val spdUnit: String,
        val ecu: Boolean,
        val ok: Boolean,
        val why: String?,
        val mode: String,
        val sm: Double,
        val fuelGal: Double,
        val lvlL: Double? = null,
    )

    companion object {
        fun gateSpeed(spd: Double, unit: String): Double {
            val u = unit.lowercase()
            return when {
                u.contains("km") -> spd
                u.contains("mph") || u.contains("mi/h") -> spd * FuelV2.KM_PER_MILE
                else -> spd
            }
        }

        private fun numOrNull(x: Double): String {
            return if (x.isFinite()) LiterFix.javaDouble(x) else "null"
        }

        private fun jsonString(s: String): String {
            val b = StringBuilder(s.length + 2)
            b.append('"')
            for (ch in s) {
                when (ch) {
                    '\\' -> b.append("\\\\")
                    '"' -> b.append("\\\"")
                    '\n' -> b.append("\\n")
                    '\r' -> b.append("\\r")
                    else -> b.append(ch)
                }
            }
            b.append('"')
            return b.toString()
        }
    }
}
