package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sqrt

class LowRangeTest {
    /** Map-free low-range inputs: steady noise 2.0 pt, default threshold / time constant / noise multiplier. */
    private fun low() = FuelMath.LowInput({ 2.0 })

    @Test
    fun test135RawZeroIsValidInTheLowRangeOnly() {
        val m = FuelMath()
        m.ingest(12.0, 0.0, nowMs = 0L)
        assertTrue(m.isValidSample(0.0, true, 12.0, lowRange = true))
        assertTrue(m.isValidSample(0.5, true, 12.0, lowRange = true))
        assertFalse(m.isValidSample(0.0, true, 25.0, lowRange = true))
        assertFalse(m.isValidSample(0.0, true, null, lowRange = true))
        // Without the low-range inputs the old rule stands: 0 only once smoothed <= 5, < 1 above that is a glitch.
        assertFalse(m.isValidSample(0.0, true, 12.0))
        assertFalse(m.isValidSample(0.5, true, 12.0))
        assertTrue(m.isValidSample(0.0, true, 4.0))
    }

    @Test
    fun test136SmoothedIsMapFreeBelow20AndAbove20NothingChanges() {
        val m = FuelMath()
        m.ingest(10.0, 0.0, nowMs = 0L, low = low())
        // Parked/idling is not steady driving: the level holds (no burn or map moves smoothed).
        for (i in 1..5) m.ingest(6.0, 0.0, nowMs = i * 1000L, usedGal = 0.13 * i, low = low())
        assertEquals(10.0, m.smoothed!!, 1e-9)
        assertTrue(m.lowPath)
        assertFalse(m.lowPulled)
        // A rejected reading holds too (the corrected outputs carry the burn).
        m.holdLow(0.0, 6000L, low())
        assertEquals(10.0, m.smoothed!!, 1e-9)
        val n = FuelMath()
        n.ingest(50.0, 60.0, nowMs = 0L, low = low())
        n.ingest(40.0, 60.0, nowMs = 1000L, usedGal = 0.13, low = low())
        assertEquals(49.95, n.smoothed!!, 1e-9) // plain 0.05 pt/s slew
        assertFalse(n.lowPath)
    }

    @Test
    fun test137SolidZeroHoldsZeroAndEndsOnASustainedRise() {
        val m = FuelMath()
        m.ingest(3.0, 60.0, nowMs = 0L, low = low())
        for (i in 1..70) m.ingest(0.0, 60.0, nowMs = i * 1000L, low = low())
        assertTrue(m.solidZero)
        assertEquals(0.0, m.smoothed!!, 0.0)
        // A level that comes back (no fill) ends the hold once zeros are under 20 % of the last minute.
        for (i in 71..150) m.ingest(6.0, 60.0, nowMs = i * 1000L, low = low())
        assertFalse(m.solidZero)
        assertTrue(m.smoothed!! > 0.0 && m.smoothed!! <= 6.0)
    }

    @Test
    fun test138BouncingZerosAreNoisyLowsAndCannotPullTheMedian() {
        val m = FuelMath()
        m.ingest(8.0, 60.0, nowMs = 0L, low = low())
        for (i in 1..120) m.ingest(if (i % 5 == 0) 0.0 else 8.0, 60.0, nowMs = i * 1000L, low = low())
        assertFalse(m.solidZero)
        assertEquals(8.0, m.smoothed!!, 1e-9)
    }

    @Test
    fun test139StaleGuardTreatsASolidZeroAsCensoredWithTheFloor() {
        val g = StaleLevelGuard()
        for (i in 0..40) assertEquals(StaleLevelGuard.Verdict.OK, g.observe(0.0, i * 0.05, 39.6, floor = 0.0))
        val old = StaleLevelGuard()
        assertEquals(StaleLevelGuard.Verdict.OK, old.observe(0.0, 0.0, 39.6))
        assertEquals(StaleLevelGuard.Verdict.REFRESH, old.observe(0.0, 0.6, 39.6))
    }

    @Test
    fun test140BandNoiseSeedsLearnsAndTheTankCloseKeepsTheReserve() {
        assertEquals(2.08, BandNoise.sd(BandNoise.Acc(), 2.0), 1e-9)
        assertEquals(0.75, BandNoise.sd(BandNoise.Acc(), 99.9), 1e-9)
        val st = LowRangeState()
        for (i in 1..120) BandNoise.add(st.tank.bands, 2.0, if (i % 2 == 0) 1.0 else -1.0)
        BandNoise.add(st.tank.bands, 2.0, 40.0) // a glitch counts as MAX_DEV only
        assertNull(st.reserveMinGal)
        st.tank.firstZeroMs = 1L
        st.tank.solidGal = 0.31
        st.tank.sinceFirstZeroGal = 0.45
        val sum = LowRange.closeTank(st, 2L, "fill")
        assertEquals("fill", sum.getString("why"))
        assertEquals(121.0, st.bands.n[0], 0.0)
        assertEquals(sqrt((120 * 2.08 * 2.08 + 120 + 225) / 241.0), BandNoise.sd(st.bands, 2.0), 1e-9)
        assertEquals(0.31, st.reserveMinGal!!, 1e-12)
        assertEquals(0.0, st.tank.solidGal, 0.0)
        assertNull(st.tank.firstZeroMs)
        val dir = Files.createTempDirectory("lowrange").toFile()
        LowRange.save(dir, st)
        val back = LowRange.load(dir)
        assertEquals(0.31, back.reserveMinGal!!, 1e-12)
        assertEquals(121.0, back.bands.n[0], 0.0)
    }

    @Test
    fun test141AfterAnUnloggedGapTheLevelReacquiresFast() {
        val m = FuelMath()
        m.ingest(15.0, 60.0, nowMs = 0L, low = low())
        for (i in 1..10) m.ingest(15.0, 60.0, nowMs = i * 1000L, low = low())
        val t0 = 10_000L + 600_000L
        for (i in 0..80) m.ingest(4.0, 60.0, nowMs = t0 + i * 1000L, low = low())
        assertTrue(abs(m.smoothed!! - 4.0) < 0.1)
    }
}
