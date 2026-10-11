package com.davidlang.torque.fuelsmoothing

import kotlin.math.max

/**
 * Units scale for count-reporting level sources (Toyota cluster 2129: 255 counts on the fixed logged scale).
 * The logged/learned level stays on the fixed 255-count scale forever (the tank size is learned from it as on
 * every vehicle). Only the %-based smoothing thresholds and the displayed Smoothed level use the vehicle's own
 * full scale: a per-profile full-scale count, frozen once (first fill plateau, or Torque's capacity), never
 * updated by learning. Until it is known, the largest count seen so far stands in.
 */
internal object LevelScale {
    const val KEY_FULL = "levelFullCounts"
    const val KEY_MAX = "levelMaxCount"
    const val FIXED_COUNTS = 255.0
    const val MIN_COUNTS = 20.0

    fun applies(sourceId: String) = sourceId == "toyota_7c0_2129"

    /** Thresholds are multiplied by this; displayed level = level / unit. 1.0 = the fixed scale. */
    fun unit(fullCounts: Double?, maxSeen: Double?): Double {
        val c = fullCounts?.takeIf { it >= MIN_COUNTS } ?: maxSeen?.takeIf { it >= MIN_COUNTS } ?: FIXED_COUNTS
        return (c / FIXED_COUNTS).coerceIn(MIN_COUNTS / FIXED_COUNTS, 1.0)
    }

    fun counts(levelPct: Double) = levelPct * FIXED_COUNTS / 100.0

    /** Frozen full scale from the first fill plateau (never below the largest count already seen). */
    fun fromFill(plateauPct: Double, maxSeen: Double?): Double = max(counts(plateauPct), maxSeen ?: 0.0).coerceIn(MIN_COUNTS, FIXED_COUNTS)

    /** Frozen full scale from Torque's profile capacity (gallons), at 0.5 L per count. */
    fun fromCapacity(gal: Double): Double = (gal * 3.785411784 / FuelSources.TOYOTA_L_PER_COUNT).coerceIn(MIN_COUNTS, FIXED_COUNTS)

    fun display(levelPct: Double, unit: Double): Double = (levelPct / unit).coerceIn(0.0, 100.0)
}
