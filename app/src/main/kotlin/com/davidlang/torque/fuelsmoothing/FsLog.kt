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

    const val LOG_DIR = "logs"
    const val LOG_FILE = "events.log"
    const val LOG_MAX_BYTES = 256L * 1024
    @Volatile private var file: java.io.File? = null
    private val fileLock = Any()
    private val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)

    /**
     * Mirror INFO/WARN/ERROR lines to files/logs/events.log (rotated to events.1.log at [LOG_MAX_BYTES]). Logcat's
     * 256 KiB main buffer on the phone is overwritten by other apps within minutes, so lifecycle lines (session,
     * rollup, Kalman, detection, profile) are kept here as well. Verbose per-tick lines go to logcat only.
     */
    fun init(filesDir: java.io.File) {
        runCatching { val d = java.io.File(filesDir, LOG_DIR); d.mkdirs(); file = java.io.File(d, LOG_FILE) }
    }

    internal fun toFile(level: Char, tag: String, msg: String, nowMs: Long = System.currentTimeMillis()) {
        val f = file ?: return
        runCatching {
            synchronized(fileLock) {
                if (f.length() > LOG_MAX_BYTES) {
                    val old = java.io.File(f.parentFile, "events.1.log"); old.delete(); f.renameTo(old)
                }
                f.appendText("${stamp.format(java.util.Date(nowMs))} $level $tag: $msg\n")
            }
        }
    }

    fun i(tag: String, msg: String) { runCatching { Log.i(tag, msg) }; toFile('I', tag, msg) }
    fun w(tag: String, msg: String) { runCatching { Log.w(tag, msg) }; toFile('W', tag, msg) }
    fun e(tag: String, msg: String, t: Throwable? = null) { runCatching { Log.e(tag, msg, t) }; toFile('E', tag, if (t != null) "$msg: $t" else msg) }

    /** Per-tick detail: only when verbose (logcat only). */
    fun v(tag: String, msg: String) { if (verbose) runCatching { Log.i(tag, msg) } }

    /** True when [value] differs from the last one seen under [key] (first call counts as a change). */
    @Synchronized fun changed(key: String, value: String): Boolean {
        if (lastValue[key] == value) return false
        lastValue[key] = value; return true
    }

    /** Logs [msg] when [value] changed for [key]; otherwise only when verbose. */
    fun change(tag: String, key: String, value: String, msg: String) {
        if (changed(key, value)) i(tag, msg) else v(tag, msg)
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
        w(tag, if (n > 0) "$msg (+$n similar in the last ${WARN_EVERY_MS / 1000} s)" else msg)
    }

    @Synchronized fun reset() { lastValue.clear(); lastWarn.clear(); suppressed.clear() }
}
