package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileOdoTest {
    @Test
    fun test13MigrationIsIdempotentAndSelectsByTorqueName() {
        val root = File("build/profile-odo-test13").apply {
            deleteRecursively()
            mkdirs()
        }
        val sessions = File(root, "sessions").apply { mkdirs() }
        File(sessions, "1791007200000-abcd.jsonl").writeText(
            """
            {"event":"open","t0":1791007200000}
            {"t":1791007200000,"rawPct":40,"smoothedPct":40,"fuelUsedGal":1,"speed":0,"fill":false}
            {"t":1791007201000,"rawPct":39,"smoothedPct":39,"fuelUsedGal":2,"speed":0,"fill":false}
            {"t":1791007202000,"rawPct":80,"smoothedPct":80,"fuelUsedGal":0.4,"speed":0,"fill":true}
            """.trimIndent() + "\n",
        )
        File(root, "bins.json").writeText("{\"bins\":[],\"reserve\":0}")
        File(root, "last-session-end.json").writeText("{}")
        File(root, "sessions-t2").mkdirs()
        File(root, "sessions-t2/1000-bbbb.jsonl").writeText(
            """
            {"event":"open","t0":1000}
            {"t":1000,"rawPct":10,"smoothedPct":10,"fuelUsedGal":0.1,"speed":0,"fill":false}
            {"t":2000,"rawPct":9,"smoothedPct":9,"fuelUsedGal":0.2,"speed":0,"fill":false}
            """.trimIndent() + "\n",
        )
        File(root, "bins-t2.json").writeText("{}")
        val now = ProfileStore.RESET_MS + 86_400_000L
        val dir = ProfileStore.migrate(
            root,
            "E350",
            ProfileStore.LedgerSeed(10.0, 1.5, null, 30.0),
            now,
        )
        assertEquals("default", dir.name)
        val profile = File(dir, "profile.json").readText()
        assertTrue(profile.contains("\"schema\":2"))
        assertTrue(profile.contains("Vehicle 1"))
        assertTrue(profile.contains("E350"))
        assertFalse(File(root, "profiles/default/legacy").exists())
        assertTrue(File(root, "sessions").isDirectory)
        assertTrue(File(root, "bins.json").isFile)
        assertTrue(File(root, "last-session-end.json").isFile)
        assertTrue(File(root, "sessions-t2").isDirectory)
        assertTrue(File(root, "bins-t2.json").isFile)
        assertTrue(File(root, "active-profile.json").isFile)
        assertTrue(File(dir, "profile.json").isFile)
        assertTrue(File(dir, "ledger.json").readText().contains("10"))
        // The next migrate renames default to the Torque-name slug (e350) once; after that it is a no-op.
        ProfileStore.migrate(root, "E350", null, now)
        assertFalse(File(root, "profiles/default").exists())
        val before = paths(root)
        ProfileStore.migrate(root, "E350", null, now)
        assertEquals(before, paths(root))
        assertFalse(File(root, "profiles/default/legacy").exists())
        assertTrue(File(sessions, "1791007200000-abcd.jsonl").isFile)

        // First connect: default (only profile, no Torque name yet) takes the current name once.
        val first = ProfileStore.select(root, "1FTESTVIN00000009", "E350", now)
        assertEquals("e350", first.id)
        assertFalse(first.created)
        assertEquals("", first.vinNote)
        assertEquals("1FTESTVIN00000009", ProfileStore.storedVin(root, "e350"))
        // Same name again: same profile, nothing created.
        assertEquals("e350", ProfileStore.select(root, null, "e350", now).id)

        File(root, "profiles/other").mkdirs()
        File(root, "profiles/other/profile.json").writeText(
            "{\"id\":\"other\",\"name\":\"Van\",\"vin\":\"1FTESTVIN00000001\",\"torqueProfileName\":\"Van\",\"schema\":2}",
        )
        // The Torque name decides; a different VIN only produces a mismatch note.
        val cross = ProfileStore.select(root, "1FTESTVIN00000001", "E350", now)
        assertEquals("e350", cross.id)
        assertEquals("VIN mismatch 1FTESTVIN00000001", cross.vinNote)
        assertEquals("1FTESTVIN00000009", ProfileStore.storedVin(root, "e350"))
        assertEquals("other", ProfileStore.select(root, null, "Van", now).id)

        // Unknown name: a new profile is created; default's name is not overwritten.
        val made = ProfileStore.select(root, null, "Wife's Civic", now)
        assertTrue(made.created)
        assertEquals("wife-s-civic", made.id)
        assertTrue(File(root, "profiles/wife-s-civic/raw").isDirectory)
        assertEquals("Wife's Civic", ProfileStore.profileField(root, made.id, "torqueProfileName"))
        assertEquals("E350", ProfileStore.profileField(root, "e350", "torqueProfileName"))
        val again = ProfileStore.select(root, null, "Wife's Civic", now)
        assertEquals(made.id, again.id)
        assertFalse(again.created)
        // Blank name: stay on the active profile.
        assertEquals(ProfileStore.activeId(root), ProfileStore.select(root, null, "  ", now).id)

        val rawDir = File(root, "retain/raw").apply { mkdirs() }
        val old = File(rawDir, "old.jsonl")
        ProfileStore.sparse(old, ProfileStore.RAW_CAP_BYTES + 1024)
        old.setLastModified(now - ProfileStore.RAW_KEEP_MS - 86_400_000L)
        val fresh = File(rawDir, "fresh.jsonl")
        fresh.writeText("keep\n")
        fresh.setLastModified(now)
        val chunks = File(root, "retain/chunks.jsonl")
        chunks.parentFile?.mkdirs()
        chunks.writeText("keep\n")
        val legacy = File(root, "retain/legacy/keep.txt")
        legacy.parentFile?.mkdirs()
        legacy.writeText("keep\n")
        ProfileStore.retainRaw(rawDir, now)
        // Pruning is disabled: nothing is deleted at any size or age.
        assertFalse(ProfileStore.RAW_PRUNE_ENABLED)
        assertTrue(old.exists())
        assertTrue(fresh.exists())
        assertTrue(chunks.isFile)
        assertTrue(legacy.isFile)
    }

    @Test
    fun test14OdometerAndProbeCommands() {
        val km = 1.0 / FuelV2.KM_PER_MILE
        val drop = Odometer()
        drop.pin(1000.0, 0.0)
        drop.onCounter("0131", 100.0)
        val beforeDrop = drop.miles
        drop.onCounter("0131", 0.0)
        assertEquals(beforeDrop, drop.miles, 1e-6)
        assertTrue(drop.events.any { it.contains("counterReset") })

        val wrap = Odometer()
        wrap.pin(0.0, 0.0)
        wrap.onCounter("0131", 65535.0)
        val atEnd = wrap.miles
        wrap.onCounter("0131", 1.0)
        assertTrue(wrap.miles + 1e-6 >= atEnd)
        assertEquals(0.0 + (1.0 + 65535.0) * km, wrap.miles, 1e-4)

        val pin = Odometer()
        pin.pin(5000.0, 10.0)
        pin.onCounter("0131", 20.0)
        assertEquals(5000.0 + 10.0 * km, pin.miles, 1e-6)

        assertEquals(null, drop.unloggedLine(0.4, 15.0))
        assertTrue(drop.unloggedLine(0.6, 15.0)!!.contains("\"unlogged\":true"))

        val services = setOf("01", "09", "21", "22")
        val fordCan = SourceProbe.commandsFor(null, "Ford", "ISO 15765-4", false)
        assertTrue(fordCan.isNotEmpty())
        assertTrue(fordCan.all { it.command.take(2) in services })
        assertTrue(fordCan.none { it.row == 31 })
        assertTrue(SourceProbe.rowIsListed(31))
        assertEquals("18DA1AF1", SourceProbe.headerArg("DA1A"))
        val honda = SourceProbe.commandsFor(null, "Honda", "ISO15765", false)
        assertEquals("18DA10F1", honda.first { it.command == "222660" }.header)
        val pwm = SourceProbe.commandsFor("1FTSW21P0XEA00001", "Ford", "SAE J1850 PWM", true)
        assertTrue(pwm.none { it.canOnly })
        assertTrue(pwm.all { it.command.take(2) in services })
        assertTrue(pwm.none { it.row == 31 })
    }

    @Test
    fun test15MigrationImportsLegacySessionsIdempotently() {
        val root = File("build/profile-odo-test15").apply {
            deleteRecursively()
            mkdirs()
        }
        val names = fixtureNames().take(3)
        File(root, "sessions").mkdirs()
        File(root, ProfileStore.IMPORT_DIR).mkdirs()
        fixture(names[0]).copyTo(File(root, "sessions/${names[0]}"))
        fixture(names[1]).copyTo(File(root, "sessions/${names[1]}"))
        fixture(names[2]).copyTo(File(root, "${ProfileStore.IMPORT_DIR}/${names[2]}"))
        val now = ProfileStore.RESET_MS - 1L
        ProfileStore.migrate(root, null, null, now)
        val raw = File(root, "profiles/default/raw")
        assertEquals(names.sorted(), raw.list()!!.sorted())
        // Sources stay untouched.
        assertTrue(File(root, "sessions/${names[0]}").readBytes().contentEquals(fixture(names[0]).readBytes()))
        // Converted files carry the same samples.
        val src = FuelV2.loadSessions(File(root, "sessions")).first { it.name == names[0].removeSuffix(".jsonl") }
        val got = FuelV2.loadSessions(raw).first { it.name == src.name }
        assertEquals(src.t.size, got.t.size)
        assertEquals(src.used.last(), got.used.last(), 1e-9)
        assertEquals(src.raw.last(), got.raw.last(), 1e-9)
        val before = raw.listFiles()!!.associate { it.name to it.readText() }
        assertEquals(0, ProfileStore.importLegacy(root, "default"))
        ProfileStore.migrate(root, null, null, now)
        assertEquals(before, raw.listFiles()!!.associate { it.name to it.readText() })
    }

    @Test
    fun test16LearningRecordIsDedupedAndPruningCannotChangeTheFit() {
        val dir = File("build/profile-odo-test16").apply {
            deleteRecursively()
            mkdirs()
        }
        val raw = File(dir, "raw").apply { mkdirs() }
        for (n in fixtureNames()) fixture(n).copyTo(File(raw, n))
        ProfileStore.rollup(dir, 35.0)
        val model1 = File(dir, "model.json").readText()
        val learn = File(dir, ProfileStore.LEARN_FILE)
        val ids1 = learn.readLines().filter { it.isNotBlank() }
        assertEquals(ids1.size, ProfileStore.learnedIds(learn).size)
        assertTrue(ids1.any { it.contains("\"group\"") })
        // Rollup again: no duplicate lines, same fit.
        ProfileStore.rollup(dir, 35.0)
        assertEquals(ids1, learn.readLines().filter { it.isNotBlank() })
        assertEquals(model1, File(dir, "model.json").readText())
        // Pruning with a huge old file present deletes nothing, so the fit is unchanged.
        val big = File(raw, "0000000000000-old.bin")
        ProfileStore.sparse(big, ProfileStore.RAW_CAP_BYTES + 1024)
        for (f in raw.listFiles()!!) f.setLastModified(1_000L)
        val count = raw.list()!!.size
        ProfileStore.retainRaw(raw, ProfileStore.RESET_MS * 2)
        assertEquals(count, raw.list()!!.size)
        big.delete()
        ProfileStore.rollup(dir, 35.0)
        assertEquals(model1, File(dir, "model.json").readText())
        assertEquals(ids1, learn.readLines().filter { it.isNotBlank() })
    }

    private fun fixtureDir(): File =
        File(checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions")).toURI())

    private fun fixtureNames(): List<String> =
        fixtureDir().list { _, n -> n.endsWith(".jsonl") }!!.sorted()

    private fun fixture(name: String): File = File(fixtureDir(), name)

    private fun paths(root: File): List<String> {
        return root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()
    }
}
