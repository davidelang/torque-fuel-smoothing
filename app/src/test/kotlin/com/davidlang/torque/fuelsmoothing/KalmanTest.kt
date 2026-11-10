package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Test

class KalmanTest {
    @Test
    fun test53Clamp() {
        // Fill-detection settings (jump_pct, fill_settle_sec) are retired; only smoothing settings are clamped.
        assertEquals(0.0 to "601", FixFuelPrefs.clamp("601", FixFuelPrefs.DEFAULT_TAU) { FixFuelPrefs.tauOk(it) })
        assertEquals(0.05 to "0", FixFuelPrefs.clamp("0", FixFuelPrefs.DEFAULT_SKEW) { FixFuelPrefs.skewOk(it) })
    }
}
