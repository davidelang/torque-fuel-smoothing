package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IdentityTest {
    private fun tmp(): File = Files.createTempDirectory("identity").toFile()

    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText() }

    private val van = Fingerprint.Fp(
        vin = "1FTBW3XM5HKA12345", calId = "HC3A-14C204-AB", protocol = "ISO 15765-4 (CAN 11/500)",
        bitmaps = mapOf("0100" to "BE3FA813", "0120" to "A005B011"), sessions = 3,
    )
    private val car = Fingerprint.Fp(
        vin = "JTDKB20U093512345", calId = "34715000", protocol = "ISO 15765-4 (CAN 11/500)",
        bitmaps = mapOf("0100" to "98180013", "0120" to "8001A001"), sessions = 3,
    )

    /** Legacy layout: profiles/default named by Torque "Van", active = default. */
    private fun legacyRoot(): File {
        val root = tmp()
        ProfileStore.migrate(root, null, null, 1L)
        ProfileStore.setProfileField(root, "default", "torqueProfileName", "Van")
        val raw = File(root, "profiles/default/raw")
        File(raw, "1791667223532-4865ff23.jsonl").writeText("{\"t\":1,\"lvl\":50}\n")
        File(root, "profiles/default/model.json").writeText("{\"x\":1}")
        return root
    }

    @Test
    fun test32DefaultRenamedToTorqueSlugAtomicallyAndIdempotently() {
        val root = legacyRoot()
        val before = snapshot(File(root, "profiles/default"))
        assertEquals("van", ProfileStore.renameDefaultToSlug(root))
        assertFalse(File(root, "profiles/default").exists())
        assertEquals("van", ProfileStore.activeId(root))
        assertEquals("van", ProfileStore.profileField(root, "van", "id"))
        assertEquals("Van", ProfileStore.profileField(root, "van", "name"))
        val after = snapshot(File(root, "profiles/van"))
        // Same files, same data (only profile.json id/name changed); nothing copied or deleted.
        assertEquals(before.keys, after.keys)
        for (k in before.keys) if (k != "profile.json") assertEquals(k, before[k], after[k])
        // Idempotent: again is a no-op, and migrate keeps using van.
        assertNull(ProfileStore.renameDefaultToSlug(root))
        ProfileStore.migrate(root, null, null, 2L)
        assertFalse(File(root, "profiles/default").exists())
        assertEquals(after, snapshot(File(root, "profiles/van")))
    }

    @Test
    fun test33RenameResumesAfterCrash() {
        // Crash after the journal and folder rename, before profile.json/active were updated.
        val root = legacyRoot()
        File(root, "profiles/rename-journal.json").writeText("{\"from\":\"default\",\"to\":\"van\"}")
        assertTrue(File(root, "profiles/default").renameTo(File(root, "profiles/van")))
        assertEquals("default", ProfileStore.activeId(root))
        ProfileStore.migrate(root, null, null, 3L)
        assertEquals("van", ProfileStore.activeId(root))
        assertEquals("van", ProfileStore.profileField(root, "van", "id"))
        assertFalse(File(root, "profiles/rename-journal.json").exists())
        assertTrue(File(root, "profiles/van/raw/1791667223532-4865ff23.jsonl").isFile)
    }

    @Test
    fun test34FingerprintParseMergeAndMatch() {
        val reply = listOf("49 02 01 31 46 54 42", "49 02 02 57 33 58 4D", "49 02 03 35 48 4B 41", "49 02 04 31 32 33 34", "49 02 05 35")
        assertEquals("1FTBW3XM5HKA12345", ProfileStore.parseVin(reply))
        assertEquals("BE3FA813", Fingerprint.parseBitmap(listOf("41 00 BE 3F A8 13"), "0100"))
        val obs = Fingerprint.Fp(protocol = van.protocol, bitmaps = mapOf("0100" to "BE3FA813"))
        val m = Fingerprint.merge(Fingerprint.Fp(), obs)
        assertEquals(1, m.sessions); assertFalse(m.firm)
        assertTrue(Fingerprint.merge(m, obs).firm)
        val all = mapOf("van" to van, "car" to car)
        // VIN wins, even over a conflicting calibration ID.
        assertEquals("van", Fingerprint.match(Fingerprint.Fp(vin = van.vin, calId = car.calId), all).id)
        assertTrue(Fingerprint.match(Fingerprint.Fp(calId = car.calId), all).let { it.id == "car" && it.certain })
        // Bitmaps + protocol: similar enough = match (not certain); a different protocol never matches.
        val bm = Fingerprint.match(Fingerprint.Fp(protocol = van.protocol, bitmaps = van.bitmaps), all)
        assertEquals("van", bm.id); assertFalse(bm.certain)
        assertNull(Fingerprint.match(Fingerprint.Fp(protocol = "SAE J1850 PWM", bitmaps = van.bitmaps), all).id)
        // Round trip.
        assertEquals(van, Fingerprint.fromJson(Fingerprint.toJson(van)))
        // Decisions.
        assertTrue(Fingerprint.decide("van", Fingerprint.Fp(vin = van.vin), all) is Fingerprint.Decision.Normal)
        val r = Fingerprint.decide("van", Fingerprint.Fp(vin = car.vin), all)
        assertTrue(r is Fingerprint.Decision.Route && r.target == "car")
        val q = Fingerprint.decide("van", Fingerprint.Fp(protocol = car.protocol, bitmaps = car.bitmaps), all)
        assertTrue(q is Fingerprint.Decision.Quarantine && q.suggest == "car")
        val n = Fingerprint.decide("van", Fingerprint.Fp(vin = "WVWZZZ1JZXW000001"), all)
        assertTrue(n is Fingerprint.Decision.Quarantine && n.suggest == null)
        // Nothing read from the ECU, or a still-learning profile: normal.
        assertTrue(Fingerprint.decide("van", Fingerprint.Fp(), all) is Fingerprint.Decision.Normal)
        assertTrue(Fingerprint.decide("new", Fingerprint.Fp(vin = "WVWZZZ1JZXW000001"), mapOf("new" to Fingerprint.Fp())) is Fingerprint.Decision.Normal)
    }

    private fun twoProfiles(): File {
        val root = tmp()
        ProfileStore.migrate(root, null, null, 1L)
        ProfileStore.setProfileField(root, "default", "torqueProfileName", "Van")
        ProfileStore.renameDefaultToSlug(root)
        val carId = ProfileStore.createProfile(root, "Car", 2L)
        Fingerprint.save(ProfileStore.profileDir(root, "van"), van)
        Fingerprint.save(ProfileStore.profileDir(root, carId), car)
        return root
    }

    private fun quarantinedSession(root: File, stamp: Long): File {
        val q = Quarantine.dir(root, stamp)
        val raw = Quarantine.rawDir(q, 1).apply { mkdirs() }
        File(raw, "$stamp-abcd.jsonl").writeText("{\"t\":$stamp,\"lvl\":40,\"fuelGal\":0.1,\"spd\":60}\n")
        return q
    }

    @Test
    fun test35QuarantineNeverWritesWrongProfileAndDiscards() {
        val root = twoProfiles()
        val d = Fingerprint.decide("van", Fingerprint.Fp(protocol = car.protocol, bitmaps = car.bitmaps), Fingerprint.loadAll(root))
        assertTrue(d is Fingerprint.Decision.Quarantine)
        val vanBefore = snapshot(File(root, "profiles/van"))
        val carBefore = snapshot(File(root, "profiles/car"))
        val q = quarantinedSession(root, 1000L)
        val moved = Quarantine.resolve(root, q, 1, null)
        assertEquals(1, moved.size)
        assertTrue(moved[0].path.contains("quarantine/discarded"))
        assertEquals(vanBefore, snapshot(File(root, "profiles/van")))
        assertEquals(carBefore, snapshot(File(root, "profiles/car")))
    }

    @Test
    fun test36CertainIdentityRoutesToMatchedProfile() {
        val root = twoProfiles()
        val d = Fingerprint.decide("van", Fingerprint.Fp(vin = car.vin), Fingerprint.loadAll(root))
        assertTrue(d is Fingerprint.Decision.Route)
        val target = (d as Fingerprint.Decision.Route).target
        assertEquals("car", target)
        val vanBefore = snapshot(File(root, "profiles/van"))
        // A held (or user-confirmed) session lands in the matched profile's raw dir only.
        val q = quarantinedSession(root, 2000L)
        val moved = Quarantine.resolve(root, q, 1, target)
        assertEquals(File(root, "profiles/car/raw/2000-abcd.jsonl").path, moved.single().path)
        assertEquals(vanBefore, snapshot(File(root, "profiles/van")))
    }

    @Test
    fun test37NotificationOffersSuggestedAndCreateNew() {
        assertEquals(listOf("car", Fingerprint.NEW_PROFILE), Fingerprint.actions("car"))
        assertEquals(listOf(Fingerprint.NEW_PROFILE), Fingerprint.actions(null))
        val root = twoProfiles()
        val vanBefore = snapshot(File(root, "profiles/van"))
        val id = Quarantine.confirmTarget(root, Fingerprint.NEW_PROFILE, 1_791_700_000_000L)
        assertTrue(id, id.startsWith("vehicle-2026-"))
        assertTrue(File(root, "profiles/$id/profile.json").isFile)
        assertEquals("car", Quarantine.confirmTarget(root, "car", 0L))
        val moved = Quarantine.resolve(root, quarantinedSession(root, 3000L), 1, id)
        assertEquals(File(root, "profiles/$id/raw/3000-abcd.jsonl").path, moved.single().path)
        assertEquals(vanBefore, snapshot(File(root, "profiles/van")))
    }

    @Test
    fun test38FeedModeOffSmoothedCorrected() {
        // Default = previous behavior (corrected); the legacy unchecked box maps to Off.
        assertEquals(FeedMode.CORRECTED, FixFuelPrefs.feedMode(null, null))
        assertEquals(FeedMode.CORRECTED, FixFuelPrefs.feedMode(null, true))
        assertEquals(FeedMode.OFF, FixFuelPrefs.feedMode(null, false))
        assertEquals(FeedMode.SMOOTHED, FixFuelPrefs.feedMode("SMOOTHED", false))
        assertEquals(FeedMode.CORRECTED, FixFuelPrefs.feedMode("bogus", null))
        assertNull(FixFuelPrefs.feedValue(FeedMode.OFF, 40.0, 35.0))
        assertEquals(40.0, FixFuelPrefs.feedValue(FeedMode.SMOOTHED, 40.0, 35.0)!!, 0.0)
        assertEquals(35.0, FixFuelPrefs.feedValue(FeedMode.CORRECTED, 40.0, 35.0)!!, 0.0)
        assertNull(FixFuelPrefs.feedValue(FeedMode.CORRECTED, 40.0, Double.NaN))
    }

    @Test
    fun test39FeedPicksItsOwnMapAndMigratesFromGaugeMap() {
        assertEquals(listOf("OFF", "SMOOTHED", "CORRECTED", "CORRECTED_BAND", "CORRECTED_KALMAN"), FeedMode.entries.map { it.name })
        assertEquals(FeedMode.CORRECTED, FixFuelPrefs.migrateFeed(FeedMode.CORRECTED, MapAlgo.CHUNK))
        assertEquals(FeedMode.CORRECTED_BAND, FixFuelPrefs.migrateFeed(FeedMode.CORRECTED, MapAlgo.BAND))
        assertEquals(FeedMode.CORRECTED_KALMAN, FixFuelPrefs.migrateFeed(FeedMode.CORRECTED, MapAlgo.KALMAN))
        assertEquals(FeedMode.SMOOTHED, FixFuelPrefs.migrateFeed(FeedMode.SMOOTHED, MapAlgo.KALMAN))
        assertEquals(FeedMode.OFF, FixFuelPrefs.migrateFeed(FeedMode.OFF, MapAlgo.BAND))
        assertEquals(FeedMode.CORRECTED_KALMAN, FixFuelPrefs.feedMode("CORRECTED_KALMAN", null))
        // Independent of the Gauge map: each feed uses its own value.
        assertEquals(71.1, FixFuelPrefs.feedValue(FeedMode.CORRECTED, 82.7, 71.1, 73.4, 75.3)!!, 0.0)
        assertEquals(73.4, FixFuelPrefs.feedValue(FeedMode.CORRECTED_BAND, 82.7, 71.1, 73.4, 75.3)!!, 0.0)
        assertEquals(75.3, FixFuelPrefs.feedValue(FeedMode.CORRECTED_KALMAN, 82.7, 71.1, 73.4, 75.3)!!, 0.0)
        assertEquals(82.7, FixFuelPrefs.feedValue(FeedMode.SMOOTHED, 82.7, 71.1, 73.4, 75.3)!!, 0.0)
        assertNull(FixFuelPrefs.feedValue(FeedMode.OFF, 82.7, 71.1, 73.4, 75.3))
        // Model not built yet: falls back to Chunk.
        assertEquals(71.1, FixFuelPrefs.feedValue(FeedMode.CORRECTED_KALMAN, 82.7, 71.1, null, null)!!, 0.0)
        val ps = File("src/main/kotlin/com/davidlang/torque/fuelsmoothing/PluginService.kt").readText()
        assertTrue(ps.contains("writeStockFuelLevel(t, smoothed, chunkOut.correctedPct, bandOut?.correctedPct, kalOut?.correctedPct)"))
    }

    @Test
    fun test40EveryMapPublishedAsItsOwnPids() {
        val ps = File("src/main/kotlin/com/davidlang/torque/fuelsmoothing/PluginService.kt").readText()
        val reg = ps.substringAfter("private fun sendExtraPidRows").substringBefore("for (extra in cfg.extras)")
        val modes = listOf(Pids.MODE_SMOOTHED, Pids.MODE_CORRECTED, Pids.MODE_REMAINING, Pids.MODE_DTE,
            Pids.MODE_CHUNK_CORRECTED, Pids.MODE_CHUNK_REMAINING, Pids.MODE_CHUNK_DTE,
            Pids.MODE_BAND_CORRECTED, Pids.MODE_BAND_REMAINING, Pids.MODE_BAND_DTE,
            Pids.MODE_KALMAN_CORRECTED, Pids.MODE_KALMAN_REMAINING, Pids.MODE_KALMAN_DTE,
            Pids.MODE_TANK_CHUNK, Pids.MODE_TANK_BAND, Pids.MODE_TANK_KALMAN)
        assertEquals(modes.size, modes.toSet().size)
        for (n in 2..8) for (w in 1..4) assertFalse(Pids.extraModePid(n, w) in modes)
        for (k in listOf("CHUNK_CORRECTED", "CHUNK_REMAINING", "CHUNK_DTE", "BAND_CORRECTED", "BAND_REMAINING", "BAND_DTE",
                "KALMAN_CORRECTED", "KALMAN_REMAINING", "KALMAN_DTE", "TANK_CHUNK", "TANK_BAND", "TANK_KALMAN"))
            assertTrue(k, reg.contains("Pids.MODE_$k") && ps.contains("Pids.PLUGIN_$k, \""))
        val live = ps.substringAfter("private fun publishFromSmoothed").substringBefore("private fun publishExtraFrom")
        for (c in listOf("publishChunk(t, chunkOut)", "publishBand(t, bandOut)", "publishKalman(t, kalOut)", "publishTankSizes(t)")) assertTrue(c, live.contains(c))
    }

    @Test
    fun test41SettingsFirstHelpAboutOnSubPages() {
        val a = File("src/main/kotlin/com/davidlang/torque/fuelsmoothing/PluginActivity.kt").readText()
        val top = a.substringAfter("override fun onCreate").substringBefore("// ---- Settings ----")
        // Above the settings only the title row, the compact status and (if missing) the Torque Pro notice reach the page.
        assertFalse(Regex("""col\.addView\((help|about|privacy|feedback|tipButton|tipChoices|thanksView|manual)\)""").containsMatchIn(top))
        assertTrue(top.contains("helpPage.addView(help)") && top.contains("aboutPage.addView(about)") && top.contains("aboutPage.addView(tipButton)"))
        assertTrue(top.contains("statusLine.maxLines = 2"))
        val settings = a.substringAfter("// ---- Settings ----").substringBefore("// Details (below the settings).")
        assertTrue(Regex("""col\.addView\(info\(""").findAll(settings).count() >= 12)
        assertFalse(settings.contains("col.addView(label("))
    }
}
