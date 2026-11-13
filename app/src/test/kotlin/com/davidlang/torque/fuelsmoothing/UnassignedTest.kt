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
        assertEquals(emptyList<File>(), File(root, "profiles/van/raw").listFiles()?.toList() ?: emptyList())
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
}
