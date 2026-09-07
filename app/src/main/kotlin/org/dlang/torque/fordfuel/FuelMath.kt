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
    private var fillPeak: Double = 0.0
    private var fillBandCenter: Double = 0.0
    private var fillBandStartMs: Long = 0L

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

    fun ingest(
        raw: Double,
        speedMph: Double,
        nowMs: Long = System.currentTimeMillis(),
        tauSec: Double = 0.0,
        skewPctPerSec: Double = SKEW_PCT_PER_SEC,
        jumpPct: Double = FILL_JUMP_PCT,
        ignoreFill: Boolean = false,
    ): Double {
        val prev = smoothed
        val last = lastIngestMs
        lastIngestMs = nowMs
        if (prev == null) {
            smoothed = raw
            mode = TrackMode.SKEW
            return raw
        }
        if (!ignoreFill &&
            mode != TrackMode.FILL &&
            speedMph <= FILL_ENTER_SPEED_MPH &&
            raw - prev >= jumpPct
        ) {
            mode = TrackMode.FILL
            smoothed = raw
            fillPeak = raw
            fillBandCenter = raw
            fillBandStartMs = nowMs
            return raw
        }
        if (mode == TrackMode.FILL) {
            return followFill(raw, speedMph, nowMs)
        }
        val dtSec = if (last == null) 1.0 else ((nowMs - last).coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.001)
        val ema = if (tauSec <= 0.0) {
            raw
        } else {
            val a = dtSec / (tauSec + dtSec)
            a * raw + (1.0 - a) * prev
        }
        val maxStep = skewPctPerSec.coerceAtLeast(0.0) * dtSec
        val y = prev + (ema - prev).coerceIn(-maxStep, maxStep)
        smoothed = y
        mode = TrackMode.SKEW
        return y
    }

    private fun followFill(raw: Double, speedMph: Double, nowMs: Long): Double {
        smoothed = raw
        if (raw > fillPeak) fillPeak = raw
        val stable = abs(raw - fillBandCenter) <= FILL_STABLE_BAND_PCT
        if (stable) {
            if ((nowMs - fillBandStartMs) / 1000.0 >= FILL_STABLE_SEC) {
                mode = TrackMode.SKEW
                return raw
            }
        } else {
            fillBandCenter = raw
            fillBandStartMs = nowMs
        }
        if (raw < fillPeak - FILL_DROP_PT || speedMph > FILL_EXIT_SPEED_MPH) {
            mode = TrackMode.SKEW
        }
        return raw
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

    fun correctedPct(smoothed: Double, remain: Double, usable: Double?, binsEmpty: Boolean): Double {
        if (binsEmpty || usable == null || usable <= 0.0) return smoothed
        return (100.0 * remain / usable).coerceIn(0.0, 100.0)
    }

    companion object {
        const val SKEW_PCT_PER_SEC = 0.05
        const val PINNED_ZERO = 0.5
        const val NEAR_EMPTY_PCT = 5.0
        const val FILL_JUMP_PCT = 25.0
        const val FILL_ENTER_SPEED_MPH = 3.0
        const val FILL_EXIT_SPEED_MPH = 5.0
        const val FILL_DROP_PT = 2.5
        const val FILL_STABLE_SEC = 12.0
        const val FILL_STABLE_BAND_PCT = 2.0

        fun toMph(value: Double, unit: String): Double {
            val u = unit.lowercase()
            return if (u.contains("km")) value * 0.621371 else value
        }
        private const val STEP = 1.0
        const val OBS_MIN_GAL = 0.05
        private const val SLOSH_GAL = OBS_MIN_GAL * 2.0

        fun covering(p: Double, bins: List<Bin>): List<Bin> {
            return bins.filter { b -> p >= b.lo && p < b.hi || (p == 100.0 && p == b.hi) }
        }

        fun density(b: Bin): Double = b.gal / (b.hi - b.lo).coerceAtLeast(1e-6)

        fun median(xs: List<Double>): Double {
            if (xs.isEmpty()) return 0.0
            val s = xs.sorted()
            val n = s.size
            return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
        }

        fun filterOutliers(bins: List<Bin>): List<Bin> {
            if (bins.isEmpty()) return emptyList()
            val aboveGate = bins.filter { it.gal > SLOSH_GAL && density(it) > 0.0 }
            if (aboveGate.isEmpty()) return emptyList()
            val center = median(aboveGate.map { density(it) })
            if (center <= 0.0) return aboveGate
            val lo = center / 4.0
            val hi = center * 3.0
            return aboveGate.filter { b ->
                val d = density(b)
                d >= lo && d <= hi
            }
        }

        fun galPerPercent(p: Double, bins: List<Bin>, nameplateGal: Double?): Double {
            val hit = covering(p, bins)
            if (hit.isNotEmpty()) {
                val kept = filterOutliers(hit)
                if (kept.isNotEmpty()) return kept.map { density(it) }.average()
            }
            if (nameplateGal != null && nameplateGal > 0.0) return nameplateGal / 100.0
            return 0.0
        }

        fun mappedGallons(bins: List<Bin>): Double {
            val kept = filterOutliers(bins)
            var gal = 0.0
            var cursor = 0.0
            while (cursor < 100.0) {
                val next = minOf(100.0, cursor + STEP)
                val mid = (cursor + next) / 2.0
                val hit = covering(mid, kept)
                if (hit.isNotEmpty()) {
                    gal += hit.map { density(it) }.average() * (next - cursor)
                }
                cursor = next
            }
            return gal
        }

        fun estimateUsable(bins: List<Bin>): Double? {
            val kept = filterOutliers(bins)
            val span = coverageSpan(kept)
            if (kept.size < 2 || span < 20.0) return null
            val mapped = mappedGallons(bins)
            if (mapped <= 0.0) return null
            return 100.0 * mapped / span
        }

        fun observationsFrom(samples: List<Sample>): SessionObs {
            val pts = samples.filter { !it.fill }
            if (pts.size < 2) return SessionObs(emptyList(), 0.0)
            val out = mutableListOf<Bin>()
            var reserve = 0.0
            var a = 0
            for (b in 1 until pts.size) {
                val used = pts[b].fuelUsedGal - pts[a].fuelUsedGal
                val sa = pts[a].smoothedPct
                val sb = pts[b].smoothedPct
                if (sa <= PINNED_ZERO && sb <= PINNED_ZERO && used >= 0.05) {
                    reserve += used
                    a = b
                    continue
                }
                val rise = pts[b].smoothedPct - pts[b - 1].smoothedPct
                if (rise > 2.0) {
                    a = b
                    continue
                }
                val drop = sa - sb
                if (drop >= 1.0 && used >= 0.05) {
                    out.add(Bin(lo = sb, hi = sa, gal = used))
                    a = b
                }
            }
            return SessionObs(out, reserve)
        }

        fun coverageSpan(bins: List<Bin>): Double {
            if (bins.isEmpty()) return 0.0
            val sorted = bins.sortedBy { it.lo }
            var span = 0.0
            var curLo = sorted[0].lo
            var curHi = sorted[0].hi
            for (i in 1 until sorted.size) {
                val b = sorted[i]
                if (b.lo <= curHi) {
                    curHi = maxOf(curHi, b.hi)
                } else {
                    span += curHi - curLo
                    curLo = b.lo
                    curHi = b.hi
                }
            }
            span += curHi - curLo
            return span
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
