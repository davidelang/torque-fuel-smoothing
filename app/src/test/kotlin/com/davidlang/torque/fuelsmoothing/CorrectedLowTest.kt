package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrectedLowTest {
    @Test
    fun test145BelowTheLowRangeBurnMovesCorrectedThroughItsOwnSize() {
        val c = CorrectedLow()
        // 40 gal map: 0.4 gal = 1 corrected pt. Smoothed held at 10 (not steady): only the burn moves corrected.
        assertEquals(8.0, c.step(8.0, 10.0, 20.0, false, false, false, 0.0, 40.0, 0L), 1e-12)
        assertEquals(7.0, c.step(8.0, 10.0, 20.0, false, false, false, 0.4, 40.0, 1000L), 1e-12)
        assertEquals(6.0, c.step(8.0, 10.0, 20.0, false, false, false, 0.8, 40.0, 2000L), 1e-12)
        // Same tick again: no second step.
        assertEquals(6.0, c.step(8.0, 10.0, 20.0, false, false, false, 1.2, 40.0, 2000L), 1e-12)
        // A steady smoothed update pulls toward the map of smoothed (60 s): 1 s -> 1/61 of the gap.
        assertEquals(6.0 + 2.0 / 61.0, c.step(8.0, 10.0, 20.0, false, true, false, 0.8, 40.0, 3000L), 1e-12)
        // A new session (counter back to 0) is no burn.
        val y = c.pct!!
        assertEquals(y, c.step(8.0, 10.0, 20.0, false, false, false, 0.0, 40.0, 4000L), 1e-12)
    }

    @Test
    fun test146SolidZeroHoldsZeroAndAboveTheRangeIsTheMapOfSmoothed() {
        val c = CorrectedLow()
        c.step(5.0, 6.0, 20.0, false, false, false, 0.0, 40.0, 0L)
        assertEquals(0.0, c.step(5.0, 6.0, 20.0, true, false, false, 1.0, 40.0, 1000L), 0.0)
        assertEquals(30.0, c.step(30.0, 35.0, 20.0, false, false, false, 1.0, 40.0, 2000L), 0.0)
        // A rise of more than 15 pt (missed fill) or a re-acquire takes the map of smoothed.
        val d = CorrectedLow()
        d.step(2.0, 3.0, 20.0, false, false, false, 0.0, 40.0, 0L)
        assertEquals(19.0, d.step(19.0, 19.5, 20.0, false, false, false, 0.0, 40.0, 1000L), 0.0)
        assertEquals(12.0, d.step(12.0, 14.0, 20.0, false, false, true, 0.4, 40.0, 2000L), 0.0)
    }

    @Test
    fun test147NoOwnSizeLeavesTheOutputUnity() {
        val c = CorrectedLow()
        assertEquals(10.0, c.step(10.0, 10.0, 20.0, false, false, false, 0.0, Double.NaN, 0L), 0.0)
        assertEquals(10.0, c.step(10.0, 10.0, 20.0, false, false, false, 5.0, Double.NaN, 1000L), 0.0)
        assertNull(c.pct)
    }
}
