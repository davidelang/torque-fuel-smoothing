package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelV2Test {
    @Test
    fun test11RawLogMonotonicFrozenAndHeld() {
        val fill = RawLog()
        fill.addDelta(1.0)
        fill.sample(1_000L, 40.0, "ford_fli_pwm", 1.0, "gal/h", 10.0, true, true, null, "SKEW", 40.0)
        fill.addDelta(1.0)
        fill.sample(2_000L, 55.0, "ford_fli_pwm", 1.0, "gal/h", 0.0, true, true, null, "FILL", 55.0)
        fill.event("fillEnd", 2_000L)
        fill.addDelta(0.5)
        fill.sample(3_000L, 90.0, "ford_fli_pwm", 0.5, "gal/h", 0.0, true, true, null, "SKEW", 90.0)
        val gals = ArrayList<Double>()
        for (line in fill.finish()) {
            if (!line.contains("\"fuelGal\"")) continue
            val v = line.substringAfter("\"fuelGal\":").substringBefore(",").toDouble()
            gals.add(v)
        }
        assertEquals(listOf(1.0, 2.0, 2.5), gals)
        for (i in 1 until gals.size) assertTrue(gals[i] >= gals[i - 1])

        val frozen = RawLog()
        for (i in 0 until 70) {
            val spd = if (i < 30) 10.0 else 0.0
            frozen.sample(1_000L + i, 40.0, "src", null, "", spd, true, true, null, "SKEW", 40.0)
        }
        val frozenLines = frozen.finish()
        val samples = frozenLines.filter { it.contains("\"lvl\"") }
        assertEquals(70, samples.size)
        assertTrue(samples.all { it.contains("\"ok\":false") && it.contains("\"why\":\"frozen\"") })
        assertTrue(frozenLines.any { it.contains("\"event\":\"frozen\"") })

        val held = RawLog()
        held.sample(5_000L, 20.0, "src", null, "", 0.0, false, false, "held:drop>15", "HOLD", 30.0)
        val heldLine = held.finish().single { it.contains("\"lvl\"") }
        assertTrue(heldLine.contains("\"ok\":false"))
        assertTrue(heldLine.contains("\"why\":\"held:drop>15\""))
    }
}
