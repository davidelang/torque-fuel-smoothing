package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BurnGateTest {
    /** Integrates like PluginService: flow in L/h, 1 s ticks, burn only when the gate allows. */
    private fun burnGal(gate: BurnGate, ticks: List<BurnGate.Input>): Double {
        var gal = 0.0
        for (i in ticks) if (gate.check(i).burn) gal += i.flow!! * Units.GAL_PER_LITER / 3600.0
        return gal
    }

    @Test
    fun test17EngineOffIgnitionOnCountsNoFuel() {
        // 14:20-14:37 on 10-10: ff125d held at ~1.01 L/h, RPM 0.
        val gate = BurnGate()
        val ticks = (0 until 970).map { BurnGate.Input(1_000L * it, 1.0107, 1_000L * it + 1, rpm = 0.0, rpmUpdMs = 1_000L * it + 1) }
        assertEquals(0.0, burnGal(gate, ticks), 0.0)
        assertEquals("engineOff", gate.check(BurnGate.Input(971_000L, 1.0107, 971_001L, rpm = 0.0)).why)
    }

    @Test
    fun test18IdleBurnsAndHeldValuesStop() {
        val gate = BurnGate()
        val idle = (0 until 120).map {
            BurnGate.Input(1_000L * it, 4.0 + 0.1 * (it % 3), rpm = 720.0 + (it % 5), rpmUpdMs = 1_000L * it + 1)
        }
        val g = burnGal(gate, idle)
        assertEquals(120 * 4.1 * Units.GAL_PER_LITER / 3600.0, g, 0.002)
        var on = 0
        for (k in 0 until 30) {
            val now = 120_000L + 1_000L * k
            if (gate.check(BurnGate.Input(now, 4.2, rpm = 730.0 + (k % 4))).burn) on++
        }
        assertTrue("on=$on", on <= (BurnGate.HELD_MS / 1000L).toInt() + 1)
        val stale = BurnGate()
        var n = 0
        for (k in 0 until 20) {
            if (stale.check(BurnGate.Input(1_000L * k, 4.0 + k * 0.01, rpm = 700.0 + k, rpmUpdMs = 5L)).burn) n++
        }
        assertTrue("n=$n", n <= 6)
    }

    @Test
    fun test19NoRpmNeedsFreshMaf() {
        val gate = BurnGate()
        assertFalse(gate.check(BurnGate.Input(0L, 1.0)).burn)
        assertEquals("noRpmNoMaf", gate.check(BurnGate.Input(1_000L, 1.1)).why)
        assertTrue(gate.check(BurnGate.Input(2_000L, 1.2, maf = 3.1)).burn)
        assertTrue(gate.check(BurnGate.Input(3_000L, 1.3, maf = 3.2)).burn)
        var last = true
        for (k in 4 until 20) last = gate.check(BurnGate.Input(1_000L * k, 1.0 + k * 0.01, maf = 3.2)).burn
        assertFalse(last)
        assertEquals("noFlow", gate.check(BurnGate.Input(21_000L, null, rpm = 700.0)).why)
    }

    @Test
    fun test20TorqueCapacityIsLitersSeed() {
        assertEquals(35.0, TankSeed.parseTorqueCapacity("132.4894123")!!, 0.01)
        assertEquals(15.0, TankSeed.parseTorqueCapacity("56.7811767")!!, 0.01)
        assertEquals(18.5, TankSeed.parseTorqueCapacity("18.5 gal")!!, 1e-9)
        assertEquals(70.0 * Units.GAL_PER_LITER, TankSeed.parseTorqueCapacity("70 L")!!, 1e-9)
        assertNull(TankSeed.parseTorqueCapacity(""))
        assertNull(TankSeed.parseTorqueCapacity("abc"))
        assertNull(TankSeed.parseTorqueCapacity("-3"))
        assertNull(TankSeed.parseTorqueCapacity(null))
        assertNull(TankSeed.parseLitersOrGal("132.4894123"))
    }
}
