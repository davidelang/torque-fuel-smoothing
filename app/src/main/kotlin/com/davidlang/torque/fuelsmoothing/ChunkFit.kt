package com.davidlang.torque.fuelsmoothing

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
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

    // ---------------- helpers (match numpy) ----------------
    fun steady(s: FuelSession): BooleanArray = Pieces.trusted(s)

    /**
     * Analysis-only level mask (equivalent to lvl=-1; raw data is untouched and fuel always counts): false during
     * the hard accel/brake blanking (HARD_HOLD, applied in [q]) and for the first SETTLE_S seconds of driving after any stop.
     */
    fun levelTrusted(s: FuelSession, q: BooleanArray = FuelV2.quietMask(s.spd, s.t, s.fill)): BooleanArray =
        Pieces.levelTrusted(s)

    /**
     * One [Part] per piece-part and one [Chunk] per included piece, in time order.
     * [Part.dens0] is the median finite rho of moving parts (NaN rho is non-moving), stamped on every part.
     * A piece is included when it is overSpan, [Pieces.FIT_WEAK] is set, or it is not weak.
     */
    fun fromPieces(parts: List<Pieces.Part>, pieces: List<Pieces.Piece>): Pair<List<Part>, List<Chunk>> {
        val rhos = parts.map { it.rho }.filter { it.isFinite() }
        val dens0 = if (rhos.isEmpty()) Double.NaN else Stats.med(rhos.toDoubleArray())
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
                val sc = 1.4826 * Stats.med(rs) + 1e-9
                for (r in 0 until nr) hub[r] = min(1.0, huberK * sc / max(rs[r], 1e-12))
            }
            val un = DoubleArray(k) { max(1.0 / h[it], U_MIN) }
            var d = 0.0; for (i in 0 until k) d = max(d, abs(un[i] - u[i]))
            u = un
            if (d < 1e-4) return DoubleArray(k) { u[it] * s0 }
        }
        return DoubleArray(k) { u[it] * s0 }
    }

    /** Part key, times, Ls/Le, F0/F1, part fuel, and fillAtStart for [Pieces.epochs]. */
    private fun epochsOf(ps: List<Part>, ch: List<Chunk>): Pair<List<DoubleArray>, List<DoubleArray>> {
        val parts = ps.map { p ->
            Pieces.Part(p.key, p.t0, p.t1, p.fStart, p.fuel, p.fillAtStart, 0, Double.NaN, Double.NaN, Double.NaN, null, 0, 0)
        }
        val pieces = ch.map { c ->
            Pieces.Piece(
                c.key, c.key, c.t0, c.t1, c.f0, c.f1, c.ls, c.le, Double.NaN, Double.NaN, 0, 0, c.dF, Double.NaN,
                false, false, Pieces.ENDPOINT, null, -1, -1, Double.NaN, Double.NaN,
            )
        }
        return Pieces.epochs(parts, pieces)
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

    fun fit(ps: List<Part>, ch: List<Chunk>, nameplate: Double?, huberK: Double = HUBER_K): Result? {
        val dens0 = ps.lastOrNull()?.dens0 ?: Double.NaN
        val t0 = if (dens0.isFinite()) 100 * dens0 else nameplate?.takeIf { it > 0 } ?: return null
        val grp = groups(ch)
        val gUnc = fitInv(grp, t0 / 100.0, huberK)
        val (cons, plats) = epochsOf(ps, ch)
        val (g, active) = project(gUnc, cons)
        val v95 = vAt(FuelV2.L_HI, g)
        val pl = plats.map { max(it[1] - (v95 - vAt(it[0], g)), 0.0) }
        val cap = v95 + if (pl.isNotEmpty()) Stats.med(pl.toDoubleArray()) else 0.07 * v95
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
