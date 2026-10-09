package org.dlang.torque.fordfuel

import kotlin.math.abs

/**
 * Legacy liter-as-gallon decision from fix_liters.py.
 * Cutoff is 2026-09-07 16:15 at fixed offset -07:00.
 */
internal object LiterFix {
    const val FACTOR = 0.264172052
    const val CUTOFF_MS = 1_788_822_900_000L
    private const val IDLE_LITER_GPH = 2.0
    private const val MOVING_LITER_GPH = 6.0
    private val TOKEN = Regex("""("fuelUsedGal":)(-?[0-9.]+(?:[eE][-+]?[0-9]+)?)""")
    private val NUM = Regex("""-?[0-9.]+(?:[eE][-+]?[0-9]+)?""")

    enum class Action { CONVERT, KEEP, SKIP, WARN }

    fun decide(lines: List<String>): Action {
        val first = lines.firstOrNull { it.isNotBlank() } ?: return Action.SKIP
        val t0 = jsonLong(first, "t0") ?: return Action.SKIP
        val (idle, mov, n) = rates(lines)
        val looksL = (idle != null && idle > IDLE_LITER_GPH) ||
            (idle == null && mov != null && mov > MOVING_LITER_GPH)
        if (t0 >= CUTOFF_MS) return if (looksL) Action.WARN else Action.KEEP
        if (n < 2) return Action.KEEP
        if (looksL) return Action.CONVERT
        if (idle != null && idle > 0.5 && idle <= IDLE_LITER_GPH && (mov == null || mov >= 1.0)) {
            return Action.KEEP
        }
        return Action.SKIP
    }

    fun convert(text: String): String {
        return TOKEN.replace(text) { m ->
            val scaled = m.groupValues[2].toDouble() * FACTOR
            m.groupValues[1] + javaDouble(scaled)
        }
    }

    /** Android JSONObject style: integral values as a long, otherwise Java Double.toString. */
    fun javaDouble(x: Double): String {
        if (x.isFinite() && abs(x) < 1e15 && x == x.toLong().toDouble()) return x.toLong().toString()
        return x.toString()
    }

    private fun rates(lines: List<String>): Triple<Double?, Double?, Int> {
        val rows = ArrayList<Pair<Long, Sample>>()
        for (line in lines) {
            if (line.isBlank() || !line.contains("\"t\"") || line.contains("\"event\"")) continue
            val t = jsonLong(line, "t") ?: continue
            val fuel = jsonDouble(line, "fuelUsedGal") ?: continue
            val speed = jsonDouble(line, "speed") ?: 0.0
            rows.add(t to Sample(fuel, speed))
        }
        var idleG = 0.0
        var idleS = 0.0
        var movG = 0.0
        var movS = 0.0
        for (i in 1 until rows.size) {
            val d = (rows[i].first - rows[i - 1].first) / 1000.0
            val du = rows[i].second.fuel - rows[i - 1].second.fuel
            if (d <= 0.0 || d > 2.0 || du < 0.0) continue
            if (rows[i].second.speed < 1.0) {
                idleG += du
                idleS += d
            } else {
                movG += du
                movS += d
            }
        }
        val idle = if (idleS >= 60.0) idleG / idleS * 3600.0 else null
        val mov = if (movS >= 60.0) movG / movS * 3600.0 else null
        return Triple(idle, mov, rows.size)
    }

    private class Sample(val fuel: Double, val speed: Double)

    private fun jsonLong(line: String, key: String): Long? {
        val i = line.indexOf("\"$key\"")
        if (i < 0) return null
        val colon = line.indexOf(':', i + key.length + 2)
        if (colon < 0) return null
        val m = NUM.find(line, colon + 1) ?: return null
        return m.value.toLongOrNull() ?: m.value.toDoubleOrNull()?.toLong()
    }

    private fun jsonDouble(line: String, key: String): Double? {
        val i = line.indexOf("\"$key\"")
        if (i < 0) return null
        val colon = line.indexOf(':', i + key.length + 2)
        if (colon < 0) return null
        val m = NUM.find(line, colon + 1) ?: return null
        return m.value.toDoubleOrNull()
    }
}
