package org.dlang.torque.fordfuel

import kotlin.math.abs

internal data class Bin(
    val lo: Double,
    val hi: Double,
    val gal: Double,
)

internal data class SessionObs(
    val bins: List<Bin>,
    val reserveGal: Double,
)

internal enum class TrackMode { HOLD, SKEW, FILL }

internal class FuelMath {
    var smoothed: Double? = null
        private set
    var mode: TrackMode = TrackMode.HOLD
        private set
    private var lastIngestMs: Long? = null

    fun isValidSample(raw: Double?, ecuConnected: Boolean?): Boolean {
        if (ecuConnected == false) return false
        if (raw == null || !raw.isFinite()) return false
        if (raw > 100.0 || raw < 0.0) return false
        if (raw == 0.0) {
            val s = smoothed
            return s != null && s <= NEAR_EMPTY_PCT
        }
        return raw > 0.0 && raw <= 100.0
    }

    fun hold() {
        mode = TrackMode.HOLD
    }

    fun ingest(raw: Double, nowMs: Long = System.currentTimeMillis()): Double {
        val prev = smoothed
        val last = lastIngestMs
        lastIngestMs = nowMs
        if (prev == null) {
            smoothed = raw
            mode = TrackMode.SKEW
            return raw
        }
        if (mode == TrackMode.FILL) {
            smoothed = raw
            return raw
        }
        val dtSec = if (last == null) 1.0 else ((nowMs - last).coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.001)
        val maxStep = SKEW_PCT_PER_SEC * dtSec
        val y = prev + (raw - prev).coerceIn(-maxStep, maxStep)
        smoothed = y
        mode = TrackMode.SKEW
        return y
    }

    fun remainingGal(
        pct: Double,
        bins: List<Bin>,
        nameplateGal: Double?,
        reserveGal: Double,
    ): Double {
        val p = pct.coerceIn(0.0, 100.0)
        var gal = 0.0
        var cursor = 0.0
        while (cursor < p) {
            val next = minOf(p, cursor + STEP)
            val mid = (cursor + next) / 2.0
            gal += galPerPercent(mid, bins, nameplateGal) * (next - cursor)
            cursor = next
        }
        if (p <= PINNED_ZERO) gal += reserveGal.coerceAtLeast(0.0)
        return gal
    }

    fun dteMiles(remainingGal: Double, mpg: Double): Double {
        if (mpg <= 0.0 || mpg.isNaN()) return 0.0
        return remainingGal * mpg
    }

    companion object {
        const val SKEW_PCT_PER_SEC = 0.05
        const val PINNED_ZERO = 0.5
        const val NEAR_EMPTY_PCT = 5.0
        private const val STEP = 1.0

        fun covering(p: Double, bins: List<Bin>): List<Bin> {
            return bins.filter { b -> p >= b.lo && p < b.hi || (p == 100.0 && p == b.hi) }
        }

        fun galPerPercent(p: Double, bins: List<Bin>, nameplateGal: Double?): Double {
            val hit = covering(p, bins)
            if (hit.isNotEmpty()) {
                return hit.map { it.gal / (it.hi - it.lo).coerceAtLeast(1e-6) }.average()
            }
            if (nameplateGal != null && nameplateGal > 0.0) return nameplateGal / 100.0
            return 0.0
        }

        fun observationsFrom(samples: List<Sample>): SessionObs {
            if (samples.size < 2) return SessionObs(emptyList(), 0.0)
            val out = mutableListOf<Bin>()
            var reserve = 0.0
            var a = 0
            for (b in 1 until samples.size) {
                val used = samples[b].fuelUsedGal - samples[a].fuelUsedGal
                if (samples[a].rawPct <= PINNED_ZERO && samples[b].rawPct <= PINNED_ZERO && used >= 0.05) {
                    reserve += used
                    a = b
                    continue
                }
                val rise = samples[b].rawPct - samples[b - 1].rawPct
                if (rise > 2.0) {
                    a = b
                    continue
                }
                val drop = samples[a].rawPct - samples[b].rawPct
                if (drop >= 1.0 && used >= 0.05) {
                    out.add(Bin(lo = samples[b].rawPct, hi = samples[a].rawPct, gal = used))
                    a = b
                }
            }
            return SessionObs(out, reserve)
        }

        fun mpgFrom(value: Double, unit: String): Double {
            val u = unit.lowercase()
            return when {
                u.contains("l/100") || u.contains("l100") -> if (value > 0.0) 235.215 / value else 0.0
                u.contains("km/l") || u.contains("kpl") -> value * 2.35215
                else -> value
            }
        }

        fun crossedOnePercent(prev: Double?, now: Double): Boolean {
            if (prev == null) return true
            return abs(now - prev) >= 1.0
        }
    }
}
