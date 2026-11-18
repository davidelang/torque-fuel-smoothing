package com.davidlang.torque.fuelsmoothing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class IdentityChecksTest {
    private fun tmp(): File = Files.createTempDirectory("idchecks").toFile()

    @Test
    fun test127CheckRingKeepsLast20AndReplacesSameId() {
        val p = File(tmp(), "profiles/van").apply { mkdirs() }
        for (i in 1..25) IdentityChecks.record(p, JSONObject().put("id", "r$i").put("t", i))
        var rows = IdentityChecks.read(p)
        assertEquals(IdentityChecks.MAX, rows.size)
        assertEquals("r6", rows.first().getString("id"))
        assertEquals("r25", rows.last().getString("id"))
        IdentityChecks.record(p, JSONObject().put("id", "r10").put("t", 99))
        rows = IdentityChecks.read(p)
        assertEquals(IdentityChecks.MAX, rows.size)
        assertEquals(1, rows.count { it.getString("id") == "r10" })
        assertEquals(99, rows.last().getInt("t"))
        assertTrue(File(p, "identity/checks.jsonl").isFile)
    }

    @Test
    fun test128ArchiveMovesIdentityOnlyFoldersAndNeverDrives() {
        val root = tmp()
        fun put(rel: String, text: String = "{}") = File(root, rel).apply { parentFile.mkdirs(); writeText(text) }
        put("quarantine/1794513859060/identity/a.json")
        put("quarantine/1794513859061/identity/b.json")
        put("quarantine/1794513859061/match-confirmation.json")
        put("quarantine/1794513859062/identity/c.json")
        put("quarantine/1794513859062/t1/1794513859062-abcd.jsonl", "{\"t\":1,\"lvl\":40}\n")
        put("quarantine/1794513859063/t1/diagnostics/x.jsonl")
        put("quarantine/discarded/1794263827068/t1/1794263834155-d53b9690.jsonl", "{\"t\":1}\n")
        val before = root.walkTopDown().filter { it.isFile }.map { it.readText() }.sorted().toList()

        val moved = Quarantine.archiveIdentityOnly(root, 1_795_000_000_000L)

        assertEquals(2, moved)
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(1_795_000_000_000L))
        val arch = File(root, "quarantine/archive/identity-only-$day")
        assertTrue(File(arch, "1794513859060/identity/a.json").isFile)
        assertTrue(File(arch, "1794513859061/match-confirmation.json").isFile)
        assertTrue(File(root, "quarantine/1794513859062/t1/1794513859062-abcd.jsonl").isFile)
        assertTrue(File(root, "quarantine/1794513859063/t1/diagnostics/x.jsonl").isFile)
        assertTrue(File(root, "quarantine/discarded/1794263827068/t1/1794263834155-d53b9690.jsonl").isFile)
        assertFalse(File(root, "quarantine/1794513859060").exists())
        // Never deletes: the same file contents exist after the move.
        assertEquals(before, root.walkTopDown().filter { it.isFile }.map { it.readText() }.sorted().toList())
        // A second pass is a no-op and adoption ignores the archive.
        assertEquals(0, Quarantine.archiveIdentityOnly(root, 1_795_000_000_000L))
        Unassigned.adoptOrphans(root)
        assertFalse(Unassigned.list(root).any { it.id == Quarantine.ARCHIVE })
    }
}
