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
        ProfileStore.migrate(root, null, null, "Vehicle 1", 1L)
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
        ProfileStore.migrate(root, null, null, "Vehicle 1", 2L)
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
        ProfileStore.migrate(root, null, null, "Vehicle 1", 3L)
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
        ProfileStore.migrate(root, null, null, "Vehicle 1", 1L)
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
    fun test35QuarantineNeverWritesWrongProfileAndParksUnassigned() {
        val root = twoProfiles()
        val d = Fingerprint.decide("van", Fingerprint.Fp(protocol = car.protocol, bitmaps = car.bitmaps), Fingerprint.loadAll(root))
        assertTrue(d is Fingerprint.Decision.Quarantine)
        val vanBefore = snapshot(File(root, "profiles/van"))
        val carBefore = snapshot(File(root, "profiles/car"))
        val q = quarantinedSession(root, 1000L)
        // Unanswered close no longer moves the files; resolve(null) leaves them for Unassigned.park.
        assertTrue(Quarantine.resolve(root, q, 1, null).isEmpty())
        val parked = Unassigned.park(root, q, "Van", "Confirm this is van", "kept unassigned",
            listOf(Unassigned.Choice("van", "Use van"), Unassigned.Choice(Fingerprint.NEW_PROFILE, "none, it's new")),
            van, 1000L, 2000L)
        assertTrue(parked != null)
        assertTrue(parked!!.dir.path.contains("/unassigned/1000"))
        assertTrue(Unassigned.hasSessionJsonl(parked.dir))
        assertEquals(1, Unassigned.list(root).size)
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
        val now = 1_791_700_000_000L
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(now))
        val id = Quarantine.confirmTarget(root, Fingerprint.NEW_PROFILE, now, "Vehicle $day")
        assertTrue(id, id.startsWith("vehicle-2026-"))
        assertTrue(File(root, "profiles/$id/profile.json").isFile)
        assertEquals("car", Quarantine.confirmTarget(root, "car", 0L, "Vehicle 1"))
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
    }

    @Test
    fun test39FeedPicksItsOwnMapAndMigratesFromGaugeMap() {
        assertEquals(listOf("OFF", "SMOOTHED", "CORRECTED", "CORRECTED_BAND", "CORRECTED_KALMAN"), FeedMode.entries.map { it.name })
        assertEquals(FeedMode.CORRECTED, FixFuelPrefs.migrateFeed(FeedMode.CORRECTED))
        assertEquals(FeedMode.CORRECTED_BAND, FixFuelPrefs.migrateFeed(FeedMode.CORRECTED_BAND))
        assertEquals(FeedMode.CORRECTED_KALMAN, FixFuelPrefs.migrateFeed(FeedMode.CORRECTED_KALMAN))
        assertEquals(FeedMode.SMOOTHED, FixFuelPrefs.migrateFeed(FeedMode.SMOOTHED))
        assertEquals(FeedMode.OFF, FixFuelPrefs.migrateFeed(FeedMode.OFF))
        assertEquals(FeedMode.CORRECTED_KALMAN, FixFuelPrefs.feedMode("CORRECTED_KALMAN", null))
        assertEquals(71.1, FixFuelPrefs.feedValue(FeedMode.CORRECTED, 82.7, 71.1, 73.4, 75.3)!!, 0.0)
        assertEquals(73.4, FixFuelPrefs.feedValue(FeedMode.CORRECTED_BAND, 82.7, 71.1, 73.4, 75.3)!!, 0.0)
        assertEquals(75.3, FixFuelPrefs.feedValue(FeedMode.CORRECTED_KALMAN, 82.7, 71.1, 73.4, 75.3)!!, 0.0)
        assertEquals(82.7, FixFuelPrefs.feedValue(FeedMode.SMOOTHED, 82.7, 71.1, 73.4, 75.3)!!, 0.0)
        assertNull(FixFuelPrefs.feedValue(FeedMode.OFF, 82.7, 71.1, 73.4, 75.3))
        assertEquals(82.7, FixFuelPrefs.feedValue(FeedMode.CORRECTED_KALMAN, 82.7, 71.1, null, null)!!, 0.0)
    }

    @Test
    fun test100IdentityHeader() {
        assertEquals("C410F1", identityHeader("2216C1", "SAE J1850 PWM"))
        assertEquals("", identityHeader("2216C1", null))
        assertEquals("", identityHeader("2216C1", ""))
        assertEquals("", identityHeader("2216C1", "CAN"))
        assertEquals("", identityHeader("0902", "J1850 PWM"))
    }

    @Test
    fun test101FirmProfileProbesUntilOkNoDoubleQueue() {
        assertEquals(listOf("0902", "2216C1"), identityQueue(true, "1FTBW3XM5HKA12345", "no"))
        assertEquals(listOf("0902"), identityQueue(true, "1FTBW3XM5HKA12345", "ok"))
        assertEquals(listOf("0100"), identityQueue(true, null, "ok"))
        assertEquals(listOf("0902", "2216C1"), identityQueue(true, "1FTBW3XM5HKA12345", null))
        assertEquals(listOf("0100", "2216C1"), identityQueue(true, null, null))
        assertEquals(listOf("0100", "0902", "0904", "0906", "090A", "2216C1"), identityQueue(false, "1FTBW3XM5HKA12345", "no"))
        assertEquals(listOf("0100", "0902", "0904", "0906", "090A", "2216C1"), identityQueue(false, null, null))
    }

}
