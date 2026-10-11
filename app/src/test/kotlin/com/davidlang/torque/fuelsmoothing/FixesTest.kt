package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FixesTest {
    private fun fixtureDir(): File = File(checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions")).toURI())
    private fun fixtures(): List<FuelSession> = FuelV2.loadSessions(fixtureDir())
    private fun tmp(p: String): File = Files.createTempDirectory(p).toFile()

    @Test
    fun test57KalmanCorruptStateKeptAndRebuiltWithOriginalC0() {
        val dir = tmp("kc")
        val a = checkNotNull(Kalman.update(dir, fixtures(), 35.0))
        val f = File(dir, Kalman.STATE)
        val text = f.readText()
        f.writeText(text.substring(0, text.length / 2)) // truncated write
        assertNull(Kalman.load(dir))
        assertNotNull(Kalman.lastLoadError)
        // The profile's tank field changed meanwhile; the rebuild must still use the original C0 (35).
        val b = checkNotNull(Kalman.update(dir, fixtures(), 99.0))
        assertTrue(File(dir, "${Kalman.STATE}.corrupt").isFile)
        assertEquals(text.length / 2, File(dir, "${Kalman.STATE}.corrupt").readText().length)
        assertEquals(35.0, b.c0, 1e-12)
        assertEquals(a.tank, b.tank, 1e-9)
        // Truncation that keeps whole lines (lost "done"/"end" lines) is detected too.
        val lines = File(dir, Kalman.STATE).readLines()
        File(dir, Kalman.STATE).writeText(lines.dropLast(3).joinToString("\n") + "\n")
        assertNull(Kalman.load(dir))
        File(dir, Kalman.MODEL).writeText("{\"version\":")
        assertNull(Kalman.loadModel(dir)) // logged, not thrown
    }

    @Test
    fun test58FuelSourceDetectionCandidatesAndPerProfileDefault() {
        val ford = FuelDetect.candidates("1FTSS34L", null, setOf("[FORD]Fuel Level", "Speed"))
        assertEquals("ford_16c1", ford.first())
        assertTrue("ford_f42f" in ford)
        assertEquals("sae_012f", ford.last())
        assertFalse(ford.any { it.startsWith("toyota") || it.startsWith("gm") })
        assertEquals(listOf("sae_012f"), FuelDetect.candidates(null, null, emptySet()))
        assertTrue("toyota_7c0_2129" in FuelDetect.candidates(null, "Toyota", emptySet()))
        val run = FuelDetect.Run(ford)
        assertEquals("ford_16c1", run.next())
        run.report("ford_16c1", null)
        run.report("ford_f42f", 140.0) // implausible: next
        assertEquals("sae_012f", run.next())
        run.report("sae_012f", 47.0)
        assertTrue(run.done); assertEquals("sae_012f", run.found)
        val none = FuelDetect.Run(listOf("sae_012f")); none.report("sae_012f", null)
        assertTrue(none.done); assertNull(none.found)
        // New profile: none, unconfirmed (no Ford default). Legacy saved global source: adopted, confirmed.
        assertEquals(FuelDetect.NONE to false, FuelDetect.resolve(null, false, null))
        assertEquals("ford_16c1" to true, FuelDetect.resolve(null, false, "ford_16c1"))
        assertEquals("gm_12c5" to false, FuelDetect.resolve("gm_12c5", false, "ford_16c1"))
        assertEquals("none", FuelSources.byId("bogus").id)
        val root = tmp("src"); File(root, "profiles/van").mkdirs(); File(root, "profiles/van/profile.json").writeText("{\"id\":\"van\",\"schema\":1}")
        assertEquals("ford_16c1" to true, FuelDetect.load(root, "van", "ford_16c1"))
        assertEquals("ford_16c1" to true, FuelDetect.load(root, "van", null)) // persisted per profile
    }

    private fun snapshot(dir: File): Map<String, String> = dir.walkTopDown()
        .filter { it.isFile && !it.path.contains("/raw/") && it.name != ProfileStore.ROLLUP_SIG && !it.name.endsWith(".tmp") }
        .associate { it.relativeTo(dir).path to it.readText().replace(Regex("\"(timestamp|ts|at)\":[0-9]+"), "") }

    @Test
    fun test59CachedIncrementalRollupIdenticalToUncached() {
        val files = fixtureDir().listFiles()!!.filter { it.name.endsWith(".jsonl") }.sortedBy { it.name }
        val a = tmp("ra"); val b = tmp("rb")
        File(a, "raw").mkdirs(); File(b, "raw").mkdirs()
        val batches = listOf(files.take(30), files.drop(30).take(30), files.drop(60))
        try {
            for (batch in batches) {
                for (f in batch) { f.copyTo(File(a, "raw/${f.name}")); f.copyTo(File(b, "raw/${f.name}")) }
                RollupCache.enabled = true
                val ga = ProfileStore.rollup(a, 35.0)
                RollupCache.enabled = false
                val gb = ProfileStore.rollup(b, 35.0)
                assertEquals(gb, ga)
                assertEquals(snapshot(b), snapshot(a))
            }
            // Nothing new: skipped, outputs untouched.
            RollupCache.enabled = true
            val before = a.walkTopDown().filter { it.isFile && it.name != ProfileStore.ROLLUP_SIG }.associate { it.path to it.lastModified() }
            Thread.sleep(20)
            ProfileStore.rollup(a, 35.0)
            assertEquals(before, a.walkTopDown().filter { it.isFile && it.name != ProfileStore.ROLLUP_SIG }.associate { it.path to it.lastModified() })
            // An open session is never read.
            val g1 = ProfileStore.rollup(a, 35.0, setOf(files.last().name.removeSuffix(".jsonl")))
            assertFalse(g1.contains(files.last().name.removeSuffix(".jsonl")))
        } finally { RollupCache.enabled = true }
    }

    @Test
    fun test60BandLambdaCachePolicy() {
        val dir = tmp("bl"); File(dir, "raw").mkdirs()
        for (f in fixtureDir().listFiles()!!.filter { it.name.endsWith(".jsonl") }) f.copyTo(File(dir, "raw/${f.name}"))
        ProfileStore.rollup(dir, 35.0)
        val (ps, ch) = ChunkFit.load(dir)
        val fresh = checkNotNull(BandFit.run(ps, ch))
        val cached = checkNotNull(BandFit.cachedLambda(dir))
        assertEquals(fresh.lambda, cached[0], 0.0)
        assertTrue(BandFit.reuse(cached, ch))
        // Reused λ gives exactly the fit at that λ.
        val other = BandFit.LAMBDAS.first { it != fresh.lambda }
        val forced = checkNotNull(BandFit.run(ps, ch, doubleArrayOf(other, 1.0, cached[2], cached[3])))
        assertEquals(other, forced.lambda, 0.0)
        assertEquals(BandFit.fit(ch, ps, other).first.toList(), forced.g.toList())
        // Five more drives (or a big chunk-count change) re-chooses by LODO = fresh result.
        val stale = doubleArrayOf(other, 1.0, cached[2] - BandFit.LAMBDA_EVERY, cached[3])
        assertFalse(BandFit.reuse(stale, ch))
        assertEquals(fresh.lambda, checkNotNull(BandFit.run(ps, ch, stale)).lambda, 0.0)
        assertFalse(BandFit.reuse(doubleArrayOf(other, 1.0, cached[2], cached[3] * 2), ch))
    }

    @Test
    fun test61NoStoreInProfileWrites() {
        val src = File("src/main/kotlin/com/davidlang/torque/fuelsmoothing/PluginService.kt")
        assertTrue(src.isFile)
        val code = src.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n")
        assertFalse(code.contains("storeInProfile("))
        assertFalse(code.contains("tankUsed"))
        assertFalse(code.contains("authoritativePct\""))
    }

    @Test
    fun test62TankUnitPickerStoresGallons() {
        assertEquals(35.0, VolUnit.L.toGal(132.49), 0.01)
        assertEquals("132.5", VolUnit.show(35.0, VolUnit.L))
        assertEquals("35.0", VolUnit.show(35.0, VolUnit.GAL))
        assertEquals("", VolUnit.show(null, VolUnit.L))
        assertEquals(12.3, VolUnit.L.toGal(VolUnit.L.fromGal(12.3)), 1e-12)
        assertEquals(VolUnit.GAL, VolUnit.of(null)); assertEquals(VolUnit.L, VolUnit.of("L"))
        assertTrue(VolUnit.FALLBACK_HINT.contains("30 gal") && VolUnit.FALLBACK_HINT.contains("25 gal"))
    }
}
