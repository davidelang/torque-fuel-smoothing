package com.davidlang.torque.fuelsmoothing

import java.io.File

/**
 * Automatic fuel-level source detection. Each profile stores its source and a confirmed flag (profile.json
 * "fuelSource", "fuelSourceConfirmed"); a new profile starts at "none". On connect, an unconfirmed profile
 * queues detection: one candidate per poll tick (one read-only request: a Torque PID read by name, or a
 * service 01/21/22 query), brand candidates only when the make or VIN WMI matches (spec §3a gating), the
 * generic SAE 012F last. The first candidate with a plausible level (0..100 %) wins and is stored confirmed.
 * The manual Detect button runs the same detection.
 */
internal object FuelDetect {
    const val NONE = "none"
    private val GATES = mapOf(
        "ford_16c1" to setOf("ford", "lincoln"),
        "ford_f42f" to setOf("ford", "lincoln"),
        "gm_12c5" to setOf("gm", "chevrolet", "gmc", "buick", "cadillac"),
        "chrysler_21a2" to setOf("stellantis", "jeep", "ram", "chrysler", "dodge", "fiat"),
        "chrysler_avg" to setOf("stellantis", "jeep", "ram", "chrysler", "dodge", "fiat"),
        "toyota_7c0_2129" to setOf("toyota", "lexus"),
    )

    /**
     * Ordered candidates. A source Torque already exposes by long name (no bus traffic) is tried first whatever
     * the make; then brand sources whose gate matches; then SAE 012F.
     */
    fun candidates(vin: String?, make: String?, torqueNames: Set<String>): List<String> {
        val out = ArrayList<String>()
        for (s in FuelSources.catalog) if (s.longName != null && s.longName in torqueNames) out.add(s.id)
        for (s in FuelSources.catalog) {
            val g = GATES[s.id] ?: continue
            if (s.id !in out && s.modePid != null && SourceProbe.gateOpen(g, vin, make)) out.add(s.id)
        }
        out.add("sae_012f")
        return out
    }

    fun plausible(pct: Double?): Boolean = pct != null && pct.isFinite() && pct in 0.0..100.0

    /** One detection pass: [next] gives the candidate to try this tick; [report] its read. */
    class Run(private val queue: List<String>) {
        private var i = 0
        var found: String? = null
            private set
        val tried = ArrayList<String>()
        val done get() = found != null || i >= queue.size
        fun next(): String? = if (done) null else queue[i]
        fun report(id: String, pct: Double?) {
            if (done || queue[i] != id) return
            tried.add(id)
            if (plausible(pct)) found = id else i++
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
