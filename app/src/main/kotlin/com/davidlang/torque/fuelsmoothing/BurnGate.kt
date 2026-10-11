package com.davidlang.torque.fuelsmoothing

/**
 * Decides whether a flow reading may be integrated as burned fuel.
 * - Flow must be fresh: Torque's update time for it advanced within STALE_MS (when Torque reports
 *   update times), and its value changed within HELD_MS. A held/default value (e.g. Torque's
 *   calculated ff125d sitting at ~1 L/h with the engine off) is never integrated.
 * - RPM available: burn only when RPM > 0 and RPM itself is fresh by the same rules.
 * - RPM unavailable: MAF must be present and fresh by the same rules.
 * Deterministic; no clock of its own (callers pass nowMs).
 */
internal class BurnGate(
    private val staleMs: Long = STALE_MS,
    private val heldMs: Long = HELD_MS,
) {
    data class Input(
        val nowMs: Long,
        val flow: Double?,
        val flowUpdMs: Long? = null,
        val rpm: Double? = null,
        val rpmUpdMs: Long? = null,
        val maf: Double? = null,
        val mafUpdMs: Long? = null,
    )

    data class Verdict(val burn: Boolean, val why: String)

    private class Track {
        var value = Double.NaN
        var changedMs = Long.MIN_VALUE
        var upd = Long.MIN_VALUE
        var updSeenMs = Long.MIN_VALUE
    }

    private val tracks = HashMap<String, Track>()
    private var startMs: Long? = null

    fun reset() {
        tracks.clear()
        startMs = null
    }

    private fun fresh(key: String, value: Double?, updMs: Long?, now: Long): Boolean {
        if (value == null || !value.isFinite()) return false
        val tr = tracks.getOrPut(key) { Track() }
        if (value != tr.value) {
            tr.value = value
            tr.changedMs = now
        }
        if (updMs != null && updMs > 0L) {
            if (updMs != tr.upd) {
                tr.upd = updMs
                tr.updSeenMs = now
            }
            if (now - tr.updSeenMs > staleLimit(now)) return false
        }
        return now - tr.changedMs <= heldLimit(now)
    }

    /** First STARTUP_MS after the first check: Torque's PID updates are slow to settle; allow 3x. */
    private fun warm(now: Long): Boolean = startMs?.let { now - it < STARTUP_MS } ?: true
    private fun staleLimit(now: Long) = if (warm(now)) staleMs * STARTUP_FACTOR else staleMs
    private fun heldLimit(now: Long) = if (warm(now)) heldMs * STARTUP_FACTOR else heldMs

    fun check(i: Input): Verdict {
        if (startMs == null) startMs = i.nowMs
        val flowFresh = fresh("flow", i.flow, i.flowUpdMs, i.nowMs)
        val rpmFresh = fresh("rpm", i.rpm, i.rpmUpdMs, i.nowMs)
        val mafFresh = fresh("maf", i.maf, i.mafUpdMs, i.nowMs)
        if (i.flow == null || !i.flow.isFinite() || i.flow < 0.0) return Verdict(false, "noFlow")
        if (i.rpm != null && i.rpm.isFinite()) {
            if (i.rpm <= 0.0) return Verdict(false, "engineOff")
            if (!rpmFresh) return Verdict(false, "rpmStale")
            if (!flowFresh) return Verdict(false, "flowStale")
            return Verdict(true, "rpm")
        }
        if (!mafFresh) return Verdict(false, if (i.maf == null) "noRpmNoMaf" else "mafStale")
        if (!flowFresh) return Verdict(false, "flowStale")
        return Verdict(true, "maf")
    }

    companion object {
        const val STALE_MS = 5_000L
        const val HELD_MS = 10_000L
        const val STARTUP_MS = 60_000L
        const val STARTUP_FACTOR = 3L
    }
}
