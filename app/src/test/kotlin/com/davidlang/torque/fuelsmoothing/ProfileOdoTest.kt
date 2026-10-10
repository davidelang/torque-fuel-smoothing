package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProfileOdoTest {
    @Test
    fun test13MigrationIsIdempotentAndSelectsByVinThenName() {
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
        assertTrue(File(root, "profiles/default/legacy/sessions").isDirectory)
        assertTrue(File(root, "profiles/default/legacy/bins.json").isFile)
        assertTrue(File(root, "profiles/default/legacy/last-session-end.json").isFile)
        assertTrue(File(root, "profiles/default/legacy/sessions-t2").isDirectory)
        assertTrue(File(root, "profiles/default/legacy/bins-t2.json").isFile)
        assertFalse(File(root, "sessions").exists())
        assertFalse(File(root, "bins.json").exists())
        assertTrue(File(root, "profiles/default/tanks/2/raw").isDirectory)
        assertTrue(File(root, "active-profile.json").isFile)
        assertTrue(File(dir, "ledger.json").readText().contains("10.0") || File(dir, "ledger.json").readText().contains("10"))
        assertTrue(File(dir, "model.json").isFile)
        assertTrue(File(dir, "chunks.jsonl").isFile)
        assertTrue(File(dir, "drives.jsonl").isFile)
        assertTrue(File(dir, "fills.jsonl").isFile)
        val raw = File(dir, "raw/1791007200000-abcd.jsonl").readText()
        val gals = raw.lines().filter { it.contains("\"lvl\"") }.map { line ->
            line.substringAfter("\"fuelGal\":").substringBefore(",").toDouble()
        }
        assertEquals(listOf(1.0, 2.0, 2.4), gals)
        val before = paths(root)
        ProfileStore.migrate(root, "E350", null, now)
        assertEquals(before, paths(root))
        assertTrue(File(root, "profiles/default/legacy/sessions/1791007200000-abcd.jsonl").isFile)

        File(root, "profiles/other").mkdirs()
        File(root, "profiles/other/profile.json").writeText(
            "{\"id\":\"other\",\"name\":\"Van\",\"vin\":\"1FTESTVIN00000001\",\"torqueProfileName\":\"Van\",\"schema\":2}",
        )
        assertEquals("other", ProfileStore.select(root, "1FTESTVIN00000001", "E350"))
        assertEquals("default", ProfileStore.select(root, null, "E350"))
        assertEquals("other", ProfileStore.select(root, null, "Van"))
        assertEquals("default", ProfileStore.select(root, null, "missing"))

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
        assertFalse(old.exists())
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

    private fun paths(root: File): List<String> {
        return root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }.sorted().toList()
    }
}
