package com.davidlang.torque.fuelsmoothing

import java.io.File

/**
 * Drive-end/fill rollup caching. Every cache returns exactly what the uncached code computes for the same input
 * (parsed sessions keyed by path + size + mtime; per-session segmentation prep memoized per parsed session;
 * a rollup whose inputs are unchanged is skipped). [enabled] = false runs everything uncached (tests compare).
 */
internal object RollupCache {
    @Volatile var enabled: Boolean = true
    private val parsed = HashMap<String, Pair<String, FuelSession?>>()

    fun key(f: File) = "${f.length()}:${f.lastModified()}"

    @Synchronized fun session(f: File, parse: (File) -> FuelSession?): FuelSession? {
        if (!enabled) return parse(f)
        val k = key(f)
        parsed[f.path]?.let { if (it.first == k) return it.second }
        val s = parse(f)
        parsed[f.path] = k to s
        return s
    }

    @Synchronized fun clear() = parsed.clear()

    /** Input signature of a rollup: the raw files (name, size, mtime) plus the settings that change outputs. */
    fun signature(files: List<File>, extra: String): String =
        files.sortedBy { it.name }.joinToString("\n", postfix = "\n$extra") { "${it.name} ${key(it)}" }
}

internal object AtomicFile {
    /** Write via a temp file and rename so readers on another thread never see a partial file. */
    fun write(f: File, text: String) {
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }
}
