package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Settle-then-rise fill detection (FuelMath.ingest): a fill is measured from the post-stop baseline. */
class FillDetectTest {
    private val jump = 5.0
    private val settle = 60.0

    /** One sample per second from [t0] for [secs] seconds; returns the next time. */
    private fun FuelMath.run(t0: Long, secs: Int, speed: Double, settleSec: Double = settle, raw: (Int) -> Double): Long {
        for (i in 0 until secs) ingest(raw(i), speed, nowMs = t0 + i * 1000L, jumpPct = jump, settleSec = settleSec)
        return t0 + secs * 1000L
    }

    @Test
    fun parkingAttitudeStepVsSmoothedIsNotAFill() {
        // Driveway case: moving at 40 %, then parked nose-high and the gauge reads 12 points higher at once.
        // Old rule (raw - smoothed >= jump while stopped) would confirm a fill; the new rule absorbs it.
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 40.0 }
        t = m.run(t, 180, 0.0) { 52.0 }
        assertNotEquals(TrackMode.FILL, m.mode)
        assertEquals(52.0, m.stopBaseline!!, 1e-9)
    }

    @Test
    fun riseDuringSettleWindowDoesNotConfirm() {
        // Slosh up 10 points for the first 40 s of the stop, then back: never a fill.
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 40.0 }
        t = m.run(t, 40, 0.0) { 50.0 }
        t = m.run(t, 120, 0.0) { 40.0 }
        assertNotEquals(TrackMode.FILL, m.mode)
    }

    @Test
    fun sustainedRiseAfterBaselineIsAFill() {
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 30.0 }
        t = m.run(t, 65, 0.0) { 30.0 }          // settled; baseline 30
        assertEquals(30.0, m.stopBaseline!!, 1e-9)
        t = m.run(t, 25, 0.0) { 37.0 }          // +7 sustained > 20 s
        assertEquals(TrackMode.FILL, m.mode)
    }

    @Test
    fun shortSpikeAfterBaselineIsNotAFill() {
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 30.0 }
        t = m.run(t, 65, 0.0) { 30.0 }
        t = m.run(t, 8, 0.0) { 45.0 }
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
    fun settleZeroTakesBaselineOnFirstStoppedSample() {
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0, settleSec = 0.0) { 30.0 }
        m.ingest(31.0, 0.0, nowMs = t, jumpPct = jump, settleSec = 0.0)
        assertEquals(31.0, m.stopBaseline!!, 1e-9)
        t += 1000L
        t = m.run(t, 25, 0.0, settleSec = 0.0) { 38.0 }
        assertEquals(TrackMode.FILL, m.mode)
    }

    @Test
    fun engineOffGapWhileStoppedUsesPreGapLevel() {
        // Stop, engine off a few seconds later (no samples), fuel added, engine on still parked.
        val m = FuelMath()
        var t = m.run(0L, 60, 50.0) { 20.0 }
        t = m.run(t, 8, 0.0) { 22.0 }
        t += 300_000L
        m.ingest(34.0, 0.0, nowMs = t, jumpPct = jump, settleSec = settle)
        assertNotNull(m.stopBaseline)
        assertEquals(22.0, m.stopBaseline!!, 1e-9)
        t = m.run(t + 1000L, 25, 0.0) { 34.0 }
        assertEquals(TrackMode.FILL, m.mode)
    }
}
