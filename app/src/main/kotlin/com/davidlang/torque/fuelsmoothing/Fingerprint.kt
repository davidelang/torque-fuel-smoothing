package com.davidlang.torque.fuelsmoothing

import java.io.File

/**
 * Vehicle fingerprint (ECU identity) stored per profile in fingerprint.json, and the deterministic
 * profile match used on connect. Only an unambiguous exact VIN automatically identifies a physical vehicle.
 * Calibration IDs, names and capability bitmaps are never unique physical keys.
 */
internal object Fingerprint {
    const val FILE = "fingerprint.json"
    const val LEARN_SESSIONS = 3
    const val BITMAP_MATCH = 0.90
    const val FIRM_BITMAP_SESSIONS = 2
    val BITMAP_PIDS = listOf("0100", "0120", "0140", "0160", "0180", "01A0", "01C0")

    data class Fp(
        val vin: String? = null,
        val calId: String? = null,
        val cvn: String? = null,
        val ecuName: String? = null,
        val protocol: String? = null,
        val bitmaps: Map<String, String> = emptyMap(),
        val probes: Map<String, String> = emptyMap(),
        val sessions: Int = 0,
    ) {
        val empty: Boolean get() = vin == null && calId == null && bitmaps.isEmpty()

        /** Historical completeness hint only; never authorizes physical-vehicle matching. */
        val firm: Boolean get() = vin != null || calId != null || (sessions >= FIRM_BITMAP_SESSIONS && bitmaps.isNotEmpty())
    }

    /** Log-safe summary: no VIN, calibration ID or CVN values (last 4 VIN chars, short hash of the cal ID). */
    fun redacted(f: Fp): String {
        fun h(x: String) = "%08x".format(x.hashCode())
        return "{vin=${f.vin?.let { "…" + it.takeLast(4) } ?: "-"},calId=${f.calId?.let { "#" + h(it) } ?: "-"},cvn=${if (f.cvn != null) "present" else "-"}," +
            "ecu=${f.ecuName ?: "-"},protocol=${f.protocol ?: "-"},bitmaps=${f.bitmaps.size},probes=${f.probes.size}}"
    }

    sealed class Decision(val why: String) {
        class Normal(why: String) : Decision(why)

        /** Physical identity is certain (VIN): write this session to [target]. */
        class Route(val target: String, why: String) : Decision(why)

        /** Wrong or unknown vehicle, not certain: hold data; [suggest] is a profile id or null = create new. */
        class Quarantine(val suggest: String?, why: String) : Decision(why)
    }

    // ---------------- parsing of mode 01/09 replies ----------------

    /**
     * Mode 09 payload hex for [pid] across reply formats: CAN multi-frame ("014", "0: 49 02 01 ..", "1: ..")
     * or one "49 pid nn .." line per frame. Frame indexes, byte counts and repeated headers are dropped.
     */
    fun payload09(parts: List<String>?, pid: String): String? {
        val reply = Mode09Replies.messages(parts, pid).singleOrNull() ?: return null
        return ObdReply.payloadAfter(reply, "49$pid")?.drop(2)
    }

    fun parseAscii(parts: List<String>?, pid: String): String? {
        val replies = Mode09Replies.messages(parts, pid)
        if (replies.isEmpty()) return null
        val values = replies.map { DiagnosticIdentityDecode.decode(pid, it) ?: return null }
        val key = if (pid == "04") "calibrationIds" else "ecuNames"
        return values.joinToString(";") { it.getJSONArray(key).toString() }
    }

    fun parseCvn(parts: List<String>?): String? {
        val replies = Mode09Replies.messages(parts, "06")
        if (replies.isEmpty()) return null
        return replies.map { DiagnosticIdentityDecode.decode("06", it) ?: return null }
            .joinToString(";") { it.getJSONArray("calibrationVerificationNumbersHex").toString() }
    }

    /** "41 00 BE 3F A8 13" -> "BE3FA813". */
    fun parseBitmap(parts: List<String>?, pid: String): String? {
        if (parts.isNullOrEmpty() || parts.size > 64 || parts.any { it.length > 512 }) return null
        val data = parts.flatMap { it.lines() }.mapNotNull { ObdReply.dataAfter(it, "41" + pid.substring(2), 4) }
        if (data.isEmpty() || data.any { it.length != 8 }) return null
        // Capability union only; this is NOT ECU identity or unique ECU attribution.
        return data.fold(0L) { mask, hex -> mask or hex.toLong(16) }.toString(16).uppercase().padStart(8, '0')
    }

    fun merge(stored: Fp, obs: Fp): Fp {
        val learning = stored.sessions < LEARN_SESSIONS
        val bm = HashMap(stored.bitmaps)
        if (learning) {
            for ((k, v) in obs.bitmaps) {
                val old = bm[k]
                bm[k] = if (old == null) v else orHex(old, v)
            }
        }
        return Fp(
            stored.vin ?: obs.vin, stored.calId ?: obs.calId, stored.cvn ?: obs.cvn, stored.ecuName ?: obs.ecuName,
            stored.protocol ?: obs.protocol, bm, mergeProbes(stored.probes, obs.probes, learning),
            stored.sessions + if (obs.empty) 0 else 1,
        )
    }

    /** An "ok" probe (or live success) is never downgraded by a later "no"; new keys are added while learning. */
    fun mergeProbes(stored: Map<String, String>, obs: Map<String, String>, learning: Boolean): Map<String, String> {
        val out = LinkedHashMap(stored)
        for ((k, v) in obs) {
            val old = out[k]
            if (old == null) { if (learning) out[k] = v }
            else if (old != "ok") out[k] = v
        }
        return out
    }

    /** Key recording that [sourceId] returned usable levels in a real session (live evidence beats the probe). */
    fun liveKey(sourceId: String) = "live:$sourceId"

    /** Record live success for [sourceId]; true when the fingerprint changed. Informational (not used for matching). */
    fun markLive(profileDir: File, sourceId: String): Boolean {
        val f = load(profileDir)
        if (f.probes[liveKey(sourceId)] == "ok") return false
        save(profileDir, f.copy(probes = f.probes + (liveKey(sourceId) to "ok")))
        return true
    }

    /** Probe reply, hex only, truncated: kept so a "no" can be diagnosed (NO DATA, 7F 22 negative reply, header). */
    fun replyHex(parts: List<String>?): String =
        (parts?.joinToString("") ?: "").uppercase().filter { it in "0123456789ABCDEF" }.take(32).ifEmpty { "none" }

    private fun orHex(a: String, b: String): String =
        if (a.length != b.length) a else (a.toLong(16) or b.toLong(16)).toString(16).uppercase().padStart(a.length, '0')

    /** Jaccard similarity of supported-PID bits over the bitmaps both sides have; 0 when protocols differ. */
    fun similarity(a: Fp, b: Fp): Double {
        if (a.protocol != null && b.protocol != null && a.protocol != b.protocol) return 0.0
        val keys = a.bitmaps.keys.intersect(b.bitmaps.keys).sorted()
        if (keys.isEmpty()) return 0.0
        var inter = 0; var union = 0
        for (k in keys) {
            val x = a.bitmaps[k]!!.toLongOrNull(16) ?: continue
            val y = b.bitmaps[k]!!.toLongOrNull(16) ?: continue
            inter += java.lang.Long.bitCount(x and y); union += java.lang.Long.bitCount(x or y)
        }
        return if (union == 0) 0.0 else inter.toDouble() / union
    }

    class Match(val id: String?, val certain: Boolean, val why: String)

    fun match(obs: Fp, profiles: Map<String, Fp>): Match {
        val vin = obs.vin ?: return Match(null, false, "physical identity unavailable")
        val hits = profiles.filterValues { it.vin?.equals(vin, ignoreCase = true) == true }.keys
        return if (hits.size == 1) Match(hits.single(), true, "vin")
        else Match(null, false, if (hits.isEmpty()) "new VIN" else "ambiguous saved VIN")
    }

    /** Weak ECU configuration evidence is diagnostic context, NOT physical vehicle identity. */
    fun decide(active: String, obs: Fp, profiles: Map<String, Fp>): Decision {
        val m = match(obs, profiles)
        return when {
            m.id == active -> Decision.Normal("matches $active (vin)")
            m.id != null -> Decision.Route(m.id, "vin")
            else -> Decision.Quarantine(null, m.why)
        }
    }

    const val NEW_PROFILE = "__new__"

    /**
     * Notification choices for a quarantined session, in order: keep in the suggested profile (if any),
     * then always "create new profile". Each is a target for [Quarantine.resolve] (NEW_PROFILE = create).
     */
    fun actions(suggest: String?): List<String> = listOfNotNull(suggest, NEW_PROFILE)

    // ---------------- storage ----------------
    private fun js(s: String?) = if (s == null) "null" else "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun mapJson(m: Map<String, String>) = m.toSortedMap().entries.joinToString(",", "{", "}") { "${js(it.key)}:${js(it.value)}" }

    fun toJson(f: Fp): String =
        "{\"vin\":${js(f.vin)},\"calId\":${js(f.calId)},\"cvn\":${js(f.cvn)},\"ecuName\":${js(f.ecuName)},\"protocol\":${js(f.protocol)}," +
            "\"bitmaps\":${mapJson(f.bitmaps)},\"probes\":${mapJson(f.probes)},\"sessions\":${f.sessions}}"

    private fun str(t: String, k: String) = Regex("\"$k\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(t)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")

    private fun obj(t: String, k: String): Map<String, String> {
        val body = Regex("\"$k\":\\{([^}]*)\\}").find(t)?.groupValues?.get(1) ?: return emptyMap()
        return Regex("\"([^\"]+)\":\"([^\"]*)\"").findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
    }

    fun fromJson(t: String): Fp = Fp(
        str(t, "vin"), str(t, "calId"), str(t, "cvn"), str(t, "ecuName"), str(t, "protocol"), obj(t, "bitmaps"), obj(t, "probes"),
        Regex("\"sessions\":(\\d+)").find(t)?.groupValues?.get(1)?.toInt() ?: 0,
    )

    fun load(profileDir: File): Fp {
        val f = File(profileDir, FILE)
        val base = if (f.isFile) fromJson(f.readText()) else Fp()
        // A VIN recorded in profile.json before fingerprints existed still counts.
        val pj = File(profileDir, "profile.json")
        val vin = if (base.vin == null && pj.isFile) str(pj.readText(), "vin") else null
        return if (vin != null) base.copy(vin = vin) else base
    }

    fun save(profileDir: File, f: Fp) {
        val tmp = File(profileDir, "$FILE.tmp")
        tmp.writeText(toJson(f))
        if (!tmp.renameTo(File(profileDir, FILE))) { File(profileDir, FILE).writeText(toJson(f)); tmp.delete() }
    }

    fun loadAll(root: File): Map<String, Fp> {
        val dirs = File(root, "profiles").listFiles { f -> f.isDirectory && File(f, "profile.json").isFile } ?: return emptyMap()
        return dirs.sortedBy { it.name }.associate { it.name to load(it) }
    }
}

/**
 * Quarantined sessions live under files/quarantine/<stamp>/t<tank>/ and never touch a profile. At session
 * end they are moved into the confirmed profile's raw dir, or (unconfirmed) set aside in
 * files/quarantine/discarded/ where no learning code reads them.
 */
internal object Quarantine {
    fun dir(root: File, stamp: Long): File = File(root, "quarantine/$stamp")

    fun rawDir(qdir: File, tank: Int): File = File(qdir, "t$tank")

    /** Resolves a notification choice to a plugin profile id. NEW_PROFILE creates one named [newName]. */
    fun confirmTarget(root: File, choice: String, nowMs: Long, newName: String): String {
        if (choice != Fingerprint.NEW_PROFILE) return choice
        return ProfileStore.createProfile(root, newName, nowMs)
    }

    /** Returns the files moved. [target] null = discard (set aside, not learned). */
    fun resolve(root: File, qdir: File, tank: Int, target: String?): List<File> {
        val src = rawDir(qdir, tank)
        val files = src.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: return emptyList()
        val dest = if (target != null) File(ProfileStore.tankDir(root, target, tank), "raw")
        else File(root, "quarantine/discarded/${qdir.name}/t$tank")
        dest.mkdirs()
        val out = ArrayList<File>()
        for (f in files) {
            val d = File(dest, f.name)
            if (d.exists()) continue
            if (f.renameTo(d)) out.add(d)
        }
        return out
    }
}
