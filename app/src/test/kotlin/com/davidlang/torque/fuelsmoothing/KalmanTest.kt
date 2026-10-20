package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class KalmanTest {
    private fun fixtures(): List<FuelSession> {
        val url = checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions"))
        return FuelV2.loadSessions(File(url.toURI()))
    }
    private fun tmp(): File = Files.createTempDirectory("kalman").toFile()

    @Test
    fun test47ParityWithPythonReference() {
        // python3 sandbox/research/chunk-study/ref_kalman.py app/src/test/resources/fuel-sessions 35
        val s = checkNotNull(Kalman.update(tmp(), fixtures(), 35.0))
        // re-pinned at ANALYSIS_VERSION 4; was 0.9666103417068203, 5.566355882546031, 13.380351552865456, 20.570642400086868, 27.413608366433728, 29.341457635318356, 30.220684620877897
        val ref = mapOf(10.0 to 1.178325678950228, 30.0 to 6.507533028258544, 50.0 to 12.790991933446733, 70.0 to 20.080316657540642, 90.0 to 27.323268355352766, 95.0 to 29.397258325633906, 100.0 to 30.3193172223203)
        for ((l, g) in ref) assertEquals("G($l)", g, s.g(l), 0.1)
        assertEquals(1.230838851913093, s.sig, 1e-6) // re-pinned at ANALYSIS_VERSION 4; was 1.1636352177965614
        assertEquals(87, s.done.size)
        assertEquals(98, s.coverage(1)); assertEquals(91, s.coverage(2)); assertEquals(65, s.coverage(3)) // re-pinned at ANALYSIS_VERSION 4; was 99/92/78
        assertEquals(4, s.cons.size)
    }

    @Test
    fun test48TrustTriggerAtCoverage2TenPercent() {
        val (drives, sig) = Kalman.inputs(fixtures())
        val s = Kalman.State(35.0); s.sig = sig
        val dir = tmp(); var first: String? = null
        for (d in drives) {
            s.apply(d)
            File(dir, Kalman.MODEL).writeText(Kalman.modelJson(s, 0L))
            val m = Kalman.loadModel(dir)
            if (s.coverage(2) < Kalman.TRUST_COV2) assertNull(d.key, Kalman.trustedTank(m))
            else {
                assertEquals(s.tank, checkNotNull(Kalman.trustedTank(m)), 1e-9)
                if (first == null) first = d.key
            }
        }
        // re-pinned at ANALYSIS_VERSION 4; was 1788043722773-9b86fc3c#0
        assertEquals("1787943778254-11820e3f#0", first)
    }

    @Test
    fun test49CorrectedPercentScaleInvariant() {
        fun run(k: Double): Kalman.State {
            val ss = fixtures().map { FuelSession(it.name, it.t, it.raw, it.sm, DoubleArray(it.used.size) { i -> it.used[i] * k }, it.spd, it.fill) }
            return checkNotNull(Kalman.update(tmp(), ss, 35.0 * k))
        }
        val a = run(1.0)
        for (k in doubleArrayOf(0.5, 2.0)) {
            val b = run(k)
            assertEquals(k * a.tank, b.tank, 0.03 * k * a.tank)
            for (l in doubleArrayOf(0.0, 10.0, 30.0, 50.0, 70.0, 90.0, 100.0)) {
                assertEquals("k=$k L=$l", FuelV2.correctedPct(l, { a.g(it) }, a.tank), FuelV2.correctedPct(l, { b.g(it) }, b.tank), 3.0) // was 1.0 on the thinned-raw rows
            }
        }
        assertEquals(100.0, FuelV2.correctedPct(100.0, { a.g(it) }, a.tank), 1e-9)
        assertEquals(0.0, FuelV2.correctedPct(0.0, { a.g(it) }, a.tank), 1e-12)
    }

    @Test
    fun test50StatePersistsAndReloads() {
        val dir = tmp()
        val s = checkNotNull(Kalman.update(dir, fixtures(), 35.0))
        val r = checkNotNull(Kalman.load(dir))
        for (k in 0 until Kalman.N) assertEquals(s.mu[k], r.mu[k], 1e-12)
        assertEquals(s.done, r.done); assertEquals(s.c0, r.c0, 0.0); assertEquals(s.ns2, r.ns2, 0.0)
        for (k in 0 until Kalman.N) assertEquals(s.cellCount[k], r.cellCount[k])
        val m = checkNotNull(Kalman.loadModel(dir))
        assertEquals(s.tank, m.tank, 1e-9); assertEquals(s.g(50.0), m.v(50.0), 1e-9)
        assertTrue(m.trusted)
    }

    @Test
    fun test51BootstrapIsIdempotentAndDeterministic() {
        val dir = tmp()
        val a = checkNotNull(Kalman.update(dir, fixtures(), 35.0))
        val state1 = File(dir, Kalman.STATE).readText(); val model1 = File(dir, Kalman.MODEL).readText()
        val b = checkNotNull(Kalman.update(dir, fixtures(), 99.0))   // c0 is frozen at creation; nothing new to apply
        assertEquals(state1, File(dir, Kalman.STATE).readText()); assertEquals(model1, File(dir, Kalman.MODEL).readText())
        assertEquals(a.tank, b.tank, 0.0)
        val c = checkNotNull(Kalman.update(tmp(), fixtures(), 35.0))
        for (k in 0 until Kalman.N) assertEquals(a.mu[k], c.mu[k], 0.0)
        // Rollup runs it with the chunk and band fits.
        val pd = tmp(); val raw = File(pd, "raw"); raw.mkdirs()
        File(checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions")).toURI()).listFiles()!!.filter { it.name.endsWith(".jsonl") }.forEach { it.copyTo(File(raw, it.name)) }
        ProfileStore.rollup(pd, 35.0)
        assertNotNull(Kalman.loadModel(pd))
        assertEquals(a.tank, checkNotNull(Kalman.load(pd)).tank, 1e-9)
    }

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
    fun test94OneApplyPerPartRowsInTimeOrder() {
        val n = 400
        val t = DoubleArray(n) { it.toDouble() }
        val used = DoubleArray(n) { 0.002 * it }
        val raw = DoubleArray(n) { 90.0 - used[it] / 0.25 }
        val spd = DoubleArray(n) { 60.0 }
        val s = FuelSession("drv", t, raw, raw.copyOf(), used, spd, BooleanArray(n))
        val (parts, pieces) = Pieces.cut(listOf(s))
        assertEquals(1, parts.size)
        assertTrue(pieces.size >= 2)
        val (rows, _) = Kalman.inputs(listOf(s))
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(parts.single().key, row.key)
        val blocks = Pieces.blockMedians(parts.single(), parts.single().nb)
        assertTrue(blocks.size >= 2)
        assertEquals(blocks.size, row.l.size)
        for (i in blocks.indices) {
            assertEquals(blocks[i][1], row.l[i], 0.0)
            assertEquals(blocks[i][2], row.f[i], 0.0)
            assertEquals(blocks[i][3], row.se[i], 0.0)
            if (i > 0) assertTrue(blocks[i][0] >= blocks[i - 1][0])
        }
        val st = Kalman.State(25.0)
        assertTrue(st.apply(row))
        assertFalse(st.apply(row))
    }

    @Test
    fun test95VarianceUsesRowLevelError() {
        val ns2 = 2.5
        val se = 0.4
        val gAtL = 0.3
        val v = Kalman.rowVar(ns2, se, gAtL)
        assertEquals(ns2 * ((se * gAtL) * (se * gAtL) + Kalman.SIG_F * Kalman.SIG_F), v, 0.0)
        assertTrue(Kalman.rowVar(ns2, se * 2, gAtL) > v)
    }

    @Test
    fun test96IndependentOfChunkModel() {
        val ss = fixtures()
        val clean = tmp()
        val dirty = tmp()
        File(dirty, "model.json").writeText("garbage")
        val a = checkNotNull(Kalman.update(clean, ss, 35.0))
        val b = checkNotNull(Kalman.update(dirty, ss, 35.0))
        for (k in 0 until Kalman.N) assertEquals(a.mu[k], b.mu[k], 0.0)
    }

    @Test
    fun test110NonMovingPartIsZeroRowPacket() {
        val n = 8
        val t = DoubleArray(n) { it.toDouble() }
        val used = DoubleArray(n) { it.toDouble() }
        val raw = DoubleArray(n) { 50.0 }
        val fill = BooleanArray(n)
        fill[0] = true
        val s = FuelSession("park", t, raw, raw.copyOf(), used, DoubleArray(n), fill)
        val (rows, _) = Kalman.inputs(listOf(s))
        val part = rows.single { it.fillAtStart }
        assertTrue(part.l.isEmpty())
        assertTrue(part.f.isEmpty() && part.se.isEmpty())
        assertTrue(part.fuel > 0.0)
        val st = Kalman.State(25.0)
        val mu = st.mu.copyOf()
        assertTrue(st.apply(part))
        assertEquals(part.fuel, st.epF, 0.0)
        assertTrue(st.epFill)
        for (k in mu.indices) assertEquals(mu[k], st.mu[k], 0.0)
        assertTrue(st.cons.isEmpty())
    }
}
