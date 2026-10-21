package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Test

class KalmanTest {
    @Test
    fun test52KalmanSettingChoice() {
        val c = TankPublish(10.0, 11.0, 40.0, 15.0, 165.0, 150.0); val b = TankPublish(9.0, 10.0, 36.0, 15.0, 150.0, 135.0)
        val k = TankPublish(8.0, 9.0, 33.0, 15.0, 135.0, 120.0)
        assertEquals(MapAlgo.KALMAN, FixFuelPrefs.mapAlgo("KALMAN"))
        assertEquals(k, FixFuelPrefs.primary(MapAlgo.KALMAN, c, b, k))
        assertEquals(c, FixFuelPrefs.primary(MapAlgo.KALMAN, c, b, null))
        assertEquals(b, FixFuelPrefs.primary(MapAlgo.BAND, c, b, k))
        assertEquals(c, FixFuelPrefs.primary(MapAlgo.CHUNK, c, b, k))
    }

    @Test
    fun test53Clamp() {
        fun jump(raw: String?) = FixFuelPrefs.clamp(raw, FixFuelPrefs.DEFAULT_JUMP) { FixFuelPrefs.jumpOk(it) }
        assertEquals(25.0 to "5", jump("5"))
        assertEquals(25.0 to "abc", jump("abc"))
        assertEquals(25.0 to "", jump(""))
        assertEquals(25.0 to "150", jump("150"))
        assertEquals(25.0 to null, jump("25"))
        assertEquals(25.0 to null, jump(" 25 "))
        assertEquals(25.0 to " 5 ", jump(" 5 "))
        assertEquals(0.0 to "601", FixFuelPrefs.clamp("601", FixFuelPrefs.DEFAULT_TAU) { FixFuelPrefs.tauOk(it) })
        assertEquals(0.05 to "0", FixFuelPrefs.clamp("0", FixFuelPrefs.DEFAULT_SKEW) { FixFuelPrefs.skewOk(it) })
    }
}
