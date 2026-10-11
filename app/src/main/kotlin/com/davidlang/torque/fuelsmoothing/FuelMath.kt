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
    private var fillStillMed: Double = 0.0
    private var fillCandMs: Long? = null
    private var movingSinceMs: Long? = null
    private val window = ArrayDeque<Pair<Long, Double>>()
    private val low = ArrayDeque<Pair<Long, Double>>()
    private var lowMoving = false

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
        // A rejected reading during a fill does not end the fill.
        if (mode != TrackMode.FILL) mode = TrackMode.HOLD
    }

    fun ingest(
        raw: Double,
        speedMph: Double,
        nowMs: Long = System.currentTimeMillis(),
        tauSec: Double = 0.0,
        skewPctPerSec: Double = SKEW_PCT_PER_SEC,
        jumpPct: Double = FILL_JUMP_PCT,
        ignoreFill: Boolean = false,
        sigmaPct: Double = DEFAULT_SIGMA_PCT,
    ): Double {
        val prev = smoothed
        val last = lastIngestMs
        lastIngestMs = nowMs
        window.addLast(nowMs to raw)
        while (window.isNotEmpty() && nowMs - window.first().first > WINDOW_MS) window.removeFirst()
        if (prev == null) {
            smoothed = raw
            mode = TrackMode.SKEW
            return raw
        }
        if (mode == TrackMode.FILL) {
            return followFill(speedMph, nowMs, sigmaPct)
        }
        // Fill candidate: stopped and well above the smoothed level. Confirmed only if it is sustained for
        // FILL_CONFIRM_SEC (a single glitch reading or a short spike never becomes a fill). While pending the
        // smoothed level is frozen rather than slewed toward the candidate.
        if (!ignoreFill && speedMph <= FILL_ENTER_SPEED_MPH && raw - prev >= jumpPct) {
            val since = fillCandMs ?: nowMs.also { fillCandMs = it }
            if (nowMs - since >= FILL_CONFIRM_MS && medianSince(since) - prev >= jumpPct) {
                fillCandMs = null
                mode = TrackMode.FILL
                val m = median(nowMs - MEDIAN_MS)
                fillPeak = m
                fillStillMed = m
                movingSinceMs = null
                smoothed = m
                return m
            }
            return prev
        }
        fillCandMs = null
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

    /**
     * FILL stays on through pump pauses of any length (pump limit, re-authorisation). It ends when the vehicle
     * drives away (anchor = median of the last stationary window, i.e. the final plateau) or when the level
     * really drops: 20 s median below the peak by more than max(3 %, 3 sigma) (anchor = that median).
     */
    private fun followFill(speedMph: Double, nowMs: Long, sigmaPct: Double): Double {
        val med = median(nowMs - MEDIAN_MS)
        if (speedMph > FILL_EXIT_SPEED_MPH) {
            val since = movingSinceMs ?: nowMs.also { movingSinceMs = it }
            if (nowMs - since >= FILL_EXIT_MOVE_MS) return endFill(fillStillMed)
            smoothed = fillStillMed
            return fillStillMed
        }
        movingSinceMs = null
        if (med > fillPeak) fillPeak = med
        fillStillMed = med
        val drop = maxOf(FILL_DROP_MIN_PCT, 3.0 * sigmaPct)
        if (med < fillPeak - drop) return endFill(med)
        smoothed = med
        return med
    }

    private fun endFill(anchor: Double): Double {
        mode = TrackMode.SKEW
        smoothed = anchor
        movingSinceMs = null
        return anchor
    }

    /** Ends an open FILL (e.g. at session close) at the plateau median; returns the anchor or null. */
    fun finishFill(): Double? = if (mode == TrackMode.FILL) endFill(fillStillMed) else null

    /**
     * Stuck-level recovery. Call with every raw reading before [isValidSample]. Readings more than
     * DROP_HOLD_PT below the smoothed level are rejected as glitches; if they persist (REANCHOR_STOP_MS while
     * stopped, REANCHOR_MOVE_MS otherwise) and agree with each other, the smoothed level re-anchors to their
     * median. Returns true when it re-anchored.
     */
    fun checkReanchor(raw: Double?, speedMph: Double, nowMs: Long): Boolean {
        val s = smoothed
        if (raw == null || !raw.isFinite() || raw < GLITCH_RAW_PCT || raw > 100.0 || s == null || s - raw <= DROP_HOLD_PT || mode == TrackMode.FILL) {
            low.clear()
            lowMoving = false
            return false
        }
        low.addLast(nowMs to raw)
        if (speedMph > FILL_ENTER_SPEED_MPH) lowMoving = true
        val need = if (lowMoving) REANCHOR_MOVE_MS else REANCHOR_STOP_MS
        if (nowMs - low.first().first < need) return false
        val v = low.map { it.second }.sorted()
        if (v.last() - v.first() > REANCHOR_SPREAD_PCT) {
            low.removeFirst()
            return false
        }
        smoothed = v[v.size / 2]
        mode = TrackMode.SKEW
        fillCandMs = null
        low.clear()
        lowMoving = false
        return true
    }

    private fun median(fromMs: Long): Double {
        val v = window.filter { it.first >= fromMs }.map { it.second }.sorted()
        return if (v.isEmpty()) smoothed ?: 0.0 else v[v.size / 2]
    }

    private fun medianSince(fromMs: Long): Double = median(fromMs)

    companion object {
        const val SKEW_PCT_PER_SEC = 0.05
        const val NEAR_EMPTY_PCT = 5.0
        const val GLITCH_RAW_PCT = 1.0
        const val DROP_HOLD_PT = 15.0
        const val FILL_JUMP_PCT = 25.0
        const val FILL_ENTER_SPEED_MPH = 3.0
        const val FILL_EXIT_SPEED_MPH = 5.0
        const val FILL_CONFIRM_MS = 20_000L
        const val FILL_EXIT_MOVE_MS = 10_000L
        const val FILL_DROP_MIN_PCT = 3.0
        const val DEFAULT_SIGMA_PCT = 1.0
        const val MEDIAN_MS = 20_000L
        const val WINDOW_MS = 30_000L
        const val REANCHOR_STOP_MS = 30_000L
        const val REANCHOR_MOVE_MS = 120_000L
        const val REANCHOR_SPREAD_PCT = 10.0

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
