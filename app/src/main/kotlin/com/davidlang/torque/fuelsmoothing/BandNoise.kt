package com.davidlang.torque.fuelsmoothing

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Steady-driving raw gauge noise per 5-point band (percent of the gauge), for the range-adaptive low-range smoothing
 * ([FuelMath.lowStep]). Each band's sd = sqrt((PRIOR_N x seed^2 + sum dev^2) / (PRIOR_N + n)): the seed counts as
 * PRIOR_N samples and learned samples take over as they pile up. dev = raw minus the 30 s raw median, steady
 * driving only, clipped at MAX_DEV_PCT (a glitch is not noise). Learned per tank and folded into the profile at
 * each fill ([LowRange.closeTank]).
 */
internal object BandNoise {
    const val BANDS = 20
    const val WIDTH_PCT = 5.0
    const val PRIOR_N = 120.0
    const val MAX_DEV_PCT = 15.0

    /**
     * Seed sd (pt) per band: steady-freeway sd from the 2026-11-18 van study (lowtank2: 0-5 2.08, 5-10 2.28,
     * 10-15 1.33, 15-20 1.64, 20-30 1.44, 50-80 1.98, 80-100 1.30); 95-100 from the round-5 MAD (0.75);
     * 30-50 (no steady data) interpolated 1.6-1.9.
     */
    val SEED = doubleArrayOf(
        2.08, 2.28, 1.33, 1.64, 1.44, 1.44, 1.6, 1.7, 1.8, 1.9,
        1.98, 1.98, 1.98, 1.98, 1.98, 1.98, 1.30, 1.30, 1.30, 0.75,
    )

    init {
        check(SEED.size == BANDS)
    }

    class Acc(val n: DoubleArray = DoubleArray(BANDS), val s2: DoubleArray = DoubleArray(BANDS)) {
        val total: Double get() = n.sum()
        fun json(): JSONObject = JSONObject().put("n", JSONArray(n.map { round(it) })).put("s2", JSONArray(s2.map { round(it) }))
        companion object {
            fun parse(o: JSONObject?): Acc {
                val a = Acc()
                if (o == null) return a
                val n = o.optJSONArray("n")
                val s2 = o.optJSONArray("s2")
                for (i in 0 until BANDS) {
                    val ni = n?.optDouble(i, 0.0) ?: 0.0
                    val si = s2?.optDouble(i, 0.0) ?: 0.0
                    if (ni.isFinite() && si.isFinite() && ni >= 0.0 && si >= 0.0) {
                        a.n[i] = ni
                        a.s2[i] = si
                    }
                }
                return a
            }
        }
    }

    fun band(pct: Double): Int = if (!pct.isFinite()) 0 else (pct / WIDTH_PCT).toInt().coerceIn(0, BANDS - 1)

    /** Noise sd (pt) at [pct] from the folded profile bands (seeded). */
    fun sd(acc: Acc, pct: Double): Double {
        val b = band(pct)
        val seed = SEED[b]
        return sqrt((PRIOR_N * seed * seed + acc.s2[b]) / (PRIOR_N + acc.n[b]))
    }

    /** One steady sample at [pct] whose raw differs from its 30 s median by [dev] pt. */
    fun add(acc: Acc, pct: Double, dev: Double) {
        if (!pct.isFinite() || !dev.isFinite() || pct < 0.0 || pct > 100.0) return
        val d = if (abs(dev) > MAX_DEV_PCT) MAX_DEV_PCT else dev
        val b = band(pct)
        acc.n[b] += 1.0
        acc.s2[b] += d * d
    }

    fun fold(into: Acc, from: Acc) {
        for (i in 0 until BANDS) {
            into.n[i] += from.n[i]
            into.s2[i] += from.s2[i]
        }
    }

    /** Learned-only sd per band (null where this accumulator has fewer than [minN] samples). */
    fun learned(acc: Acc, minN: Double = 30.0): List<Double?> =
        (0 until BANDS).map { if (acc.n[it] >= minN) sqrt(acc.s2[it] / acc.n[it]) else null }

    private fun round(x: Double): Double = Math.round(x * 1000.0) / 1000.0
}
