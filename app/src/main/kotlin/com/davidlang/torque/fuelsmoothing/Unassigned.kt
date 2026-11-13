package com.davidlang.torque.fuelsmoothing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Holding area for drive data that was recorded while the vehicle was unknown, and that the user
 * did not assign before the drive ended. Lives under files/unassigned/<id>/ (not under profiles/,
 * so it is never learned from). Nothing here is deleted automatically. An explicit user discard
 * moves the folder to files/quarantine/discarded/<id>/ with a user marker; only then is it hidden
 * from the setup page.
 */
internal object Unassigned {
    const val ROOT = "unassigned"
    const val META = "unassigned.json"
    const val DISCARD_MARK = "discarded.json"
    const val REASON_USER = "user"

    data class Choice(val target: String, val label: String)
    data class Entry(
        val id: String,
        val torqueProfile: String?,
        val startedMs: Long,
        val endedMs: Long,
        val title: String,
        val text: String,
        val choices: List<Choice>,
        val fingerprint: Fingerprint.Fp?,
        val dir: File,
    )

    fun root(files: File): File = File(files, ROOT)

    fun dir(files: File, id: String): File = File(root(files), id)

    fun rawDir(files: File, id: String, tank: Int): File = File(dir(files, id), "t$tank")

    fun list(files: File): List<Entry> {
        val dirs = root(files).listFiles { f -> f.isDirectory }?.sortedByDescending { it.name } ?: return emptyList()
        return dirs.mapNotNull { read(it) }
    }

    fun hasAny(files: File): Boolean = list(files).isNotEmpty()

    fun read(dir: File): Entry? {
        if (!dir.isDirectory || !hasSessionJsonl(dir)) return null
        val meta = File(dir, META)
        val o = if (meta.isFile) runCatching { JSONObject(meta.readText()) }.getOrNull() else null
        val choices = ArrayList<Choice>()
        val arr = o?.optJSONArray("choices")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val c = arr.optJSONObject(i) ?: continue
                val target = c.optString("target")
                val label = c.optString("label")
                if (target.isNotEmpty()) choices.add(Choice(target, label.ifEmpty { target }))
            }
        }
        val fpText = o?.optJSONObject("fingerprint")?.toString()
        val fp = fpText?.let { runCatching { Fingerprint.fromJson(it) }.getOrNull() }
        val started = o?.optLong("startedMs", 0L) ?: 0L
        val ended = o?.optLong("endedMs", 0L) ?: 0L
        return Entry(
            id = dir.name,
            torqueProfile = o?.optString("torqueProfile")?.takeIf { it.isNotEmpty() },
            startedMs = if (started > 0L) started else dir.name.toLongOrNull() ?: 0L,
            endedMs = ended,
            title = o?.optString("title").orEmpty().ifEmpty { "Unassigned drive" },
            text = o?.optString("text").orEmpty(),
            choices = choices,
            fingerprint = fp,
            dir = dir,
        )
    }

    /**
     * Move a quarantine directory that still holds session files into unassigned/<id>/ and write
     * metadata. Idempotent if the destination already exists. Returns null when there is no session
     * data to keep (identity-only breadcrumbs are left alone).
     */
    fun park(
        files: File,
        qdir: File,
        torqueProfile: String?,
        title: String,
        text: String,
        choices: List<Choice>,
        fingerprint: Fingerprint.Fp?,
        startedMs: Long,
        endedMs: Long,
    ): Entry? {
        if (!qdir.isDirectory || !hasSessionJsonl(qdir)) return null
        val id = qdir.name
        val dest = dir(files, id)
        dest.parentFile?.mkdirs()
        if (dest.canonicalPath != qdir.canonicalPath) {
            if (dest.exists()) {
                // Already parked; copy any jsonl that is not yet in dest, leave the rest.
                mergeTree(qdir, dest)
            } else if (!qdir.renameTo(dest)) {
                mergeTree(qdir, dest)
            }
        }
        writeMeta(dest, torqueProfile, title, text, choices, fingerprint, startedMs, endedMs)
        return read(dest)
    }

    fun assign(files: File, id: String, profileId: String): List<File> {
        val src = dir(files, id)
        if (!src.isDirectory) return emptyList()
        val moved = ArrayList<File>()
        val tanks = src.listFiles { f -> f.isDirectory && f.name.matches(Regex("t[0-9]+")) } ?: emptyArray()
        for (tankDir in tanks) {
            val tank = tankDir.name.removePrefix("t").toIntOrNull() ?: continue
            val dest = File(ProfileStore.tankDir(files, profileId, tank), "raw")
            dest.mkdirs()
            val filesToMove = tankDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedBy { it.name }
                ?: continue
            for (f in filesToMove) {
                val d = File(dest, f.name)
                if (d.exists()) continue
                if (f.renameTo(d)) moved.add(d)
            }
        }
        // Remove the holding dir only when no session jsonl remains.
        if (!hasSessionJsonl(src)) src.deleteRecursively()
        return moved
    }

    /** Explicit user discard only. Moves to quarantine/discarded/<id>/ and writes a user marker. */
    fun discard(files: File, id: String): Boolean {
        val src = dir(files, id)
        if (!src.isDirectory) return false
        val dest = File(files, "quarantine/discarded/$id")
        dest.parentFile?.mkdirs()
        if (dest.exists()) mergeTree(src, dest) else if (!src.renameTo(dest)) mergeTree(src, dest)
        AtomicFile.write(File(dest, DISCARD_MARK), JSONObject().put("reason", REASON_USER)
            .put("t", System.currentTimeMillis()).toString())
        if (src.exists() && src.canonicalPath != dest.canonicalPath && !hasSessionJsonl(src)) src.deleteRecursively()
        return hasSessionJsonl(dest) || File(dest, DISCARD_MARK).isFile
    }

    /**
     * Promote leftover session data that would otherwise sit unseen: open files/quarantine/<stamp>/
     * trees with jsonl, and files/quarantine/discarded/<stamp>/ trees that were not explicitly
     * discarded by the user. Identity-only breadcrumb dirs are ignored. [skip] is a live quarantine
     * directory that must not be moved mid-drive.
     */
    fun adoptOrphans(files: File, skip: File? = null): Int {
        var n = 0
        val qroot = File(files, "quarantine")
        if (qroot.isDirectory) {
            val skipPath = skip?.canonicalPath
            for (child in qroot.listFiles { f -> f.isDirectory } ?: emptyArray()) {
                if (child.name == "discarded" || child.name == "unassigned") continue
                if (skipPath != null && child.canonicalPath == skipPath) continue
                if (!hasSessionJsonl(child)) continue
                if (park(files, child, null, "Unassigned drive", "", emptyList(), null,
                        child.name.toLongOrNull() ?: 0L, 0L) != null) n++
            }
        }
        val discarded = File(files, "quarantine/discarded")
        if (discarded.isDirectory) {
            for (child in discarded.listFiles { f -> f.isDirectory } ?: emptyArray()) {
                if (isUserDiscard(child) || !hasSessionJsonl(child)) continue
                if (park(files, child, null, "Unassigned drive", "", emptyList(), null,
                        child.name.toLongOrNull() ?: 0L, 0L) != null) n++
            }
        }
        return n
    }

    fun isUserDiscard(dir: File): Boolean {
        val f = File(dir, DISCARD_MARK)
        if (!f.isFile) return false
        val o = runCatching { JSONObject(f.readText()) }.getOrNull() ?: return false
        return o.optString("reason") == REASON_USER
    }

    fun hasSessionJsonl(dir: File): Boolean {
        if (!dir.isDirectory) return false
        val tanks = dir.listFiles { f -> f.isDirectory && f.name.matches(Regex("t[0-9]+")) } ?: emptyArray()
        return tanks.any { t -> t.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.isNotEmpty() == true }
    }

    fun tanksWithFiles(dir: File): List<Int> {
        val tanks = dir.listFiles { f -> f.isDirectory && f.name.matches(Regex("t[0-9]+")) } ?: return emptyList()
        return tanks.mapNotNull { t -> t.name.removePrefix("t").toIntOrNull() }.sorted()
    }

    private fun writeMeta(
        dest: File,
        torqueProfile: String?,
        title: String,
        text: String,
        choices: List<Choice>,
        fingerprint: Fingerprint.Fp?,
        startedMs: Long,
        endedMs: Long,
    ) {
        dest.mkdirs()
        val arr = JSONArray()
        for (c in choices) arr.put(JSONObject().put("target", c.target).put("label", c.label))
        val o = JSONObject()
            .put("v", 1)
            .put("torqueProfile", torqueProfile ?: JSONObject.NULL)
            .put("startedMs", startedMs)
            .put("endedMs", endedMs)
            .put("title", title)
            .put("text", text)
            .put("choices", arr)
        if (fingerprint != null) o.put("fingerprint", JSONObject(Fingerprint.toJson(fingerprint)))
        AtomicFile.write(File(dest, META), o.toString())
    }

    private fun mergeTree(src: File, dest: File) {
        if (!src.exists() || src.canonicalPath == dest.canonicalPath) return
        dest.mkdirs()
        val kids = src.listFiles() ?: return
        for (child in kids) {
            val d = File(dest, child.name)
            if (child.isDirectory) {
                mergeTree(child, d)
                if (child.listFiles()?.isEmpty() != false) child.delete()
            } else if (!d.exists()) {
                if (!child.renameTo(d)) {
                    child.copyTo(d, overwrite = false)
                    child.delete()
                }
            }
        }
        if (src.listFiles()?.isEmpty() != false) src.delete()
    }
}
