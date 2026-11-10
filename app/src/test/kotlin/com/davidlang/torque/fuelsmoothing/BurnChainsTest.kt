package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Analysis-only burn chains: adds and gaps found from at-speed levels, never from live fill flags. */
class BurnChainsTest {
    /** Builds one session, 1 Hz, from segments of (seconds, speed km/h, level start, level end); burn 0.001 gal/s. */
    private class B(val name: String, val t0: Double) {
        val t = ArrayList<Double>(); val raw = ArrayList<Double>(); val spd = ArrayList<Double>(); val used = ArrayList<Double>()
        val fill = ArrayList<Boolean>()
        fun seg(secs: Int, speed: Double, l0: Double, l1: Double = l0, fillFlag: Boolean = false, level: ((Int) -> Double)? = null): B {
            for (i in 0 until secs) {
                val now = t0 + t.size
                t.add(now)
                raw.add(level?.invoke(i) ?: (l0 + (l1 - l0) * i / maxOf(1, secs - 1)))
                spd.add(speed)
                used.add((used.lastOrNull() ?: 0.0) + 0.001)
                fill.add(fillFlag)
            }
            return this
        }
        fun build() = FuelSession(
            name, t.toDoubleArray(), raw.toDoubleArray(), raw.toDoubleArray(), used.toDoubleArray(), spd.toDoubleArray(),
            fill.toBooleanArray(),
        )
    }

    private val day = 86_400.0

    @Test
    fun steadyBurnAcrossSessionsIsOneHardChain() {
        val a = B("a", 0.0).seg(600, 60.0, 90.0, 80.0).build()
        val b = B("b", day).seg(600, 60.0, 80.0, 70.0).build()
        val c = BurnChains.build(listOf(a, b))
        assertEquals(1, c.size)
        assertEquals("open", c[0].endedBy)
        assertTrue(c[0].hard)
        assertEquals(0.0, c[0].gapPct, 0.0)
        assertTrue(c[0].startPct > 88.0 && c[0].endPct < 72.0)
        // Burn spans most of both sessions (1.2 gal total; the median windows trim the ends).
        assertTrue(c[0].burnedGal > 1.0 && c[0].burnedGal < 1.2)
        assertEquals(2, c[0].sessions)
    }

    @Test
    fun betweenSessionAtSpeedRiseClosesChain() {
        val a = B("a", 0.0).seg(600, 60.0, 50.0, 40.0).build()
        val b = B("b", day).seg(600, 60.0, 90.0, 85.0).build()
        val c = BurnChains.build(listOf(a, b))
        assertEquals(2, c.size)
        assertEquals("rise", c[0].endedBy)
        assertEquals(50.0, c[0].risePct, 1.5)
        assertEquals("rise", c[1].startedBy)
        assertTrue(c[1].startPct > 88.0)
    }

    @Test
    fun inSessionRiseWhileStoppedClosesChain() {
        // Drive, stop 5 minutes at the pump (level rises while parked), drive on higher. No live fill flag.
        val s = B("a", 0.0)
            .seg(600, 60.0, 40.0, 30.0)
            .seg(300, 0.0, 30.0, 60.0)
            .seg(600, 60.0, 60.0, 55.0)
            .build()
        val c = BurnChains.build(listOf(s))
        assertEquals(2, c.size)
        assertEquals("rise", c[0].endedBy)
        assertTrue(c[1].startPct > 58.0)
    }

    @Test
    fun liveFillFlagIsIgnored() {
        // A live fill flag on a parked stretch with no at-speed rise does not break the chain.
        val s = B("a", 0.0)
            .seg(600, 60.0, 50.0, 45.0)
            .seg(300, 0.0, 45.0, 45.0, fillFlag = true)
            .seg(600, 60.0, 45.0, 40.0)
            .build()
        assertEquals(1, BurnChains.build(listOf(s)).size)
    }

    @Test
    fun betweenSessionDropAccumulatesGap() {
        val a = B("a", 0.0).seg(600, 60.0, 90.0, 80.0).build()
        val b = B("b", day).seg(600, 60.0, 70.0, 65.0).build()   // 10 points vanished between drives
        val c2 = B("c", 2 * day).seg(600, 60.0, 65.0, 60.0).build()  // small 0-point step: not a gap
        val c = BurnChains.build(listOf(a, b, c2))
        assertEquals(1, c.size)
        assertFalse(c[0].hard)
        assertEquals(1, c[0].gaps)
        assertEquals(10.9, c[0].gapPct, 1.0)
    }

    @Test
    fun parkedOnlyAttitudeJumpIsNotAnAdd() {
        // Drive ends at 40 % (at speed), parked nose-high reads 53 %, a parked-only session also reads 53 %,
        // next drive is back at 39 % at speed. Parked levels are never compared: one chain, no gap.
        val a = B("a", 0.0).seg(600, 60.0, 45.0, 40.0).seg(300, 0.0, 53.0).build()
        val parked = B("p", day).seg(300, 0.0, 53.0).build()
        val b = B("b", 2 * day).seg(120, 0.0, 53.0).seg(600, 60.0, 39.5, 35.0).build()
        val c = BurnChains.build(listOf(a, parked, b))
        assertEquals(1, c.size)
        assertTrue(c[0].hard)
        assertEquals(3, c[0].sessions)
    }

    @Test
    fun missingLevelsAreSkippedNotEmpty() {
        // lvl -1 / ok:false rows are NaN after parseSession; they must never read as an empty tank.
        val s = B("a", 0.0).seg(1200, 60.0, 0.0, level = { i -> if (i % 3 == 0) Double.NaN else 60.0 - i / 120.0 }).build()
        val c = BurnChains.build(listOf(s))
        assertEquals(1, c.size)
        assertTrue(c[0].endPct > 45.0)
        assertTrue(c[0].startPct < 61.0)
    }

    @Test
    fun jsonCarriesTheSummaryFields() {
        val a = B("a", 0.0).seg(600, 60.0, 90.0, 80.0).build()
        val j = BurnChains.json(BurnChains.build(listOf(a)), 5.0)
        for (k in listOf("startPct", "endPct", "burnedGal", "gapPct", "hard", "t0", "t1", "sessions", "endedBy")) {
            assertTrue(k, j.contains("\"$k\":"))
        }
    }
}
