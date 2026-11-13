package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PidsTest {
    @Test
    fun test110PluginPidCatalogIsUniqueAndOmitsRetired() {
        val extras = listOf(2, 3)
        val rows = Pids.allOutputs(extras)
        assertEquals(rows.map { it.name }.distinct(), rows.map { it.name })
        assertEquals(rows.map { it.shortName }.distinct(), rows.map { it.shortName })
        assertEquals(rows.map { it.modeAndPid }.distinct(), rows.map { it.modeAndPid })
        assertTrue(rows.any { it.name == Pids.PLUGIN_SMOOTHED && it.shortName == "SmFuel" })
        assertTrue(rows.any { it.name == Pids.PLUGIN_CHUNK_CORRECTED && it.shortName == "CorrChk" })
        assertTrue(rows.any { it.name == Pids.PLUGIN_BAND_CORRECTED && it.shortName == "CorrBand" })
        assertTrue(rows.any { it.name == Pids.PLUGIN_KALMAN_CORRECTED && it.shortName == "CorrKal" })
        assertTrue(rows.any { it.name == Pids.PLUGIN_TANK_CHUNK && it.shortName == "TankChk" })
        assertTrue(rows.any { it.name == Pids.PLUGIN_TANK_BAND && it.shortName == "TankBand" })
        assertTrue(rows.any { it.name == Pids.PLUGIN_TANK_KALMAN && it.shortName == "TankKal" })
        val t2 = Pids.extraPidNames(2)
        assertTrue(rows.any { it.name == t2.smoothed && it.shortName == t2.shortSm })
        assertTrue(rows.any { it.name == t2.corrected && it.shortName == t2.shortCorr })
        val retired = Pids.retiredNames(extras)
        assertTrue(retired.contains(Pids.PLUGIN_CHUNK_REMAINING))
        assertTrue(retired.contains(Pids.PLUGIN_KALMAN_DTE))
        assertTrue(retired.contains(t2.remaining))
        assertTrue(retired.contains(t2.dte))
        for (name in retired) {
            assertFalse("retired $name must not be registered or written", rows.any { it.name == name })
        }
        assertEquals("l", Pids.volumeUnit(true))
        assertEquals("gal", Pids.volumeUnit(false))
        assertEquals(Pids.TANK_SIZE_MAX_GAL, Pids.volumeMax(false))
        assertEquals(Pids.output(Pids.PLUGIN_KALMAN_CORRECTED)!!.shortName, "CorrKal")
    }
}
