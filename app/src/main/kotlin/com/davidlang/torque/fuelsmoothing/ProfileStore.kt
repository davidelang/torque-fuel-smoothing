package com.davidlang.torque.fuelsmoothing

import java.io.File
import java.io.RandomAccessFile

/**
 * Schema 2 profile tree. A missing default profile is created in place.
 * Raw retention drops only raw files older than 14 days, and only when raw/ exceeds 50 MB.
 */
internal object ProfileStore {
    const val SCHEMA = 2
    const val DEFAULT_ID = "default"
    /** 2026-10-03T00:00:00-06:00. Reset repair applies only at or after this instant. */
    const val RESET_MS = 1_791_007_200_000L
    const val RAW_CAP_BYTES = 50L * 1024L * 1024L
    const val RAW_KEEP_MS = 14L * 24L * 60L * 60L * 1000L
    /** Raw pruning is OFF: no raw or session file is ever deleted, at any size or age. */
    const val RAW_PRUNE_ENABLED = false
    /** Permanent, append-only learning record per tank dir: one line per session, deduped by id. */
    const val LEARN_FILE = "learn-chunks.jsonl"
    /** Extra drop-in dir (under filesDir) whose legacy *.jsonl sessions are imported like files/sessions. */
    const val IMPORT_DIR = "import"

    data class LedgerSeed(
        val fillAnchorGal: Double?,
        val burnSinceFillGal: Double,
        val learnedUsable: Double?,
        val tankGal: Double?,
    )

    data class ProfileHit(val id: String, val vin: String?, val torqueProfileName: String?)

    fun migrate(
        root: File,
        torqueProfileName: String?,
        ledger: LedgerSeed?,
        nowMs: Long = System.currentTimeMillis(),
    ): File {
        val profile = File(root, "profiles/$DEFAULT_ID")
        val marker = File(profile, "profile.json")
        if (marker.isFile && fieldInt(marker.readText(), "schema") == SCHEMA) {
            importLegacy(root, activeId(root))
            retainRaw(File(profile, "raw"), nowMs)
            profile.listFiles()?.forEach { child ->
                if (child.isDirectory && child.name == "tanks") {
                    child.listFiles()?.forEach { tank -> retainRaw(File(tank, "raw"), nowMs) }
                }
            }
            return profile
        }
        profile.mkdirs()
        File(profile, "raw").mkdirs()
        val nameplate = ledger?.tankGal
        marker.writeText(profileJson(DEFAULT_ID, "Vehicle 1", null, torqueProfileName, nowMs, nameplate))
        val active = File(root, "active-profile.json")
        if (!active.isFile) active.writeText("{\"profileId\":\"$DEFAULT_ID\"}")
        File(profile, "ledger.json").writeText(ledgerJson(ledger))
        importLegacy(root, activeId(root))
        return profile
    }

    /**
     * Converts legacy sessions (files/sessions, files/import -> tank 1; files/sessions-tN -> tank N)
     * into the profile's raw/ dirs. Idempotent: a session whose raw file exists is skipped.
     * Sources are never deleted or modified. Returns the number of sessions imported.
     */
    fun importLegacy(root: File, profileId: String): Int {
        val sources = ArrayList<Pair<File, Int>>()
        sources.add(File(root, "sessions") to 1)
        sources.add(File(root, IMPORT_DIR) to 1)
        root.listFiles { f -> f.isDirectory && f.name.matches(Regex("sessions-t[0-9]+")) }
            ?.sortedBy { it.name }
            ?.forEach { sources.add(it to it.name.removePrefix("sessions-t").toInt()) }
        var n = 0
        for ((src, tank) in sources) {
            if (!src.isDirectory) continue
            val files = src.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }?.sortedBy { it.name } ?: continue
            if (files.isEmpty()) continue
            val raw = File(tankDir(root, profileId, tank), "raw").apply { mkdirs() }
            for (f in files) {
                val dest = File(raw, f.name)
                if (dest.exists()) continue
                val tmp = File(raw, f.name + ".part")
                runCatching { convertOne(f, tmp, profileId, tank) }
                    .onFailure { android.util.Log.w("FuelSmooth", "import ${f.name}: ${it.message}") }
                if (tmp.isFile && tmp.renameTo(dest)) n++
            }
        }
        return n
    }

    data class Selection(val id: String, val created: Boolean, val vinNote: String)

    /**
     * Binds the active profile to Torque's selected vehicle profile name.
     * - Blank name: stay on the active profile.
     * - One-time migration: while `default` is the only profile and has no Torque name, it takes
     *   the current name (existing history stays with the vehicle it was recorded on).
     * - Name match (case-insensitive) selects that profile; an unknown name creates a new profile.
     * The VIN never drives selection: it is stored on first sight and only produces a mismatch note.
     */
    fun select(
        root: File,
        vin: String?,
        torqueProfileName: String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Selection {
        val name = torqueProfileName?.trim().orEmpty()
        val id = if (name.isEmpty()) {
            Selection(activeId(root), false, "")
        } else {
            val hits = profiles(root)
            val match = hits.firstOrNull { it.torqueProfileName?.equals(name, ignoreCase = true) == true }
            val solo = hits.singleOrNull()
            when {
                match != null -> Selection(match.id, false, "")
                solo != null && solo.id == DEFAULT_ID && solo.torqueProfileName == null -> {
                    setProfileField(root, DEFAULT_ID, "torqueProfileName", name)
                    Selection(DEFAULT_ID, false, "")
                }
                else -> Selection(create(root, name, hits.map { it.id }.toSet(), nowMs), true, "")
            }
        }
        val stored = storedVin(root, id.id)
        if (stored.isNullOrBlank() && !vin.isNullOrBlank()) setProfileField(root, id.id, "vin", vin)
        return id.copy(vinNote = vinNote(stored, vin))
    }

    private fun create(root: File, name: String, taken: Set<String>, nowMs: Long): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "vehicle" }
        var id = base
        var n = 2
        while (id in taken || id == DEFAULT_ID || File(root, "profiles/$id").exists()) id = "$base-${n++}"
        val dir = File(root, "profiles/$id")
        File(dir, "raw").mkdirs()
        File(dir, "profile.json").writeText(profileJson(id, name, null, name, nowMs, null))
        File(dir, "ledger.json").writeText(ledgerJson(null))
        return id
    }

    fun activeId(root: File): String {
        val f = File(root, "active-profile.json")
        if (!f.isFile) return DEFAULT_ID
        return fieldString(f.readText(), "profileId") ?: DEFAULT_ID
    }

    fun setActiveId(root: File, id: String) {
        File(root, "active-profile.json").writeText("{\"profileId\":${jsonString(id)}}")
    }

    fun profileDir(root: File, id: String = activeId(root)): File = File(root, "profiles/$id")

    fun tankDir(root: File, profileId: String, tank: Int): File {
        val base = profileDir(root, profileId)
        return if (tank <= 1) base else File(base, "tanks/$tank")
    }

    fun profileField(root: File, profileId: String, key: String): String? {
        val f = File(profileDir(root, profileId), "profile.json")
        if (!f.isFile) return null
        return fieldString(f.readText(), key)
    }

    fun setProfileField(root: File, profileId: String, key: String, value: String?) {
        val f = File(profileDir(root, profileId), "profile.json")
        if (!f.isFile) return
        var text = f.readText()
        val rendered = if (value == null) "null" else jsonString(value)
        val token = "\"$key\":"
        if (!text.contains(token)) {
            text = text.replace("\"schema\":", "$token$rendered,\"schema\":")
        } else {
            text = Regex("\"$key\":(\"[^\"]*\"|null)").replace(text, "$token$rendered")
        }
        f.writeText(text)
    }

    fun addPin(root: File, profileId: String, miles: Double, tMs: Long) {
        val f = File(profileDir(root, profileId), "profile.json")
        if (!f.isFile) return
        val text = f.readText()
        val m = Regex("\"odometerPins\":\\[([^\\]]*)]").find(text) ?: return
        val inner = m.groupValues[1].trim()
        val item = "{\"tMs\":$tMs,\"miles\":$miles}"
        val next = if (inner.isEmpty()) item else "$inner,$item"
        f.writeText(text.replaceRange(m.range, "\"odometerPins\":[$next]"))
    }

    fun distanceManual(root: File, profileId: String): Boolean {
        val f = File(profileDir(root, profileId), "profile.json")
        if (!f.isFile) return false
        return f.readText().contains("\"manual\":true")
    }

    fun storedVin(root: File, profileId: String): String? {
        val f = File(profileDir(root, profileId), "profile.json")
        if (!f.isFile) return null
        return fieldString(f.readText(), "vin")
    }

    /** Empty when the read VIN matches or either side is unknown. */
    fun vinNote(stored: String?, read: String?): String {
        if (stored.isNullOrBlank() || read.isNullOrBlank()) return ""
        if (stored.equals(read, ignoreCase = true)) return ""
        return "VIN mismatch $read"
    }

    fun parseVin(parts: List<String>?): String? {
        if (parts.isNullOrEmpty()) return null
        val joined = parts.joinToString(" ")
        val hex = joined.uppercase().replace(Regex("[^0-9A-F]"), "")
        val chars = StringBuilder()
        var i = 0
        while (i + 1 < hex.length) {
            val b = hex.substring(i, i + 2).toIntOrNull(16) ?: break
            val ch = b.toChar()
            if (ch in 'A'..'Z' || ch in '0'..'9') chars.append(ch)
            i += 2
        }
        val s = chars.toString()
        for (start in 0..s.length - 17) {
            val vin = s.substring(start, start + 17)
            if (vin.none { it == 'I' || it == 'O' || it == 'Q' }) return vin
        }
        return null
    }

    fun retainRaw(rawDir: File, nowMs: Long, enabled: Boolean = RAW_PRUNE_ENABLED) {
        if (!enabled) return
        if (!rawDir.isDirectory) return
        val learned = learnedIds(File(rawDir.parentFile ?: rawDir, LEARN_FILE))
        val files = rawDir.listFiles { f -> f.isFile }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        if (total <= RAW_CAP_BYTES) return
        val cutoff = nowMs - RAW_KEEP_MS
        // Only files whose chunk summary is already in the learning record may ever be pruned.
        val old = files.filter { it.lastModified() < cutoff && it.name.removeSuffix(".jsonl") in learned }.sortedBy { it.lastModified() }
        for (f in old) {
            if (total <= RAW_CAP_BYTES) break
            val n = f.length()
            if (f.delete()) total -= n
        }
    }

    /**
     * Rebuild chunks, drives, fills, and model.json from raw/.
     * Does not call FuelMath.estimateUsable.
     */
    fun rollup(dir: File, nameplate: Double?): String {
        val raw = File(dir, "raw")
        val sessions = if (raw.isDirectory) FuelV2.loadSessions(raw) else emptyList()
        val t0 = FuelV2.chooseT0(nameplate, sessions)
        val fitted = if (sessions.isEmpty()) {
            null
        } else {
            runCatching { FuelV2.replay(sessions, 1.0, t0) }
                .onFailure { android.util.Log.w("FuelSmooth", "replay failed; keeping model.json: ${it.message}") }
                .getOrNull()
        }
        // A replay with zero chunks is just the prior; do not let it replace a learned model.
        val model = fitted?.takeIf { it.chunks > 0 || !File(dir, "model.json").isFile }
        if (model != null) {
            File(dir, "model.json").writeText(model.json)
            val chunksOut = File(dir, "chunks.jsonl")
            chunksOut.writeText("")
            val fillsOut = File(dir, "fills.jsonl")
            fillsOut.writeText("")
            for (s in sessions) {
                val chunks = FuelV2.chunksSf(
                    s,
                    { level -> FuelV2.gpp(level, model.edges, model.g) },
                    model.nMin,
                    4.0 * model.sigmaQ,
                )
                for (c in chunks) {
                    chunksOut.appendText(
                        "{\"sessionId\":${jsonString(s.name)},\"group\":${c.g},\"t\":${c.t},\"F\":${c.f},\"L\":${c.l},\"n\":${c.n}}\n",
                    )
                }
                if (model.fills.contains(s.name)) {
                    fillsOut.appendText("{\"sessionId\":${jsonString(s.name)},\"t\":${s.t.first()}}\n")
                }
            }
        } else if (!File(dir, "model.json").isFile) {
            // Seed only. A failed or empty replay must not overwrite a previously learned model.
            val cap = (if (nameplate != null && nameplate > 0.0) nameplate else t0) * 0.95 * 1.07
            File(dir, "model.json").writeText(
                "{\"active\":[],\"capToZero\":$cap,\"chunks\":0,\"edges\":[0.0,95.0],\"g\":[${t0 / 100.0}],\"gUnc\":[${t0 / 100.0}],\"nMin\":28,\"se\":[0.0],\"sigmaQ\":1.1,\"tau\":3.5}",
            )
            if (!File(dir, "chunks.jsonl").isFile) File(dir, "chunks.jsonl").writeText("")
            if (!File(dir, "fills.jsonl").isFile) File(dir, "fills.jsonl").writeText("")
        }
        appendLearned(dir, sessions, model)
        val drives = File(dir, "drives.jsonl")
        if (sessions.isNotEmpty()) {
            drives.writeText("")
            for (s in sessions) {
                val suspect = FuelV2.fuelSuspect(s, sessions)
                val fuel = if (s.used.isEmpty()) 0.0 else s.used.last() - s.used.first()
                drives.appendText(
                    "{\"sessionId\":${jsonString(s.name)},\"t0\":${s.t.first()},\"t1\":${s.t.last()},\"fuelGal\":$fuel," +
                        "\"fuelSuspect\":$suspect,\"unlogged\":false}\n",
                )
            }
        } else if (!drives.isFile) {
            drives.writeText("")
        }
        val gap = gapText(sessions, model)
        File(dir, "gap.txt").writeText(gap)
        return gap
    }

    fun storedMap(dir: File): Triple<DoubleArray, DoubleArray, Double>? {
        val f = File(dir, "model.json")
        if (!f.isFile) return null
        val text = f.readText()
        val edges = arrayField(text, "edges") ?: return null
        val g = arrayField(text, "g") ?: return null
        if (edges.size < 2 || g.isEmpty()) return null
        val cap = doubleField(text, "capToZero") ?: return null
        return Triple(edges, g, cap)
    }

    fun gapText(sessions: List<FuelSession>, model: FuelModel?): String {
        if (sessions.isEmpty()) return ""
        val b = StringBuilder()
        var prevEnd: Double? = null
        for (s in sessions) {
            val start = s.sm.firstOrNull { it.isFinite() } ?: continue
            val end = s.sm.lastOrNull { it.isFinite() } ?: continue
            val logged = start - end
            val gap = if (prevEnd == null) 0.0 else prevEnd - start
            val gapGal = if (model != null && prevEnd != null) model.v(prevEnd) - model.v(start) else 0.0
            b.append("session ").append(s.name)
            b.append(" loggedPct=").append(logged)
            b.append(" gapPct=").append(gap)
            b.append(" gapGal=").append(gapGal)
            b.append('\n')
            prevEnd = end
        }
        return b.toString()
    }

    /** Session ids already in the learning record. */
    fun learnedIds(f: File): Set<String> {
        if (!f.isFile) return emptySet()
        val out = HashSet<String>()
        f.forEachLine { line -> fieldString(line, "sessionId")?.let { out.add(it) } }
        return out
    }

    /**
     * Appends one compact line per not-yet-recorded session: its chunk summaries (group, t, F, L, n),
     * chunked with the model current at that time, plus fill flag and fuel span. Never rewrites.
     */
    fun appendLearned(dir: File, sessions: List<FuelSession>, m: FuelModel?) {
        // Without a fitted model there is nothing to chunk with; those sessions are recorded next time.
        if (sessions.isEmpty() || m == null) return
        val f = File(dir, LEARN_FILE)
        val have = learnedIds(f)
        val todo = sessions.filter { it.name !in have && it.t.size >= 2 }
        if (todo.isEmpty()) return
        val b = StringBuilder()
        for (s in todo) {
            val chunks = runCatching {
                FuelV2.chunksSf(s, { level -> FuelV2.gpp(level, m.edges, m.g) }, m.nMin, 4.0 * m.sigmaQ)
            }.getOrDefault(emptyList())
            b.append("{\"sessionId\":").append(jsonString(s.name))
            b.append(",\"t0\":").append(s.t.first()).append(",\"t1\":").append(s.t.last())
            b.append(",\"fuelGal\":").append(s.used.last() - s.used.first())
            b.append(",\"fill\":").append(s.fill.any { it })
            b.append(",\"chunks\":[")
            chunks.forEachIndexed { i, c ->
                if (i > 0) b.append(',')
                b.append("{\"group\":").append(c.g).append(",\"t\":").append(c.t)
                    .append(",\"F\":").append(c.f).append(",\"L\":").append(c.l).append(",\"n\":").append(c.n).append('}')
            }
            b.append("]}\n")
        }
        runCatching { f.appendText(b.toString()) }
            .onFailure { android.util.Log.w("FuelSmooth", "learn append: ${it.message}") }
    }

    private fun convertOne(src: File, dest: File, profileId: String, tank: Int) {
        val original = src.readText()
        val lines = original.lines()
        val action = LiterFix.decide(lines)
        val text = if (action == LiterFix.Action.CONVERT) LiterFix.convert(original) else original
        val rows = ArrayList<Row>()
        var t0 = 0L
        for (line in text.lines()) {
            if (line.isBlank()) continue
            if (t0 == 0L) {
                val openT = longField(line, "t0")
                if (openT != null) t0 = openT
            }
            if (line.contains("\"event\"") || !line.contains("\"t\"")) continue
            val t = longField(line, "t") ?: continue
            val fuel = doubleField(line, "fuelUsedGal") ?: doubleField(line, "fuelGal") ?: continue
            if (t0 == 0L) t0 = t
            rows.add(
                Row(
                    t = t,
                    fuel = fuel,
                    raw = doubleField(line, "rawPct") ?: doubleField(line, "lvl") ?: Double.NaN,
                    sm = doubleField(line, "smoothedPct") ?: doubleField(line, "sm") ?: Double.NaN,
                    spd = doubleField(line, "speed") ?: doubleField(line, "spd") ?: 0.0,
                    fill = line.contains("\"fill\":true") || line.contains("\"mode\":\"FILL\""),
                ),
            )
        }
        if (t0 >= RESET_MS && rows.size >= 2) repairReset(rows)
        val log = RawLog()
        val sessionId = src.name.removeSuffix(".jsonl")
        log.openLine(sessionId, t0, profileId, tank, "", "{}")
        var prev = 0.0
        var have = false
        for (row in rows) {
            val d = if (!have) row.fuel.coerceAtLeast(0.0) else row.fuel - prev
            if (d.isFinite() && d > 0.0) log.addDelta(d)
            have = true
            prev = row.fuel
            log.sample(
                t = row.t,
                lvl = row.raw,
                lvlSrc = "legacy",
                fuelRaw = null,
                fuelUnit = "gal",
                spd = row.spd,
                spdUnit = "",
                ecu = true,
                ok = row.raw.isFinite() && row.raw >= 0.0,
                why = if (row.raw.isFinite() && row.raw >= 0.0) null else "missing",
                mode = if (row.fill) "FILL" else "SKEW",
                sm = if (row.sm.isFinite()) row.sm else row.raw,
            )
            if (row.fill) log.event("fillEnd", row.t)
        }
        log.closeLine(rows.lastOrNull()?.t ?: t0, rows.size)
        dest.writeText(log.finish().joinToString("\n") + "\n")
    }

    /** If fuel drops, add the pre-drop value to that sample and every later sample. */
    private fun repairReset(rows: MutableList<Row>) {
        var i = 1
        while (i < rows.size) {
            val prev = rows[i - 1].fuel
            if (rows[i].fuel < prev) {
                for (j in i until rows.size) rows[j] = rows[j].copy(fuel = rows[j].fuel + prev)
            }
            i++
        }
    }

    private fun profiles(root: File): List<ProfileHit> {
        val dir = File(root, "profiles")
        val files = dir.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        return files.map { f ->
            val text = File(f, "profile.json").let { if (it.isFile) it.readText() else "" }
            ProfileHit(f.name, fieldString(text, "vin"), fieldString(text, "torqueProfileName"))
        }
    }

    private fun profileJson(
        id: String,
        name: String,
        vin: String?,
        torqueProfileName: String?,
        createdMs: Long,
        nameplateGal: Double?,
    ): String {
        return buildString {
            append("{\"id\":").append(jsonString(id))
            append(",\"name\":").append(jsonString(name))
            append(",\"vin\":").append(if (vin == null) "null" else jsonString(vin))
            append(",\"torqueProfileName\":").append(if (torqueProfileName == null) "null" else jsonString(torqueProfileName))
            append(",\"createdMs\":").append(createdMs)
            append(",\"nameplateGal\":").append(if (nameplateGal == null) "null" else nameplateGal.toString())
            append(",\"sources\":{},\"odometerPins\":[],\"schema\":").append(SCHEMA).append("}")
        }
    }

    private fun ledgerJson(seed: LedgerSeed?): String {
        val anchor = seed?.fillAnchorGal
        val burn = seed?.burnSinceFillGal ?: 0.0
        val learned = seed?.learnedUsable
        val tank = seed?.tankGal
        return buildString {
            append("{\"fillAnchorGal\":").append(if (anchor == null) "null" else anchor.toString())
            append(",\"burnSinceFillGal\":").append(burn)
            append(",\"learnedUsable\":").append(if (learned == null) "null" else learned.toString())
            append(",\"tankGal\":").append(if (tank == null) "null" else tank.toString())
            append("}")
        }
    }

    private fun moveInto(src: File, destDir: File) {
        if (!src.exists()) return
        destDir.mkdirs()
        val dest = File(destDir, src.name)
        if (dest.exists()) return
        if (!src.renameTo(dest)) {
            src.copyRecursively(dest, overwrite = false)
            src.deleteRecursively()
        }
    }

    private data class Row(
        val t: Long,
        val fuel: Double,
        val raw: Double,
        val sm: Double,
        val spd: Double,
        val fill: Boolean,
    )

    private fun jsonString(s: String): String {
        val b = StringBuilder(s.length + 2)
        b.append('"')
        for (ch in s) {
            when (ch) {
                '\\' -> b.append("\\\\")
                '"' -> b.append("\\\"")
                else -> b.append(ch)
            }
        }
        b.append('"')
        return b.toString()
    }

    private fun fieldString(text: String, key: String): String? {
        val token = "\"$key\""
        val i = text.indexOf(token)
        if (i < 0) return null
        val colon = text.indexOf(':', i + token.length)
        if (colon < 0) return null
        var p = colon + 1
        while (p < text.length && text[p].isWhitespace()) p++
        if (p >= text.length) return null
        if (text.startsWith("null", p)) return null
        if (text[p] != '"') return null
        val start = p + 1
        var q = start
        while (q < text.length && text[q] != '"') q++
        return text.substring(start, q)
    }

    private fun fieldInt(text: String, key: String): Int? {
        val token = "\"$key\""
        val i = text.indexOf(token)
        if (i < 0) return null
        val colon = text.indexOf(':', i + token.length)
        if (colon < 0) return null
        return text.substring(colon + 1).trimStart().takeWhile { it.isDigit() || it == '-' }.toIntOrNull()
    }

    private fun arrayField(text: String, key: String): DoubleArray? {
        val token = "\"$key\""
        val i = text.indexOf(token)
        if (i < 0) return null
        val lb = text.indexOf('[', i)
        val rb = if (lb < 0) -1 else text.indexOf(']', lb)
        if (lb < 0 || rb < 0) return null
        val parts = text.substring(lb + 1, rb).split(',').mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.isEmpty()) return null
        return parts.toDoubleArray()
    }

    private fun doubleField(line: String, key: String): Double? {
        val token = "\"$key\""
        val i = line.indexOf(token)
        if (i < 0) return null
        val colon = line.indexOf(':', i + token.length)
        if (colon < 0) return null
        val tail = line.substring(colon + 1).trimStart()
        val num = tail.takeWhile { it.isDigit() || it == '-' || it == '.' || it == 'e' || it == 'E' || it == '+' }
        return num.toDoubleOrNull()
    }

    private fun longField(line: String, key: String): Long? = doubleField(line, key)?.toLong()

    /** Test helper: a sparse file whose length() exceeds the raw cap. */
    fun sparse(file: File, bytes: Long) {
        file.parentFile?.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(bytes) }
    }
}
