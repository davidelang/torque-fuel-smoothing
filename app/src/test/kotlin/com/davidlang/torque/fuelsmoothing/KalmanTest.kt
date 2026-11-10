package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Test

class KalmanTest {
    @Test
    fun test53Clamp() {
        fun jump(raw: String?) = FixFuelPrefs.clamp(raw, FixFuelPrefs.DEFAULT_JUMP) { FixFuelPrefs.jumpOk(it) }
        // Minimum fill % floor is FixFuelPrefs.MIN_JUMP (1); 5 is now a valid setting.
        assertEquals(5.0 to null, jump("5"))
        assertEquals(25.0 to "0.5", jump("0.5"))
        assertEquals(25.0 to "abc", jump("abc"))
        assertEquals(25.0 to "", jump(""))
        assertEquals(25.0 to "150", jump("150"))
        assertEquals(25.0 to null, jump("25"))
        assertEquals(25.0 to null, jump(" 25 "))
        assertEquals(25.0 to " 0.5 ", jump(" 0.5 "))
        assertEquals(0.0 to "601", FixFuelPrefs.clamp("601", FixFuelPrefs.DEFAULT_TAU) { FixFuelPrefs.tauOk(it) })
        assertEquals(0.05 to "0", FixFuelPrefs.clamp("0", FixFuelPrefs.DEFAULT_SKEW) { FixFuelPrefs.skewOk(it) })
        fun settle(raw: String?) = FixFuelPrefs.clamp(raw, FixFuelPrefs.DEFAULT_SETTLE) { FixFuelPrefs.settleOk(it) }
        assertEquals(60.0 to null, settle(null))
        assertEquals(0.0 to null, settle("0"))
        assertEquals(300.0 to null, settle("300"))
        assertEquals(60.0 to "301", settle("301"))
        assertEquals(60.0 to "-1", settle("-1"))
        assertEquals(60.0 to "NaN", settle("NaN"))
    }
}
