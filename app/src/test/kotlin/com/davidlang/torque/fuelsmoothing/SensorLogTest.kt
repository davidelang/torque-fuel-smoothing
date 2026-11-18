package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLogTest {
    private fun reply(accX: Double = 0.012, height: Double = 412.37, bearing: Double = 271.6) =
        doubleArrayOf(accX, -0.0204, 0.998, 1.0, bearing, bearing, 96.54, height, 4.0)

    @Test
    fun test124NeverRequestsOrWritesPosition() {
        val req = SensorLog.request().map { it.lowercase() }
        assertTrue(req.none { it in SensorLog.FORBIDDEN })
        assertTrue("ff1005" in SensorLog.FORBIDDEN && "ff1006" in SensorLog.FORBIDDEN)
        assertEquals(listOf("ff1220", "ff1221", "ff1222", "ff1223"), req.take(4))
        val o = SensorLog.row(1000L, reply(), SensorLog.State())!!
        val keys = o.keys().asSequence().toSet()
        assertTrue(keys.none { it.contains("lat", true) || it.contains("lon", true) })
        assertFalse("gpsHeight" in keys) // absolute height is never written
        assertEquals("sensors", o.getString("event"))
    }

    @Test
    fun test125RoundsAndWritesHeightOnlyAsChangeFromFirstFix() {
        val st = SensorLog.State()
        assertNull(SensorLog.row(0L, doubleArrayOf(1.0), st)) // wrong size
        val a = SensorLog.row(1000L, reply(height = 0.0), st)!! // no fix yet: no height base
        assertFalse(a.has("gpsHeightDelta"))
        val b = SensorLog.row(2000L, reply(height = 412.37), st)!!
        assertEquals(0.0, b.getDouble("gpsHeightDelta"), 1e-9)
        val c = SensorLog.row(3000L, reply(accX = 0.01234, height = 409.07, bearing = 271.4), st)!!
        assertEquals(-3.3, c.getDouble("gpsHeightDelta"), 1e-9)
        assertEquals(0.012, c.getDouble("accXg"), 1e-9)
        assertEquals(271L, c.getLong("gpsBearingDeg"))
        assertEquals(96.5, c.getDouble("gpsSpeed"), 1e-9)
    }

    @Test
    fun test126AtMostOneRowPerSecond() {
        val st = SensorLog.State()
        assertTrue(SensorLog.due(st, 10_000L))
        SensorLog.row(10_000L, reply(), st)
        assertFalse(SensorLog.due(st, 10_500L))
        assertTrue(SensorLog.due(st, 11_000L))
        assertTrue(SensorLog.due(st, 5_000L)) // clock went back
        val nan = DoubleArray(SensorLog.PIDS.size) { Double.NaN }
        assertNull(SensorLog.row(12_000L, nan, st))
    }
}
