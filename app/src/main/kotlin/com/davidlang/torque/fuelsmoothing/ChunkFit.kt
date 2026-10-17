package com.davidlang.torque.fuelsmoothing

import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Gauge map and tank size fit from stored pieces: one chunk per piece. Kalman still reads [kalmanDrives].
 *
 * [fromPieces] maps pieces.jsonl onto parts and chunks. The map is refit from all of those chunks:
 * inverse overlap fit with weights fuel x 1/noise^2 and Huber (k=2) bounded influence, h >= H_MIN,
 * then lifted to satisfy hard constraints (logged burn within a tank epoch).
 */
internal object ChunkFit {
    const val VERSION = 1
    const val FILE = "segments.jsonl"
    const val FILL_JUMP = 10.0
    const val SE_TARGET = 0.7
    const val MIN_BLOCKS = 4
    const val MIN_SPAN = 2.0
    const val PEN_K = 3.0
    const val Z_MERGE = 2.0
    const val FLOW_REL = 0.25
    const val NB_MIN = 10
    const val BIN = 5.0
    const val U_MIN = 0.06
    const val H_MIN = 0.2
    const val LAM_SMOOTH = 0.5
    const val LAM_PRIOR = 0.02
    const val HUBER_K = 2.0
    const val INV_ITERS = 8
    const val IRLS_ITERS = 6
    const val CONS_MIN_SPAN = 10.0
    /** Seconds of steady driving after any stop (or hard accel/brake) before the level is trusted (slope skew from parking). */
    const val SETTLE_S = Pieces.SETTLE_S
    /** Max gauge span of one chunk (2 bins), so long drives yield several chunks across the map. */
    const val MAX_SPAN = 2 * 5.0
    const val STOP_KMH = Pieces.STOP_KMH

    /** Future anomaly exclusion hook (|robust z| above this drops a chunk). Off: data too sparse. */
    val EXCLUDE_Z: Double? = null

    val EDGES: DoubleArray = DoubleArray(20) { it * BIN }

    class Part(
        val key: String, val t0: Double, val t1: Double, val fStart: Double, val fuel: Double,
        val fillAtStart: Boolean, val nb: Int, val sigmaQ: Double, val tau: Double, val dens0: Double,
    )

    class Chunk(
        val key: String, val t0: Double, val t1: Double, val f0: Double, val f1: Double,
        val ls: Double, val le: Double, val dF: Double, val n: Int, val blocks: Int, val resid: Double, val seEnd: Double,
    )

    class Result(
        val g: DoubleArray, val gUnc: DoubleArray, val v95: Double, val capToZero: Double, val t0: Double,
        val chunks: Int, val groups: Int, val cons: List<DoubleArray>, val active: Int, val plateaus: List<Double>,
    ) {
        fun v(level: Double) = vAt(level, g)
    }

    private class Work(
        val key: String, val s: FuelSession, val a: Int, val b: Int, val moving: Boolean,
        val i0: Int, val i1: Int, val q: BooleanArray?, val fillAtStart: Boolean,
    )

    // ---------------- helpers (match numpy) ----------------
    fun med(x: DoubleArray): Double = Stats.med(x)

    private fun finiteMed(x: List<Double>): Double {
        val f = x.filter { it.isFinite() }.toDoubleArray()
        return if (f.isEmpty()) Double.NaN else med(f)
    }

    private fun ols(f: DoubleArray, l: DoubleArray, a: Int = 0, b: Int = f.size): DoubleArray {
        val n = b - a
        var mf = 0.0; var ml = 0.0
        for (i in a until b) { mf += f[i]; ml += l[i] }
        mf /= n; ml /= n
        var sxx = 0.0; var sxy = 0.0
        for (i in a until b) { sxx += (f[i] - mf) * (f[i] - mf); sxy += (f[i] - mf) * (l[i] - ml) }
        val c1 = if (sxx > 0) sxy / sxx else 0.0
        return doubleArrayOf(ml - c1 * mf, c1, sxx)
    }

    fun steady(s: FuelSession): BooleanArray = Pieces.trusted(s)

    /**
     * Analysis-only level mask (equivalent to lvl=-1; raw data is untouched and fuel always counts): false during
     * the hard accel/brake blanking (HARD_HOLD, applied in [q]) and for the first SETTLE_S seconds of driving after any stop.
     */
    fun levelTrusted(s: FuelSession, q: BooleanArray = FuelV2.quietMask(s.spd, s.t, s.fill)): BooleanArray =
        Pieces.levelTrusted(s)

    private fun movingGph(s: FuelSession): Double? {
        var cnt = 0; var fu = 0.0; var dt = 0.0
        for (i in 1 until s.t.size) if (s.spd[i] > 5) { cnt++; fu += s.used[i] - s.used[i - 1]; dt += s.t[i] - s.t[i - 1] }
        if (cnt < 120) return null
        return fu / max(dt, 1.0) * 3600
    }

    // ---------------- segmentation ----------------
    /** Per-session work that does not depend on other sessions; memoized per session object (rollup cache). */
    private class Prep(val first: Double, val lastMed: Double, val gph: Double?, val cuts: List<Int>, val q: BooleanArray)
    private val prepMemo = java.util.Collections.synchronizedMap(java.util.WeakHashMap<FuelSession, Prep>())

    private fun prep(s: FuelSession): Prep {
        if (RollupCache.enabled) prepMemo[s]?.let { return it }
        val n = s.t.size; val l = s.raw
        val fin = (0 until n).filter { l[it].isFinite() }
        val first = if (fin.isEmpty()) Double.NaN else finiteMed(fin.take(60).map { l[it] })
        val last = if (fin.isEmpty()) Double.NaN else finiteMed(fin.takeLast(60).map { l[it] })
        val cut = TreeSet<Int>(); cut.add(0)
        val fi = (0 until n).filter { s.fill[it] }
        if (fi.isNotEmpty()) { cut.add(fi.first()); cut.add(fi.last() + 1) }
        for (i in 60 until n - 60) {
            var mx = Double.NEGATIVE_INFINITY
            for (k in i - 60 until i + 60) mx = max(mx, s.spd[k])
            if (mx < 1.0) {
                val a = finiteMed((i - 60 until i).map { l[it] }); val b = finiteMed((i until i + 60).map { l[it] })
                if (a.isFinite() && b.isFinite() && b - a >= FILL_JUMP) { cut.add(i); break }
            }
        }
        val p = Prep(first, last, movingGph(s), cut.filter { it in 0 until n } + n, steady(s))
        if (RollupCache.enabled) prepMemo[s] = p
        return p
    }

    private fun parts(sessions: List<FuelSession>): List<Work> {
        val preps = sessions.map { prep(it) }
        val gs = preps.mapNotNull { it.gph }.toDoubleArray()
        val ref = if (gs.isEmpty()) 0.0 else med(gs)
        val out = ArrayList<Work>()
        var prevEnd = Double.NaN
        for ((si, s) in sessions.withIndex()) {
            val pr = preps[si]
            val first = pr.first
            val between = prevEnd.isFinite() && first.isFinite() && first - prevEnd >= FILL_JUMP
            val g = pr.gph; val dropout = g != null && g < FLOW_REL * ref
            val cuts = pr.cuts
            val q = pr.q
            for (p in 0 until cuts.size - 1) {
                val a = cuts[p]; val b = cuts[p + 1]
                val mv = (a until b).filter { s.spd[it] >= 5.0 }
                val moving = mv.isNotEmpty() && !dropout
                out.add(
                    Work(
                        "${s.name}#$p", s, a, b, moving, if (moving) mv.first() else 0, if (moving) mv.last() + 1 else 0,
                        if (moving) q else null, p > 0 || between,
                    ),
                )
            }
            if (pr.lastMed.isFinite()) prevEnd = pr.lastMed
        }
        return out
    }

    private fun steadyIdx(w: Work): IntArray = (w.i0 until w.i1).filter { w.q!![it] }.toIntArray()

    /** (sigmaQ, tau, nb, sb2) */
    private fun noise(ps: List<Work>): DoubleArray {
        val sds = ArrayList<Double>(); val taus = ArrayList<Double>()
        for (w in ps) {
            if (!w.moving) continue
            val idx = steadyIdx(w); if (idx.isEmpty()) continue
            var st = 0
            for (k in 1..idx.size) {
                if (k == idx.size || idx[k] - idx[k - 1] > 1) {
                    val len = k - st
                    if (len >= 120) {
                        val x = DoubleArray(len) { w.s.raw[idx[st + it]] }
                        val tt = DoubleArray(len) { it.toDouble() }
                        val c = ols(tt, x)
                        for (i in 0 until len) x[i] -= c[0] + c[1] * tt[i]
                        val m = x.average()
                        sds.add(sqrt(x.sumOf { (it - m) * (it - m) } / len))
                        val ma = (0 until len - 1).sumOf { x[it] } / (len - 1)
                        val mb = (1 until len).sumOf { x[it] } / (len - 1)
                        var sab = 0.0; var saa = 0.0; var sbb = 0.0
                        for (i in 0 until len - 1) {
                            val a = x[i] - ma; val b = x[i + 1] - mb
                            sab += a * b; saa += a * a; sbb += b * b
                        }
                        val den = sqrt(saa * sbb)
                        val r1 = if (den > 0) sab / den else 0.0
                        taus.add(-1.0 / ln(min(max(r1, 0.05), 0.99)))
                    }
                    st = k
                }
            }
        }
        val sig = if (sds.isEmpty()) 1.0 else med(sds.toDoubleArray())
        val tau = if (taus.isEmpty()) 3.0 else med(taus.toDoubleArray())
        val nb = max(ceil(2 * tau * (sig / SE_TARGET) * (sig / SE_TARGET)).toInt(), NB_MIN)
        val sb2 = (1.25 * sig) * (1.25 * sig) * 2 * tau / nb
        return doubleArrayOf(sig, tau, nb.toDouble(), sb2)
    }

    /** One drive part for the Kalman estimator: trusted samples (steady mask) thinned to one per [thinS] s. */
    class KDrive(val key: String, val session: String, val t0: Double, val fuel: Double, val fillAtStart: Boolean, val l: DoubleArray, val f: DoubleArray)

    /** Drive parts in time order plus the gauge sigma; the last part of [openName] is left out. */
    fun kalmanDrives(sessions: List<FuelSession>, thinS: Double, openName: String? = null): Pair<List<KDrive>, Double> {
        val ps = parts(sessions)
        val sig = noise(ps)[0]
        val lastOpen = ps.lastOrNull { it.s.name == openName }?.key
        val out = ArrayList<KDrive>()
        for (w in ps) {
            if (w.key == lastOpen) continue
            val s = w.s; val ls = ArrayList<Double>(); val fs = ArrayList<Double>()
            if (w.moving) {
                var last = -1e9
                for (i in w.i0 until w.i1) if (w.q!![i] && s.t[i] - last >= thinS) { ls.add(s.raw[i]); fs.add(s.used[i] - s.used[w.a]); last = s.t[i] }
            }
            out.add(KDrive(w.key, s.name, s.t[w.a], s.used[w.b - 1] - s.used[w.a], w.fillAtStart, ls.toDoubleArray(), fs.toDoubleArray()))
        }
        return out to sig
    }

    /**
     * One [Part] per piece-part and one [Chunk] per included piece, in time order.
     * [Part.dens0] is the median finite rho of moving parts (NaN rho is non-moving), stamped on every part.
     * A piece is included when it is overSpan, [Pieces.FIT_WEAK] is set, or it is not weak.
     */
    fun fromPieces(parts: List<Pieces.Part>, pieces: List<Pieces.Piece>): Pair<List<Part>, List<Chunk>> {
        val rhos = parts.map { it.rho }.filter { it.isFinite() }
        val dens0 = if (rhos.isEmpty()) Double.NaN else med(rhos.toDoubleArray())
        val partsOut = parts.map { p ->
            Part(p.key, p.t0, p.t1, p.fStart, p.fuel, p.fillAtStart, p.nb, p.sigma, p.tau, dens0)
        }
        val chunksOut = ArrayList<Chunk>()
        for (pc in pieces.sortedWith(compareBy({ it.t0 }, { it.t1 }, { it.key }))) {
            if (!(pc.overSpan || Pieces.FIT_WEAK || !pc.weak)) continue
            chunksOut.add(
                Chunk(
                    pc.part, pc.t0, pc.t1, pc.f0, pc.f1, pc.ls, pc.le, pc.dF, pc.nS + pc.nE, 0, 0.0,
                    max(pc.seS, pc.seE),
                ),
            )
        }
        return partsOut to chunksOut
    }

    // ---------------- fit ----------------
    fun vAt(level: Double, g: DoubleArray): Double {
        var v = 0.0
        for (k in g.indices) v += min(max(level - EDGES[k], 0.0), BIN) * g[k]
        return v
    }

    private class Grp(val f: DoubleArray, val l: DoubleArray, val w: DoubleArray)

    private fun groups(ch: List<Chunk>): List<Grp> {
        val mdf = if (ch.isEmpty()) 1.0 else ch.sumOf { max(it.dF, 0.05) } / ch.size
        val m = HashMap<String, MutableList<DoubleArray>>()
        for (c in ch) {
            val w = (max(c.dF, 0.05) / mdf) / (max(c.seEnd, 0.2) * max(c.seEnd, 0.2))
            for ((l, f) in listOf(c.ls to c.f0, c.le to c.f1)) {
                if (l > FuelV2.L_LO && l < FuelV2.L_HI) m.getOrPut(c.key) { ArrayList() }.add(doubleArrayOf(f, l, w))
            }
        }
        val out = ArrayList<Grp>()
        for (k in m.keys.sorted()) {
            val p = m[k]!!
            val ls = p.map { it[1] }
            if (p.size >= 2) {
                out.add(Grp(p.map { it[0] }.toDoubleArray(), ls.toDoubleArray(), p.map { it[2] }.toDoubleArray()))
            }
        }
        if (out.isNotEmpty()) {
            val all = out.flatMap { it.w.toList() }; val wm = all.sum() / all.size
            return out.map { g -> Grp(g.f, g.l, DoubleArray(g.w.size) { g.w[it] / wm }) }
        }
        return out
    }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val m = Array(n) { a[it].copyOf() }; val v = b.copyOf()
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            if (p != c) { val t = m[p]; m[p] = m[c]; m[c] = t; val tv = v[p]; v[p] = v[c]; v[c] = tv }
            for (r in c + 1 until n) {
                val f = m[r][c] / m[c][c]
                if (f == 0.0) continue
                for (k in c until n) m[r][k] -= f * m[c][k]
                v[r] -= f * v[c]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = v[r]
            for (k in r + 1 until n) s -= m[r][k] * x[k]
            x[r] = s / m[r][r]
        }
        return x
    }

    private fun solveBounded(m: Array<DoubleArray>, v: DoubleArray, lo: Double): DoubleArray {
        val k = v.size; val fixed = BooleanArray(k)
        var h = DoubleArray(k) { lo }
        repeat(k + 1) {
            h = DoubleArray(k) { lo }
            val free = (0 until k).filter { !fixed[it] }
            if (free.isNotEmpty()) {
                val mf = Array(free.size) { i -> DoubleArray(free.size) { j -> m[free[i]][free[j]] } }
                val vf = DoubleArray(free.size) { i ->
                    var s = v[free[i]]
                    for (j in 0 until k) if (fixed[j]) s -= m[free[i]][j] * h[j]
                    s
                }
                val x = solve(mf, vf)
                for (i in free.indices) h[free[i]] = x[i]
            }
            var viol = false
            for (i in free) if (h[i] < lo) { fixed[i] = true; viol = true }
            if (!viol) return h
        }
        return DoubleArray(k) { max(h[it], lo) }
    }

    private fun wavg(x: DoubleArray, w: DoubleArray): Double {
        var s = 0.0; var sw = 0.0
        for (i in x.indices) { s += x[i] * w[i]; sw += w[i] }
        return s / sw
    }

    /** Relative-density inverse overlap fit. Returns g (gal per %). [huberK] = +inf disables robustness. */
    private fun fitInv(grp: List<Grp>, s0: Double, huberK: Double = HUBER_K): DoubleArray {
        val k = EDGES.size - 1
        var u = DoubleArray(k) { 1.0 }
        if (grp.isEmpty()) return DoubleArray(k) { s0 }
        val p = Array(k) { DoubleArray(k) }
        for (i in 0 until k) p[i][i] += LAM_PRIOR
        for (i in 0 until k - 1) {
            val c = LAM_SMOOTH * 4.0
            p[i][i] += c; p[i + 1][i + 1] += c; p[i][i + 1] -= c; p[i + 1][i] -= c
        }
        repeat(INV_ITERS) {
            val cap = DoubleArray(k) { u[it] * BIN }
            val qk = DoubleArray(k + 1); for (i in 0 until k) qk[i + 1] = qk[i] + cap[i]
            val rows = ArrayList<DoubleArray>(); val rhs = ArrayList<Double>(); val ws = ArrayList<Double>()
            for (g in grp) {
                val n = g.f.size
                val fr = DoubleArray(n) { g.f[it] / s0 }
                val vl = DoubleArray(n) { j -> vAt(g.l[j], u) }
                val c = wavg(DoubleArray(n) { vl[it] + fr[it] }, g.w)
                val psi = Array(n) { j -> DoubleArray(k) { i -> min(max(c - fr[j] - qk[i], 0.0), cap[i]) } }
                val sw = g.w.sum()
                for (i in 0 until k) {
                    var m = 0.0; for (j in 0 until n) m += psi[j][i] * g.w[j]; m /= sw
                    for (j in 0 until n) psi[j][i] -= m
                }
                val ml = wavg(g.l, g.w)
                for (j in 0 until n) { rows.add(psi[j]); rhs.add(g.l[j] - ml); ws.add(g.w[j]) }
            }
            val nr = rows.size; val w = ws.toDoubleArray(); val b = rhs.toDoubleArray()
            val hub = DoubleArray(nr) { 1.0 }
            var h = DoubleArray(k) { 1.0 }
            repeat(IRLS_ITERS) {
                val m = Array(k) { i -> p[i].copyOf() }
                val v = DoubleArray(k) { LAM_PRIOR }
                for (r in 0 until nr) {
                    val ww = w[r] * hub[r]; val a = rows[r]
                    for (i in 0 until k) {
                        if (a[i] == 0.0) continue
                        v[i] += a[i] * ww * b[r]
                        for (j in 0 until k) m[i][j] += a[i] * ww * a[j]
                    }
                }
                h = solveBounded(m, v, H_MIN)
                val rs = DoubleArray(nr) { r ->
                    var s = b[r]; for (i in 0 until k) s -= rows[r][i] * h[i]
                    abs(s) * sqrt(w[r])
                }
                val sc = 1.4826 * med(rs) + 1e-9
                for (r in 0 until nr) hub[r] = min(1.0, huberK * sc / max(rs[r], 1e-12))
            }
            val un = DoubleArray(k) { max(1.0 / h[it], U_MIN) }
            var d = 0.0; for (i in 0 until k) d = max(d, abs(un[i] - u[i]))
            u = un
            if (d < 1e-4) return DoubleArray(k) { u[it] * s0 }
        }
        return DoubleArray(k) { u[it] * s0 }
    }

    /** Hard constraints (La, Lb, burn) and plateau points (La, Fa) from tank epochs. */
    internal fun epochs(ps: List<Part>, ch: List<Chunk>): Pair<List<DoubleArray>, List<DoubleArray>> {
        val by = ch.groupBy { it.key }
        class Ep(val fill: Boolean, val pts: MutableList<DoubleArray> = ArrayList())
        val eps = ArrayList<Ep>(); var cur: Ep? = null; var off = 0.0
        for (d in ps.sortedBy { it.t0 }) {
            if (cur == null || d.fillAtStart) { cur = Ep(d.fillAtStart); eps.add(cur); off = 0.0 }
            for (c in by[d.key].orEmpty()) {
                cur.pts.add(doubleArrayOf(c.t0, c.ls, off + c.f0 - d.fStart))
                cur.pts.add(doubleArrayOf(c.t1, c.le, off + c.f1 - d.fStart))
            }
            off += d.fuel
        }
        val cons = ArrayList<DoubleArray>(); val plats = ArrayList<DoubleArray>()
        for (e in eps) {
            val pts = e.pts.filter { it[1] > FuelV2.L_LO && it[1] < FuelV2.L_HI }
                .sortedWith(compareBy<DoubleArray>({ it[0] }, { it[1] }, { it[2] }))
            if (pts.isEmpty()) continue
            val a = pts[0]
            val b = pts.minWith(compareBy<DoubleArray>({ it[1] }, { it[0] }))
            if (a[1] - b[1] >= CONS_MIN_SPAN && b[2] > a[2]) cons.add(doubleArrayOf(a[1], b[1], b[2] - a[2]))
            if (e.fill) plats.add(doubleArrayOf(a[1], a[2]))
        }
        return cons to plats
    }

    private fun project(g0: DoubleArray, cons: List<DoubleArray>): Pair<DoubleArray, Int> {
        val g = g0.copyOf(); var active = 0
        for (c in cons) {
            var hit = false
            for (it in 0 until 50) {
                val d = vAt(c[0], g) - vAt(c[1], g)
                if (d >= c[2] - 1e-9) break
                hit = true
                val f = c[2] / d
                for (k in g.indices) if (EDGES[k] < c[0] && EDGES[k + 1] > c[1]) g[k] *= f
            }
            if (hit) active++
        }
        return g to active
    }

    fun fit(ps: List<Part>, ch: List<Chunk>, nameplate: Double?, huberK: Double = HUBER_K): Result {
        val dens0 = ps.lastOrNull()?.dens0 ?: Double.NaN
        val t0 = if (dens0.isFinite()) 100 * dens0 else (nameplate?.takeIf { it > 0 } ?: 30.0)
        val grp = groups(ch)
        val gUnc = fitInv(grp, t0 / 100.0, huberK)
        val (cons, plats) = epochs(ps, ch)
        val (g, active) = project(gUnc, cons)
        val v95 = vAt(FuelV2.L_HI, g)
        val pl = plats.map { max(it[1] - (v95 - vAt(it[0], g)), 0.0) }
        val cap = v95 + if (pl.isNotEmpty()) med(pl.toDoubleArray()) else 0.07 * v95
        return Result(g, gUnc, v95, cap, t0, ch.size, grp.size, cons, active, pl)
    }

    // ---------------- store ----------------
    private fun d(x: Double) = if (x.isFinite()) x.toString() else "null"

    fun modelJson(r: Result, raw: FuelModel?, nowMs: Long): String {
        val arr = { a: DoubleArray -> a.joinToString(",", "[", "]") { d(it) } }
        val rawPart = if (raw == null) "" else ",\"raw\":{\"v95\":${d(raw.v95())},\"capToZero\":${d(raw.capToZero)},\"chunks\":${raw.chunks}}"
        return "{\"version\":$VERSION,\"source\":\"chunks\",\"timestamp\":$nowMs,\"edges\":${arr(EDGES)},\"g\":${arr(r.g)},\"gUnc\":${arr(r.gUnc)}," +
            "\"v95\":${d(r.v95)},\"capToZero\":${d(r.capToZero)},\"t0\":${d(r.t0)},\"chunks\":${r.chunks},\"groups\":${r.groups}," +
            "\"constraints\":${r.cons.size},\"activeConstraints\":${r.active},\"nMin\":${raw?.nMin ?: 28},\"sigmaQ\":${d(raw?.sigmaQ ?: 1.1)}," +
            "\"tau\":${d(raw?.tau ?: 3.5)}$rawPart}"
    }
}
