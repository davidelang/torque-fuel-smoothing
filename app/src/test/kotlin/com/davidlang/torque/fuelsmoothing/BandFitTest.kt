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
        Pieces.store(dir, fixtures())
        val (pp, pcs) = Pieces.load(dir)
        val (ps, ch) = ChunkFit.fromPieces(pp, pcs)
        val r = checkNotNull(BandFit.run(ps, ch))
        assertEquals(1000.0, r.lambda, 1e-12) // re-pinned at ANALYSIS_VERSION 3; was 0.3
        assertEquals(0.5877055468859453, r.lodoRms, 0.01) // re-pinned at ANALYSIS_VERSION 3; was 0.7888755388909849
        // re-pinned at ANALYSIS_VERSION 3; was 0.10999842790069102, 0.11750118095460527, 0.12665796151545836, 0.07023368673078903, 0.05543437924058408, 0.16508600454649938, 0.1960590927139637, 0.5186412249115221, 0.297467452280851, 0.39696646763395727, 0.3049384363970512, 0.15410127496483594, 0.35186108612259653, 0.4496919096176997, 0.16870148610926652, 0.5093458845940436, 0.3028831534978558, 0.47327606645886894, 0.6280135494624337, 0.7884888201395112
        val ref = doubleArrayOf(0.4124416814393542, 0.32756763392448573, 0.2453202170030607, 0.17391702709729998, 0.12511044178023242, 0.10666060772326011, 0.12224977940721046, 0.17134840892341496, 0.2420531575876042, 0.3167608094751123, 0.3825978441169252, 0.4307455611826585, 0.4564968020516288, 0.4584808993789942, 0.43931653344107846, 0.4048994898672622, 0.35941068404746357, 0.30778784374375023, 0.25297212146580855, 0.19736884957574397)
        for (k in ref.indices) assertEquals("g[$k]", ref[k], r.g[k], 0.005)
        assertEquals(28.150380044863493, r.v95, 0.05) // re-pinned at ANALYSIS_VERSION 3; was 28.703841593871978
        assertEquals(29.254804077719847, r.capToZero, 0.05) // re-pinned at ANALYSIS_VERSION 3; was 29.650719074892795
    }

    @Test
    fun test44BandCorrectedPercentIsScaleInvariant() {
        val base = fixtures()
        fun fitScaled(k: Double): BandFit.Result {
            val s = base.map { FuelSession(it.name, it.t, it.raw, it.sm, DoubleArray(it.used.size) { i -> it.used[i] * k }, it.spd, it.fill) }
            val dir = tmp()
            Pieces.store(dir, s)
            val (pp, pcs) = Pieces.load(dir)
            val (ps, ch) = ChunkFit.fromPieces(pp, pcs)
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
        val (pp, pcs) = Pieces.load(dir)
        val (ps, ch) = ChunkFit.fromPieces(pp, pcs)
        val r = checkNotNull(BandFit.run(ps, ch))
        for (k in g.indices) assertEquals(r.g[k], g[k], 1e-12)
        assertEquals(r.capToZero, cap, 1e-12)
        assertNotNull(ProfileStore.storedMap(dir))
        val before = File(dir, BandFit.FILE).readText()
        ProfileStore.rollup(dir, 35.0)
        assertEquals(before, File(dir, BandFit.FILE).readText())
    }
}
