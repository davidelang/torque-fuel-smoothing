package com.davidlang.torque.fuelsmoothing

/**
 * One corrected output's level below the low range (chunk, band and Kalman each keep their own; the 012F override
 * follows whichever it feeds). Smoothed is map-free ([FuelMath.LowInput]); here, with this output's own map and
 * size [capGal]:
 * - below the low range the corrected % falls by counter burn through the map (100 x burn / cap: corrected % is
 *   gallons-linear), and is pulled toward the map of smoothed with [TAU_SEC] only on a steady-driving smoothed
 *   update ([FuelMath.lowPulled]),
 * - a solid raw 0 ([FuelMath.solidZero]) holds 0 (this map's 0),
 * - a smoothed re-acquire (gap / untaken rise), a rise of more than [RISE_PCT], or a level at/above the low range
 *   takes the map of smoothed as is,
 * - no own size (unity / no map) leaves the output as given.
 * Replay: sandbox/lowrange-20261118/replay2.py (CorrLow), grid3.txt.
 */
internal class CorrectedLow {
    var pct: Double? = null
        private set
    private var lastUsedGal: Double? = null
    private var lastMs: Long? = null

    /**
     * [mapped]: this output's corrected % of the current smoothed. [smoothed], [rangePct]: level units.
     * [usedGal]: session cumulative counter (a drop, e.g. a new session, counts as no burn). One step per [nowMs].
     */
    fun step(
        mapped: Double,
        smoothed: Double,
        rangePct: Double,
        solidZero: Boolean,
        pulled: Boolean,
        reacquiring: Boolean,
        usedGal: Double,
        capGal: Double,
        nowMs: Long,
        tauSec: Double = TAU_SEC,
    ): Double {
        if (lastMs == nowMs) return pct ?: mapped
        val burn = lastUsedGal?.let { usedGal - it }?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val d = lastMs?.let { ((nowMs - it).coerceAtLeast(0L) / 1000.0).coerceAtMost(DT_CAP_SEC) } ?: 0.0
        if (usedGal.isFinite()) lastUsedGal = usedGal
        lastMs = nowMs
        if (!mapped.isFinite() || !(capGal > 0.0)) {
            pct = null
            return mapped
        }
        if (solidZero) {
            pct = 0.0
            return 0.0
        }
        val prev = pct
        if (smoothed >= rangePct || prev == null || reacquiring) {
            pct = mapped
            return mapped
        }
        val pred = (prev - 100.0 * burn / capGal).coerceAtLeast(0.0)
        if (mapped - pred > RISE_PCT) {
            pct = mapped
            return mapped
        }
        val a = if (pulled && d > 0.0) d / (tauSec.coerceAtLeast(0.0) + d) else 0.0
        val y = (pred + a * (mapped - pred)).coerceIn(0.0, 100.0)
        pct = y
        return y
    }

    companion object {
        /** Pull toward the map of smoothed (replay grid3.txt: 60 s; 30 s scores the same, longer drifts). */
        const val TAU_SEC = 60.0
        const val RISE_PCT = 15.0
        const val DT_CAP_SEC = 5.0
    }
}
