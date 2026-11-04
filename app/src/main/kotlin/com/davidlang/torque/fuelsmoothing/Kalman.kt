package com.davidlang.torque.fuelsmoothing

import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Level-from-fuel map (lvfit6). 100 bins of 1 gauge point; G(L) is cumulative gallons from 0%.
 * Bins 0–10% continue the straight line through the free bins 10–25%. No c0 prior. Capacity is G(98).
 *
 * Fuel below 0% is a separate buffer, not capacity, and is not used to update the gauge.
 * A 0% alert plus a countdown learned from below-0% burns is a future feature: no flag and no stub.
 * Raw L <= 0 maps to 0% (G(0) = 0) and never enters the map.
 */
internal object Kalman {
    const val VERSION = 5
    const val STATE = "kalman-state.txt"
    const val MODEL = "model-kalman.json"
    const val PUBLISH_SD = 1.5

    /** Bin width in gauge points. */
    private const val BW = 1.0
    private const val NB = 100
    /** Extrapolated low bins (0–10%) and the free bins their line is fitted through (10–25%). */
    private const val NX = 10
    private const val NT = 15
    private const val NF = NB - NX
    private const val S_TH = 3.0
    private const val S_D2 = 0.05
    private const val N0 = 40.0
    private const val HI = 99.85
    private const val ALLCLIP = -5.0
    private const val TAIL_FLOOR = 0.02
    private const val H_MU = 0.05
    private const val H_SP = 0.2
    private const val H_SN = 0.1
    private const val H_NU = 1.0
    private const val S_O = 1.5
    private const val LO_C = 0.0
    private const val HI_C = 99.9
    private const val C_EPS = 0.05
    private const val MINMOVE = 45.0
    private const val MINSTOP = 20
    private const val WIN = 0.25
    private const val K_OFF = 3.0
    private const val MINOFF = 0.5
    private const val K2 = 2.5
    private const val HOLD = 60.0
    private const val INFO_N = NF * (NF + 1) / 2
    private val BAND_EDGES = doubleArrayOf(0.0, 5.0, 10.0, 20.0, 40.0, 60.0, 80.0, 90.0, 95.0, 101.0)
    private val BAND_SIG = doubleArrayOf(2.21, 2.17, 1.75, 1.89, 1.85, 2.00, 2.17, 2.19, 0.75)
    private const val SIG_MID = 1.85
    /** Previous fit's gallons per point, loaded at the start of update. Null uses one flat ratio. */
    private var slopeG: DoubleArray? = null
    private var slopeFlat: Double = Double.NaN

    /** Free θ, per-segment hole/offset, and the version-5 posterior (information, anchor, folded keys). */
    class State(
        val q: DoubleArray,
        val segs: List<SegPar>,
        val info: DoubleArray,
        val anchorK: Double,
        val anchorC0: Double,
        val anchorC: Double,
        val anchorEp: Int,
        val tailMed: Double,
        val got: List<String>,
    )

    class SegPar(val key: String, val first: Boolean, val z: Double, val o: Double)

    class Model(val g: DoubleArray, val g98: Double, val sd98: Double, val complete: Boolean) {
        /** Cumulative gallons on NB bins of BW points. L = 100 uses every bin: BW·Σ g. */
        fun v(level: Double): Double = gallons(g, level)
    }

    /** Publish bar for the profile tank guess and the settings line. Gauge and TankKal do not use it. */
    fun publishable(m: Model?): Boolean =
        m != null && m.complete && m.sd98 <= PUBLISH_SD && m.g98.isFinite() && m.g98 > 0.0

    fun gallons(g: DoubleArray, level: Double): Double {
        val L = level.coerceIn(0.0, 100.0)
        val k = floor(L / BW).toInt().coerceIn(0, NB - 1)
        var s = 0.0
        for (i in 0 until k) s += BW * g[i]
        s += (L - BW * k) * g[k]
        return s
    }

    @Volatile var lastLoadError: String? = null
    private var fitName = ""
    private var nativeLoaded = false

    private fun warn(msg: String) { runCatching { FsLog.w("FuelSmooth", msg) } }

    private fun ds(x: Double) = x.toString()

    private fun loadNative() {
        if (nativeLoaded) return
        try {
            System.loadLibrary("kalmanfit")
        } catch (e: UnsatisfiedLinkError) {
            throw IllegalStateException("kalmanfit load failed: ${e.message}")
        }
        nativeLoaded = true
    }

    /**
     * Refit from stored captures. [jumpPct] is the fill-break threshold (10..100); a value outside that throws
     * and the previous state and model stay. An empty session list does not fit.
     */
    fun update(dir: File, sessions: List<FuelSession>, jumpPct: Double, openName: String? = null): State? {
        return try {
            val prevModel = loadModel(dir)
            slopeG = prevModel?.g
            slopeFlat = if (slopeG == null) flatGpp(sessions) else Double.NaN
            quarantineState(dir)
            if (sessions.isEmpty()) return load(dir)
            if (!jumpPct.isFinite() || jumpPct < 10.0 || jumpPct > 100.0) {
                throw IllegalArgumentException("jumpPct $jumpPct outside 10..100")
            }
            fitName = sessions.last().name
            loadNative()
            val saved = load(dir)
            val (parts, _) = Pieces.cut(sessions, openName)
            if (parts.isEmpty()) return saved
            var full = !incReady(saved)
            if (!full) {
                var seenNew = false
                val got = saved!!.got.toHashSet()
                for (p in parts) {
                    if (p.key !in got) seenNew = true
                    else if (seenNew) {
                        full = true
                        break
                    }
                }
                if (!seenNew) return saved
            }
            val newKeys = if (full) parts.map { it.key } else parts.filter { it.key !in saved!!.got }.map { it.key }
            // Block rows are only the parts not yet folded in. cut still names every part so a hole forces a full rebuild.
            var assembled = assemble(parts, jumpPct, if (full) null else newKeys.toHashSet(), if (full) null else seedOf(saved!!))
            var mode = if (full) 0 else 1
            if (assembled.skippedAll) {
                if (!full && saved != null) {
                    save(dir, copyGot(saved, mergeGot(saved.got, newKeys)))
                }
                return load(dir)
            }
            var built = assembled.built
            if (built.segs.isEmpty()) throw IllegalStateException("no fit segments on $fitName")
            var out = nativeSolve(mode, built, saved)
            if (out.status == 1 && mode == 1) {
                full = true
                mode = 0
                assembled = assemble(parts, jumpPct, null, null)
                if (assembled.skippedAll) return load(dir)
                built = assembled.built
                if (built.segs.isEmpty()) throw IllegalStateException("no fit segments on $fitName")
                out = nativeSolve(0, built, saved)
            }
            if (out.status == 1) throw IllegalStateException("singular covariance on $fitName")
            if (out.status != 0) throw IllegalStateException("non-finite residual on $fitName")
            val got = if (full) mergeGot(emptyList(), parts.map { it.key }) else mergeGot(saved!!.got, newKeys)
            val state = State(
                out.q,
                List(built.segs.size) { j -> SegPar(built.segs[j].key, built.first[j], out.z[j], out.o[j]) },
                out.info,
                out.kTail,
                built.segs.last().C0,
                built.cTail,
                built.epTail,
                assembled.tailMed,
                got,
            )
            save(dir, state)
            val complete = if (full) built.breaks >= 2 else prevModel?.complete == true || built.breaks >= 2
            val modelFile = File(dir, MODEL)
            if (!modelFile.isFile) {
                val gal = out.g98.takeIf { it.isFinite() }?.let { java.lang.String.format(java.util.Locale.US, "%.1f", it) } ?: "none"
                FsLog.i("FuelSmooth", "fit_own_size fit=kalman tank=${ProfileStore.tankNumber(dir)} gal=$gal")
            }
            AtomicFile.write(modelFile, modelJson(out.g, out.g98, out.sd98, complete, System.currentTimeMillis()))
            runCatching {
                FsLog.i(
                    "FuelSmooth",
                    "fit kalman g98=${"%.2f".format(out.g98)} sd98=${"%.2f".format(out.sd98)} complete=$complete " +
                        "breaks=${built.breaks} iter=${out.iters} ms=${out.ms} stop=${out.stop}",
                )
            }
            state
        } catch (e: Exception) {
            warn("kalman update failed; keeping $STATE: ${e.message}")
            load(dir)
        }
    }

    private fun incReady(st: State?): Boolean {
        if (st == null || st.info.size != INFO_N || st.got.isEmpty()) return false
        if (!st.anchorK.isFinite() || !st.anchorC0.isFinite() || !st.anchorC.isFinite() || !st.tailMed.isFinite()) return false
        return KalmanNative.infoPd(st.info)
    }

    // anchorEp >= 0 continues that epoch. Negative is -(running+1): a dropped fill closed it.
    private fun seedOf(st: State): Seed {
        val raw = st.anchorEp
        val open = raw >= 0
        val ep = if (open) raw else -raw - 1
        return Seed(st.anchorC, ep, st.tailMed, open)
    }

    private fun mergeGot(old: List<String>, extra: List<String>): List<String> {
        val out = ArrayList<String>(old.size + extra.size)
        val seen = HashSet<String>()
        for (k in old) if (seen.add(k)) out.add(k)
        for (k in extra) if (seen.add(k)) out.add(k)
        return out
    }

    private fun copyGot(st: State, got: List<String>) =
        State(st.q, st.segs, st.info, st.anchorK, st.anchorC0, st.anchorC, st.anchorEp, st.tailMed, got)

    /**
     * Rename a state file that [load] cannot read. Version ≠ [VERSION] → `.v2`; any other failure → `.corrupt`.
     * Returns true when the file was present and rejected. A file [load] accepts is not stale.
     */
    fun quarantineState(dir: File): Boolean {
        val f = File(dir, STATE)
        if (!f.isFile) return false
        if (load(dir) != null) return false
        val found = peekVersion(f)
        if (found != VERSION) {
            val dst = moveState(dir, ".v2")
            warn("kalman state version change; kept as ${dst?.name} ($found vs $VERSION)")
        } else {
            val dst = moveState(dir, ".corrupt")
            warn("kalman state kept as ${dst?.name} (${lastLoadError})")
        }
        return true
    }

    private fun peekVersion(f: File): Int? {
        val line = f.bufferedReader().use { it.readLine() } ?: return null
        if (!line.startsWith("version ")) return null
        return line.removePrefix("version ").trim().toIntOrNull()
    }

    private fun moveState(dir: File, suffix: String): File? {
        val f = File(dir, STATE)
        if (!f.isFile) return null
        var dst = File(dir, "$STATE$suffix")
        var k = 1
        while (dst.exists()) dst = File(dir, "$STATE$suffix.${k++}")
        if (!f.renameTo(dst)) {
            f.copyTo(dst, overwrite = true)
            f.delete()
        }
        return dst
    }

    /** Same move as [moveState], for [MODEL]. `.v2` / `.corrupt`, then `.1`, `.2` when that name is taken. */
    fun moveModel(dir: File, suffix: String): File? {
        val f = File(dir, MODEL)
        if (!f.isFile) return null
        var dst = File(dir, "$MODEL$suffix")
        var k = 1
        while (dst.exists()) dst = File(dir, "$MODEL$suffix.${k++}")
        if (!f.renameTo(dst)) {
            f.copyTo(dst, overwrite = true)
            f.delete()
        }
        return dst
    }

    fun load(dir: File): State? {
        lastLoadError = null
        val f = File(dir, STATE)
        if (!f.isFile) return null
        return try {
            parse(f)
        } catch (e: Exception) {
            lastLoadError = "${e.javaClass.simpleName}: ${e.message}"
            warn("kalman state unreadable (${f.name}, ${f.length()} bytes): $lastLoadError")
            null
        }
    }

    private fun wordDoubles(body: String): DoubleArray {
        val t = body.trim()
        if (t.isEmpty()) return DoubleArray(0)
        return t.split(Regex("\\s+")).map { it.toDouble() }.toDoubleArray()
    }

    private fun parse(f: File): State {
        val lines = f.readLines().filter { it.isNotBlank() }
        check(lines.isNotEmpty()) { "empty state" }
        val ver = lines[0].removePrefix("version ").trim().toIntOrNull()
        check(lines[0].startsWith("version ") && ver == VERSION) { "state version $ver, expected $VERSION" }
        val qLine = lines.firstOrNull { it.startsWith("q ") } ?: throw IllegalStateException("missing q")
        val q = wordDoubles(qLine.substring(2))
        check(q.size == NF) { "q has ${q.size}" }
        check(q.all { it.isFinite() }) { "non-finite q" }
        val infoLine = lines.firstOrNull { it.startsWith("info ") }
        val info = if (infoLine == null) DoubleArray(0) else wordDoubles(infoLine.substring(5))
        if (info.isNotEmpty()) {
            check(info.size == INFO_N) { "info has ${info.size}" }
            check(info.all { it.isFinite() }) { "non-finite info" }
        }
        var anchorK = Double.NaN
        var anchorC0 = Double.NaN
        var anchorC = Double.NaN
        var anchorEp = 0
        var tailMed = Double.NaN
        val anchorLine = lines.firstOrNull { it.startsWith("anchor ") }
        if (anchorLine != null) {
            val p = anchorLine.removePrefix("anchor ").trim().split(Regex("\\s+"))
            check(p.size == 5) { "bad anchor line" }
            anchorK = p[0].toDouble()
            anchorC0 = p[1].toDouble()
            anchorC = p[2].toDouble()
            anchorEp = p[3].toInt()
            tailMed = p[4].toDouble()
        }
        val got = ArrayList<String>()
        val segs = ArrayList<SegPar>()
        for (l in lines) {
            if (l.startsWith("got ")) {
                val key = l.removePrefix("got ").trim()
                check(key.isNotEmpty()) { "empty got" }
                got.add(key)
                continue
            }
            if (!l.startsWith("seg ")) continue
            val p = l.trim().split(Regex("\\s+"))
            check(p.size == 5) { "bad seg line" }
            val z = p[3].toDouble()
            val o = p[4].toDouble()
            check(z.isFinite() && o.isFinite()) { "non-finite seg" }
            segs.add(SegPar(p[1], p[2] == "1", z, o))
        }
        return State(q, segs, info, anchorK, anchorC0, anchorC, anchorEp, tailMed, got)
    }

    private fun save(dir: File, s: State) {
        val sb = StringBuilder()
        sb.append("version $VERSION\n")
        sb.append("q ").append(s.q.joinToString(" ") { ds(it) }).append('\n')
        sb.append("info ").append(s.info.joinToString(" ") { ds(it) }).append('\n')
        sb.append("anchor ${ds(s.anchorK)} ${ds(s.anchorC0)} ${ds(s.anchorC)} ${s.anchorEp} ${ds(s.tailMed)}\n")
        for (key in s.got) sb.append("got ").append(key).append('\n')
        for (seg in s.segs) sb.append("seg ${seg.key} ${if (seg.first) 1 else 0} ${ds(seg.z)} ${ds(seg.o)}\n")
        val tmp = File(dir, "$STATE.tmp")
        tmp.writeText(sb.toString())
        if (!tmp.renameTo(File(dir, STATE))) {
            File(dir, STATE).writeText(sb.toString())
            tmp.delete()
        }
    }

    fun modelJson(g: DoubleArray, g98: Double, sd98: Double, complete: Boolean, nowMs: Long): String =
        "{\"version\":$VERSION,\"source\":\"kalman\",\"timestamp\":$nowMs,\"g\":${g.joinToString(",", "[", "]") { ds(it) }}," +
            "\"g98\":${ds(g98)},\"sd98\":${ds(sd98)},\"complete\":$complete}"

    fun loadModel(dir: File): Model? {
        val f = File(dir, MODEL)
        if (!f.isFile) return null
        return try {
            parseModel(f.readText())
        } catch (e: Exception) {
            warn("kalman model unreadable (${f.name}): ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** Why [MODEL] cannot be read, or null when the file is missing or this version can read it. Does not rename. */
    class StaleModel(val suffix: String, val detail: String)

    fun staleModel(dir: File): StaleModel? {
        val f = File(dir, MODEL)
        if (!f.isFile) return null
        val t = try {
            f.readText()
        } catch (e: Exception) {
            return StaleModel(".corrupt", "${e.javaClass.simpleName}: ${e.message}")
        }
        val ver = peekModelVersion(t)
        if (ver.present && ver.number != null && ver.number != VERSION) {
            return StaleModel(".v2", "version ${ver.number} vs $VERSION")
        }
        if (!ver.present) return StaleModel(".corrupt", "missing version")
        if (ver.number == null) return StaleModel(".corrupt", "version not an integer")
        return try {
            modelBody(t)
            null
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            val g = Regex("g has (\\d+)").find(msg)
            val detail = if (g != null) "g ${g.groupValues[1]} vs $NB" else msg
            StaleModel(".corrupt", detail)
        }
    }

    private class ModelVersion(val present: Boolean, val number: Int?)

    /** `"version"` as a JSON integer, before any read of `g`. A missing key is not present. */
    private fun peekModelVersion(t: String): ModelVersion {
        val raw = Regex("\"version\"\\s*:\\s*([^,}\\s]+)").find(t)?.groupValues?.get(1)
            ?: return ModelVersion(false, null)
        if (!Regex("-?[0-9]+").matches(raw)) return ModelVersion(true, null)
        val n = raw.toIntOrNull() ?: return ModelVersion(true, null)
        return ModelVersion(true, n)
    }

    private fun parseModel(t: String): Model {
        val ver = peekModelVersion(t)
        if (!ver.present) throw IllegalStateException("missing version")
        if (ver.number == null) throw IllegalStateException("version not an integer")
        if (ver.number != VERSION) throw IllegalStateException("version ${ver.number}, expected $VERSION")
        return try {
            modelBody(t)
        } catch (e: IllegalStateException) {
            val n = Regex("^g has (\\d+)$").find(e.message ?: "")
            if (n != null) warn("kalman model g has ${n.groupValues[1]} values, expected $NB")
            throw e
        }
    }

    private fun modelBody(t: String): Model {
        fun num(k: String): Double {
            val m = Regex("\"$k\":(-?[0-9.eE+-]+)").find(t) ?: throw IllegalStateException("missing $k")
            return m.groupValues[1].toDouble()
        }
        val body = Regex("\"g\":\\[([^\\]]*)]").find(t)?.groupValues?.get(1) ?: throw IllegalStateException("missing g")
        val g = if (body.isBlank()) DoubleArray(0) else body.split(',').map { it.trim().toDouble() }.toDoubleArray()
        if (g.size != NB) throw IllegalStateException("g has ${g.size}")
        return Model(g, num("g98"), num("sd98"), t.contains("\"complete\":true"))
    }

    // ---------------- drives, blocks, breaks, segments ----------------

    private class Row(val t: Double, val f: Double, val l: Double, val v: Double, val fill: Boolean)
    private class Blk(val l: Double, val f: Double, val se: Double)
    private class Prt(
        val key: String, val session: String, val t0: Double, val t1: Double, val fStart: Double,
        val fuel: Double, var fill: Boolean, val blocks: MutableList<Blk>,
    )
    private class Seg(val key: String, val L: DoubleArray, val F: DoubleArray, val se: DoubleArray, val C0: Double, val ep: Int)
    private class Built(val segs: List<Seg>, val first: BooleanArray, val breaks: Int, val cTail: Double, val epTail: Int)
    private class Assembled(val built: Built, val skippedAll: Boolean, val tailMed: Double)
    private class Seed(val cTail: Double, val epTail: Int, val tailMed: Double, val open: Boolean)

    private fun assemble(parts: List<Pieces.Part>, jumpPct: Double, only: Set<String>?, seed: Seed?): Assembled {
        val byDrive = ArrayList<ArrayList<Pieces.Part>>()
        for (p in parts) {
            if (only != null && p.key !in only) continue
            val name = p.session?.name ?: p.key.substringBefore('#')
            val prev = byDrive.lastOrNull()?.firstOrNull()?.let { it.session?.name ?: it.key.substringBefore('#') }
            if (prev != name) byDrive.add(ArrayList())
            byDrive.last().add(p)
        }
        val drives = ArrayList<ArrayList<Prt>>()
        var prevRise: DoubleArray? = null
        var skipped = 0
        var firstDrive = true
        var lastLevels: DoubleArray? = null
        for (group in byDrive) {
            val s = group.first().session ?: throw IllegalStateException("raw file missing for drive ${group.first().key.substringBefore('#')}")
            val name = s.name
            if (group.any { it.fuel < 0.0 }) {
                warn("kalman skip $name: negative counter fuel on $name")
                skipped++
                continue
            }
            val rows = sessionRows(s)
            try {
                checkFuel(rows, name)
            } catch (e: IllegalStateException) {
                warn("kalman skip $name: ${e.message}")
                skipped++
                continue
            }
            fitName = name
            val tt = ArrayList<Double>(); val ff = ArrayList<Double>(); val ll = ArrayList<Double>(); val vv = ArrayList<Double>()
            val rL = ArrayList<Double>(); val rV = ArrayList<Double>(); val rT = ArrayList<Double>()
            for (r in rows) {
                if (r.l.isFinite()) { rL.add(r.l); rV.add(r.v); rT.add(r.t) }
                if (r.l.isFinite() && r.l >= 0.0 && r.l <= 100.0 && r.f.isFinite() && r.v.isFinite()) {
                    tt.add(r.t); ff.add(r.f); ll.add(r.l); vv.add(r.v)
                }
            }
            val t = tt.toDoubleArray(); val f = ff.toDoubleArray(); val l = ll.toDoubleArray(); val v = vv.toDoubleArray()
            val flags = if (t.size >= 20) detectFlags(t, f, l, v) else BooleanArray(t.size)
            val prts = ArrayList<Prt>()
            for (part in group) prts.add(Prt(part.key, name, part.t0, part.t1, part.fStart, part.fuel, false, blocksOf(part, t, f, l, v, flags)))
            val prevMed = if (firstDrive) seed?.tailMed else null
            val levels = rL.toDoubleArray()
            placeBreaks(prts, rows, levels, rV.toDoubleArray(), rT.toDoubleArray(), if (prevMed != null) null else prevRise, jumpPct, prevMed)
            if (rL.isNotEmpty()) {
                prevRise = levels
                lastLevels = levels
            }
            firstDrive = false
            drives.add(prts)
        }
        val flat = ArrayList<Prt>()
        for (d in drives) flat.addAll(d)
        refill(flat, jumpPct)
        collapse(flat)
        val skippedAll = byDrive.isNotEmpty() && skipped == byDrive.size
        return Assembled(segments(drives, seed), skippedAll, tailMedian(lastLevels))
    }

    private fun tailMedian(levels: DoubleArray?): Double {
        if (levels == null || levels.isEmpty()) return Double.NaN
        val n = min(60, levels.size)
        return medOf(levels.copyOfRange(levels.size - n, levels.size))
    }

    /** Rows already on the session. [FuelSession.t] is seconds; the raw file is not opened. */
    private fun sessionRows(s: FuelSession): List<Row> {
        val out = ArrayList<Row>(s.t.size)
        for (i in s.t.indices) out.add(Row(s.t[i], s.used[i], s.raw[i], s.spd[i], s.fill[i]))
        return out
    }

    private fun checkFuel(rows: List<Row>, name: String) {
        var prev = Double.NaN
        for (r in rows) {
            if (!r.f.isFinite()) continue
            if (prev.isFinite() && r.f < prev - 1e-9) throw IllegalStateException("negative counter fuel on $name")
            prev = r.f
        }
    }

    private fun blocksOf(
        part: Pieces.Part, t: DoubleArray, f: DoubleArray, l: DoubleArray, v: DoubleArray, flags: BooleanArray,
    ): MutableList<Blk> {
        val med = Pieces.blockMedians(part, part.nb)
        if (med.isEmpty()) return ArrayList()
        val idx = ArrayList<Int>()
        for (i in t.indices) if (t[i] >= part.t0 - 1e-6 && t[i] <= part.t1 + 1e-6) idx.add(i)
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (i in idx) {
            if (v[i] < Pieces.V_MIN_KMH) continue
            if (l[i] > 2.0 && l[i] < 97.0) { xs.add(f[i] - part.fStart); ys.add(l[i]) }
        }
        var slope = 0.0
        if (xs.size > 20) {
            val xa = xs.toDoubleArray()
            if (xa.max() - xa.min() > 0.2) slope = slopeLS(xa, ys.toDoubleArray())
        }
        val out = ArrayList<Blk>()
        for (b in med) {
            val bl = b[1]; val bf = b[2]; val bs = b[3]
            val x = ArrayList<Double>()
            for (i in idx) {
                if (v[i] < Pieces.V_MIN_KMH) continue
                if (abs((f[i] - part.fStart) - bf) > 0.1) continue
                var d = l[i] - slope * ((f[i] - part.fStart) - bf)
                if (d < 0.0) d = 0.0
                if (d > HI_C) d = HI_C
                x.add(d)
            }
            if (x.size < 5) {
                warn("kalman skip ${part.key}: block without raw samples")
                continue
            }
            var nW = 0; var nF = 0
            for (i in idx) {
                if (abs((f[i] - part.fStart) - bf) > 0.05) continue
                nW++; if (flags[i]) nF++
            }
            if (nW > 0 && nF.toDouble() / nW > 0.5) continue
            val sg = bandSig(bl)
            out.add(Blk(cmean(x.toDoubleArray(), sg), bf, bs * sg / SIG_MID))
        }
        return out
    }

    private fun placeBreaks(
        parts: List<Prt>, rows: List<Row>, l: DoubleArray, v: DoubleArray, t: DoubleArray, prev: DoubleArray?, jump: Double,
        prevMed: Double? = null,
    ) {
        for (r in rows) if (r.fill) place(parts, r.t)
        val n = l.size
        if (n >= 120) {
            for (k in 60 until n - 60) {
                var fast = false
                for (i in k - 60 until k + 60) if (!v[i].isFinite() || v[i] >= 1.0) { fast = true; break }
                if (fast) continue
                val rise = medOf(l.copyOfRange(k, k + 60)) - medOf(l.copyOfRange(k - 60, k))
                if (rise >= jump) place(parts, t[k])
            }
        }
        if (prev != null && prev.isNotEmpty() && n > 0) {
            val a = min(60, n)
            val b = min(60, prev.size)
            val btw = medOf(l.copyOfRange(0, a)) - medOf(prev.copyOfRange(prev.size - b, prev.size))
            if (btw >= jump && parts.isNotEmpty()) parts[0].fill = true
        } else if (prevMed != null && prevMed.isFinite() && n > 0 && parts.isNotEmpty()) {
            val a = min(60, n)
            val btw = medOf(l.copyOfRange(0, a)) - prevMed
            if (btw >= jump) parts[0].fill = true
        }
    }

    /** The part that contains the sample. A shared edge belongs to the part that opens there. */
    private fun place(parts: List<Prt>, time: Double) {
        parts.lastOrNull { it.t0 <= time + 1e-9 && time <= it.t1 + 1e-9 }?.fill = true
    }

    /** At-speed rise across a stop. OR'd onto breaks already placed; does not clear them. */
    private fun refill(parts: List<Prt>, jump: Double) {
        var lastL: Double? = null
        val since = ArrayList<Prt>()
        val extra = HashSet<String>()
        for (p in parts) {
            since.add(p)
            if (p.blocks.isNotEmpty()) {
                val firstL = p.blocks.first().l
                if (lastL != null && firstL - lastL >= jump) extra.add((since.firstOrNull { it.fill } ?: since[0]).key)
                lastL = p.blocks.last().l
                since.clear()
            }
        }
        for (p in parts) if (p.key in extra) p.fill = true
    }

    private fun collapse(parts: List<Prt>) {
        var prev = false
        for (p in parts) {
            if (p.fill && prev) p.fill = false else if (p.fill) prev = true
            if (p.blocks.isNotEmpty()) prev = false
        }
    }

    private fun segments(drives: List<List<Prt>>, seed: Seed?): Built {
        val all = ArrayList<Seg>()
        var breaks = 0
        var c = seed?.cTail ?: 0.0
        var ep = seed?.epTail ?: -1
        var started = seed != null && seed.open
        for (d in drives) {
            var curL = ArrayList<Double>()
            var curF = ArrayList<Double>()
            var curS = ArrayList<Double>()
            var curKey = ""
            var curEp = 0
            var open = false
            var off = 0.0
            val c0 = c
            fun close() {
                if (!open) return
                all.add(Seg(curKey, curL.toDoubleArray(), curF.toDoubleArray(), curS.toDoubleArray(), c0, curEp))
                open = false
            }
            for (p in d) {
                if (p.fill) breaks++
                if (p.fill || !started) { ep++; started = true }
                if (!open || p.fill) {
                    close()
                    curL = ArrayList(); curF = ArrayList(); curS = ArrayList()
                    curKey = p.key; curEp = ep; open = true
                }
                for (b in p.blocks) { curL.add(b.l); curF.add(off + b.f); curS.add(b.se) }
                off += p.fuel
            }
            close()
            c += off
        }
        val kept = all.filter { it.L.size >= 2 }
        val seedOpen = seed?.open ?: false
        val seedEp = seed?.epTail
        val first = BooleanArray(kept.size) { i ->
            if (i == 0) seed == null || !seedOpen || kept[0].ep != seedEp else kept[i].ep != kept[i - 1].ep
        }
        // A trailing fill with fewer than two blocks is not kept, but it still advances ep.
        // Saving that running epoch as -(ep+1) makes the next incremental walk start a new chain.
        val epTail = if (kept.isEmpty() || kept.last().ep == ep) ep else -(ep + 1)
        return Built(kept, first, breaks, c, epTail)
    }

    private fun bandSig(level: Double): Double {
        var i = 0
        while (i < BAND_EDGES.size && BAND_EDGES[i] <= level) i++
        return BAND_SIG[(i - 1).coerceIn(0, BAND_SIG.size - 1)]
    }

    // ---------------- tilt detector (stopdet.detect) ----------------

    private class Side(val slope: Double, val intercept: Double, val s: Double, val n: Int)

    private fun detectFlags(t: DoubleArray, f: DoubleArray, l: DoubleArray, v: DoubleArray): BooleanArray {
        val n = t.size
        val flag = BooleanArray(n)
        val mv = IntArray(n) { i -> if (v[i] >= Pieces.V_MIN_KMH) 2 else if (v[i] <= 0.0) 0 else 1 }
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && mv[j + 1] == mv[i]) j++
            runs.add(intArrayOf(i, j, mv[i]))
            i = j + 1
        }
        for (r in runs) if (r[2] == 2 && t[r[1]] - t[r[0]] < MINMOVE) r[2] = 1
        val sp = ArrayList<IntArray>()
        for (r in runs) {
            if (sp.isNotEmpty() && sp.last()[2] == r[2]) sp.last()[1] = r[1] else sp.add(r.copyOf())
        }
        for (si in sp.indices) {
            val a = sp[si][0]; val b = sp[si][1]
            if (sp[si][2] != 0 || b - a + 1 < MINSTOP) continue
            val fs = medOf(f.copyOfRange(a, b + 1))
            val preds = ArrayList<Double>(); val vars = ArrayList<Double>()
            for (prevSide in intArrayOf(1, 0)) {
                var nb: IntArray? = null
                if (prevSide == 1) { for (q in si - 1 downTo 0) if (sp[q][2] == 2) { nb = sp[q]; break } }
                else { for (q in si + 1 until sp.size) if (sp[q][2] == 2) { nb = sp[q]; break } }
                if (nb == null) continue
                val anchor = if (prevSide == 1) f[nb[1]] else f[nb[0]]
                val idx = ArrayList<Int>()
                for (k in nb[0]..nb[1]) if (abs(f[k] - anchor) <= WIN) idx.add(k)
                val use = if (prevSide == 0 && idx.size >= 30) ArrayList(idx.subList(idx.size / 2, idx.size)) else idx
                val sf = sideFit(f, l, use) ?: continue
                preds.add(sf.slope * fs + sf.intercept)
                vars.add(sf.s * sf.s / (sf.n / 3.0))
            }
            if (preds.isEmpty()) continue
            val ls = l.copyOfRange(a, b + 1)
            val off = medOf(ls) - preds.average()
            val sd = sqrt(mad(ls).let { it * it } / ((b - a + 1) / 3.0) + vars.average() + 0.09)
            if (!(abs(off) > K_OFF * sd && abs(off) > MINOFF)) continue
            var nxt = -1
            for (q in si + 1 until sp.size) if (sp[q][2] == 2) { nxt = q; break }
            if (nxt < 0) continue
            val na = sp[nxt][0]; val nb1 = sp[nxt][1]
            val idx = (na..nb1).toList()
            val late = idx.subList(idx.size / 2, idx.size)
            val sf = if (late.size >= 15) sideFit(f, l, late) else null
            if (sf == null) {
                for (k in na..nb1) flag[k] = true
            } else {
                val res = DoubleArray(idx.size) { l[idx[it]] - (sf.slope * f[idx[it]] + sf.intercept) }
                val rm = DoubleArray(idx.size) { k -> medOf(res.copyOfRange(max(0, k - 15), min(idx.size, k + 15))) }
                val thresh = K2 * sf.s / sqrt(10.0)
                val ok = BooleanArray(idx.size) { abs(rm[it]) <= thresh }
                var settled: Int? = null
                var k = 0
                while (k < idx.size) {
                    if (ok[k]) {
                        var m = k
                        while (m + 1 < idx.size && ok[m + 1]) m++
                        if (t[idx[m]] - t[idx[k]] >= HOLD || m == idx.size - 1) { settled = k; break }
                        k = m + 1
                    } else k++
                }
                val upto = settled ?: idx.size
                for (k2 in a until na) flag[k2] = true
                for (k2 in 0 until upto) flag[idx[k2]] = true
            }
        }
        for (k in 0 until n) if (v[k] < Pieces.V_MIN_KMH) flag[k] = false
        return flag
    }

    private fun sideFit(f: DoubleArray, l: DoubleArray, idx: List<Int>): Side? {
        if (idx.size < 15) return null
        val ls = DoubleArray(idx.size) { l[idx[it]] }
        val fs = DoubleArray(idx.size) { f[idx[it]] }
        val gp = gpp(medOf(ls))
        if (!gp.isFinite() || gp <= 0.0) return null
        val sl = -1.0 / gp
        val f0 = medOf(fs)
        val adj = DoubleArray(idx.size) { ls[it] - sl * (fs[it] - f0) }
        return Side(sl, medOf(adj) - sl * f0, mad(adj), idx.size)
    }

    /** Gallons per point on the BW-point bins. Previous model, else one flat ratio for every level. */
    private fun gpp(level: Double): Double {
        val g = slopeG
        if (g != null && g.isNotEmpty()) return g[floor(level / BW).toInt().coerceIn(0, g.lastIndex)]
        return slopeFlat
    }

    /** One gallons-per-point from the fuel and level rows in this fit. */
    private fun flatGpp(sessions: List<FuelSession>): Double {
        var fuel = 0.0
        var level = 0.0
        for (s in sessions) {
            for (i in 1 until s.t.size) {
                val df = s.used[i] - s.used[i - 1]
                val dl = s.raw[i] - s.raw[i - 1]
                if (!df.isFinite() || !dl.isFinite() || df <= 0.0 || dl == 0.0) continue
                fuel += df
                level += abs(dl)
            }
        }
        return if (level > 0.0) fuel / level else Double.NaN
    }

    private fun mad(x: DoubleArray): Double {
        if (x.isEmpty()) return Double.NaN
        val m = medOf(x)
        return 1.4826 * medOf(DoubleArray(x.size) { abs(x[it] - m) })
    }

    private fun medOf(x: DoubleArray) = Stats.med(x)

    // ---------------- censored mean ----------------

    private fun cmean(x: DoubleArray, sigma: Double): Double {
        var a = -40.0
        var b = 140.0
        repeat(40) {
            val m = (a + b) / 2.0
            var s = 0.0
            var nLo = 0
            var nHi = 0
            for (v in x) {
                when {
                    v <= LO_C + C_EPS -> nLo++
                    v >= HI_C - C_EPS -> nHi++
                    else -> s += v - m
                }
            }
            s = s / (sigma * sigma) - nLo * mills((LO_C - m) / sigma) / sigma + nHi * mills((m - HI_C) / sigma) / sigma
            if (s > 0.0) a = m else b = m
        }
        return (a + b) / 2.0
    }

    private fun mills(z: Double): Double {
        if (z < -8.0) return -z
        if (z > 8.0) return 0.0
        val phi = 0.3989422804014327 * exp(-0.5 * z * z)
        val phiCdf = 0.5 * (1.0 + erf(z / 1.4142135623730951))
        if (phiCdf <= 0.0) return -z
        return phi / phiCdf
    }

    private fun erf(x: Double): Double {
        val ax = abs(x)
        val t = 1.0 / (1.0 + 0.3275911 * ax)
        val p = (((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t)
        val y = 1.0 - p * exp(-ax * ax)
        return if (x < 0.0) -y else y
    }

    private fun slopeLS(x: DoubleArray, y: DoubleArray): Double {
        val n = x.size.toDouble()
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        for (i in x.indices) { sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; sxy += x[i] * y[i] }
        val den = n * sxx - sx * sx
        if (abs(den) < 1e-18) return 0.0
        return (n * sxy - sx * sy) / den
    }


    private class NativeOut(
        val status: Int,
        val q: DoubleArray,
        val z: DoubleArray,
        val o: DoubleArray,
        val g: DoubleArray,
        val info: DoubleArray,
        val g98: Double,
        val sd98: Double,
        val kTail: Double,
        val iters: Int,
        val stop: String,
        val ms: Long,
    )

    /** mode 0 full, 1 incremental. status 0 ok, 1 singular Schur, 2 non-finite. */
    private fun nativeSolve(mode: Int, built: Built, saved: State?): NativeOut {
        val segs = built.segs
        val ns = segs.size
        var n = 0
        for (s in segs) n += s.L.size
        val L = DoubleArray(n)
        val F = DoubleArray(n)
        val sd = DoubleArray(n)
        val segI = IntArray(n)
        var i = 0
        for (j in segs.indices) {
            val w = sqrt(max(1.0, segs[j].L.size / N0))
            for (k in segs[j].L.indices) {
                val se = segs[j].se[k]
                if (!se.isFinite() || se < 0.0) throw IllegalStateException("non-finite block se on $fitName")
                L[i] = segs[j].L[k]
                F[i] = segs[j].F[k]
                sd[i] = se * sqrt(2.5) * w
                segI[i] = j
                if (!(sd[i] > 0.0) || !L[i].isFinite() || !F[i].isFinite()) throw IllegalStateException("zero block sd on $fitName")
                i++
            }
        }
        val first = IntArray(ns) { if (built.first[it]) 1 else 0 }
        val c0 = DoubleArray(ns) { segs[it].C0 }
        val matched = IntArray(ns)
        val zS = DoubleArray(ns)
        val oS = DoubleArray(ns)
        if (mode == 0 && saved != null) {
            val old = HashMap<String, SegPar>()
            for (s in saved.segs) old[s.key] = s
            for (j in segs.indices) {
                val m = old[segs[j].key]
                if (m != null && m.first == built.first[j]) {
                    matched[j] = 1
                    zS[j] = m.z
                    oS[j] = m.o
                }
            }
        }
        val qOut = DoubleArray(NF)
        val zOut = DoubleArray(ns)
        val oOut = DoubleArray(ns)
        val gOut = DoubleArray(NB)
        val infoOut = DoubleArray(INFO_N)
        val stats = DoubleArray(5)
        val t0 = System.currentTimeMillis()
        val status = KalmanNative.fit(
            mode, L, F, sd, segI, c0, first,
            if (saved != null) 1 else 0,
            if (saved != null) saved.q else DoubleArray(0),
            if (mode == 1 && saved != null) saved.info else DoubleArray(0),
            if (mode == 1 && saved != null) saved.anchorK else 0.0,
            if (mode == 1 && saved != null) saved.anchorC0 else 0.0,
            matched, zS, oS, qOut, zOut, oOut, gOut, infoOut, stats,
        )
        val ms = System.currentTimeMillis() - t0
        val stops = arrayOf("grad", "step", "g98", "iters", "stall")
        val stop = if (status == 0) stops[stats[4].toInt().coerceIn(0, stops.lastIndex)] else ""
        if (status == 0) {
            val kind = if (mode == 0) "full" else "inc"
            runCatching {
                FsLog.i("FuelSmooth", "fit kalman $kind iter=${stats[3].toInt()} ms=$ms stop=$stop")
            }
        }
        return NativeOut(status, qOut, zOut, oOut, gOut, infoOut, stats[0], stats[1], stats[2], stats[3].toInt(), stop, ms)
    }
}
