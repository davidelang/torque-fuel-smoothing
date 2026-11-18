package com.davidlang.torque.fuelsmoothing

import org.json.JSONObject
import java.io.File

/**
 * Per-profile identity check history: profiles/<id>/identity/checks.jsonl, a ring of the last [MAX] checks
 * (connect reads, one-time ID probes, Check vehicle button runs). Private file; raw replies may contain a VIN,
 * so nothing here is ever copied to FsLog, logcat or a notification. An entry with the same "id" as an
 * existing one replaces it (one read never takes more than one slot).
 */
internal object IdentityChecks {
    const val FILE = "identity/checks.jsonl"
    const val MAX = 20

    fun file(profileDir: File): File = File(profileDir, FILE)

    fun read(profileDir: File): List<JSONObject> {
        val f = file(profileDir)
        if (!f.isFile) return emptyList()
        return f.readLines().mapNotNull { l -> if (l.isBlank()) null else runCatching { JSONObject(l) }.getOrNull() }
    }

    fun record(profileDir: File, entry: JSONObject) {
        val id = entry.optString("id", "")
        val rows = read(profileDir).filter { id.isEmpty() || it.optString("id", "") != id }.toMutableList()
        rows.add(entry)
        val keep = rows.takeLast(MAX)
        val f = file(profileDir)
        f.parentFile?.mkdirs()
        AtomicFile.write(f, keep.joinToString("") { it.toString() + "\n" })
    }
}
