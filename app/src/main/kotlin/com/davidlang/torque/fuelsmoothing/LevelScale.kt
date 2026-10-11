package com.davidlang.torque.fuelsmoothing

import kotlin.math.max

/**
 * Units scale for count-reporting level sources (Toyota cluster 2129: 255 counts on the fixed logged scale).
 * The logged/learned level stays on the fixed 255-count scale forever (the tank size is learned from it as on
 * every vehicle). Only the %-based smoothing thresholds and the displayed Smoothed level use the vehicle's own
 * full scale = the highest settled (smoothed, non-slosh, not filling) count seen so far, which only ever rises,
 * optionally seeded from Torque's profile capacity. A fill is never assumed to be full.
 */
internal object LevelScale {
    /** Seed from Torque's profile capacity (counts), stored once. */
    const val KEY_FULL = "levelFullCounts"
    /** Highest settled count seen (only rises). */
    const val KEY_MAX = "levelMaxCount"
    const val FIXED_COUNTS = 255.0
    const val MIN_COUNTS = 20.0

    fun applies(sourceId: String) = sourceId == "toyota_7c0_2129"

    /** Full scale in counts: the larger of the capacity seed and the highest settled count. */
    fun fullCounts(seed: Double?, maxSettled: Double?): Double? =
        listOfNotNull(seed, maxSettled).filter { it >= MIN_COUNTS }.maxOrNull()?.coerceAtMost(FIXED_COUNTS)

    /** Thresholds are multiplied by this; displayed level = level / unit. 1.0 = the fixed scale. */
    fun unit(seed: Double?, maxSettled: Double?): Double = (fullCounts(seed, maxSettled) ?: FIXED_COUNTS) / FIXED_COUNTS

    fun counts(levelPct: Double) = levelPct * FIXED_COUNTS / 100.0

    /** New highest settled count, or null when [smoothedPct] does not raise it (only SKEW-mode tracking counts). */
    fun raise(maxSettled: Double?, smoothedPct: Double, settled: Boolean): Double? {
        if (!settled || !smoothedPct.isFinite()) return null
        val c = counts(smoothedPct).coerceIn(0.0, FIXED_COUNTS)
        return if (c > (maxSettled ?: 0.0) + 0.5) c else null
    }

    /** Seed from Torque's profile capacity (gallons), at 0.5 L per count. */
    fun fromCapacity(gal: Double): Double = (gal * 3.785411784 / FuelSources.TOYOTA_L_PER_COUNT).coerceIn(MIN_COUNTS, FIXED_COUNTS)

    fun display(levelPct: Double, unit: Double): Double = (levelPct / unit).coerceIn(0.0, 100.0)
}
