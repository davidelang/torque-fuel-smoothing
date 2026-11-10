package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live follow (FuelMath.ingest): display-only skew unlock after a settled-then-sustained rise. Fixed thresholds,
 * no settings: FuelMath.FILL_SETTLE_SEC (60 s) and FuelMath.LIVE_FOLLOW_PCT (15 %).
 */
class FillDetectTest {
    /** One sample per second from [t0] for [secs] seconds, with the service's fixed live thresholds; returns the next time. */
    private fun FuelMath.run(t0: Long, secs: Int, speed: Double, raw: (Int) -> Double): Long {
        for (i in 0 until secs) ingest(raw(i), speed, nowMs = t0 + i * 1000L)
        return t0 + secs * 1000L
    }

    @Test
    fun defaultsAreTheFixedLiveThresholds() {
        assertEquals(15.0, FuelMath.LIVE_FOLLOW_PCT, 0.0)
        assertEquals(60.0, FuelMath.FILL_SETTLE_SEC, 0.0)
        assertEquals(5.0, FuelMath.ANALYSIS_JUMP_PCT, 0.0)
    }

    @Test
    fun parkingAttitudeStepVsSmoothedIsNotAFill() {
        // Driveway case: moving at 40 %, then parked nose-high and the gauge reads 13 points higher at once.
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 40.0 }
        t = m.run(t, 180, 0.0) { 53.0 }
        assertNotEquals(TrackMode.FILL, m.mode)
        assertEquals(53.0, m.stopBaseline!!, 1e-9)
    }

    @Test
    fun riseDuringSettleWindowDoesNotConfirm() {
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 40.0 }
        t = m.run(t, 40, 0.0) { 60.0 }
        t = m.run(t, 120, 0.0) { 40.0 }
        assertNotEquals(TrackMode.FILL, m.mode)
    }

    @Test
    fun sustainedLargeRiseAfterBaselineUnlocksSkew() {
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 30.0 }
        t = m.run(t, 65, 0.0) { 30.0 }          // settled; baseline 30
        assertEquals(30.0, m.stopBaseline!!, 1e-9)
        t = m.run(t, 25, 0.0) { 47.0 }          // +17 sustained > 20 s
        assertEquals(TrackMode.FILL, m.mode)
        assertEquals(47.0, m.smoothed!!, 1e-9)   // jumped, not crawling at 0.05 %/s
    }

    @Test
    fun smallerSustainedRiseDoesNotUnlockLive() {
        // 10 points is an add for analysis (>= 5 %) but below the 15 % live follow; the dash keeps crawling.
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 30.0 }
        t = m.run(t, 65, 0.0) { 30.0 }
        t = m.run(t, 60, 0.0) { 40.0 }
        assertNotEquals(TrackMode.FILL, m.mode)
        assertTrue(m.smoothed!! < 35.0)
    }

    @Test
    fun shortSpikeAfterBaselineIsNotAFill() {
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 30.0 }
        t = m.run(t, 65, 0.0) { 30.0 }
        t = m.run(t, 8, 0.0) { 55.0 }
        t = m.run(t, 60, 0.0) { 30.0 }
        assertNotEquals(TrackMode.FILL, m.mode)
    }

    @Test
    fun trafficLightClearsStopBeforeBaseline() {
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 30.0 }
        t = m.run(t, 30, 0.0) { 36.0 }
        assertNull(m.stopBaseline)
        t = m.run(t, 5, 40.0) { 30.0 }
        t = m.run(t, 40, 0.0) { 36.0 }
        assertNull(m.stopBaseline)               // clock restarted; 40 s < 60 s settle
        assertNotEquals(TrackMode.FILL, m.mode)
    }

    @Test
    fun engineOffGapWhileStoppedUsesPreGapLevel() {
        // Stop, engine off a few seconds later (no samples), fuel added, engine on still parked.
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 20.0 }
        t = m.run(t, 8, 0.0) { 22.0 }
        t += 300_000L
        m.ingest(44.0, 0.0, nowMs = t)
        assertNotNull(m.stopBaseline)
        assertEquals(22.0, m.stopBaseline!!, 1e-9)
        t = m.run(t + 1000L, 25, 0.0) { 44.0 }
        assertEquals(TrackMode.FILL, m.mode)
    }
}
