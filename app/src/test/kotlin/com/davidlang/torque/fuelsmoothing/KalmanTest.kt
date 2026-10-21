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
}
