package com.davidlang.torque.fuelsmoothing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal data class ImportOutcome(val ok: Boolean, val reason: String?)

/** files/ already live. [prefsDir] is the staged shared_prefs tree, not yet applied. */
internal class StagedFiles(val prefsDir: File, val okJson: String)

/**
 * Export copies files/ and shared_prefs/ under the data lock, then streams a ustar.
 * Import replaces whole roots and does not check manifest contents.
 */
internal object DataTransfer {
    private const val TO_OLD = 1
    private const val TO_LIVE = 2

    private val NEVER_ROOTS = setOf(
        "datatransfer-staging",
        "datatransfer-old",
        "cache",
        "code_cache",
        "no_backup",
    )
    private val ALWAYS_ROOTS = listOf("files", "shared_prefs")

    fun exportSnapshot(
        dataDir: File,
        noBackupFilesDir: File,
        out: OutputStream,
        info: BackupManifest.Info,
        recording: Boolean,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        if (recording) throw IllegalStateException("datatransfer: export refused while recording")
        val parent = File(noBackupFilesDir, "datatransfer")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("datatransfer: cannot create ${parent.path}")
        val snapshot = File(parent, "export-$nowMs")
        if (snapshot.exists()) throw IOException("datatransfer: snapshot already exists ${snapshot.path}")
        if (!snapshot.mkdirs()) throw IOException("datatransfer: cannot create ${snapshot.path}")
        var primary: Throwable? = null
        try {
            ProfileStore.DataLock.lock()
            try {
                copyRoot(File(dataDir, "files"), File(snapshot, "files"))
                copyRoot(File(dataDir, "shared_prefs"), File(snapshot, "shared_prefs"))
            } finally {
                ProfileStore.DataLock.unlock()
            }
            writeTar(out, snapshot, info, nowMs)
        } catch (t: Throwable) {
            primary = t
            throw t
        } finally {
            val gone = runCatching { if (snapshot.exists()) snapshot.deleteRecursively() else true }.getOrDefault(false)
            if (!gone || snapshot.exists()) {
                val e = IOException("datatransfer: cannot delete snapshot ${snapshot.path}")
                if (primary != null) primary.addSuppressed(e) else throw e
            }
        }
    }

    /**
     * Exact replace of tar roots under [dataDir]. [recording] refuses before any rename.
     * An escaping tar entry throws before live trees are renamed.
     */
    fun importReplace(
        dataDir: File,
        tar: InputStream,
        recording: Boolean,
        statusFile: File,
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
        serviceStopped: Boolean = false,
    ): ImportOutcome {
        if (recording) {
            writeStatus(statusFile, errorStatus("recording"))
            return ImportOutcome(false, "recording")
        }
        var committed = false
        try {
            swap(dataDir, tar, statusFile, rename, serviceStopped, filesOnly = false)
            committed = true
            val old = File(dataDir, "datatransfer-old")
            if (old.exists() && !old.deleteRecursively()) throw IOException("datatransfer: cannot delete ${old.path}")
        } catch (t: Throwable) {
            if (!committed) {
                try {
                    writeStatus(statusFile, errorStatus(t.message ?: t.javaClass.simpleName))
                } catch (s: Throwable) {
                    t.addSuppressed(s)
                }
            }
            throw t
        }
        return ImportOutcome(true, null)
    }

    /**
     * Moves only files/. shared_prefs stays staged for an in-process Editor apply.
     * Does not write status. Throws before any live rename when the tar has another root.
     */
    fun importFilesOnly(
        dataDir: File,
        tar: InputStream,
        rename: (File, File) -> Boolean = { from, to -> from.renameTo(to) },
    ): StagedFiles {
        val staged = swap(dataDir, tar, statusFile = null, rename, serviceStopped = false, filesOnly = true)
        return staged ?: throw IOException("datatransfer: files-only import did not stage prefs")
    }

    fun writeStatusFile(file: File, text: String) = writeStatus(file, text)

    /** Drops import scratch so a later full swap can run. Live trees are not restored. */
    fun clearScratch(dataDir: File) {
        val old = File(dataDir, "datatransfer-old")
        if (old.exists() && !old.deleteRecursively()) throw IOException("datatransfer: cannot delete ${old.path}")
        wipe(File(dataDir, "datatransfer-staging"))
    }

    private fun swap(
        dataDir: File,
        tar: InputStream,
        statusFile: File?,
        rename: (File, File) -> Boolean,
        serviceStopped: Boolean,
        filesOnly: Boolean,
    ): StagedFiles? {
        if (!dataDir.isDirectory) throw IOException("datatransfer: data dir missing ${dataDir.path}")
        val staging = File(dataDir, "datatransfer-staging")
        val old = File(dataDir, "datatransfer-old")
        if (old.exists()) throw IOException("datatransfer: datatransfer-old already exists")
        wipe(staging)
        if (!staging.mkdirs()) throw IOException("datatransfer: cannot create ${staging.path}")
        val entryNames = DataTransferTar.extract(tar, staging)
        val manifestFile = File(staging, BackupManifest.NAME)
        val manifestText = if (manifestFile.isFile) manifestFile.readText() else null
        if (manifestFile.exists() && !manifestFile.isFile) {
            throw IOException("datatransfer: backup-manifest.json is not a file")
        }
        if (manifestFile.exists() && !manifestFile.delete()) {
            throw IOException("datatransfer: cannot drop backup-manifest.json")
        }
        val mismatches = ArrayList<String>()
        var manifestError: String? = null
        if (manifestText != null) {
            try {
                recordMismatches(staging, BackupManifest.decode(manifestText), mismatches)
            } catch (e: Exception) {
                manifestError = e.message ?: e.javaClass.simpleName
            }
        }
        val top = staging.listFiles() ?: throw IOException("datatransfer: cannot list ${staging.path}")
        val stray = top.filter { it.isFile }.map { it.name }.sorted()
        if (stray.isNotEmpty()) {
            throw IllegalArgumentException("datatransfer: top-level file is not a root: ${stray.joinToString()}")
        }
        val extras = top.filter { it.isDirectory }.map { it.name }
            .filter { it !in NEVER_ROOTS && it !in ALWAYS_ROOTS }
            .sorted()
        if (filesOnly && extras.isNotEmpty()) {
            throw IOException("datatransfer: extra root needs restart: ${extras.joinToString()}")
        }
        val roots = if (filesOnly) listOf("files") else ALWAYS_ROOTS + extras
        for (name in if (filesOnly) ALWAYS_ROOTS else roots) {
            val dir = File(staging, name)
            if (!dir.exists() && !dir.mkdirs()) throw IOException("datatransfer: cannot create empty root $name")
        }
        if (!old.mkdirs()) throw IOException("datatransfer: cannot create ${old.path}")
        val ops = ArrayList<Move>()
        try {
            for (name in roots) {
                val live = File(dataDir, name)
                val staged = File(staging, name)
                val bak = File(old, name)
                if (live.exists()) {
                    if (!rename(live, bak)) throw IOException("datatransfer: rename failed ${live.path} -> ${bak.path}")
                    ops.add(Move(TO_OLD, name))
                }
                if (!staged.exists()) throw IOException("datatransfer: staged root missing $name")
                if (!rename(staged, live)) throw IOException("datatransfer: rename failed ${staged.path} -> ${live.path}")
                ops.add(Move(TO_LIVE, name))
            }
        } catch (t: Throwable) {
            val back = rollback(dataDir, staging, old, ops, rename)
            if (back != null) t.addSuppressed(back)
            throw t
        }
        val json = okStatus(manifestText != null, manifestError, mismatches, entryNames, serviceStopped)
        if (filesOnly) return StagedFiles(File(staging, "shared_prefs"), json)
        writeStatus(statusFile ?: throw IOException("datatransfer: status file missing"), json)
        return null
    }

    private class Move(val kind: Int, val root: String)

    private fun rollback(
        dataDir: File,
        staging: File,
        old: File,
        ops: List<Move>,
        rename: (File, File) -> Boolean,
    ): Throwable? {
        for (op in ops.asReversed()) {
            try {
                val live = File(dataDir, op.root)
                when (op.kind) {
                    TO_LIVE -> {
                        val staged = File(staging, op.root)
                        if (staged.exists()) throw IOException("datatransfer: rollback staged path occupied ${staged.path}")
                        if (!rename(live, staged)) {
                            throw IOException("datatransfer: rollback rename failed ${live.path} -> ${staged.path}")
                        }
                    }
                    TO_OLD -> {
                        val bak = File(old, op.root)
                        if (live.exists()) throw IOException("datatransfer: rollback live path occupied ${live.path}")
                        if (!rename(bak, live)) {
                            throw IOException("datatransfer: rollback rename failed ${bak.path} -> ${live.path}")
                        }
                    }
                    else -> throw IOException("datatransfer: unknown rollback op ${op.kind}")
                }
            } catch (t: Throwable) {
                return t
            }
        }
        if (old.exists() && !old.deleteRecursively()) {
            return IOException("datatransfer: cannot delete ${old.path} after rollback")
        }
        return null
    }

    private fun recordMismatches(staging: File, fields: BackupManifest.Fields, mismatches: MutableList<String>) {
        for (e in fields.entries) {
            val rel = try {
                DataTransferTar.normalizeRelative(e.path)
            } catch (ex: IllegalArgumentException) {
                mismatches.add("${e.path}: manifest path refused")
                continue
            }
            val f = File(staging, rel)
            if (!f.isFile) {
                mismatches.add("${e.path}: missing")
                continue
            }
            val got = sha256(f)
            if (got != e.sha256.lowercase(Locale.US) || f.length() != e.bytes) {
                mismatches.add("${e.path}: sha256 manifest=${e.sha256} actual=$got")
            }
        }
    }

    private fun copyRoot(src: File, dest: File) {
        if (!src.exists()) return
        if (java.nio.file.Files.isSymbolicLink(src.toPath())) {
            throw IOException("datatransfer: symlink refused: ${src.path}")
        }
        if (!src.isDirectory) throw IOException("datatransfer: root is not a directory: ${src.path}")
        copyRec(src, dest)
    }

    private fun copyRec(src: File, dest: File) {
        if (!dest.isDirectory && !dest.mkdirs()) throw IOException("datatransfer: cannot mkdir ${dest.path}")
        val kids = src.listFiles() ?: throw IOException("datatransfer: cannot list ${src.path}")
        for (child in kids) {
            if (child.name.endsWith(".tmp") || child.name.endsWith(".part")) continue
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) {
                throw IOException("datatransfer: symlink refused: ${child.path}")
            }
            val target = File(dest, child.name)
            when {
                child.isDirectory -> copyRec(child, target)
                child.isFile -> copyFile(child, target)
                else -> throw IOException("datatransfer: not a regular file: ${child.path}")
            }
        }
    }

    private fun copyFile(src: File, dest: File) {
        val parent = dest.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("datatransfer: cannot mkdir ${parent.path}")
        }
        src.inputStream().use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
    }

    private fun writeTar(out: OutputStream, snapshot: File, info: BackupManifest.Info, nowMs: Long) {
        val items = ArrayList<Pair<String, File>>()
        collect(snapshot, snapshot, items)
        for ((path, _) in items) {
            if (path.toByteArray(Charsets.UTF_8).size > 255) {
                throw IllegalArgumentException("datatransfer: path longer than 255 bytes: $path")
            }
        }
        val entries = items.filter { it.second.isFile }.map { (path, file) ->
            BackupManifest.Entry(path, file.length(), sha256(file))
        }
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
                createdUtc = utc(nowMs),
                androidSdkInt = info.androidSdkInt,
                entries = entries,
                excluded = BackupManifest.EXCLUDED,
            ),
        ).toByteArray(Charsets.UTF_8)
        val mtime = nowMs / 1000L
        DataTransferTar.writeFile(out, BackupManifest.NAME, manifest, mtime)
        for ((path, file) in items) {
            if (file.isDirectory) DataTransferTar.writeDir(out, path, mtime)
            else DataTransferTar.writeFileFrom(out, path, file, mtime)
        }
        DataTransferTar.writeEnd(out)
    }

    private fun collect(root: File, dir: File, out: MutableList<Pair<String, File>>) {
        val kids = dir.listFiles() ?: throw IOException("datatransfer: cannot list ${dir.path}")
        for (child in kids.sortedBy { it.name }) {
            out.add(relPath(root, child) to child)
            if (child.isDirectory) collect(root, child, out)
        }
    }

    private fun relPath(root: File, file: File): String {
        val base = root.absolutePath.trimEnd(File.separatorChar) + File.separator
        val path = file.absolutePath
        if (!path.startsWith(base)) throw IOException("datatransfer: snapshot path outside ${root.path}: $path")
        return path.removePrefix(base).replace(File.separatorChar, '/')
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun wipe(dir: File) {
        if (!dir.exists()) return
        if (!dir.deleteRecursively()) throw IOException("datatransfer: cannot wipe ${dir.path}")
    }

    private fun utc(nowMs: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(nowMs))
    }

    private fun errorStatus(reason: String): String =
        JSONObject().put("result", "error").put("reason", reason).toString()

    private fun okStatus(
        manifest: Boolean,
        manifestError: String?,
        mismatches: List<String>,
        entries: List<String>,
        serviceStopped: Boolean = false,
    ): String {
        val o = JSONObject().put("result", "ok").put("manifest", manifest)
        if (serviceStopped) o.put("serviceStopped", true)
        if (manifestError != null) o.put("manifestError", manifestError)
        val mm = JSONArray()
        for (line in mismatches) mm.put(line)
        val names = JSONArray()
        for (name in entries) names.put(name)
        return o.put("mismatches", mm).put("entries", names).toString()
    }

    private fun writeStatus(file: File, text: String) {
        val dir = file.parentFile ?: throw IOException("datatransfer: status file has no parent")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("datatransfer: cannot create ${dir.path}")
        val tmp = File(dir, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (file.exists() && !file.delete()) throw IOException("datatransfer: cannot replace ${file.path}")
        if (!tmp.renameTo(file)) throw IOException("datatransfer: cannot rename status file to ${file.path}")
    }
}
