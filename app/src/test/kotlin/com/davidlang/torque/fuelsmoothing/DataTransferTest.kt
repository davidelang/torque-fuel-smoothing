package com.davidlang.torque.fuelsmoothing

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class DataTransferTest {
    private val info = BackupManifest.Info(
        applicationId = "com.example.torque.fuelsmoothing",
        versionName = "test",
        versionCode = 1,
        buildType = "debug",
        androidSdkInt = 35,
    )

    private fun tmp(): File = Files.createTempDirectory("datatransfer").toFile()

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    private fun tarOf(vararg files: Pair<String, ByteArray>, manifest: ByteArray? = null): ByteArray {
        val out = ByteArrayOutputStream()
        if (manifest != null) DataTransferTar.writeFile(out, BackupManifest.NAME, manifest)
        for ((path, data) in files) DataTransferTar.writeFile(out, path, data)
        DataTransferTar.writeEnd(out)
        return out.toByteArray()
    }

    private fun importTar(dataDir: File, tar: ByteArray, status: File, rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) }) {
        DataTransfer.importReplace(dataDir, ByteArrayInputStream(tar), false, status, rename)
    }

    @Test
    fun test102RoundTripByteIdenticalManifestFirst() {
        val root = tmp()
        val data = File(root, "data").apply { mkdirs() }
        val longName = "n".repeat(95) + ".txt"
        val nested = File(data, "files/sub")
        nested.mkdirs()
        val body = bytes("round-trip-bytes")
        val longBody = bytes("prefix-field")
        File(nested, "a.txt").writeBytes(body)
        File(data, "files").resolve(longName).writeBytes(longBody)
        File(data, "files/skip.tmp").writeBytes(bytes("nope"))
        File(data, "files/skip.part").writeBytes(bytes("nope"))
        File(data, "shared_prefs").mkdirs()
        val pref = bytes("<map><string name=\"k\">v</string></map>")
        File(data, "shared_prefs/p.xml").writeBytes(pref)
        val out = ByteArrayOutputStream()
        DataTransfer.exportSnapshot(data, File(root, "nobackup"), out, info, recording = false, nowMs = 1_700_000_000_000L)
        val tar = out.toByteArray()
        val raw = DataTransferTar.readRaw(ByteArrayInputStream(tar))
        assertEquals(BackupManifest.NAME, raw.first().path)
        assertTrue(raw.none { it.path.endsWith(".tmp") || it.path.endsWith(".part") })
        val longPath = "files/$longName"
        assertTrue(longPath.toByteArray(Charsets.UTF_8).size > 100)
        assertTrue(raw.any { it.path == longPath && it.data.contentEquals(longBody) })
        assertTrue(raw.any { it.path == "files/sub/a.txt" && it.data.contentEquals(body) })
        assertTrue(raw.any { it.path == "shared_prefs/p.xml" && it.data.contentEquals(pref) })
        val extracted = File(root, "extracted")
        DataTransferTar.extract(ByteArrayInputStream(tar), extracted)
        assertTrue(File(extracted, "files/sub/a.txt").readBytes().contentEquals(body))
        assertTrue(File(extracted, longPath).readBytes().contentEquals(longBody))
        assertTrue(File(extracted, "shared_prefs/p.xml").readBytes().contentEquals(pref))
        val manifest = BackupManifest.decode(raw.first().data.toString(Charsets.UTF_8))
        assertEquals(BackupManifest.FORMAT, manifest.format)
        assertEquals(1, manifest.formatVersion)
        assertEquals(BackupManifest.APP_KEY, manifest.appKey)
        assertEquals(null, manifest.dbSchemaVersion)
        assertEquals(BackupManifest.EXCLUDED, manifest.excluded)
        val listed = manifest.entries.first { it.path == "files/sub/a.txt" }
        assertEquals(body.size.toLong(), listed.bytes)
        assertEquals(raw.first { it.path == "files/sub/a.txt" }.data.size.toLong(), listed.bytes)
    }

    @Test
    fun test103ImportWithoutManifestReplacesExactly() {
        val root = tmp()
        val data = File(root, "data").apply { mkdirs() }
        File(data, "files").mkdirs()
        File(data, "files/old.txt").writeBytes(bytes("old"))
        File(data, "shared_prefs").mkdirs()
        File(data, "shared_prefs/a.xml").writeBytes(bytes("oldxml"))
        File(data, "cache").mkdirs()
        File(data, "cache/keep.txt").writeBytes(bytes("cache"))
        val tar = tarOf(
            "files/new.txt" to bytes("new"),
            "shared_prefs/b.xml" to bytes("newxml"),
        )
        importTar(data, tar, File(root, "last-import.json"))
        assertFalse(File(data, "files/old.txt").exists())
        assertEquals("new", File(data, "files/new.txt").readText())
        assertFalse(File(data, "shared_prefs/a.xml").exists())
        assertEquals("newxml", File(data, "shared_prefs/b.xml").readText())
        assertEquals("cache", File(data, "cache/keep.txt").readText())
        val status = JSONObject(File(root, "last-import.json").readText())
        assertEquals("ok", status.getString("result"))
        assertFalse(status.getBoolean("manifest"))
    }

    @Test
    fun test104ShaMismatchRecordedNotRefused() {
        val root = tmp()
        val data = File(root, "data").apply { mkdirs() }
        File(data, "files").mkdirs()
        File(data, "files/a.txt").writeBytes(bytes("before"))
        val payload = bytes("landed")
        val wrong = "0123456789abcdef".repeat(4)
        val manifest = BackupManifest.encode(
            BackupManifest.Fields(
                format = BackupManifest.FORMAT,
                formatVersion = BackupManifest.FORMAT_VERSION,
                appKey = BackupManifest.APP_KEY,
                applicationId = info.applicationId,
                versionName = info.versionName,
                versionCode = info.versionCode,
                buildType = info.buildType,
                dbSchemaVersion = null,
                createdUtc = "2026-10-16T20:15:00Z",
                androidSdkInt = 35,
                entries = listOf(BackupManifest.Entry("files/a.txt", payload.size.toLong(), wrong)),
                excluded = BackupManifest.EXCLUDED,
            ),
        ).toByteArray(Charsets.UTF_8)
        val tar = tarOf("files/a.txt" to payload, manifest = manifest)
        val statusFile = File(root, "last-import.json")
        importTar(data, tar, statusFile)
        assertEquals("landed", File(data, "files/a.txt").readText())
        val status = JSONObject(statusFile.readText())
        assertEquals("ok", status.getString("result"))
        assertTrue(status.getBoolean("manifest"))
        val lines = status.getJSONArray("mismatches")
        assertTrue((0 until lines.length()).any { lines.getString(it).contains(wrong) })
    }

    @Test
    fun test105ImportReplacesEverythingIncludingBadData() {
        val root = tmp()
        val data = File(root, "data").apply { mkdirs() }
        File(data, "files/profiles/default").mkdirs()
        File(data, "files/profiles/default/profile.json").writeBytes(bytes("{\"id\":\"default\"}"))
        File(data, "shared_prefs").mkdirs()
        val garbage = byteArrayOf(0, 1, 2, 255.toByte(), 10, 13)
        val badId = bytes("not-a-profile")
        val tar = tarOf(
            "files/profiles/wrong-id/profile.json" to badId,
            "files/garbage.bin" to garbage,
            "shared_prefs/p.xml" to bytes("x"),
        )
        importTar(data, tar, File(root, "last-import.json"))
        assertFalse(File(data, "files/profiles/default/profile.json").exists())
        assertTrue(File(data, "files/profiles/wrong-id/profile.json").readBytes().contentEquals(badId))
        assertTrue(File(data, "files/garbage.bin").readBytes().contentEquals(garbage))
        assertEquals("x", File(data, "shared_prefs/p.xml").readText())
        assertFalse(JSONObject(File(root, "last-import.json").readText()).getBoolean("manifest"))
    }

    @Test
    fun test106NonShellUidSecurityException() {
        assertTrue(datatransferAllowed(2000))
        assertTrue(datatransferAllowed(0))
        assertFalse(datatransferAllowed(1000))
        assertFalse(datatransferAllowed(-1))
    }

    @Test
    fun test107SwapRollbackLeavesLiveUnchanged() {
        val root = tmp()
        val data = File(root, "data").apply { mkdirs() }
        File(data, "files").mkdirs()
        File(data, "files/keep.txt").writeBytes(bytes("live-files"))
        File(data, "shared_prefs").mkdirs()
        File(data, "shared_prefs/a.xml").writeBytes(bytes("live-prefs"))
        val tar = tarOf(
            "files/keep.txt" to bytes("new-files"),
            "shared_prefs/a.xml" to bytes("new-prefs"),
        )
        var calls = 0
        val rename = { from: File, to: File ->
            calls++
            if (calls == 2) false else from.renameTo(to)
        }
        try {
            importTar(data, tar, File(root, "last-import.json"), rename)
            throw AssertionError("expected rename failure")
        } catch (e: Exception) {
            assertTrue(e.message ?: "", (e.message ?: "").contains("rename failed"))
        }
        assertEquals("live-files", File(data, "files/keep.txt").readText())
        assertEquals("live-prefs", File(data, "shared_prefs/a.xml").readText())
        assertTrue(calls >= 2)
    }

    @Test
    fun test108SameVersionRestoreKeepsDerivedFiles() {
        val root = tmp()
        val data = File(root, "data").apply { mkdirs() }
        File(data, "files/profiles/default").mkdirs()
        File(data, "files/profiles/default/pieces.jsonl").writeBytes(bytes("old-pieces\n"))
        File(data, "shared_prefs").mkdirs()
        val pieces = bytes("NOT-A-REAL-PIECE-LINE\n")
        val tar = tarOf(
            "files/profiles/default/pieces.jsonl" to pieces,
            "files/profiles/default/analysis.version" to bytes("4"),
            "shared_prefs/p.xml" to bytes("p"),
        )
        importTar(data, tar, File(root, "last-import.json"))
        val onDisk = File(data, "files/profiles/default/pieces.jsonl")
        assertTrue(onDisk.isFile)
        assertTrue(onDisk.readBytes().contentEquals(pieces))
        assertEquals("4", File(data, "files/profiles/default/analysis.version").readText())
    }

    @Test
    fun test109EscapingEntryFailsBeforeSwap() {
        val cases = listOf(
            Triple("..", "files/../../escaped.txt", '0'),
            Triple("absolute", "/tmp/datatransfer-abs.txt", '0'),
            Triple("symlink", "files/link", '2'),
            Triple("hardlink", "files/hard", '1'),
            Triple("device", "files/dev", '3'),
        )
        for ((label, path, type) in cases) {
            val root = tmp()
            val data = File(root, "data").apply { mkdirs() }
            File(data, "files").mkdirs()
            File(data, "files/sibling.txt").writeBytes(bytes("safe"))
            val out = ByteArrayOutputStream()
            DataTransferTar.writeFile(out, "files/new.txt", bytes("NEW"))
            if (type == '0') DataTransferTar.writeFile(out, path, bytes("bad"))
            else DataTransferTar.writeSpecial(out, path, type, "sibling.txt")
            DataTransferTar.writeEnd(out)
            try {
                importTar(data, out.toByteArray(), File(root, "last-import.json"))
                throw AssertionError("$label expected a refusal")
            } catch (e: Exception) {
                assertTrue("$label ${e.message}", e is IllegalArgumentException)
            }
            assertEquals("safe", File(data, "files/sibling.txt").readText())
            assertFalse(File(data, "files/new.txt").exists())
            assertFalse(File(root, "escaped.txt").exists())
        }
    }
}
