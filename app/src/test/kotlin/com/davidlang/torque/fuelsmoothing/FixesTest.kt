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
        // New-format file that lost "done" lines but kept its end line: count mismatch is detected.
        val lines = File(dir, Kalman.STATE).readLines()
        File(dir, Kalman.STATE).writeText((lines.filterNot { it.startsWith("end ") }.dropLast(2) + lines.last { it.startsWith("end ") }).joinToString("\n") + "\n")
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

    private fun stateDump(s: Kalman.State) = listOf(s.c0, s.ns2, s.sig, s.tank).toString() + s.mu.toList() + s.done.toList() + s.cons.keys.toList() + (0..3).map { s.coverage(it) }

    @Test
    fun test63LegacyStateWithoutEndLineLoads() {
        val dir = tmp("kl")
        checkNotNull(Kalman.update(dir, fixtures(), 35.0))
        val ref = checkNotNull(Kalman.load(dir))
        val f = File(dir, Kalman.STATE)
        val legacy = f.readLines().filterNot { it.startsWith("end ") }
        f.writeText(legacy.joinToString("\n") + "\n")
        val got = checkNotNull(Kalman.load(dir))
        assertNull(Kalman.lastLoadError)
        assertEquals(stateDump(ref), stateDump(got))
        // An update on top of a legacy file keeps it (no .corrupt, same state); new writes add the end line.
        assertEquals(stateDump(ref), stateDump(checkNotNull(Kalman.update(dir, fixtures(), 99.0))))
        assertFalse(File(dir, "${Kalman.STATE}.corrupt").exists())
        Kalman.save(dir, got)
        assertEquals("end ${got.done.size}", f.readLines().last())
    }

    @Test
    fun test64TruncatedLegacyStateSetAside() {
        val dir = tmp("kt")
        val a = checkNotNull(Kalman.update(dir, fixtures(), 35.0))
        val f = File(dir, Kalman.STATE)
        val legacy = f.readLines().filterNot { it.startsWith("end ") }.joinToString("\n")
        val cut = legacy.substring(0, legacy.length / 3)
        f.writeText(cut)
        assertNull(Kalman.load(dir)); assertNotNull(Kalman.lastLoadError)
        val b = checkNotNull(Kalman.update(dir, fixtures(), 99.0))
        assertEquals(cut, File(dir, "${Kalman.STATE}.corrupt").readText())
        assertEquals(35.0, b.c0, 1e-12)
        assertEquals(a.tank, b.tank, 1e-9)
    }

    private fun poll(run: FuelDetect.Run, id: String, vals: List<Pair<Double?, Long?>>) { for ((v, u) in vals) run.report(id, v, u) }

    @Test
    fun test65DetectionNeedsFreshNonZeroStableReadsAndAgreementToConfirm() {
        val ids = listOf("ford_16c1", "sae_012f")
        // Unpolled Torque PID: 0.0 with no update time, every poll -> no answer at all.
        val r0 = FuelDetect.Run(ids)
        repeat(8) { r0.report("ford_16c1", 0.0, 0L) }
        repeat(8) { r0.report("sae_012f", null, null) }
        assertTrue(r0.done); assertNull(r0.result().id)
        // Stale value (update time never advances) counts once only -> not accepted.
        val r1 = FuelDetect.Run(listOf("ford_16c1"))
        repeat(8) { r1.report("ford_16c1", 47.0, 1000L) }
        assertNull(r1.result().id)
        // Exact 0.0 from a bus reply is no answer either.
        val r2 = FuelDetect.Run(listOf("sae_012f")); repeat(8) { r2.report("sae_012f", 0.0, null) }
        assertNull(r2.result().id)
        // Unstable reads (spread > 3 %) never accepted.
        val r3 = FuelDetect.Run(listOf("sae_012f"))
        poll(r3, "sae_012f", listOf(20.0, 60.0, 35.0, 80.0, 10.0, 55.0, 90.0, 30.0).map { it to null })
        assertNull(r3.result().id)
        // One strong source: chosen but NOT confirmed (user must confirm).
        val r4 = FuelDetect.Run(ids)
        poll(r4, "ford_16c1", listOf(47.0 to 1000L, 47.2 to 2000L, 47.1 to 3000L))
        repeat(3) { r4.report("sae_012f", null, null) }
        assertEquals(FuelDetect.Result("ford_16c1", false, 47.1, "single source; needs your confirmation"), r4.result())
        // Two sources agree within 5 %: confirmed.
        val r5 = FuelDetect.Run(ids)
        poll(r5, "ford_16c1", listOf(47.0 to 1000L, 47.2 to 2000L, 47.1 to 3000L))
        poll(r5, "sae_012f", listOf(45.1 to null, 45.5 to null, 45.1 to null))
        assertTrue(r5.result().confirmed); assertEquals("ford_16c1", r5.result().id)
        // Disagreeing second source (a reused PID reading something else): not confirmed.
        val r6 = FuelDetect.Run(ids)
        poll(r6, "ford_16c1", listOf(47.0 to 1000L, 47.2 to 2000L, 47.1 to 3000L))
        poll(r6, "sae_012f", listOf(80.0 to null, 80.0 to null, 80.4 to null))
        assertFalse(r6.result().confirmed)
    }

    @Test
    fun test66BrandSourcesGatedByMakeOrVin() {
        val names = setOf("[FORD]Fuel Level")
        assertEquals("ford_16c1", FuelDetect.candidates("1FDSS3", null, names).first())
        assertFalse("ford_16c1" in FuelDetect.candidates("JTDKB20U", null, names)) // Toyota VIN: Ford PID never tried
        assertFalse("ford_16c1" in FuelDetect.candidates(null, "Honda", names))
        assertTrue("ford_16c1" in FuelDetect.candidates(null, null, names)) // unknown vehicle: tried, never self-confirmed
        val toy = FuelDetect.candidates("JTDKB20U", null, emptySet())
        assertTrue("toyota_7c0_2129" in toy); assertFalse(toy.any { it.startsWith("ford") || it.startsWith("gm") || it.startsWith("chrysler") })
        val gm = FuelDetect.candidates("1G1ZT", null, emptySet()); assertEquals(listOf("gm_12c5", "sae_012f"), gm)
        val jeep = FuelDetect.candidates("1C4RJF", null, emptySet()); assertTrue("chrysler_21a2" in jeep && "chrysler_avg" in jeep)
        assertEquals(listOf("sae_012f"), FuelDetect.candidates("WVWZZZ", "Volkswagen", emptySet()))
    }

    @Test
    fun test67RollupReleasesParsedSessions() {
        val dir = tmp("rc"); File(dir, "raw").mkdirs()
        for (f in fixtureDir().listFiles()!!.filter { it.name.endsWith(".jsonl") }.take(10)) f.copyTo(File(dir, "raw/${f.name}"))
        ProfileStore.rollup(dir, 35.0)
        assertEquals(0, RollupCache.size())
        ProfileStore.fillUpdate(dir, 35.0, null)
        assertEquals(0, RollupCache.size())
    }

    @Test
    fun test68OpenSessionReadOnlyUpToSubmitLength() {
        val src = fixtureDir().listFiles()!!.filter { it.name.endsWith(".jsonl") }.maxBy { it.length() }
        val dir = tmp("os"); val f = File(dir, src.name); src.copyTo(f)
        val text = f.readText()
        val cut = text.length / 2
        val whole = text.lastIndexOf('\n', cut - 1) + 1
        val partial = File(dir, "p.jsonl"); partial.writeText(text.substring(0, whole))
        // Appends continue after the submit length, including a half-written line.
        f.appendText("{\"t\":9")
        val capped = checkNotNull(FuelV2.parseSession(f, cut.toLong()))
        val ref = checkNotNull(FuelV2.parseSession(partial))
        assertEquals(ref.t.toList(), capped.t.toList()); assertEquals(ref.raw.toList(), capped.raw.toList())
        assertEquals(ref.used.toList(), capped.used.toList())
        val id = src.name.removeSuffix(".jsonl")
        val s = FuelV2.loadSessions(dir, exclude = setOf("p"), caps = mapOf(id to cut.toLong()))
        assertEquals(ref.t.size, s.single().t.size)
    }

    @Test
    fun test69ProfileFieldHandlesQuotesAndConcurrentWriters() {
        val root = tmp("pf"); File(root, "profiles/van").mkdirs()
        File(root, "profiles/van/profile.json").writeText("{\"id\":\"van\",\"name\":\"Van\",\"odometerPins\":[],\"schema\":1}")
        ProfileStore.setProfileField(root, "van", "make", "Ford \"E-350\" / Club\\Wagon")
        assertEquals("Ford \"E-350\" / Club\\Wagon", ProfileStore.profileField(root, "van", "make"))
        ProfileStore.setProfileField(root, "van", "make", "Ford")
        assertEquals("Ford", ProfileStore.profileField(root, "van", "make"))
        assertEquals("Van", ProfileStore.profileField(root, "van", "name"))
        ProfileStore.setProfileField(root, "van", "newKey", "x") // no "schema" anchor needed any more
        val ts = (0 until 8).map { k -> Thread { repeat(25) { n -> ProfileStore.setProfileField(root, "van", "k$k", "v$n") } } }
        ts.forEach { it.start() }; ts.forEach { it.join() }
        for (k in 0 until 8) assertEquals("lost write k$k", "v24", ProfileStore.profileField(root, "van", "k$k"))
        org.json.JSONObject(File(root, "profiles/van/profile.json").readText()) // still valid JSON
        assertEquals("x", ProfileStore.profileField(root, "van", "newKey"))
    }

    @Test
    fun test70IdentityLogIsRedacted() {
        val fp = Fingerprint.Fp(vin = "1FDSS34L05HA12345", calId = "RZAA0F1", cvn = "AB12CD34", protocol = "ISO 15765-4")
        val r = Fingerprint.redacted(fp)
        assertFalse(r.contains("1FDSS34L05HA")); assertTrue(r.contains("2345"))
        assertFalse(r.contains("RZAA0F1")); assertFalse(r.contains("AB12CD34"))
        val code = File("src/main/kotlin/com/davidlang/torque/fuelsmoothing/PluginService.kt").readText()
        assertFalse(Regex("Log\\.i\\([^\\n]*Fingerprint\\.toJson").containsMatchIn(code))
    }

    @Test
    fun test71AtomicArtefactsShortDestroyWaitAndProWording() {
        val ps = File("src/main/kotlin/com/davidlang/torque/fuelsmoothing/ProfileStore.kt").readText()
        for (n in listOf("model.json", "model-raw.json", "gap.txt")) assertFalse(n, ps.contains("File(dir, \"$n\").writeText"))
        assertFalse(ps.contains("sigFile.writeText"))
        val svc = File("src/main/kotlin/com/davidlang/torque/fuelsmoothing/PluginService.kt").readText()
        assertTrue(svc.contains("awaitTermination(2,")); assertTrue(svc.contains("if (!destroyed) done()"))
        val strings = File("src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("Torque Lite does not support plugins"))
    }
}
