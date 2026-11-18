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
        // A complete, declared-length CAN 0902 block is a VIN.
        val can = listOf("014", "0: 49 02 01 31 46 54", "1: 42 57 33 58 4D 35 48", "2: 4B 41 31 32 33 34 35")
        assertEquals("1FTBW3XM5HKA12345", ProfileStore.parseVin(can))
        // Pre-CAN per-line packets: one complete numbered set is a VIN (more cases in test150).
        val lines = listOf("49 02 01 31 46 54 42", "49 02 02 57 33 58 4D", "49 02 03 35 48 4B 41", "49 02 04 31 32 33 34", "49 02 05 35")
        assertEquals("1FTBW3XM5HKA12345", ProfileStore.parseVin(lines))
        // A block that is short of its declared length is not a VIN either.
        assertNull(ProfileStore.parseVin(listOf("014", "0: 49 02 01 31 46 54", "1: 42 57 33 58 4D 35 48")))
        assertEquals("BE3FA813", Fingerprint.parseBitmap(listOf("41 00 BE 3F A8 13"), "0100"))
        val obs = Fingerprint.Fp(protocol = van.protocol, bitmaps = mapOf("0100" to "BE3FA813"))
        val m = Fingerprint.merge(Fingerprint.Fp(), obs)
        assertEquals(1, m.sessions); assertFalse(m.firm)
        assertTrue(Fingerprint.merge(m, obs).firm)
        val all = mapOf("van" to van, "car" to car)
        // VIN wins, even over a conflicting calibration ID.
        assertEquals("van", Fingerprint.match(Fingerprint.Fp(vin = van.vin, calId = car.calId), all).id)
        assertTrue(Fingerprint.match(Fingerprint.Fp(vin = van.vin), all).certain)
        // Calibration IDs and bitmaps are never physical keys (VIN-less vehicles go through solidMatch /
        // vinlessCandidates and a confirmation instead): no match, even for an identical bitmap.
        assertNull(Fingerprint.match(Fingerprint.Fp(calId = car.calId), all).id)
        val bm = Fingerprint.match(Fingerprint.Fp(protocol = van.protocol, bitmaps = van.bitmaps), all)
        assertNull(bm.id); assertFalse(bm.certain)
        assertNull(Fingerprint.match(Fingerprint.Fp(protocol = "SAE J1850 PWM", bitmaps = van.bitmaps), all).id)
        // Round trip.
        assertEquals(van, Fingerprint.fromJson(Fingerprint.toJson(van)))
        // Decisions.
        assertTrue(Fingerprint.decide("van", Fingerprint.Fp(vin = van.vin), all) is Fingerprint.Decision.Normal)
        val r = Fingerprint.decide("van", Fingerprint.Fp(vin = car.vin), all)
        assertTrue(r is Fingerprint.Decision.Route && r.target == "car")
        // Another car's bitmap is held, and a bitmap never suggests a profile by itself.
        val q = Fingerprint.decide("van", Fingerprint.Fp(protocol = car.protocol, bitmaps = car.bitmaps), all)
        assertTrue(q is Fingerprint.Decision.Quarantine && q.suggest == null)
        val n = Fingerprint.decide("van", Fingerprint.Fp(vin = "WVWZZZ1JZXW000001"), all)
        assertTrue(n is Fingerprint.Decision.Quarantine && n.suggest == null)
        // Nothing read from the ECU, or an unknown VIN on a still-learning profile: never silently normal.
        assertTrue(Fingerprint.decide("van", Fingerprint.Fp(), all) is Fingerprint.Decision.Quarantine)
        assertTrue(Fingerprint.decide("new", Fingerprint.Fp(vin = "WVWZZZ1JZXW000001"), mapOf("new" to Fingerprint.Fp())) is Fingerprint.Decision.Quarantine)
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
    fun test150PreCanVinOnlyFromOneCompleteNumberedSet() {
        val vin = "1FTBW3XM5HKA12345"
        // J1979 layout: 5 packets x 4 bytes, packet 1 = 3 bytes of 00 fill + the first character.
        val set = listOf(
            "49 02 01 00 00 00 31", "49 02 02 46 54 42 57", "49 02 03 33 58 4D 35",
            "49 02 04 48 4B 41 31", "49 02 05 32 33 34 35",
        )
        assertEquals(vin, ProfileStore.parseVin(set))
        // Out of order but complete and unique: every packet carries its number, so the set is unambiguous.
        assertEquals(vin, ProfileStore.parseVin(listOf(set[3], set[0], set[4], set[2], set[1])))
        // Missing packet (gap) -> no VIN.
        assertNull(ProfileStore.parseVin(set - set[2]))
        // Duplicate number with different data (two ECUs mixed) -> no VIN, even with the set otherwise complete.
        assertNull(ProfileStore.parseVin(set.take(4) + "49 02 04 48 4B 41 32"))
        // Any repeated number is ambiguous, even with the same data.
        assertNull(ProfileStore.parseVin(set.take(4) + set[3]))
        // A second ECU's extra packet, or a whole second set -> no VIN.
        assertNull(ProfileStore.parseVin(set + "49 02 03 33 58 4D 36"))
        assertNull(ProfileStore.parseVin(set + set))
        // A foreign line mixed in -> no VIN.
        assertNull(ProfileStore.parseVin(set.take(4) + "41 00 BE 3F A8 13"))
        // Wrong numbering (0-based, or a 6th packet instead of the 5th).
        assertNull(ProfileStore.parseVin(set.mapIndexed { i, l -> l.replaceRange(6, 8, "0$i") }))
        assertNull(ProfileStore.parseVin(set.take(4) + "49 02 06 32 33 34 35"))
        // A short middle packet, or more than 3 fill bytes / missing fill -> not 17 characters -> no VIN.
        assertNull(ProfileStore.parseVin(set.toMutableList().also { it[1] = "49 02 02 46 54 42" }))
        assertNull(ProfileStore.parseVin(set.toMutableList().also { it[0] = "49 02 01 41 00 00 31" }))
        // Same VIN checks as the CAN block path: no I/O/Q, not one repeated character.
        assertNull(ProfileStore.parseVin(set.toMutableList().also { it[4] = "49 02 05 32 33 34 49" }))
        assertNull(ProfileStore.parseVin(listOf("49 02 01 00 00 00 31") + (2..5).map { "49 02 0$it 31 31 31 31" }))
        // The van's real 0902 replies: none, or the 7F 09 negative response (service not supported) -> no VIN.
        assertNull(ProfileStore.parseVin(emptyList()))
        assertNull(ProfileStore.parseVin(listOf("7F0902000011")))
        assertNull(ProfileStore.parseVin(listOf("7F 09 02 00 00 11")))
    }

    @Test
    fun test101EveryConnectionReadsVinAndBitmapAnd16C1ProbeIsGatedNoDoubleQueue() {
        // The one check per connection compares protocol + the exact 0100 bitmap (+ 16C1): both are always read,
        // whatever the profile already knows.
        assertEquals(listOf("0902", "0100"), identityQueue())
        val pwm = "SAE J1850 PWM"
        val vin = "1FTBW3XM5HKA12345"
        assertTrue(wantsFordProbe(null, null, false, false, pwm))
        assertTrue(wantsFordProbe("ok", null, false, false, pwm)) // VIN-less: every connection (solid gate)
        assertTrue(wantsFordProbe("no", vin, false, false, pwm)) // VIN vehicle: until a stored "ok"
        assertTrue(wantsFordProbe(null, vin, false, false, pwm))
        assertFalse(wantsFordProbe("ok", vin, false, false, pwm))
        assertFalse(wantsFordProbe(null, null, false, true, pwm)) // already queued (0100 re-ask): no double queue
        assertFalse(wantsFordProbe(null, null, true, false, pwm)) // already answered this read
        assertFalse(wantsFordProbe(null, null, false, false, "ISO 15765-4 (CAN 11/500)")) // never on CAN
        assertFalse(wantsFordProbe(null, null, false, false, null))
    }

}
