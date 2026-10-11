package com.davidlang.torque.fuelsmoothing

import java.io.File

/**
 * Vehicle fingerprint (ECU identity) stored per profile in fingerprint.json, and the deterministic
 * profile match used on connect. Exact VIN wins; else calibration ID; else supported-PID bitmaps +
 * protocol similarity >= BITMAP_MATCH.
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

        /** Firm enough that a mismatching vehicle is treated as a different vehicle. */
        val firm: Boolean get() = vin != null || calId != null || (sessions >= FIRM_BITMAP_SESSIONS && bitmaps.isNotEmpty())
    }

    sealed class Decision(val why: String) {
        class Normal(why: String) : Decision(why)

        /** Identity is certain (VIN / calibration ID): write this session to [target]. */
        class Route(val target: String, why: String) : Decision(why)

        /** Wrong or unknown vehicle, not certain: hold data; [suggest] is a profile id or null = create new. */
        class Quarantine(val suggest: String?, why: String) : Decision(why)
    }

    // ---------------- parsing of mode 01/09 replies ----------------
    private fun hexOf(parts: List<String>?): String =
        parts.orEmpty().joinToString(" ").uppercase().replace(Regex("[^0-9A-F]"), "")

    /**
     * Mode 09 payload hex for [pid] across reply formats: CAN multi-frame ("014", "0: 49 02 01 ..", "1: ..")
     * or one "49 pid nn .." line per frame. Frame indexes, byte counts and repeated headers are dropped.
     */
    fun payload09(parts: List<String>?, pid: String): String? {
        val head = "49$pid"
        val sb = StringBuilder(); var seen = false
        for (line0 in parts.orEmpty()) {
            val line = line0.uppercase().replace(Regex("^\\s*[0-9A-F]{1,2}:"), "")
            var hex = line.replace(Regex("[^0-9A-F]"), "")
            if (hex.length <= 3 && !line.contains(' ')) continue
            val at = hex.indexOf(head)
            if (at >= 0 && (at == 0 || !seen)) { hex = hex.substring(at + 4).let { if (it.length >= 2) it.substring(2) else "" }; seen = true }
            if (seen) sb.append(hex)
        }
        return if (seen) sb.toString() else null
    }

    /** Printable ASCII after the 49xx header bytes (multi-frame line numbers dropped as non-printable). */
    fun parseAscii(parts: List<String>?, pid: String): String? {
        val hex = payload09(parts, pid) ?: return null
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < hex.length) {
            val b = hex.substring(i, i + 2).toInt(16)
            if (b in 0x21..0x7E) sb.append(b.toChar()) else if (b == 0x20 && sb.isNotEmpty()) sb.append(' ')
            i += 2
        }
        return sb.toString().trim().takeIf { it.length >= 4 }
    }

    /** CVN: hex bytes after 4906 + count byte. */
    fun parseCvn(parts: List<String>?): String? {
        val hex = hexOf(parts)
        val at = hex.indexOf("4906")
        if (at < 0 || hex.length < at + 14) return null
        return hex.substring(at + 6, minOf(hex.length, at + 6 + 8)).takeIf { it.length == 8 }
    }

    /** "41 00 BE 3F A8 13" -> "BE3FA813". */
    fun parseBitmap(parts: List<String>?, pid: String): String? {
        val hex = hexOf(parts)
        val at = hex.indexOf("41" + pid.substring(2))
        if (at < 0 || hex.length < at + 12) return null
        return hex.substring(at + 4, at + 12)
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
            stored.protocol ?: obs.protocol, bm, if (learning) stored.probes + obs.probes else stored.probes,
            stored.sessions + if (obs.empty) 0 else 1,
        )
    }

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
        val ids = profiles.keys.sorted()
        if (obs.vin != null) {
            ids.firstOrNull { profiles[it]!!.vin.equals(obs.vin, ignoreCase = true) }?.let { return Match(it, true, "vin") }
        }
        if (obs.calId != null) {
            ids.firstOrNull { p -> val f = profiles[p]!!; f.calId == obs.calId && (f.vin == null || obs.vin == null) }
                ?.let { return Match(it, true, "calId") }
        }
        var best: String? = null; var bestS = 0.0
        for (id in ids) {
            val f = profiles[id]!!
            // A known, different VIN or calibration ID rules a profile out.
            if (obs.vin != null && f.vin != null) continue
            if (obs.calId != null && f.calId != null && f.calId != obs.calId) continue
            val s = similarity(obs, f)
            if (s > bestS) { bestS = s; best = id }
        }
        return if (best != null && bestS >= BITMAP_MATCH) Match(best, false, "bitmaps %.2f".format(java.util.Locale.ROOT, bestS))
        else Match(null, false, "no match (best %.2f)".format(java.util.Locale.ROOT, bestS))
    }

    /** What to do with a session on [active] given the observed identity. */
    fun decide(active: String, obs: Fp, profiles: Map<String, Fp>): Decision {
        if (obs.empty) return Decision.Normal("no ECU identity read")
        val cur = profiles[active] ?: Fp()
        val m = match(obs, profiles)
        return when {
            m.id == active -> Decision.Normal("matches $active (${m.why})")
            m.id != null && m.certain -> Decision.Route(m.id, "identity of ${m.id} (${m.why}), Torque profile is $active")
            m.id != null -> Decision.Quarantine(m.id, "looks like ${m.id} (${m.why}), Torque profile is $active")
            cur.firm -> Decision.Quarantine(null, "unknown vehicle on firm profile $active (${m.why})")
            else -> Decision.Normal("learning $active (${m.why})")
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

    /** Resolves a notification choice to a plugin profile id; NEW_PROFILE creates one ("Vehicle <date>"). */
    fun confirmTarget(root: File, choice: String, nowMs: Long): String {
        if (choice != Fingerprint.NEW_PROFILE) return choice
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(nowMs))
        return ProfileStore.createProfile(root, "Vehicle $day", nowMs)
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
