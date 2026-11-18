package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class UnassignedTest {
    private fun tmp(): File = Files.createTempDirectory("unassigned").toFile()

    private fun snapshot(dir: File): Map<String, String> =
        dir.walkTopDown().filter { it.isFile }.associate { it.relativeTo(dir).path to it.readText() }

    private fun session(dir: File, stamp: Long, tank: Int = 1): File {
        val raw = File(dir, "t$tank").apply { mkdirs() }
        File(raw, "$stamp-abcd.jsonl").writeText("{\"t\":$stamp,\"lvl\":40,\"fuelGal\":0.1,\"spd\":60,\"sm\":40,\"ok\":true,\"lvlSrc\":\"ford_16c1\"}\n")
        return dir
    }

    @Test
    fun test111UnassignedAssignMovesIntoProfileAndDoesNotTrainUntilThen() {
        val root = tmp()
        ProfileStore.migrate(root, null, null, "Vehicle 1", 1L)
        ProfileStore.setProfileField(root, "default", "torqueProfileName", "Van")
        ProfileStore.renameDefaultToSlug(root)
        val vanBefore = snapshot(File(root, "profiles/van"))
        val q = session(Quarantine.dir(root, 3000L), 3000L)
        val parked = Unassigned.park(root, q, "Van", "Unassigned drive", "held", emptyList(), null, 3000L, 4000L)
        assertTrue(parked != null)
        assertEquals(emptyList<File>(), File(root, "profiles/van/raw").listFiles()?.toList() ?: emptyList<File>())
        val moved = Unassigned.assign(root, parked!!.id, "van")
        assertEquals(1, moved.size)
        assertEquals(File(root, "profiles/van/raw/3000-abcd.jsonl").path, moved.single().path)
        assertFalse(Unassigned.hasAny(root))
        assertTrue(vanBefore.keys.none { it.startsWith("raw/") && it.contains("3000-abcd") })
    }

    @Test
    fun test112ExplicitDiscardIsHiddenAndNotAdopted() {
        val root = tmp()
        val q = session(Quarantine.dir(root, 4000L), 4000L)
        Unassigned.park(root, q, null, "Unassigned drive", "", emptyList(), null, 4000L, 0L)
        assertTrue(Unassigned.discard(root, "4000"))
        assertFalse(Unassigned.hasAny(root))
        assertTrue(Unassigned.isUserDiscard(File(root, "quarantine/discarded/4000")))
        assertEquals(0, Unassigned.adoptOrphans(root))
        assertFalse(Unassigned.hasAny(root))
    }

    @Test
    fun test113TimeoutDiscardedOrphansAreAdoptedNotUserDiscards() {
        val root = tmp()
        val leftover = session(File(root, "quarantine/discarded/5000"), 5000L)
        val user = session(File(root, "quarantine/discarded/6000"), 6000L)
        File(user, Unassigned.DISCARD_MARK).writeText("{\"reason\":\"user\"}")
        val open = session(Quarantine.dir(root, 7000L), 7000L)
        val n = Unassigned.adoptOrphans(root, skip = open)
        assertTrue(n >= 1)
        val ids = Unassigned.list(root).map { it.id }.toSet()
        assertTrue("5000" in ids)
        assertFalse("6000" in ids)
        assertFalse("7000" in ids)
        assertTrue(Unassigned.hasSessionJsonl(open))
    }

    private fun rawCopy(root: File, profile: String, from: File): File {
        val raw = File(root, "profiles/$profile/raw").apply { mkdirs() }
        val dest = File(raw, from.name)
        from.copyTo(dest, overwrite = true)
        return dest
    }

    @Test
    fun test117AdoptSkipsDrivesAlreadyInRawAndMarksThemRecovered() {
        val root = tmp()
        File(root, "profiles/van").mkdirs()
        // Recovered earlier: byte-identical copies already sit in van raw.
        val q = session(Quarantine.dir(root, 1794513859059L), 1794513859059L)
        val d1 = session(File(root, "quarantine/discarded/1794263887330"), 1794263887330L)
        rawCopy(root, "van", File(q, "t1/1794513859059-abcd.jsonl"))
        rawCopy(root, "van", File(d1, "t1/1794263887330-abcd.jsonl"))
        // Never recovered (the Nov 9 35-line drive): not in raw, must stay unassigned.
        session(File(root, "quarantine/discarded/1794263827068"), 1794263827068L)
        // Same name in raw but different bytes: not a duplicate.
        val d3 = session(File(root, "quarantine/discarded/1794515916478"), 1794515916478L)
        File(root, "profiles/van/raw/1794515916478-abcd.jsonl").writeText("{\"t\":1,\"lvl\":1}\n")
        val before = snapshot(root)

        Unassigned.adoptOrphans(root)

        val ids = Unassigned.list(root).map { it.id }.toSet()
        assertEquals(setOf("1794263827068", "1794515916478"), ids)
        assertTrue(File(q, Unassigned.RECOVERED_MARK).isFile)
        assertTrue(File(d1, Unassigned.RECOVERED_MARK).isFile)
        assertFalse(File(d3, Unassigned.RECOVERED_MARK).exists())
        // Recovered folders and raw are untouched (non-duplicates moved to unassigned/ by park).
        val after = snapshot(root)
        val kept = before.filterKeys { it.startsWith("profiles") || it.contains("1794513859059") || it.contains("1794263887330") }
        assertEquals(5, kept.size)
        for ((path, text) in kept) assertEquals(path, text, after[path])
        assertTrue(File(Unassigned.root(root), "1794263827068/t1/1794263827068-abcd.jsonl").isFile)
        assertTrue(File(q, "t1/1794513859059-abcd.jsonl").isFile)
        assertTrue(File(d1, "t1/1794263887330-abcd.jsonl").isFile)
        // A second pass is a no-op.
        assertEquals(0, Unassigned.adoptOrphans(root))
    }

    @Test
    fun test118AssignTreatsIdenticalRawCopyAsDoneAndKeepsDifferentOnes() {
        val root = tmp()
        File(root, "profiles/van").mkdirs()
        val a = Unassigned.park(root, session(Quarantine.dir(root, 8000L), 8000L), null, "Unassigned drive", "",
            emptyList(), null, 8000L, 0L)!!
        val inRaw = rawCopy(root, "van", File(a.dir, "t1/8000-abcd.jsonl"))
        val moved = Unassigned.assign(root, "8000", "van")
        assertEquals(listOf(inRaw.path), moved.map { it.path })
        assertTrue(File(a.dir, "t1/8000-abcd.jsonl").isFile) // kept, not deleted
        assertTrue(File(a.dir, Unassigned.RECOVERED_MARK).isFile)
        assertFalse(Unassigned.list(root).any { it.id == "8000" })

        val b = Unassigned.park(root, session(Quarantine.dir(root, 9000L), 9000L), null, "Unassigned drive", "",
            emptyList(), null, 9000L, 0L)!!
        File(root, "profiles/van/raw/9000-abcd.jsonl").writeText("different\n")
        assertTrue(Unassigned.assign(root, "9000", "van").isEmpty())
        assertTrue(File(b.dir, "t1/9000-abcd.jsonl").isFile)
        assertTrue(Unassigned.list(root).any { it.id == "9000" })
    }
}
