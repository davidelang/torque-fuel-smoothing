package com.davidlang.torque.fuelsmoothing

import kotlin.math.abs

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

    fun isValidSample(raw: Double?, ecuConnected: Boolean?, lastGood: Double? = smoothed): Boolean {
        if (ecuConnected == false) return false
        if (raw == null || !raw.isFinite()) return false
        if (raw > 100.0 || raw < 0.0) return false
        if (raw == 0.0) {
            return lastGood != null && lastGood <= NEAR_EMPTY_PCT
        }
        if (lastGood != null && lastGood > NEAR_EMPTY_PCT && raw < GLITCH_RAW_PCT) return false
        if (lastGood != null && lastGood > NEAR_EMPTY_PCT && lastGood - raw > DROP_HOLD_PT) return false
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

    companion object {
        const val SKEW_PCT_PER_SEC = 0.05
        const val NEAR_EMPTY_PCT = 5.0
        const val GLITCH_RAW_PCT = 1.0
        const val DROP_HOLD_PT = 15.0
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

        fun crossedOnePercent(prev: Double?, now: Double): Boolean {
            if (prev == null) return true
            return abs(now - prev) >= 1.0
        }
    }
}
