package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BandFitTest {
    @Test
    fun test45PrimaryAlgoSettingSwitch() {
        val chunk = TankPublish(10.0, 11.0, 40.0, 15.0, 165.0, 150.0)
        val band = TankPublish(9.0, 10.0, 36.0, 15.0, 150.0, 135.0)
        assertEquals(MapAlgo.CHUNK, FixFuelPrefs.mapAlgo(null))
        assertEquals(MapAlgo.CHUNK, FixFuelPrefs.mapAlgo("junk"))
        assertEquals(MapAlgo.BAND, FixFuelPrefs.mapAlgo("BAND"))
        assertSame(chunk, FixFuelPrefs.primary(MapAlgo.CHUNK, chunk, band))
        assertSame(band, FixFuelPrefs.primary(MapAlgo.BAND, chunk, band))
        // Band chosen but no band model yet: chunk keeps driving the outputs.
        assertSame(chunk, FixFuelPrefs.primary(MapAlgo.BAND, chunk, null))
        // The Corrected feed carries the chosen algorithm's corrected %.
        assertEquals(36.0, FixFuelPrefs.feedValue(FeedMode.CORRECTED, 50.0, FixFuelPrefs.primary(MapAlgo.BAND, chunk, band).correctedPct)!!, 1e-12)
    }
}
