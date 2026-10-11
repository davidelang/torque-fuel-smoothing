package com.davidlang.torque.fuelsmoothing

import java.io.File

/**
 * Automatic fuel-level source detection. Each profile stores its source and a confirmed flag (profile.json
 * "fuelSource", "fuelSourceConfirmed"); a new profile starts at "none". On connect, an unconfirmed profile
 * queues detection: one candidate per poll tick (one read-only request: a Torque PID read by name, or a
 * service 01/21/22 query), brand candidates only when the make or VIN WMI matches (spec §3a gating), the
 * generic SAE 012F last. A candidate needs fresh, non-zero, stable reads (see [Run]); it is confirmed only when a
 * second source agrees, otherwise stored unconfirmed and the user is asked to confirm. Detect runs it on demand.
 */
internal object FuelDetect {
    const val NONE = "none"
    const val KEY_TRIED = "fuelDetectTried"
    const val KEY_NOTIFIED = "fuelDetectNotified"
    const val REDETECT_MIN_SAMPLES = 300

    /** Auto-detect only for a profile with no source that has not been auto-detected before. */
    fun shouldAutoDetect(sourceId: String, triedBefore: Boolean): Boolean = sourceId == NONE && !triedBefore

    /**
     * A session (>= 5 min) in which the configured source gave no usable level: alert the user once for that session.
     * Communication failures never trigger detection and never change the stored source.
     */
    fun noLevelAlert(sourceId: String, samples: Int, valid: Int): Boolean =
        sourceId != NONE && samples >= REDETECT_MIN_SAMPLES && valid == 0
    private val GATES = mapOf(
        "ford_16c1" to setOf("ford", "lincoln"),
        "ford_f42f" to setOf("ford", "lincoln"),
        "gm_12c5" to setOf("gm", "chevrolet", "gmc", "buick", "cadillac"),
        "chrysler_21a2" to setOf("stellantis", "jeep", "ram", "chrysler", "dodge", "fiat"),
        "chrysler_avg" to setOf("stellantis", "jeep", "ram", "chrysler", "dodge", "fiat"),
        "toyota_7c0_2129" to setOf("toyota", "lexus"),
    )

    /**
     * Ordered candidates. Brand sources (mode 21/22 identifiers are manufacturer-defined and reused with other
     * meanings by other makes) are tried only when the make or VIN WMI matches their gate; a Torque PID read by
     * long name (no bus traffic) is also allowed when neither make nor VIN is known, but then it can only be
     * confirmed by agreement with another source or by the user. SAE 012F (same meaning on every make) last.
     */
    fun candidates(vin: String?, make: String?, torqueNames: Set<String>): List<String> {
        val out = ArrayList<String>()
        val unknown = vin.isNullOrBlank() && make.isNullOrBlank()
        fun gateOk(id: String) = GATES[id]?.let { SourceProbe.gateOpen(it, vin, make) } ?: true
        for (s in FuelSources.catalog) if (s.longName != null && s.longName in torqueNames && (gateOk(s.id) || unknown)) out.add(s.id)
        for (s in FuelSources.catalog) {
            val g = GATES[s.id] ?: continue
            if (s.id !in out && s.modePid != null && SourceProbe.gateOpen(g, vin, make)) out.add(s.id)
        }
        out.add("sae_012f")
        return out
    }

    const val NEED = 3            // valid reads needed per candidate
    const val MAX_POLLS = 8       // polls per candidate before moving on
    const val NO_REPLY_POLLS = 3  // give up early when nothing valid came back in this many polls
    const val STABLE_PCT = 3.0    // max spread of the valid reads
    const val AGREE_PCT = 5.0     // two different sources within this many % points confirm each other

    /**
     * A read counts only if it is a real, fresh answer: finite, > 0 and <= 100 (exact 0.0 is what Torque returns
     * for a PID it has not polled yet), and for a Torque PID read by name the update time must be set and must
     * have advanced since the previous read. [updateMs] null = a bus reply (fresh by construction).
     */
    fun valid(pct: Double?, updateMs: Long?, prevUpdateMs: Long?): Boolean {
        if (pct == null || !pct.isFinite() || pct <= 0.0 || pct > 100.0) return false
        if (updateMs == null) return true
        return updateMs > 0L && updateMs != prevUpdateMs
    }

    data class Result(val id: String?, val confirmed: Boolean, val level: Double?, val why: String)

    /**
     * One detection pass, one poll per tick. Every candidate is polled until it gives [NEED] valid, stable reads
     * (accepted) or runs out of polls. Result: the first accepted candidate in priority order; confirmed only
     * when a second, different accepted source agrees within [AGREE_PCT]. A single source is never
     * auto-confirmed: the user is asked to confirm it.
     */
    class Run(private val queue: List<String>) {
        private var i = 0
        private var polls = 0
        private var prevUpd: Long? = null
        private val reads = ArrayList<Double>()
        private val accepted = LinkedHashMap<String, Double>()
        val tried = ArrayList<String>()
        val done get() = i >= queue.size
        fun next(): String? = if (done) null else queue[i]

        /** Candidates whose reading is trusted only if 012F agrees (GM 12C5 two-byte form). */
        private val needs012f = HashSet<String>()

        fun report(id: String, pct: Double?, updateMs: Long?, needsAgreement: Boolean = false) {
            if (done || queue[i] != id) return
            if (needsAgreement) needs012f.add(id)
            polls++
            if (valid(pct, updateMs, prevUpd)) reads.add(pct!!)
            if (updateMs != null) prevUpd = updateMs
            val stable = reads.size >= NEED && reads.takeLast(NEED).let { it.max() - it.min() <= STABLE_PCT }
            if (stable) accepted[id] = reads.takeLast(NEED).sorted()[NEED / 2]
            if (stable || polls >= MAX_POLLS || (polls >= NO_REPLY_POLLS && reads.isEmpty())) {
                tried.add(id); i++; polls = 0; prevUpd = null; reads.clear()
            }
        }

        fun result(): Result {
            val sae = accepted["sae_012f"]
            val usable = accepted.entries.filter { e ->
                e.key !in needs012f || (sae != null && kotlin.math.abs(e.value - sae) <= AGREE_PCT)
            }
            val first = usable.firstOrNull() ?: return Result(null, false, null, "no source gave $NEED fresh, stable, non-zero reads")
            val other = usable.drop(1).firstOrNull { kotlin.math.abs(it.value - first.value) <= AGREE_PCT }
            return if (other != null) Result(first.key, true, first.value, "agrees with ${other.key} (${"%.1f".format(other.value)} %)")
            else Result(first.key, false, first.value, "single source; needs your confirmation")
        }
    }

    // ---------- per-profile storage ----------
    /** (source id, confirmed). Legacy installs: an explicitly saved global source is adopted as confirmed. */
    fun resolve(profileSource: String?, profileConfirmed: Boolean, legacyGlobal: String?): Pair<String, Boolean> = when {
        !profileSource.isNullOrBlank() -> profileSource to profileConfirmed
        !legacyGlobal.isNullOrBlank() -> legacyGlobal to true
        else -> NONE to false
    }

    fun load(root: File, pid: String, legacyGlobal: String?): Pair<String, Boolean> {
        val src = ProfileStore.profileField(root, pid, "fuelSource")
        val conf = ProfileStore.profileField(root, pid, "fuelSourceConfirmed") == "true"
        val r = resolve(src, conf, legacyGlobal)
        if (src.isNullOrBlank() && File(ProfileStore.profileDir(root, pid), "profile.json").isFile) store(root, pid, r.first, r.second)
        return r
    }

    fun store(root: File, pid: String, id: String, confirmed: Boolean) {
        ProfileStore.setProfileField(root, pid, "fuelSource", id)
        ProfileStore.setProfileField(root, pid, "fuelSourceConfirmed", confirmed.toString())
    }
}
