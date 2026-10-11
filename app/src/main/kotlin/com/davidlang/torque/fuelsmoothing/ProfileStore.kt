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
        renameDefaultToSlug(root)
        val existing = profileDir(root, activeId(root))
        val profile = if (File(existing, "profile.json").isFile) existing else File(root, "profiles/$DEFAULT_ID")
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
                    setProfileField(root, DEFAULT_ID, "name", name)
                    Selection(renameDefaultToSlug(root) ?: DEFAULT_ID, false, "")
                }
                else -> Selection(create(root, name, hits.map { it.id }.toSet(), nowMs), true, "")
            }
        }
        val stored = storedVin(root, id.id)
        if (stored.isNullOrBlank() && !vin.isNullOrBlank()) setProfileField(root, id.id, "vin", vin)
        return id.copy(vinNote = vinNote(stored, vin))
    }

    fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifEmpty { "vehicle" }

    private const val RENAME_JOURNAL = "profiles/rename-journal.json"

    /**
     * Renames the legacy `default` profile folder to the slug of its Torque profile name (e.g. "Van" -> van),
     * then updates profile.json id/name and active-profile.json. Copies and deletes no data. Idempotent and
     * crash-safe: a journal is written first and replayed on the next call. Returns the new id or null.
     */
    fun renameDefaultToSlug(root: File): String? {
        val journal = File(root, RENAME_JOURNAL)
        var from = DEFAULT_ID
        var to: String? = null
        if (journal.isFile) {
            val t = journal.readText()
            from = fieldString(t, "from") ?: DEFAULT_ID
            to = fieldString(t, "to")
        }
        if (to == null) {
            val dir = File(root, "profiles/$DEFAULT_ID")
            val pj = File(dir, "profile.json")
            if (!pj.isFile) return null
            val name = fieldString(pj.readText(), "torqueProfileName")?.trim().orEmpty()
            if (name.isEmpty()) return null
            val want = slug(name)
            if (want == DEFAULT_ID || File(root, "profiles/$want").exists()) return null
            to = want
            writeAtomic(journal, "{\"from\":${jsonString(from)},\"to\":${jsonString(want)}}")
        }
        val src = File(root, "profiles/$from")
        val dest = File(root, "profiles/$to")
        if (src.isDirectory && !dest.exists() && !src.renameTo(dest)) {
            android.util.Log.w("FuelSmooth", "rename profile $from -> $to failed; will retry")
            return null
        }
        if (!dest.isDirectory) return null
        val pj = File(dest, "profile.json")
        if (pj.isFile) {
            var text = pj.readText()
            val name = fieldString(text, "torqueProfileName") ?: to
            text = Regex("\"id\":(\"[^\"]*\"|null)").replaceFirst(text, "\"id\":${jsonString(to)}".replace("$", "\\$"))
            text = Regex("\"name\":(\"[^\"]*\"|null)").replaceFirst(text, "\"name\":${jsonString(name)}".replace("$", "\\$"))
            writeAtomic(pj, text)
        }
        if (activeId(root) == from) writeAtomic(File(root, "active-profile.json"), "{\"profileId\":${jsonString(to)}}")
        journal.delete()
        runCatching { android.util.Log.i("FuelSmooth", "profile renamed $from -> $to") }
        return to
    }

    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) { f.writeText(text); tmp.delete() }
    }

    /** Per-profile tank-size guess (gal) and whether it was learned (true) or entered/seeded (false). */
    fun tankGuess(root: File, id: String): Pair<String?, Boolean> =
        profileField(root, id, "tankGal") to (profileField(root, id, "tankEstimated") == "true")

    /** Stores the profile's tank guess rounded to 0.1 gal; returns false when unchanged (< 0.1 gal, same flag). */
    fun setTankGuess(root: File, id: String, gal: Double, estimated: Boolean): Boolean {
        if (!gal.isFinite() || gal <= 0.0) return false
        if (!File(profileDir(root, id), "profile.json").isFile) return false
        val (oldRaw, oldEst) = tankGuess(root, id)
        val rounded = Math.round(gal * 10.0) / 10.0
        val old = oldRaw?.toDoubleOrNull()
        if (old != null && kotlin.math.abs(old - rounded) < 0.1 && oldEst == estimated) return false
        setProfileField(root, id, "tankGal", rounded.toString())
        setProfileField(root, id, "tankEstimated", estimated.toString())
        return true
    }

    /** Entered by the user: stored as typed (validated by the caller), never "estimated". Blank clears it. */
    fun setTankEntered(root: File, id: String, raw: String) {
        if (!File(profileDir(root, id), "profile.json").isFile) return
        val old = profileField(root, id, "tankGal")
        if (old == raw.ifBlank { null }) return
        setProfileField(root, id, "tankGal", raw.ifBlank { null })
        setProfileField(root, id, "tankEstimated", "false")
    }

    fun createProfile(root: File, name: String, nowMs: Long = System.currentTimeMillis()): String =
        create(root, name, profiles(root).map { it.id }.toSet(), nowMs)

    private fun create(root: File, name: String, taken: Set<String>, nowMs: Long): String {
        val base = slug(name)
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
        val hex = Fingerprint.payload09(parts, "02") ?: joined.uppercase().replace(Regex("[^0-9A-F]"), "")
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
    const val ROLLUP_SIG = "rollup.sig"

    /**
     * [exclude]: session ids still being recorded (never read mid-drive). Skipped entirely, outputs untouched,
     * when the included raw files and settings are unchanged since the last rollup (RollupCache.enabled).
     */
    fun rollup(dir: File, nameplate: Double?, exclude: Set<String> = emptySet()): String {
        val raw = File(dir, "raw")
        val files = if (raw.isDirectory) FuelV2.sessionFiles(raw, exclude) else emptyList()
        val sig = RollupCache.signature(files, "nameplate=$nameplate v=${ChunkFit.VERSION}/${BandFit.VERSION}/${Kalman.VERSION}")
        val sigFile = File(dir, ROLLUP_SIG)
        if (RollupCache.enabled && files.isNotEmpty() && sigFile.isFile && File(dir, "model.json").isFile && sigFile.readText() == sig) {
            runCatching { android.util.Log.i("FuelSmooth", "rollup skipped: no new or changed raw sessions (${files.size})") }
            return File(dir, "gap.txt").takeIf { it.isFile }?.readText() ?: ""
        }
        val r = rollupFull(dir, nameplate, exclude)
        if (files.isNotEmpty()) sigFile.writeText(sig)
        return r
    }

    private fun rollupFull(dir: File, nameplate: Double?, exclude: Set<String>): String {
        val raw = File(dir, "raw")
        val sessions = if (raw.isDirectory) FuelV2.loadSessions(raw, exclude) else emptyList()
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
        // The chunk fit (ChunkFit, all stored chunks) is the authority for model.json; the raw replay is
        // kept in model-raw.json for comparison. Falls back to the raw fit until a chunk group exists.
        val chunkFit = chunkUpdate(dir, sessions, nameplate, fitted, null)
        if (model != null) File(dir, "model-raw.json").writeText(model.json)
        if (chunkFit != null) {
            writeModelIfChanged(File(dir, "model.json"), chunkFit)
            BandFit.update(dir)
        } else if (model != null) {
            File(dir, "model.json").writeText(model.json)
        }
        Kalman.update(dir, sessions, nameplate)
        if (model != null) {
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

    /**
     * Appends this profile's new drive parts to segments.jsonl and refits from all stored chunks.
     * Returns model.json text, or null when no chunk group exists yet. [openName] is a session still being
     * recorded (fill detection): only its parts completed by a fill are stored.
     */
    fun chunkUpdate(dir: File, sessions: List<FuelSession>, nameplate: Double?, raw: FuelModel?, openName: String?): String? {
        return runCatching {
            dir.mkdirs()
            val added = ChunkFit.append(dir, sessions, openName)
            val (ps, ch) = ChunkFit.load(dir)
            val r = ChunkFit.fit(ps, ch, nameplate)
            val msg = "fit chunk v95=${"%.2f".format(r.v95)} cap=${"%.2f".format(r.capToZero)} chunks=${r.chunks} groups=${r.groups} " +
                "newParts=$added active=${r.active}/${r.cons.size} | raw v95=${raw?.let { "%.2f".format(it.v95()) }} " +
                "cap=${raw?.let { "%.2f".format(it.capToZero) }} authority=${if (r.groups > 0) "chunk" else "raw"}"
            runCatching { android.util.Log.i("FuelSmooth", msg) }
            if (r.groups > 0) ChunkFit.modelJson(r, raw, System.currentTimeMillis()) else null
        }.onFailure { e -> runCatching { android.util.Log.w("FuelSmooth", "chunk fit failed; keeping model.json: ${e.message}") } }
            .getOrNull()
    }

    /** At fill detection: store the drive parts completed by the fill and refit. Raw replay untouched. */
    fun fillUpdate(dir: File, nameplate: Double?, openName: String?): Boolean {
        val raw = File(dir, "raw")
        val sessions = if (raw.isDirectory) FuelV2.loadSessions(raw) else return false
        val json = chunkUpdate(dir, sessions, nameplate, null, openName) ?: return false
        writeModelIfChanged(File(dir, "model.json"), json)
        BandFit.update(dir)
        Kalman.update(dir, sessions, nameplate, openName)
        return true
    }

    /** Keeps model.json (and its timestamp) unchanged when the refit gives the same result. */
    private fun writeModelIfChanged(f: File, json: String) {
        val strip = { x: String -> x.replace(Regex("\"timestamp\":[0-9]+"), "") }
        if (f.isFile && strip(f.readText()) == strip(json)) return
        AtomicFile.write(f, json)
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
