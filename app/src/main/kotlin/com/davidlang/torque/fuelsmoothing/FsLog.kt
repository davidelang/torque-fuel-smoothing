package com.davidlang.torque.fuelsmoothing

import android.util.Log

/**
 * Logging policy: routine per-tick values are in the session files, so logcat carries only state changes and
 * events (connect/disconnect, session open/close, source or mode change, burn on/off, fills, refits, profile
 * decisions, errors). Repeating warnings are rate-limited per key. [verbose] (setup page, off by default)
 * restores the per-tick lines for debugging.
 */
internal object FsLog {
    const val KEY_VERBOSE = "verbose_log"
    const val WARN_EVERY_MS = 60_000L
    @Volatile var verbose: Boolean = false
    private val lastValue = HashMap<String, String>()
    private val lastWarn = HashMap<String, Long>()
    private val suppressed = HashMap<String, Int>()

    /** Per-tick detail: only when verbose. */
    fun v(tag: String, msg: String) { if (verbose) runCatching { Log.i(tag, msg) } }

    /** True when [value] differs from the last one seen under [key] (first call counts as a change). */
    @Synchronized fun changed(key: String, value: String): Boolean {
        if (lastValue[key] == value) return false
        lastValue[key] = value; return true
    }

    /** Logs [msg] when [value] changed for [key]; otherwise only when verbose. */
    fun change(tag: String, key: String, value: String, msg: String) {
        if (changed(key, value)) runCatching { Log.i(tag, msg) } else v(tag, msg)
    }

    /** Rate decision for a repeating warning: null = suppress, else the number suppressed since the last one. */
    @Synchronized fun allow(key: String, nowMs: Long, everyMs: Long = WARN_EVERY_MS): Int? {
        val last = lastWarn[key]
        if (last != null && nowMs - last < everyMs) { suppressed[key] = (suppressed[key] ?: 0) + 1; return null }
        lastWarn[key] = nowMs
        return suppressed.remove(key) ?: 0
    }

    fun warn(tag: String, key: String, msg: String, nowMs: Long = System.currentTimeMillis()) {
        if (verbose) { runCatching { Log.w(tag, msg) }; return }
        val n = allow(key, nowMs) ?: return
        runCatching { Log.w(tag, if (n > 0) "$msg (+$n similar in the last ${WARN_EVERY_MS / 1000} s)" else msg) }
    }

    @Synchronized fun reset() { lastValue.clear(); lastWarn.clear(); suppressed.clear() }
}
