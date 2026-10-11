package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BandFitTest {
    private fun fixtures(): List<FuelSession> {
        val url = checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions"))
        return FuelV2.loadSessions(File(url.toURI()))
    }
    private fun tmp(): File = Files.createTempDirectory("bandfit").toFile()

    @Test
    fun test43BandParityWithPythonReference() {
        // python3 sandbox/research/chunk-study/ref_bandfit.py app/src/test/resources/fuel-sessions
        val dir = tmp()
        ChunkFit.append(dir, fixtures())
        val (ps, ch) = ChunkFit.load(dir)
        val r = checkNotNull(BandFit.run(ps, ch))
        assertEquals(0.3, r.lambda, 1e-12)
        assertEquals(0.7888755388909849, r.lodoRms, 0.01)
        val ref = doubleArrayOf(0.10999842790069102, 0.11750118095460527, 0.12665796151545836, 0.07023368673078903, 0.05543437924058408, 0.16508600454649938, 0.1960590927139637, 0.5186412249115221, 0.297467452280851, 0.39696646763395727, 0.3049384363970512, 0.15410127496483594, 0.35186108612259653, 0.4496919096176997, 0.16870148610926652, 0.5093458845940436, 0.3028831534978558, 0.47327606645886894, 0.6280135494624337, 0.7884888201395112)
        for (k in ref.indices) assertEquals("g[$k]", ref[k], r.g[k], 0.005)
        assertEquals(28.703841593871978, r.v95, 0.05)
        assertEquals(29.650719074892795, r.capToZero, 0.05)
    }

    @Test
    fun test44BandCorrectedPercentIsScaleInvariant() {
        val base = fixtures()
        fun fitScaled(k: Double): BandFit.Result {
            val s = base.map { FuelSession(it.name, it.t, it.raw, it.sm, DoubleArray(it.used.size) { i -> it.used[i] * k }, it.spd, it.fill) }
            val (ps, ch) = ChunkFit.segmentAll(s)
            return checkNotNull(BandFit.run(ps, ch))
        }
        val a = fitScaled(1.0)
        for (k in doubleArrayOf(0.5, 2.0)) {
            val b = fitScaled(k)
            assertEquals(k * a.v95, b.v95, 0.02 * k * a.v95)
            for (lvl in doubleArrayOf(0.0, 5.0, 20.0, 40.0, 60.0, 80.0, 95.0)) {
                assertEquals("k=$k lvl=$lvl", FuelV2.correctedPct(lvl, { a.v(it) }, a.capToZero), FuelV2.correctedPct(lvl, { b.v(it) }, b.capToZero), 0.5)
            }
        }
        // Gauge 0 maps to 0 %, and full reaches 100 % (capToZero >= V(100) is clamped).
        assertEquals(0.0, FuelV2.correctedPct(0.0, { a.v(it) }, a.capToZero), 1e-12)
        assertTrue(FuelV2.correctedPct(100.0, { a.v(it) }, a.capToZero) > 95.0)
    }

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

    @Test
    fun test46BothModelsPersistedAndReloaded() {
        val dir = tmp()
        val raw = File(dir, "raw"); raw.mkdirs()
        val src = File(checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions")).toURI())
        src.listFiles()!!.filter { it.name.endsWith(".jsonl") }.forEach { it.copyTo(File(raw, it.name)) }
        ProfileStore.rollup(dir, 35.0)
        assertTrue(File(dir, "model.json").isFile)
        val text = File(dir, BandFit.FILE).readText()
        assertTrue(text, text.contains("\"version\":${BandFit.VERSION}") && text.contains("\"source\":\"band\"") && text.contains("\"timestamp\":"))
        val (g, cap) = checkNotNull(BandFit.load(dir))
        val (ps, ch) = ChunkFit.load(dir)
        val r = checkNotNull(BandFit.run(ps, ch))
        for (k in g.indices) assertEquals(r.g[k], g[k], 1e-12)
        assertEquals(r.capToZero, cap, 1e-12)
        assertNotNull(ProfileStore.storedMap(dir))
        val before = File(dir, BandFit.FILE).readText()
        ProfileStore.rollup(dir, 35.0)
        assertEquals(before, File(dir, BandFit.FILE).readText())
    }
}
