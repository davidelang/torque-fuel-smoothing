package com.davidlang.torque.fuelsmoothing

import java.io.File
import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Chunk-based gauge map and tank size (CHUNK_STUDY.md). Mirrors
 * sandbox/research/chunk-study/ref_chunkfit.py line by line; ChunkFitTest checks parity.
 *
 * Per drive part (never across sessions or fills; lvl<0 skipped): steady samples -> nb-sample median
 * blocks -> top-down piecewise-linear level-on-fuel segmentation -> chunks. Parts and chunks are appended
 * to segments.jsonl (append-only, deduplicated by "<session>#<part>"). The map is refit from ALL stored
 * chunks: inverse overlap fit with weights fuel x 1/noise^2 and Huber (k=2) bounded influence, h >= H_MIN,
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
    const val SETTLE_S = 25.0
    /** Max gauge span of one chunk (2 bins), so long drives yield several chunks across the map. */
    const val MAX_SPAN = 2 * 5.0
    const val STOP_KMH = 5.0

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
    fun med(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

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

    private fun sse(f: DoubleArray, l: DoubleArray, a: Int, b: Int): Double {
        val c = ols(f, l, a, b); var s = 0.0
        for (i in a until b) { val r = l[i] - (c[0] + c[1] * f[i]); s += r * r }
        return s
    }

    fun steady(s: FuelSession): BooleanArray {
        val q = FuelV2.quietMask(s.spd, s.t, s.fill)
        val n = s.t.size
        val frozen = BooleanArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && s.raw[j + 1] == s.raw[i]) j++
            if (j - i + 1 >= FuelV2.FROZEN_S) {
                var mv = 0
                for (k in i..j) if (s.spd[k] > 5.0) mv++
                if (mv >= FuelV2.FROZEN_S / 2) for (k in i..j) frozen[k] = true
            }
            i = j + 1
        }
        val ok = levelTrusted(s, q)
        return BooleanArray(n) { q[it] && ok[it] && s.raw[it].isFinite() && !frozen[it] }
    }

    /**
     * Analysis-only level mask (equivalent to lvl=-1; raw data is untouched and fuel always counts): false during
     * the hard accel/brake blanking (HARD_HOLD, applied in [q]) and for the first SETTLE_S seconds of driving after any stop.
     */
    fun levelTrusted(s: FuelSession, q: BooleanArray = FuelV2.quietMask(s.spd, s.t, s.fill)): BooleanArray {
        val n = s.t.size
        val out = BooleanArray(n)
        var start = Double.NaN
        for (i in 0 until n) {
            if (s.spd[i] < STOP_KMH) { start = Double.NaN; continue }
            if (start.isNaN()) start = s.t[i]
            out[i] = s.t[i] - start >= SETTLE_S
        }
        return out
    }

    private fun movingGph(s: FuelSession): Double? {
        var cnt = 0; var fu = 0.0; var dt = 0.0
        for (i in 1 until s.t.size) if (s.spd[i] > 5) { cnt++; fu += s.used[i] - s.used[i - 1]; dt += s.t[i] - s.t[i - 1] }
        if (cnt < 120) return null
        return fu / max(dt, 1.0) * 3600
    }

    // ---------------- segmentation ----------------
    private fun parts(sessions: List<FuelSession>): List<Work> {
        val gs = sessions.mapNotNull { movingGph(it) }.toDoubleArray()
        val ref = if (gs.isEmpty()) 0.0 else med(gs)
        val out = ArrayList<Work>()
        var prevEnd = Double.NaN
        for (s in sessions) {
            val n = s.t.size; val l = s.raw
            val fin = (0 until n).filter { l[it].isFinite() }
            val first = if (fin.isEmpty()) Double.NaN else finiteMed(fin.take(60).map { l[it] })
            val between = prevEnd.isFinite() && first.isFinite() && first - prevEnd >= FILL_JUMP
            val g = movingGph(s); val dropout = g != null && g < FLOW_REL * ref
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
            val cuts = cut.filter { it in 0 until n } + n
            val q = steady(s)
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
            if (fin.isNotEmpty()) prevEnd = finiteMed(fin.takeLast(60).map { l[it] })
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

    /** Blocks as columns F, L, n, t, joined (1.0 when no untrusted/unsteady sample lies between it and the previous block). */
    private fun blocks(w: Work, nb: Int): Array<DoubleArray> {
        val idx = steadyIdx(w)
        val cnt = if (idx.size >= nb) (idx.size - nb) / nb + 1 else 0
        val f = DoubleArray(cnt); val l = DoubleArray(cnt); val c = DoubleArray(cnt); val t = DoubleArray(cnt); val j = DoubleArray(cnt)
        for (b in 0 until cnt) {
            val ii = idx.copyOfRange(b * nb, b * nb + nb)
            f[b] = med(DoubleArray(nb) { w.s.used[ii[it]] }); l[b] = med(DoubleArray(nb) { w.s.raw[ii[it]] })
            c[b] = nb.toDouble(); t[b] = med(DoubleArray(nb) { w.s.t[ii[it]] })
            // Breakable before block b only if blocks b-1 and b are one contiguous run of trusted samples.
            j[b] = if (b > 0 && idx[b * nb + nb - 1] - idx[(b - 1) * nb] == 2 * nb - 1) 1.0 else 0.0
        }
        return arrayOf(f, l, c, t, j)
    }

    private class Seg(val a: Int, val b: Int, val c0: Double, val c1: Double, val sse: Double, val dens: Double, val seD: Double)

    private fun segment(bl: Array<DoubleArray>, sb2: Double, fmin: Double): List<Seg> {
        val f = bl[0]; val l = bl[1]; val n = f.size; val joined = bl[4]
        val pen = PEN_K * ln(max(n, 2).toDouble()) * sb2
        var segs = listOf(0 to n); var changed = true
        while (changed) {
            changed = false
            val nw = ArrayList<Pair<Int, Int>>()
            for ((a, b) in segs) {
                val tot = sse(f, l, a, b); var bestG = 0.0; var bestK = -1
                for (k in a + MIN_BLOCKS..b - MIN_BLOCKS) {
                    // Never break inside a questionable (masked) period: only between contiguous trusted blocks.
                    if (joined[k] < 0.5) continue
                    if (abs(l[a] - l[k - 1]) < MIN_SPAN || abs(l[k] - l[b - 1]) < MIN_SPAN) continue
                    if (f[k - 1] - f[a] < fmin || f[b - 1] - f[k] < fmin) continue
                    val gain = tot - sse(f, l, a, k) - sse(f, l, k, b)
                    if (bestK < 0 || gain > bestG) { bestG = gain; bestK = k }
                }
                if (bestK >= 0 && bestG > pen) { nw.add(a to bestK); nw.add(bestK to b); changed = true } else nw.add(a to b)
            }
            segs = nw
        }
        fun st(a: Int, b: Int): Seg {
            val c = ols(f, l, a, b)
            val seB = if (c[2] > 0) sqrt(sb2 / c[2]) else Double.POSITIVE_INFINITY
            val dens = if (c[1] < 0) -1.0 / c[1] else Double.NaN
            val seD = if (c[1] != 0.0) seB / (c[1] * c[1]) else Double.POSITIVE_INFINITY
            return Seg(a, b, c[0], c[1], sse(f, l, a, b), dens, seD)
        }
        val s = segs.map { st(it.first, it.second) }.toMutableList()
        var merged = true
        while (merged && s.size > 1) {
            merged = false
            for (k in 0 until s.size - 1) {
                val x = s[k]; val y = s[k + 1]
                if (x.dens.isFinite() && y.dens.isFinite() && abs(x.dens - y.dens) < Z_MERGE * hypot(x.seD, y.seD)) {
                    s[k] = st(x.a, y.b); s.removeAt(k + 1); merged = true; break
                }
            }
        }
        // Span cap: split long segments into ceil(span/MAX_SPAN) near-equal pieces, breaking only between
        // contiguous trusted blocks with >= MIN_BLOCKS on each side.
        val out = ArrayList<Seg>()
        for (x in s) {
            val m = ceil(abs(x.c1 * (f[x.b - 1] - f[x.a])) / MAX_SPAN - 1e-9).toInt()
            var prev = x.a
            for (j in 1 until max(m, 1)) {
                val target = x.a + Math.round(j.toDouble() * (x.b - x.a) / m).toInt()
                var pick = -1
                for (dk in 0..(x.b - x.a)) {
                    for (k in intArrayOf(target + dk, target - dk)) {
                        if (pick < 0 && k >= prev + MIN_BLOCKS && k <= x.b - MIN_BLOCKS && joined[k] >= 0.5) pick = k
                    }
                    if (pick >= 0) break
                }
                if (pick < 0) continue
                out.add(st(prev, pick)); prev = pick
            }
            out.add(if (prev == x.a) x else st(prev, x.b))
        }
        return out
    }

    /**
     * Segments every part of [sessions] whose key is not in [skip]. Parts of [openName] (a session still
     * being recorded) are only returned when complete, i.e. ended by a fill cut.
     */
    fun segmentAll(sessions: List<FuelSession>, skip: Set<String> = emptySet(), openName: String? = null): Pair<List<Part>, List<Chunk>> {
        val ps = parts(sessions)
        val nz = noise(ps); val nb = nz[2].toInt(); val sb2 = nz[3]
        val dd = ArrayList<Double>()
        for (w in ps) {
            if (!w.moving) continue
            val b = blocks(w, nb)
            if (b[0].size >= 2 * MIN_BLOCKS && b[1][0] - b[1][b[1].size - 1] >= 5.0) {
                val c = ols(b[0], b[1]); if (c[1] < 0) dd.add(-1.0 / c[1])
            }
        }
        val dens0 = if (dd.isEmpty()) Double.NaN else med(dd.toDoubleArray())
        val fmin = MIN_SPAN * (if (dens0.isFinite()) dens0 else 0.3)
        val lastOpen = ps.lastOrNull { it.s.name == openName }?.key
        val partsOut = ArrayList<Part>(); val chunksOut = ArrayList<Chunk>()
        for (w in ps) {
            if (w.key in skip || w.key == lastOpen) continue
            val s = w.s
            partsOut.add(Part(w.key, s.t[w.a], s.t[w.b - 1], s.used[w.a], s.used[w.b - 1] - s.used[w.a], w.fillAtStart, nb, nz[0], nz[1], dens0))
            if (!w.moving) continue
            val b = blocks(w, nb)
            if (b[0].size < MIN_BLOCKS) continue
            for (sg in segment(b, sb2, fmin)) {
                val f0 = b[0][sg.a]; val f1 = b[0][sg.b - 1]
                var n = 0.0; for (k in sg.a until sg.b) n += b[2][k]
                val nbk = sg.b - sg.a
                chunksOut.add(
                    Chunk(
                        w.key, b[3][sg.a], b[3][sg.b - 1], f0, f1, sg.c0 + sg.c1 * f0, sg.c0 + sg.c1 * f1, f1 - f0, n.toInt(), nbk,
                        sqrt(sg.sse / max(nbk - 2, 1)), sqrt(4 * sb2 / nbk),
                    ),
                )
            }
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
            if (p.size >= 2 && ls.max() - ls.min() >= 1.0) {
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
    private fun epochs(ps: List<Part>, ch: List<Chunk>): Pair<List<DoubleArray>, List<DoubleArray>> {
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
    private fun num(s: String, k: String): Double? {
        val m = Regex("\"$k\":(-?[0-9.eE+-]+|NaN|-?Infinity)").find(s) ?: return null
        return m.groupValues[1].toDoubleOrNull()
    }

    private fun str(s: String, k: String): String? = Regex("\"$k\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(s)?.groupValues?.get(1)

    private fun d(x: Double) = if (x.isFinite()) x.toString() else "null"

    fun load(dir: File): Pair<List<Part>, List<Chunk>> {
        val f = File(dir, FILE)
        val ps = ArrayList<Part>(); val ch = ArrayList<Chunk>(); val seen = HashSet<String>()
        if (!f.isFile) return ps to ch
        f.forEachLine { line ->
            val key = str(line, "key") ?: return@forEachLine
            when (str(line, "type")) {
                "part" -> if (seen.add(key)) ps.add(
                    Part(
                        key, num(line, "t0")!!, num(line, "t1")!!, num(line, "Fstart")!!, num(line, "fuel")!!,
                        line.contains("\"fillAtStart\":true"), num(line, "nb")!!.toInt(), num(line, "sigmaQ")!!,
                        num(line, "tau")!!, num(line, "dens0") ?: Double.NaN,
                    ),
                )
                "chunk" -> ch.add(
                    Chunk(
                        key, num(line, "t0")!!, num(line, "t1")!!, num(line, "F0")!!, num(line, "F1")!!, num(line, "Ls")!!,
                        num(line, "Le")!!, num(line, "dF")!!, num(line, "n")!!.toInt(), num(line, "blocks")!!.toInt(),
                        num(line, "resid")!!, num(line, "seEnd")!!,
                    ),
                )
            }
        }
        return ps to ch
    }

    /** Appends parts/chunks not yet stored. Returns the number of new parts. Append-only; never rewrites. */
    fun append(dir: File, sessions: List<FuelSession>, openName: String? = null): Int {
        val have = load(dir).first.map { it.key }.toSet()
        val (ps, ch) = segmentAll(sessions, have, openName)
        if (ps.isEmpty()) return 0
        val sb = StringBuilder()
        for (p in ps) {
            sb.append("{\"type\":\"part\",\"v\":$VERSION,\"key\":\"${p.key}\",\"t0\":${d(p.t0)},\"t1\":${d(p.t1)},\"Fstart\":${d(p.fStart)},")
            sb.append("\"fuel\":${d(p.fuel)},\"fillAtStart\":${p.fillAtStart},\"nb\":${p.nb},\"sigmaQ\":${d(p.sigmaQ)},\"tau\":${d(p.tau)},\"dens0\":${d(p.dens0)}}\n")
            for (c in ch.filter { it.key == p.key }) {
                sb.append("{\"type\":\"chunk\",\"key\":\"${c.key}\",\"t0\":${d(c.t0)},\"t1\":${d(c.t1)},\"F0\":${d(c.f0)},\"F1\":${d(c.f1)},")
                sb.append("\"Ls\":${d(c.ls)},\"Le\":${d(c.le)},\"dF\":${d(c.dF)},\"n\":${c.n},\"blocks\":${c.blocks},\"resid\":${d(c.resid)},\"seEnd\":${d(c.seEnd)}}\n")
            }
        }
        File(dir, FILE).appendText(sb.toString())
        return ps.size
    }

    fun modelJson(r: Result, raw: FuelModel?, nowMs: Long): String {
        val arr = { a: DoubleArray -> a.joinToString(",", "[", "]") { d(it) } }
        val rawPart = if (raw == null) "" else ",\"raw\":{\"v95\":${d(raw.v95())},\"capToZero\":${d(raw.capToZero)},\"chunks\":${raw.chunks}}"
        return "{\"version\":$VERSION,\"source\":\"chunks\",\"timestamp\":$nowMs,\"edges\":${arr(EDGES)},\"g\":${arr(r.g)},\"gUnc\":${arr(r.gUnc)}," +
            "\"v95\":${d(r.v95)},\"capToZero\":${d(r.capToZero)},\"t0\":${d(r.t0)},\"chunks\":${r.chunks},\"groups\":${r.groups}," +
            "\"constraints\":${r.cons.size},\"activeConstraints\":${r.active},\"nMin\":${raw?.nMin ?: 28},\"sigmaQ\":${d(raw?.sigmaQ ?: 1.1)}," +
            "\"tau\":${d(raw?.tau ?: 3.5)}$rawPart}"
    }
}
