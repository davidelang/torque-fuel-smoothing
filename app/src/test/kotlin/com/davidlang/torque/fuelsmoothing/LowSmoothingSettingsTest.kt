package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LowSmoothingSettingsTest {
    @Test
    fun test148LowSmoothingSettingsValidateAndDefaultToTheReplayBest() {
        assertEquals(20.0, FixFuelPrefs.DEFAULT_LOW_PCT, 0.0)
        assertEquals(20.0, FixFuelPrefs.DEFAULT_LOW_TAU, 0.0)
        assertEquals(1.0, FixFuelPrefs.DEFAULT_LOW_NOISE, 0.0)
        assertTrue(FixFuelPrefs.lowPctOk(0.0))
        assertTrue(FixFuelPrefs.lowPctOk(50.0))
        assertFalse(FixFuelPrefs.lowPctOk(50.1))
        assertFalse(FixFuelPrefs.lowPctOk(-1.0))
        assertTrue(FixFuelPrefs.lowTauOk(600.0))
        assertFalse(FixFuelPrefs.lowTauOk(600.1))
        assertTrue(FixFuelPrefs.lowNoiseOk(0.0))
        assertFalse(FixFuelPrefs.lowNoiseOk(Double.NaN))
        assertFalse(FixFuelPrefs.lowNoiseOk(5.5))
        assertEquals(FixFuelPrefs.DEFAULT_LOW_TAU to "abc", FixFuelPrefs.clamp("abc", FixFuelPrefs.DEFAULT_LOW_TAU, FixFuelPrefs::lowTauOk))
    }

    @Test
    fun test149NoiseMultiplierAndThresholdShapeTheLowStep() {
        val unit = 1.0
        // sd 2.6 = 2 x the reference: f = 1 + m x 3.
        assertEquals(4.0, FuelMath.LowInput({ 2.6 }).factor(2.6, unit), 1e-9)
        assertEquals(1.0, FuelMath.LowInput({ 2.6 }, noiseMult = 0.0).factor(2.6, unit), 0.0)
        assertEquals(7.0, FuelMath.LowInput({ 2.6 }, noiseMult = 2.0).factor(2.6, unit), 1e-9)
        assertEquals(1.0, FuelMath.LowInput({ 0.5 }).factor(0.5, unit), 0.0) // quiet bands never speed up
        // Threshold 0 = off: a 10 % level takes the plain slew path even with low-range inputs.
        val m = FuelMath()
        m.ingest(10.0, 60.0, nowMs = 0L, low = FuelMath.LowInput({ 2.0 }, rangePct = 0.0))
        m.ingest(9.0, 60.0, nowMs = 1000L, low = FuelMath.LowInput({ 2.0 }, rangePct = 0.0))
        assertFalse(m.lowPath)
        assertEquals(9.95, m.smoothed!!, 1e-9)
        // A raw 0 at 10 % is then a glitch again (old near-empty rule).
        assertFalse(m.isValidSample(0.0, true, 10.0, lowRange = true, lowRangePct = 0.0))
    }
}
