package com.davidlang.torque.fuelsmoothing

import kotlin.math.abs

internal enum class TrackMode { HOLD, SKEW, FILL }

internal class FuelMath {
    /** Units scale for the %-based thresholds (LevelScale.unit; 1.0 for % sources). */
    var unit: Double = 1.0

    var smoothed: Double? = null
        private set
    var mode: TrackMode = TrackMode.HOLD
        private set

    /** Settled parked level a fill is measured from (null while moving or still settling). */
    val stopBaseline: Double? get() = stopBase
    private var lastIngestMs: Long? = null
    private var fillPeak: Double = 0.0
    private var fillStillMed: Double = 0.0
    /** Session fuel used (gal) when [fillPeak] was last raised; null when the caller gives no burn signal. */
    private var fillPeakUsedGal: Double? = null
    private var fillCandMs: Long? = null
    private var movingSinceMs: Long? = null
    /** Start of the current continuous stop (speed <= FILL_ENTER_SPEED_KMH); null while moving. */
    private var stopSinceMs: Long? = null
    /** Post-stop baseline: short raw median frozen once the stop has settled; null until then. */
    private var stopBase: Double? = null
    private val window = ArrayDeque<Pair<Long, Double>>()
    private val low = ArrayDeque<Pair<Long, Double>>()
    private var lowMoving = false

    /**
     * Range-adaptive inputs for the low end (tank 1 of a confirmed vehicle only). Null keeps the plain slew path
     * everywhere (extra tanks, pending vehicles). Map-free: smoothed uses raw readings and non-map signals only
     * (speed, ingest gaps, raw noise); it never converts burn through a tank map. The burn-driven level below the
     * low range lives in the corrected outputs ([CorrectedLow]).
     * [sdPct]: steady-driving raw noise (level units) at a level ([BandNoise]). [rangePct]: low-range threshold
     * (% of gauge, 0 = off). [tauSec]: base low-range time constant. [noiseMult]: extra smoothing for noisy bands,
     * f = max(1, 1 + noiseMult x ((sd / LOW_SD_REF_PCT)^2 - 1)); 0 = no band weighting. Settings: [FixFuelPrefs].
     */
    class LowInput(
        val sdPct: (Double) -> Double,
        val rangePct: Double = LOW_RANGE_PCT,
        val tauSec: Double = LOW_TAU_SEC,
        val noiseMult: Double = LOW_NOISE_MULT,
    ) {
        /** Extra smoothing factor for a band sd (level units). */
        fun factor(sd: Double, unit: Double): Double {
            val r = sd / (LOW_SD_REF_PCT * unit)
            if (!r.isFinite() || !noiseMult.isFinite()) return 1.0
            return maxOf(1.0, 1.0 + noiseMult * (r * r - 1.0))
        }
    }

    /** Raw has been a solid 0 (>= SOLID_SHARE zeros over SOLID_MS while moving): level held at 0 ("at/below the 0% point"). */
    var solidZero: Boolean = false
        private set
    /** Last speed sample was steady driving (>= LOW_STEADY_KMH, |accel| <= LOW_STEADY_ACC, no hard braking in LOW_BRAKE_HOLD_MS). */
    var steadyNow: Boolean = false
        private set
    /** The last update used the low-range path. */
    var lowPath: Boolean = false
        private set
    /** The last low-range step was a steady-driving pull toward the raw median (corrected outputs anchor only then). */
    var lowPulled: Boolean = false
        private set
    /** Fast re-acquire after an ingest gap or an untaken rise is running (corrected outputs take their map of it). */
    var reacquiring: Boolean = false
        private set
    private val speeds = ArrayDeque<Pair<Long, Double>>()
    private var brakeMs: Long? = null
    /** (time, raw == 0, moving) for the solid-zero test, low-range inputs only. */
    private val zeros = ArrayDeque<Triple<Long, Boolean, Boolean>>()
    private var acqFromMs: Long? = null
    private var acqUntilMs = 0L
    private var acquiring = false

    /**
     * [lowRange] (range-adaptive tank 1): a raw 0 is a valid reading ("at or below the sender's 0% point") whenever
     * the level is in the low range (<= [lowRangePct], the low-range setting): a bouncing 0 is a noisy low reading for the median, a solid
     * 0 holds the level at 0. Without it the old rule stands (0 only once smoothed <= NEAR_EMPTY_PCT).
     */
    fun isValidSample(
        raw: Double?,
        ecuConnected: Boolean?,
        lastGood: Double? = smoothed,
        lowRange: Boolean = false,
        lowRangePct: Double = LOW_RANGE_PCT,
    ): Boolean {
        if (ecuConnected == false) return false
        if (raw == null || !raw.isFinite()) return false
        if (raw > 100.0 || raw < 0.0) return false
        val near = nearEmpty(lowRange, lowRangePct) * unit
        if (raw == 0.0) {
            return lastGood != null && lastGood <= near
        }
        if (lastGood != null && lastGood > near && raw < GLITCH_RAW_PCT * unit) return false
        if (lastGood != null && lastGood > near && lastGood - raw > DROP_HOLD_PT * unit) return false
        return raw > 0.0 && raw <= 100.0
    }

    /**
     * A rejected or missing reading with low-range inputs: the level holds (map-free; the corrected outputs carry
     * the burn), a solid 0 stays at 0. Otherwise the same as [hold].
     */
    @Suppress("UNUSED_PARAMETER")
    fun holdLow(speedKmh: Double, nowMs: Long, low: LowInput) {
        noteSpeed(nowMs, speedKmh)
        hold()
        lowPulled = false
        if (smoothed == null || mode == TrackMode.FILL) return
        if (solidZero) smoothed = 0.0
    }

    /** Median of the raw readings since [fromMs] (inside WINDOW_MS); the smoothed level when there are none. */
    fun rawMedian(fromMs: Long): Double = median(fromMs)

    fun hold() {
        // A rejected reading during a fill does not end the fill.
        if (mode != TrackMode.FILL) mode = TrackMode.HOLD
    }

    fun ingest(
        raw: Double,
        speedKmh: Double,
        nowMs: Long = System.currentTimeMillis(),
        tauSec: Double = 0.0,
        skewPctPerSec: Double = SKEW_PCT_PER_SEC,
        jumpPct: Double = LIVE_FOLLOW_PCT,
        settleSec: Double = FILL_SETTLE_SEC,
        ignoreFill: Boolean = false,
        sigmaPct: Double = DEFAULT_SIGMA_PCT,
        /** Session cumulative fuel used (gal), or null if unknown. Lets a fill tell "parked, no burn" apart. */
        usedGal: Double? = null,
        /** Range-adaptive low-end inputs; null = plain slew path at every level. */
        low: LowInput? = null,
    ): Double {
        val prev = smoothed
        val last = lastIngestMs
        val stopped = speedKmh <= FILL_ENTER_SPEED_KMH
        // Engine off at the pump: no samples arrive while the ECU is off. Stopped before the gap and still stopped
        // after it means the gap was parked time, so the pre-gap stopped level becomes the baseline (even if the
        // settle time had not run out) and fuel added during the gap still reads as a rise.
        val stopAt = stopSinceMs
        if (!ignoreFill && stopped && stopBase == null && stopAt != null && last != null && nowMs - last > GAP_MS) {
            stopBase = median(maxOf(stopAt, last - BASELINE_MS))
        }
        lastIngestMs = nowMs
        window.addLast(nowMs to raw)
        while (window.isNotEmpty() && nowMs - window.first().first > WINDOW_MS) window.removeFirst()
        val steady = noteSpeed(nowMs, speedKmh)
        if (low != null) {
            zeros.addLast(Triple(nowMs, raw == 0.0, speedKmh > FILL_ENTER_SPEED_KMH))
            while (zeros.isNotEmpty() && nowMs - zeros.first().first > SOLID_MS) zeros.removeFirst()
        }
        if (prev == null) {
            smoothed = raw
            mode = TrackMode.SKEW
            return raw
        }
        if (low != null && last != null && nowMs - last > GAP_REACQUIRE_MS) {
            // No reading for a while (parked, or a drive that was not logged): fuel may have burned off the counter.
            acqFromMs = nowMs
            acqUntilMs = nowMs + ACQ_MS
            acquiring = false
        }
        if (mode == TrackMode.FILL) {
            return followFill(speedKmh, nowMs, sigmaPct, usedGal)
        }
        // Settle-then-rise. Stopping changes the reading by itself (parking attitude/slope shows up at once, then
        // slosh dies out), so a fill is never measured against the smoothed/moving level. Once the vehicle has
        // been stopped continuously for settleSec, a short raw median is frozen as the post-stop baseline. Only
        // after that does a rise of jumpPct (LIVE_FOLLOW_PCT in the service) above the baseline start a candidate, confirmed when
        // the median since the candidate is still that far above the same baseline after FILL_CONFIRM_MS (a
        // glitch or a short spike never becomes a fill). Moving again before the baseline exists (a traffic
        // light) clears the stop. While a candidate is pending the smoothed level is frozen.
        if (ignoreFill || !stopped) {
            clearStop()
        } else {
            val since = stopSinceMs ?: nowMs.also { stopSinceMs = it }
            val settle = if (settleSec.isFinite()) settleSec.coerceAtLeast(0.0) else FILL_SETTLE_SEC
            if (stopBase == null && nowMs - since >= (settle * 1000.0).toLong()) {
                stopBase = median(maxOf(since, nowMs - BASELINE_MS))
            }
            val base = stopBase
            if (base != null && raw - base >= jumpPct * unit) {
                val cand = fillCandMs ?: nowMs.also { fillCandMs = it }
                if (nowMs - cand >= FILL_CONFIRM_MS && medianSince(cand) - base >= jumpPct * unit) {
                    fillCandMs = null
                    clearStop()
                    mode = TrackMode.FILL
                    solidZero = false
                    acquiring = false
                    acqUntilMs = 0L
                    val m = median(nowMs - MEDIAN_MS)
                    fillPeak = m
                    fillStillMed = m
                    fillPeakUsedGal = usedGal
                    movingSinceMs = null
                    smoothed = m
                    return m
                }
                return prev
            }
        }
        fillCandMs = null
        val dtSec = if (last == null) 1.0 else ((nowMs - last).coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.001)
        if (low != null && (prev < low.rangePct * unit || solidZero)) {
            return lowStep(prev, nowMs, dtSec, steady, speedKmh, skewPctPerSec, low)
        }
        lowPath = false
        lowPulled = false
        reacquiring = false
        // The sender saturates at 99.9: a reading at the ceiling means "at least full", not that exact number.
        // It may pull the level up to the ceiling but never pulls a level already at/above it down.
        val target = if (raw >= SENDER_CEIL_PCT * unit && prev >= raw) prev else raw
        val ema = if (tauSec <= 0.0) {
            target
        } else {
            val a = dtSec / (tauSec + dtSec)
            a * target + (1.0 - a) * prev
        }
        val maxStep = skewPctPerSec.coerceAtLeast(0.0) * unit * dtSec
        val y = prev + (ema - prev).coerceIn(-maxStep, maxStep)
        smoothed = y
        mode = TrackMode.SKEW
        return y
    }

    /**
     * Low range (< [LowInput.rangePct], replay: sandbox/lowrange-20261118/replay2.py). Map-free: the level holds and
     * moves only toward the gauge, and only:
     * - toward the 30 s raw MEDIAN (outlier resistant; zeros are censored lows and cannot move a median until they
     *   are half the window),
     * - while driving steadily (LOW_STEADY_KMH, |accel| <= LOW_STEADY_ACC, LOW_BRAKE_HOLD_MS after hard braking),
     * - slowly: time constant [LowInput.tauSec] x [LowInput.factor] (band noise), within the usual slew limit.
     * Solid 0 (>= SOLID_SHARE zeros over SOLID_MS while moving) holds 0 until zeros fall below SOLID_EXIT_SHARE
     * with a median >= SOLID_EXIT_MED_PCT (the caller counts the burn while held as reserve evidence).
     * After an ingest gap > GAP_REACQUIRE_MS, if the first ACQ_WAIT_MS of readings disagree with the carried level by
     * more than ACQ_DIFF_PCT (unlogged burn, missed fill), the level re-acquires fast (ACQ_TAU_SEC, no slew limit);
     * so does a 30 s median more than LIVE_FOLLOW_PCT above it (a rise the fill detector did not take).
     */
    private fun lowStep(prev: Double, nowMs: Long, dtSec: Double, steady: Boolean, speedKmh: Double, skewPctPerSec: Double, low: LowInput): Double {
        lowPath = true
        lowPulled = false
        reacquiring = false
        mode = TrackMode.SKEW
        val n = zeros.size
        val z = zeros.count { it.second }
        val mv = zeros.count { it.third }
        val span = if (n > 0) zeros.last().first - zeros.first().first else 0L
        val med = median(nowMs - LOW_MEDIAN_MS)
        if (solidZero) {
            if (n >= SOLID_MIN_N && z.toDouble() / n < SOLID_EXIT_SHARE && med >= SOLID_EXIT_MED_PCT * unit) {
                solidZero = false
            } else {
                smoothed = 0.0
                return 0.0
            }
        } else if (n >= SOLID_MIN_N && span >= SOLID_MS - SOLID_SPAN_SLACK_MS && z.toDouble() / n >= SOLID_SHARE &&
            mv.toDouble() / n >= 0.5 && speedKmh > FILL_ENTER_SPEED_KMH
        ) {
            solidZero = true
            smoothed = 0.0
            return 0.0
        }
        val pred = prev
        val d = minOf(dtSec, LOW_DT_CAP_SEC)
        if (!acquiring && med - pred > LIVE_FOLLOW_PCT * unit) {
            // The 30 s median far above the carried level is a rise the fill detector did not take (moving off
            // the pump, suppressed fill): follow it fast instead of crawling up at the low-range rate.
            acquiring = true
            acqFromMs = nowMs
            acqUntilMs = nowMs + ACQ_MS
        }
        if (nowMs < acqUntilMs) {
            val from = acqFromMs ?: nowMs
            if (!acquiring && nowMs - from >= ACQ_WAIT_MS) {
                if (abs(median(from) - pred) > ACQ_DIFF_PCT * unit) acquiring = true else acqUntilMs = nowMs
            }
            if (acquiring) {
                reacquiring = true
                val y = (pred + (med - pred) * d / (ACQ_TAU_SEC + d)).coerceAtLeast(0.0)
                smoothed = y
                return y
            }
        }
        if (!steady) {
            smoothed = pred
            return pred
        }
        val f = low.factor(low.sdPct(prev), unit)
        val a = d / (low.tauSec.coerceAtLeast(0.0) * f + d)
        lowPulled = true
        val maxStep = skewPctPerSec.coerceAtLeast(0.0) * unit * dtSec
        val y = (pred + (a * (med - pred)).coerceIn(-maxStep, maxStep)).coerceAtLeast(0.0)
        smoothed = y
        return y
    }

    /** Speed history for the steady-driving gate; accel = speed change over the last ACCEL_SPAN_MS. */
    private fun noteSpeed(nowMs: Long, speedKmh: Double): Boolean {
        speeds.addLast(nowMs to speedKmh)
        while (speeds.isNotEmpty() && nowMs - speeds.first().first > SPEED_HIST_MS) speeds.removeFirst()
        val old = speeds.lastOrNull { nowMs - it.first >= ACCEL_SPAN_MS }
        val acc = if (old == null) 0.0 else (speedKmh - old.second) / 3.6 / ((nowMs - old.first) / 1000.0).coerceAtLeast(0.001)
        if (abs(acc) > LOW_BRAKE_ACC) brakeMs = nowMs
        val b = brakeMs
        steadyNow = speedKmh >= LOW_STEADY_KMH && abs(acc) <= LOW_STEADY_ACC && (b == null || nowMs - b >= LOW_BRAKE_HOLD_MS)
        return steadyNow
    }

    /**
     * FILL stays on through pump pauses of any length (pump limit, re-authorisation). It ends when the vehicle
     * drives away (anchor = median of the last stationary window, i.e. the final plateau) or when the level
     * really drops: 20 s median below the peak by more than max(3 %, 3 sigma) (anchor = that median).
     *
     * Top of tank: parked with no fuel burned since the peak ([usedGal] known and grown by less than
     * FILL_NO_BURN_GAL), fuel cannot have left, so a falling reading (the sender reads 99.9 then sags to ~98 as
     * the neck fills) neither ends nor lowers the fill: the level shown and the final anchor are the higher of
     * the peak and the settled median. Without a burn signal the old rule applies.
     */
    private fun followFill(speedKmh: Double, nowMs: Long, sigmaPct: Double, usedGal: Double?): Double {
        val med = median(nowMs - MEDIAN_MS)
        if (fillPeakUsedGal == null) fillPeakUsedGal = usedGal
        val peakUsed = fillPeakUsedGal
        val noBurn = usedGal != null && peakUsed != null && usedGal - peakUsed < FILL_NO_BURN_GAL
        val plateau = if (noBurn) maxOf(fillPeak, fillStillMed) else fillStillMed
        if (speedKmh > FILL_EXIT_SPEED_KMH) {
            val since = movingSinceMs ?: nowMs.also { movingSinceMs = it }
            if (nowMs - since >= FILL_EXIT_MOVE_MS) return endFill(plateau)
            smoothed = plateau
            return plateau
        }
        movingSinceMs = null
        if (med > fillPeak) {
            fillPeak = med
            fillPeakUsedGal = usedGal
        }
        fillStillMed = med
        if (noBurn) {
            smoothed = fillPeak
            return fillPeak
        }
        val drop = maxOf(FILL_DROP_MIN_PCT * unit, 3.0 * sigmaPct)
        if (med < fillPeak - drop) return endFill(med)
        smoothed = med
        return med
    }

    private fun endFill(anchor: Double): Double {
        mode = TrackMode.SKEW
        smoothed = anchor
        movingSinceMs = null
        // A fill that ends while still parked re-settles; the pre-fill baseline must not start a second fill.
        clearStop()
        return anchor
    }

    private fun clearStop() {
        stopSinceMs = null
        stopBase = null
        fillCandMs = null
    }

    /** Ends an open FILL (e.g. at session close) at the plateau median; returns the anchor or null. */
    fun finishFill(usedGal: Double? = null): Double? {
        if (mode != TrackMode.FILL) return null
        val peakUsed = fillPeakUsedGal
        val noBurn = usedGal != null && peakUsed != null && usedGal - peakUsed < FILL_NO_BURN_GAL
        return endFill(if (noBurn) maxOf(fillPeak, fillStillMed) else fillStillMed)
    }

    /**
     * Stuck-level recovery. Call with every raw reading before [isValidSample]. Readings more than
     * DROP_HOLD_PT below the smoothed level are rejected as glitches; if they persist (REANCHOR_STOP_MS while
     * stopped, REANCHOR_MOVE_MS otherwise) and agree with each other, the smoothed level re-anchors to their
     * median. Returns true when it re-anchored.
     */
    fun checkReanchor(raw: Double?, speedKmh: Double, nowMs: Long): Boolean {
        val s = smoothed
        if (raw == null || !raw.isFinite() || raw < GLITCH_RAW_PCT * unit || raw > 100.0 || s == null || s - raw <= DROP_HOLD_PT * unit || mode == TrackMode.FILL) {
            low.clear()
            lowMoving = false
            return false
        }
        low.addLast(nowMs to raw)
        if (speedKmh > FILL_ENTER_SPEED_KMH) lowMoving = true
        val need = if (lowMoving) REANCHOR_MOVE_MS else REANCHOR_STOP_MS
        if (nowMs - low.first().first < need) return false
        val v = low.map { it.second }.sorted()
        if (v.last() - v.first() > REANCHOR_SPREAD_PCT * unit) {
            low.removeFirst()
            return false
        }
        smoothed = v[v.size / 2]
        mode = TrackMode.SKEW
        clearStop()
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
        /** Low range: range-adaptive smoothing below this, and raw 0 is valid at or below it (lowRange callers). */
        const val LOW_RANGE_PCT = 20.0
        /** Band sd that gets the base time constant (the quietest steady bands, 10-15 % and 80-95 %). */
        const val LOW_SD_REF_PCT = 1.3
        /**
         * Default base time constant in the low range (setting); map-free replay over the 08-27/09-11/10-08/11-18
         * tanks (sandbox/lowrange-20261118/grid2.txt): 20 s with noise multiplier 1 and threshold 20 %.
         */
        const val LOW_TAU_SEC = 20.0
        /** Default noise multiplier (setting); 1 = f = max(1, (sd / LOW_SD_REF_PCT)^2). */
        const val LOW_NOISE_MULT = 1.0
        const val LOW_STEADY_KMH = 30.0
        /** m/s^2 */
        const val LOW_STEADY_ACC = 0.3
        const val LOW_BRAKE_ACC = 1.0
        const val LOW_BRAKE_HOLD_MS = 10_000L
        const val LOW_MEDIAN_MS = 30_000L
        const val LOW_DT_CAP_SEC = 5.0
        const val ACCEL_SPAN_MS = 3_000L
        const val SPEED_HIST_MS = 12_000L
        const val SOLID_MS = 60_000L
        const val SOLID_SPAN_SLACK_MS = 10_000L
        const val SOLID_SHARE = 0.8
        const val SOLID_MIN_N = 30
        const val SOLID_EXIT_SHARE = 0.2
        const val SOLID_EXIT_MED_PCT = 2.0
        const val GAP_REACQUIRE_MS = 300_000L
        const val ACQ_MS = 60_000L
        const val ACQ_WAIT_MS = 10_000L
        const val ACQ_TAU_SEC = 5.0
        const val ACQ_DIFF_PCT = 5.0

        fun nearEmpty(lowRange: Boolean, lowRangePct: Double = LOW_RANGE_PCT): Double =
            if (lowRange) maxOf(lowRangePct, NEAR_EMPTY_PCT) else NEAR_EMPTY_PCT
        const val GLITCH_RAW_PCT = 1.0
        const val DROP_HOLD_PT = 15.0
        /**
         * Analysis add threshold (constant, no setting): a rise this large between at-speed levels is fuel added.
         * Ends a burn chain ([BurnChains]) and breaks the Kalman fit.
         */
        const val ANALYSIS_JUMP_PCT = 5.0
        /**
         * Live follow threshold (constant, no setting): a settled-then-sustained rise this large lifts the skew
         * limit (FILL mode) so the dash jumps to a real fill. Display only. Analysis finds adds itself
         * ([BurnChains], Kalman, Pieces) and never needs this to fire.
         */
        const val LIVE_FOLLOW_PCT = 15.0
        /** Live follow: settling time after stopping before the post-stop baseline is taken (constant, no setting). */
        const val FILL_SETTLE_SEC = 60.0
        /** Raw median length for the post-stop baseline (well inside WINDOW_MS). */
        const val BASELINE_MS = 5_000L
        /** Ingest gap (ECU off, e.g. engine off at the pump) that counts as parked time when stopped on both sides. */
        const val GAP_MS = 10_000L
        const val FILL_ENTER_SPEED_KMH = 5.0
        const val FILL_EXIT_SPEED_KMH = 8.0
        const val FILL_CONFIRM_MS = 20_000L
        const val FILL_EXIT_MOVE_MS = 10_000L
        const val FILL_DROP_MIN_PCT = 3.0
        const val DEFAULT_SIGMA_PCT = 1.0
        /** Fuel burned since the fill peak below this (gal) counts as "parked, no burn" (idle ~0.6 gal/h). */
        const val FILL_NO_BURN_GAL = 0.1
        /** Sender ceiling: the ford 16C1 level saturates at 99.90; at/above this a reading means "at least full". */
        const val SENDER_CEIL_PCT = 99.85
        const val MEDIAN_MS = 20_000L
        const val WINDOW_MS = 30_000L
        const val REANCHOR_STOP_MS = 30_000L
        const val REANCHOR_MOVE_MS = 120_000L
        const val REANCHOR_SPREAD_PCT = 10.0

        fun crossedOnePercent(prev: Double?, now: Double): Boolean {
            if (prev == null) return true
            return abs(now - prev) >= 1.0
        }
    }
}
