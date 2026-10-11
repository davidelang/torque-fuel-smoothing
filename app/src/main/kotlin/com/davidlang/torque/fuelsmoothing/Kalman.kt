package com.davidlang.torque.fuelsmoothing

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Third gauge-map method (next to ChunkFit and BandFit): sequential information filter on the fuel density
 * g_k (gal per 1 % of gauge, k = 0..99); G(L) = gallons above gauge 0 = sum of g over [0, L]; tank = G(100).
 * Port of sandbox/research/chunk-study/kalman_replay_v2.py (reference: ref_kalman.py, fixed noise factor).
 *
 * Prior: g_k ~ N(C0/100, s_g^2), s_g = max(0.5 C0/100, 0.05), plus a first-difference link sd 0.1 gal/%.
 * C0 is frozen when the state is created: the profile's tank size (entered or seeded), else [DEFAULT_C0].
 * Data per drive part (fills are part boundaries): trusted samples (ChunkFit steady mask) thinned to one per 30 s,
 * G(L_t) + F_t = k_drive with k eliminated by weighted centering; var = NS2 ((sigma_gauge g(L_t))^2 + 0.03^2),
 * EIV density from the pre-drive mean, iterated once. Drive weight min(1, 2 / sqrt(chi2/dof)) from the predictive
 * residual before the update. Reported mean: Mahalanobis projection onto g_k >= 0.01 and, per tank epoch started
 * by a fill, G(top) - G(lowest) >= logged burn.
 * Updated only at drive end and at fill boundaries (never mid-drive). The state is replayed from the stored raw
 * drives when missing (bootstrap) and each drive part is applied once (keys recorded), so it is idempotent.
 */
internal object Kalman {
    const val VERSION = 1
    const val STATE = "kalman-state.txt"
    const val MODEL = "model-kalman.json"
    const val N = 100
    const val THIN_S = 30.0
    const val SIG_F = 0.03
    const val HUBER_K = 2.0
    const val S_G_REL = 0.5
    const val S_G_FLOOR = 0.05
    const val S_D1 = 0.1
    const val G_FLOOR = 0.01
    const val NS2_START = 2.5
    /** Online noise recalibration (NS2 <- NS2 x median chi2/dof over the last drives). Off: fixed 2.5 as in v2. */
    const val RECALIBRATE = false
    const val RECAL_MIN_DRIVES = 20
    /** Neutral prior scale when the profile has no tank size: data dominates within a few drives (v2 replay). */
    const val DEFAULT_C0 = 25.0
    /** Trust trigger: learned G(100) replaces the tank size once >= this % of gauge cells have >= 2 distinct drives. */
    const val TRUST_COV2 = 10

    class State(val c0: Double) {
        var ns2 = NS2_START
        var sig = 1.0
        var h = Array(N) { DoubleArray(N) }
        var b = DoubleArray(N)
        var mu = DoubleArray(N)
        var p = Array(N) { DoubleArray(N) }
        val cons = LinkedHashMap<String, DoubleArray>()   // epoch key -> (top, low, burn)
        var epKey = ""; var epFill = false; var epTop = Double.NaN; var epLow = Double.NaN; var epLowF = 0.0; var epF = 0.0
        val cellCount = IntArray(N); val cellLast = Array(N) { "" }
        val done = LinkedHashSet<String>()
        val bad = ArrayList<Double>()
        var lastTank = Double.NaN

        init {
            val sg = max(S_G_REL * c0 / 100, S_G_FLOOR)
            for (i in 0 until N) h[i][i] += 1 / (sg * sg)
            for (i in 0 until N - 1) {
                val w = 1 / (S_D1 * S_D1)
                h[i][i] += w; h[i + 1][i + 1] += w; h[i][i + 1] -= w; h[i + 1][i] -= w
            }
            for (i in 0 until N) { var s = 0.0; for (j in 0 until N) s += h[i][j] * c0 / 100; b[i] = s }
            solve()
        }

        fun coverage(n: Int) = cellCount.count { it >= n }
        val trusted get() = coverage(2) >= TRUST_COV2
        fun g(l: Double): Double { val r = row(l); var s = 0.0; for (k in 0 until N) s += r[k] * mu[k]; return s }
        fun sdG(l: Double): Double { val r = row(l); return sqrt(quad(p, r, r)) }
        val tank get() = mu.sum()

        fun solve() {
            p = inverse(h)
            val m = mul(p, b)
            val cs = cons.values.map { conRow(it) to it[2] }
            if (m.min() >= G_FLOOR && cs.all { dot(it.first, m) >= it.second }) { mu = m; return }
            var x = boundProj(h, m)
            for (it in 0 until 30) {
                var hit = false
                for ((c, bb) in cs) {
                    val d = dot(c, x)
                    if (d < bb - 1e-9) {
                        val pc = mul(p, c); val den = dot(c, pc)
                        for (k in 0 until N) x[k] += pc[k] * (bb - d) / den
                        hit = true
                    }
                }
                if (!hit) break
                if (x.min() < G_FLOOR) x = boundProj(h, x)
            }
            mu = DoubleArray(N) { max(x[it], G_FLOOR) }
        }

        private fun terms(ls: DoubleArray, fs: DoubleArray, gm: DoubleArray): Triple<Array<DoubleArray>, DoubleArray, DoubleArray> {
            val n = ls.size
            val a = Array(n) { row(ls[it]) }
            val v = DoubleArray(n) { val d = gm[min(ls[it].toInt(), N - 1).coerceAtLeast(0)]; ns2 * ((sig * d) * (sig * d) + SIG_F * SIG_F) }
            var ws = 0.0; for (x in v) ws += 1 / x
            val am = DoubleArray(N); var ym = 0.0
            for (i in 0 until n) { val wm = 1 / v[i] / ws; for (k in 0 until N) am[k] += wm * a[i][k]; ym += wm * -fs[i] }
            val ac = Array(n) { i -> DoubleArray(N) { k -> a[i][k] - am[k] } }
            val yc = DoubleArray(n) { -fs[it] - ym }
            return Triple(ac, yc, v)
        }

        fun badness(ls: DoubleArray, fs: DoubleArray): Double {
            val (ac, yc, v) = terms(ls, fs, mu)
            val n = ls.size
            val r = DoubleArray(n) { yc[it] - dot(ac[it], mu) }
            val pa = Array(n) { mul(p, ac[it]) }
            val s = Array(n) { i -> DoubleArray(n) { j -> dot(ac[i], pa[j]) + if (i == j) v[i] else 0.0 } }
            return dot(r, solveLin(s, r)) / max(n - 1, 1)
        }

        fun update(ls: DoubleArray, fs: DoubleArray, w: Double) {
            val h0 = Array(N) { h[it].copyOf() }; val b0 = b.copyOf(); var gm = mu
            repeat(2) {
                val (ac, yc, v) = terms(ls, fs, gm)
                val nh = Array(N) { h0[it].copyOf() }; val nb = b0.copyOf()
                for (i in ls.indices) {
                    val wi = w / v[i]; val r = ac[i]
                    for (k in 0 until N) { if (r[k] == 0.0) continue; nb[k] += r[k] * wi * yc[i]; val rk = r[k] * wi; for (j in 0 until N) nh[k][j] += rk * r[j] }
                }
                h = nh; b = nb; solve(); gm = mu
            }
        }

        /** Apply one drive part (Python replay + prep epoch logic). Returns false if already applied. */
        fun apply(d: ChunkFit.KDrive): Boolean {
            if (d.key in done) return false
            if (d.fillAtStart || epKey.isEmpty()) { epKey = d.key; epFill = d.fillAtStart; epTop = Double.NaN; epLow = Double.NaN; epLowF = 0.0; epF = 0.0 }
            var con: DoubleArray? = null
            if (d.l.isNotEmpty() && epFill) {
                if (!epTop.isFinite()) epTop = med(d.l.take(3).toDoubleArray())
                var j = 0; for (i in d.l.indices) if (d.l[i] < d.l[j]) j = i
                if (!epLow.isFinite() || d.l[j] < epLow) { epLow = d.l[j]; epLowF = epF + d.f[j] }
                if (epTop - epLow >= 10 && epLowF > 0) con = doubleArrayOf(epTop, epLow, epLowF)
            }
            epF += d.fuel
            if (d.l.size >= 3) {
                val bd = badness(d.l, d.f)
                val w = if (bd > 0) min(1.0, HUBER_K / sqrt(bd)) else 1.0
                update(d.l, d.f, w)
                if (bd.isFinite()) bad.add(bd)
                if (RECALIBRATE && bad.size >= RECAL_MIN_DRIVES) ns2 *= med(bad.takeLast(RECAL_MIN_DRIVES).toDoubleArray()).coerceIn(0.5, 2.0)
            }
            if (con != null) { cons[epKey] = con; solve() }
            for (l in d.l) {
                val k = min(max(l.toInt(), 0), N - 1)
                if (cellLast[k] != d.session) { cellLast[k] = d.session; cellCount[k]++ }
            }
            done.add(d.key); lastTank = tank
            return true
        }
    }

    // ---------------- math ----------------
    fun row(l0: Double): DoubleArray { val l = min(max(l0, 0.0), 100.0); return DoubleArray(N) { (l - it).coerceIn(0.0, 1.0) } }
    private fun conRow(c: DoubleArray): DoubleArray { val a = row(c[0]); val b = row(c[1]); return DoubleArray(N) { a[it] - b[it] } }
    private fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }
    private fun mul(m: Array<DoubleArray>, v: DoubleArray) = DoubleArray(m.size) { dot(m[it], v) }
    private fun quad(m: Array<DoubleArray>, a: DoubleArray, b: DoubleArray) = dot(a, mul(m, b))
    private fun med(x: DoubleArray) = ChunkFit.med(x)

    fun inverse(a: Array<DoubleArray>): Array<DoubleArray> {
        val n = a.size; val m = Array(n) { a[it].copyOf() }; val inv = Array(n) { i -> DoubleArray(n) { if (it == i) 1.0 else 0.0 } }
        for (c in 0 until n) {
            var p = c; for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            var t = m[p]; m[p] = m[c]; m[c] = t; t = inv[p]; inv[p] = inv[c]; inv[c] = t
            val d = m[c][c]
            for (k in 0 until n) { m[c][k] /= d; inv[c][k] /= d }
            for (r in 0 until n) {
                if (r == c) continue; val f = m[r][c]; if (f == 0.0) continue
                for (k in 0 until n) { m[r][k] -= f * m[c][k]; inv[r][k] -= f * inv[c][k] }
            }
        }
        return inv
    }

    private fun solveLin(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val inv = inverse(a); return mul(inv, b)
    }

    /** argmin (x - t)' H (x - t) s.t. x >= G_FLOOR (active set; same optimum as BVLS on the Cholesky factor). */
    fun boundProj(h: Array<DoubleArray>, t: DoubleArray): DoubleArray {
        val act = BooleanArray(N)
        var x = t.copyOf()
        for (iter in 0 until 4 * N) {
            val free = (0 until N).filter { !act[it] }
            x = DoubleArray(N) { if (act[it]) G_FLOOR else 0.0 }
            if (free.isNotEmpty()) {
                val hff = Array(free.size) { i -> DoubleArray(free.size) { j -> h[free[i]][free[j]] } }
                val rhs = DoubleArray(free.size) { i ->
                    var s = 0.0; for (k in 0 until N) if (act[k]) s += h[free[i]][k] * (G_FLOOR - t[k])
                    -s
                }
                val dx = solveLin(hff, rhs)
                for (i in free.indices) x[free[i]] = t[free[i]] + dx[i]
            }
            var worst = -1; var wv = -1e-12
            for (k in free) if (G_FLOOR - x[k] > wv) { wv = G_FLOOR - x[k]; worst = k }
            if (worst >= 0) { for (k in free) if (x[k] < G_FLOOR - 1e-12) act[k] = true; continue }
            // multipliers on the active set: lambda = (H (x - t))_k >= 0
            var rel = -1; var rv = -1e-10
            for (k in 0 until N) if (act[k]) { var g = 0.0; for (j in 0 until N) g += h[k][j] * (x[j] - t[j]); if (g < rv) { rv = g; rel = k } }
            if (rel < 0) break
            act[rel] = false
        }
        return x
    }

    // ---------------- persistence ----------------
    private fun ds(x: Double) = x.toString()

    fun save(dir: File, s: State) {
        val sb = StringBuilder()
        sb.append("version $VERSION\nc0 ${ds(s.c0)}\nns2 ${ds(s.ns2)}\nsig ${ds(s.sig)}\n")
        sb.append("b ").append(s.b.joinToString(" ") { ds(it) }).append('\n')
        for (i in 0 until N) sb.append("h ").append(s.h[i].joinToString(" ") { ds(it) }).append('\n')
        sb.append("ep ${s.epKey.ifEmpty { "-" }} ${s.epFill} ${ds(s.epTop)} ${ds(s.epLow)} ${ds(s.epLowF)} ${ds(s.epF)}\n")
        for ((k, v) in s.cons) sb.append("con $k ${v.joinToString(" ") { ds(it) }}\n")
        sb.append("cells ").append(s.cellCount.joinToString(" ")).append('\n')
        sb.append("last ").append(s.cellLast.joinToString(" ") { it.ifEmpty { "-" } }).append('\n')
        sb.append("bad ").append(s.bad.joinToString(" ") { ds(it) }).append('\n')
        for (k in s.done) sb.append("done $k\n")
        sb.append("end ${s.done.size}\n")
        val tmp = File(dir, "$STATE.tmp"); tmp.writeText(sb.toString())
        if (!tmp.renameTo(File(dir, STATE))) { File(dir, STATE).writeText(sb.toString()); tmp.delete() }
    }

    /** Why the last [load] returned null for an existing file (null when it loaded or no file existed). */
    @Volatile var lastLoadError: String? = null

    private fun warn(msg: String) { runCatching { android.util.Log.w("FuelSmooth", msg) } }

    fun load(dir: File): State? {
        lastLoadError = null
        val f = File(dir, STATE); if (!f.isFile) return null
        return try { parse(f) } catch (e: Exception) {
            lastLoadError = "${e.javaClass.simpleName}: ${e.message}"
            warn("kalman state unreadable (${f.name}, ${f.length()} bytes): $lastLoadError")
            null
        }
    }

    private fun parse(f: File): State = run {
        val lines = f.readLines(); val kv = { k: String -> lines.first { it.startsWith("$k ") }.substring(k.length + 1) }
        kv("version").trim().toInt().let { v -> check(v == VERSION) { "state version $v, expected $VERSION" } }
        val s = State(kv("c0").toDouble())
        s.ns2 = kv("ns2").toDouble(); s.sig = kv("sig").toDouble()
        s.b = kv("b").split(' ').map { it.toDouble() }.toDoubleArray()
        val hs = lines.filter { it.startsWith("h ") }; check(hs.size == N)
        s.h = Array(N) { i -> hs[i].substring(2).split(' ').map { it.toDouble() }.toDoubleArray() }
        val ep = kv("ep").split(' ')
        s.epKey = if (ep[0] == "-") "" else ep[0]; s.epFill = ep[1].toBoolean(); s.epTop = ep[2].toDouble(); s.epLow = ep[3].toDouble(); s.epLowF = ep[4].toDouble(); s.epF = ep[5].toDouble()
        for (l in lines.filter { it.startsWith("con ") }) { val p = l.split(' '); s.cons[p[1]] = doubleArrayOf(p[2].toDouble(), p[3].toDouble(), p[4].toDouble()) }
        kv("cells").split(' ').forEachIndexed { i, c -> s.cellCount[i] = c.toInt() }
        kv("last").split(' ').forEachIndexed { i, c -> s.cellLast[i] = if (c == "-") "" else c }
        kv("bad").trim().takeIf { it.isNotEmpty() }?.split(' ')?.forEach { s.bad.add(it.toDouble()) }
        for (l in lines.filter { it.startsWith("done ") }) s.done.add(l.substring(5))
        check(s.b.size == N) { "b has ${s.b.size} values" }
        // A complete file ends with "end <done count>"; anything else is truncated (would re-apply drives).
        val end = lines.lastOrNull { it.isNotBlank() }
        check(end == "end ${s.done.size}") { "truncated (last line '${end?.take(40)}')" }
        s.solve(); s.lastTank = s.tank
        check(s.mu.all { it.isFinite() }) { "non-finite mean" }
        s
    }

    /** Keeps an unreadable file as STATE.corrupt (never deleted; numbered if one already exists). */
    fun quarantineCorrupt(dir: File): File? {
        val f = File(dir, STATE); if (!f.isFile) return null
        var dst = File(dir, "$STATE.corrupt"); var k = 1
        while (dst.exists()) dst = File(dir, "$STATE.corrupt.${k++}")
        return if (f.renameTo(dst)) dst else { f.copyTo(dst); dst }
    }

    /** C0 recorded in model-kalman.json (so a rebuild after a lost state keeps the original prior scale). */
    fun storedC0(dir: File): Double? {
        val f = File(dir, MODEL); if (!f.isFile) return null
        return Regex("\"c0\":(-?[0-9.eE+-]+)").find(f.readText())?.groupValues?.get(1)?.toDoubleOrNull()?.takeIf { it > 0 }
    }

    fun modelJson(s: State, nowMs: Long): String =
        "{\"version\":$VERSION,\"source\":\"kalman\",\"timestamp\":$nowMs,\"c0\":${ds(s.c0)},\"ns2\":${ds(s.ns2)},\"sigmaGauge\":${ds(s.sig)}," +
            "\"tank\":${ds(s.tank)},\"v95\":${ds(s.g(95.0))},\"sdTank\":${ds(s.sdG(100.0))},\"coverage1\":${s.coverage(1)},\"coverage2\":${s.coverage(2)}," +
            "\"coverage3\":${s.coverage(3)},\"trusted\":${s.trusted},\"drives\":${s.done.size},\"g\":${s.mu.joinToString(",", "[", "]") { ds(it) }}}"

    class Model(val g: DoubleArray, val tank: Double, val cov1: Int, val cov2: Int, val cov3: Int, val trusted: Boolean) {
        fun v(level: Double): Double { val r = row(level); var s = 0.0; for (k in 0 until N) s += r[k] * g[k]; return s }
    }

    fun loadModel(dir: File): Model? {
        val f = File(dir, MODEL); if (!f.isFile) return null
        return try { parseModel(f.readText()) } catch (e: Exception) {
            warn("kalman model unreadable (${f.name}): ${e.javaClass.simpleName}: ${e.message}"); null
        }
    }

    private fun parseModel(t: String): Model = run {
        fun num(k: String) = Regex("\"$k\":(-?[0-9.eE+-]+)").find(t)!!.groupValues[1].toDouble()
        val g = Regex("\"g\":\\[([^\\]]*)\\]").find(t)!!.groupValues[1].split(',').map { it.trim().toDouble() }.toDoubleArray()
        check(g.size == N)
        Model(g, num("tank"), num("coverage1").toInt(), num("coverage2").toInt(), num("coverage3").toInt(), t.contains("\"trusted\":true"))
    }

    /** The learned tank size to store in the profile, or null while the trust trigger has not fired. */
    fun trustedTank(m: Model?): Double? = m?.takeIf { it.trusted && it.tank.isFinite() && it.tank > 0 }?.tank

    /**
     * Drive end / fill: apply every stored drive part not yet applied, in time order (bootstrap when no state),
     * then persist the state and model-kalman.json. [c0] is used only when the state is created.
     */
    fun update(dir: File, sessions: List<FuelSession>, c0: Double?, openName: String? = null): State? = runCatching {
        if (sessions.isEmpty()) return@runCatching load(dir)
        val (drives, sig) = ChunkFit.kalmanDrives(sessions, THIN_S, openName)
        var s = load(dir)
        if (s == null && File(dir, STATE).isFile) {
            val kept = quarantineCorrupt(dir)
            warn("kalman state kept as ${kept?.name} and rebuilt from the stored drives (${lastLoadError})")
        }
        if (s == null) {
            val prior = storedC0(dir)
            s = State(prior ?: c0?.takeIf { it.isFinite() && it > 0 } ?: DEFAULT_C0)
            if (prior != null) runCatching { android.util.Log.i("FuelSmooth", "kalman rebuild uses the original C0=$prior") }
        }
        s.sig = sig
        var n = 0
        for (d in drives) if (s.apply(d)) n++
        if (n > 0 || !File(dir, STATE).isFile) {
            save(dir, s)
            AtomicFile.write(File(dir, MODEL), modelJson(s, System.currentTimeMillis()))
        }
        runCatching {
            android.util.Log.i("FuelSmooth", "fit kalman tank=${"%.2f".format(s.tank)} sd=${"%.2f".format(s.sdG(100.0))} v95=${"%.2f".format(s.g(95.0))} " +
                "cov1/2/3=${s.coverage(1)}/${s.coverage(2)}/${s.coverage(3)} trusted=${s.trusted} newParts=$n c0=${s.c0}")
        }
        s
    }.onFailure { e -> runCatching { android.util.Log.w("FuelSmooth", "kalman update failed; keeping $STATE: ${e.message}") } }.getOrNull()
}
