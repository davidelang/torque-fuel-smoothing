package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
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
        val ref = mapOf(10.0 to 0.9666103417068203, 30.0 to 5.566355882546031, 50.0 to 13.380351552865456, 70.0 to 20.570642400086868, 90.0 to 27.413608366433728, 95.0 to 29.341457635318356, 100.0 to 30.220684620877897)
        for ((l, g) in ref) assertEquals("G($l)", g, s.g(l), 0.1)
        assertEquals(1.1636352177965614, s.sig, 1e-6)
        assertEquals(87, s.done.size)
        assertEquals(99, s.coverage(1)); assertEquals(92, s.coverage(2)); assertEquals(78, s.coverage(3))
        assertEquals(4, s.cons.size)
    }

    @Test
    fun test48TrustTriggerAtCoverage2TenPercent() {
        val (drives, sig) = ChunkFit.kalmanDrives(fixtures(), Kalman.THIN_S)
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
        assertEquals("1788043722773-9b86fc3c#0", first)
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
                assertEquals("k=$k L=$l", FuelV2.correctedPct(l, { a.g(it) }, a.tank), FuelV2.correctedPct(l, { b.g(it) }, b.tank), 1.0)
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
}
