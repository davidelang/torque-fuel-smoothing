package com.davidlang.torque.fuelsmoothing

import java.io.File
import java.util.TreeSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Median: empty → 0, odd → middle, even → mean of the two middle. */
internal object Stats {
    fun med(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }
}

internal enum class EndpointMethod {
    PIECE_FIT,
    END_LINFIT_60,
    END_LINFIT_120,
    END_MEDIAN_30,
    END_MEDIAN_60,
    END_MEDIAN_120,
    PLUGIN_SMOOTHED,
}

/**
 * Piece cutter. A part is today's fill cut; a piece is at most [MAX_SPAN] gauge points inside a part.
 * ChunkFit, BandFit, and Kalman keep their own fits. Stored rows use [ENDPOINT].
 */
internal object Pieces {
    const val FILE = "pieces.jsonl"
    const val V_MIN_KMH = 30.0
    const val A_WIN = 5
    const val A_MAX = 0.5
    const val A_HARD = 1.5
    const val HARD_HOLD = 10
    const val FROZEN_S = 60
    const val SETTLE_S = 25.0
    const val STOP_KMH = 5.0
    const val MAX_SPAN = 1.0
    const val END_MIN_READINGS = 16
    const val END_WIN_S = 60.0
    const val RHO_FALLBACK = 0.3
    const val SE_TARGET = 0.7
    const val FIT_WEAK = true
    val ENDPOINT = EndpointMethod.PIECE_FIT
    const val ANALYSIS_VERSION = 2

    enum class Side { START, END }

    class End(val level: Double, val se: Double, val n: Int)

    class Part(
        val key: String,
        val t0: Double,
        val t1: Double,
        val fStart: Double,
        val fuel: Double,
        val fillAtStart: Boolean,
        val nb: Int,
        val sigma: Double,
        val tau: Double,
        val rho: Double,
        internal val session: FuelSession?,
        internal val a: Int,
        internal val b: Int,
    )

    class Piece(
        val key: String,
        val part: String,
        val t0: Double,
        val t1: Double,
        val f0: Double,
        val f1: Double,
        val ls: Double,
        val le: Double,
        val seS: Double,
        val seE: Double,
        val nS: Int,
        val nE: Int,
        val dF: Double,
        val gapFrac: Double,
        val weak: Boolean,
        val overSpan: Boolean,
        val method: EndpointMethod,
        internal val session: FuelSession?,
        internal val i0: Int,
        internal val i1: Int,
        internal val sigma: Double,
        internal val tau: Double,
    )

    /** Quiet cruise: speed gate, smoothed accel, hard-accel hold, not a fill sample. */
    fun quietMask(spd: DoubleArray, t: DoubleArray, fill: BooleanArray): BooleanArray {
        val n = spd.size
        val a = if (n > 2) gradient(spd, t) else DoubleArray(n)
        val a5 = convolveSame(a, A_WIN)
        val block = BooleanArray(n)
        for (i in 0 until n) {
            if (abs(a[i]) > A_HARD) {
                val end = min(n, i + HARD_HOLD + 1)
                for (j in i until end) block[j] = true
            }
        }
        return BooleanArray(n) { i ->
            spd[i] >= V_MIN_KMH && abs(a5[i]) <= A_MAX && !block[i] && !fill[i]
        }
    }

    /** Long unchanged raw while moving: the gauge is stuck, not a real level. */
    fun frozenMask(raw: DoubleArray, spd: DoubleArray): BooleanArray {
        val n = raw.size
        val m = BooleanArray(n)
        var i = 0
        while (i < n) {
            var j = i
            while (j + 1 < n && raw[j + 1] == raw[i]) j++
            if (j - i + 1 >= FROZEN_S) {
                var fast = 0
                for (k in i..j) if (spd[k] > 5.0) fast++
                if (fast >= FROZEN_S / 2) {
                    for (k in i..j) m[k] = true
                }
            }
            i = j + 1
        }
        return m
    }

    /** After a stop, the next [SETTLE_S] seconds of driving are not a trusted level. */
    fun levelTrusted(s: FuelSession): BooleanArray {
        val n = s.t.size
        val out = BooleanArray(n)
        var start = Double.NaN
        for (i in 0 until n) {
            if (s.spd[i] < STOP_KMH) {
                start = Double.NaN
                continue
            }
            if (start.isNaN()) start = s.t[i]
            out[i] = s.t[i] - start >= SETTLE_S
        }
        return out
    }

    /** Today's ChunkFit.steady: quiet, settled, finite raw, not frozen. */
    fun trusted(s: FuelSession): BooleanArray {
        val q = quietMask(s.spd, s.t, s.fill)
        val ok = levelTrusted(s)
        val frozen = frozenMask(s.raw, s.spd)
        return BooleanArray(s.t.size) { q[it] && ok[it] && s.raw[it].isFinite() && !frozen[it] }
    }

    /**
     * Parts and pieces for [sessions]. [openName]'s last part is omitted (same rule as ChunkFit.segmentAll).
     * [method] changes only the endpoint levels, not the cuts.
     */
    fun cut(
        sessions: List<FuelSession>,
        openName: String? = null,
        method: EndpointMethod = ENDPOINT,
    ): Pair<List<Part>, List<Piece>> {
        val works = parts(sessions)
        val slopes = DoubleArray(works.size) { i -> if (works[i].moving) slopeOf(works[i]) else Double.NaN }
        val good = ArrayList<Double>()
        for (sl in slopes) if (sl < 0.0 && sl.isFinite()) good.add(-1.0 / sl)
        val rhoMed = if (good.isEmpty()) RHO_FALLBACK else Stats.med(good.toDoubleArray())
        val rhos = DoubleArray(works.size) { i ->
            val sl = slopes[i]
            if (!works[i].moving) Double.NaN else if (sl < 0.0 && sl.isFinite()) -1.0 / sl else rhoMed
        }
        val drafts = works.mapIndexed { i, w -> if (w.moving) cutWalk(w, rhos[i]) else emptyList() }
        val (sig, tau, nb) = noise(works, drafts)
        val lastOpen = works.lastOrNull { it.s.name == openName }?.key
        val partsOut = ArrayList<Part>()
        val piecesOut = ArrayList<Piece>()
        for (i in works.indices) {
            val w = works[i]
            if (w.key == lastOpen) continue
            val s = w.s
            val part = Part(
                w.key, s.t[w.a], s.t[w.b - 1], s.used[w.a], s.used[w.b - 1] - s.used[w.a], w.fillAtStart,
                nb, sig, tau, rhos[i], s, w.a, w.b,
            )
            partsOut.add(part)
            if (!w.moving) continue
            drafts[i].forEachIndexed { n, d ->
                val piece = finish(w, d, part, n, method, sig, tau)
                if (FIT_WEAK || d.over || !piece.weak) piecesOut.add(piece)
            }
        }
        return partsOut to piecesOut
    }

    /** Rewrites pieces.jsonl whole. Returns the number of stored parts. */
    fun store(dir: File, sessions: List<FuelSession>, openName: String? = null): Int {
        dir.mkdirs()
        val (ps, pcs) = cut(sessions, openName, ENDPOINT)
        val sb = StringBuilder()
        for (p in ps) {
            sb.append(partLine(p))
            for (c in pcs) if (c.part == p.key) sb.append(pieceLine(c))
        }
        AtomicFile.write(File(dir, FILE), sb.toString())
        return ps.size
    }

    /** Every part line and every piece line. Non-finite numbers come back as NaN. */
    fun load(dir: File): Pair<List<Part>, List<Piece>> {
        val f = File(dir, FILE)
        val ps = ArrayList<Part>()
        val pcs = ArrayList<Piece>()
        if (!f.isFile) return ps to pcs
        f.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            val key = str(line, "key") ?: return@forEachLine
            when (str(line, "type")) {
                "part" -> ps.add(
                    Part(
                        key, num(line, "t0"), num(line, "t1"), num(line, "Fstart"), num(line, "fuel"),
                        flag(line, "fillAtStart"), intNum(line, "nb"), num(line, "sigma"), num(line, "tau"),
                        num(line, "rho"), null, 0, 0,
                    ),
                )
                "piece" -> pcs.add(
                    Piece(
                        key, str(line, "part") ?: throw IllegalStateException("pieces.jsonl piece $key missing part"),
                        num(line, "t0"), num(line, "t1"), num(line, "F0"), num(line, "F1"),
                        num(line, "Ls"), num(line, "Le"), num(line, "seS"), num(line, "seE"),
                        intNum(line, "nS"), intNum(line, "nE"), num(line, "dF"), num(line, "gapFrac"),
                        flag(line, "weak"), flag(line, "overSpan"), methodOf(line),
                        null, -1, -1, Double.NaN, Double.NaN,
                    ),
                )
            }
        }
        return ps to pcs
    }

    fun endpoint(piece: Piece, side: Side, method: EndpointMethod): End {
        val s = piece.session ?: throw IllegalStateException("endpoint needs a live piece (${piece.key})")
        if (!piece.sigma.isFinite() || !piece.tau.isFinite()) {
            throw IllegalStateException("endpoint missing noise on ${piece.key}")
        }
        return endpointOf(s, piece.i0, piece.i1, piece.t0, piece.t1, piece.f0, piece.f1, piece.sigma, piece.tau, side, method)
    }

    /**
     * Non-overlapping strides of [nb] on the part's trusted-index list. Gaps inside a stride are kept.
     * Remainder dropped. Each block is median t, median raw, median (used − Fstart), block se.
     */
    fun blockMedians(part: Part, nb: Int): List<DoubleArray> {
        val s = part.session ?: throw IllegalStateException("blockMedians needs a live part (${part.key})")
        if (nb <= 0) throw IllegalStateException("blockMedians nb=$nb on ${part.key}")
        val trust = trusted(s)
        val idx = (part.a until part.b).filter { trust[it] }.toIntArray()
        val cnt = if (idx.size >= nb) (idx.size - nb) / nb + 1 else 0
        val se = 1.25 * part.sigma * sqrt(2.0 * part.tau / nb)
        val out = ArrayList<DoubleArray>(cnt)
        for (b in 0 until cnt) {
            val ii = idx.copyOfRange(b * nb, b * nb + nb)
            out.add(
                doubleArrayOf(
                    Stats.med(DoubleArray(nb) { s.t[ii[it]] }),
                    Stats.med(DoubleArray(nb) { s.raw[ii[it]] }),
                    Stats.med(DoubleArray(nb) { s.used[ii[it]] - part.fStart }),
                    se,
                ),
            )
        }
        return out
    }

    /** Today's ChunkFit.epochs, grouped by [Piece.part] (several pieces share one part). */
    fun epochs(ps: List<Part>, pieces: List<Piece>): Pair<List<DoubleArray>, List<DoubleArray>> {
        val by = pieces.groupBy { it.part }
        class Ep(val fill: Boolean, val pts: MutableList<DoubleArray> = ArrayList())
        val eps = ArrayList<Ep>()
        var cur: Ep? = null
        var off = 0.0
        for (d in ps.sortedBy { it.t0 }) {
            if (cur == null || d.fillAtStart) {
                cur = Ep(d.fillAtStart)
                eps.add(cur)
                off = 0.0
            }
            for (c in by[d.key].orEmpty()) {
                cur.pts.add(doubleArrayOf(c.t0, c.ls, off + c.f0 - d.fStart))
                cur.pts.add(doubleArrayOf(c.t1, c.le, off + c.f1 - d.fStart))
            }
            off += d.fuel
        }
        val cons = ArrayList<DoubleArray>()
        val plats = ArrayList<DoubleArray>()
        for (e in eps) {
            val pts = e.pts.filter { it[1] > FuelV2.L_LO && it[1] < FuelV2.L_HI }
                .sortedWith(compareBy<DoubleArray>({ it[0] }, { it[1] }, { it[2] }))
            if (pts.isEmpty()) continue
            val a = pts[0]
            val b = pts.minWith(compareBy<DoubleArray>({ it[1] }, { it[0] }))
            if (a[1] - b[1] >= ChunkFit.CONS_MIN_SPAN && b[2] > a[2]) cons.add(doubleArrayOf(a[1], b[1], b[2] - a[2]))
            if (e.fill) plats.add(doubleArrayOf(a[1], a[2]))
        }
        return cons to plats
    }

    // ---------------- parts (today's ChunkFit.prep / parts) ----------------

    private class Work(
        val key: String,
        val s: FuelSession,
        val a: Int,
        val b: Int,
        val moving: Boolean,
        val fillAtStart: Boolean,
        val trust: BooleanArray?,
    )

    private class Prep(val first: Double, val lastMed: Double, val gph: Double?, val cuts: List<Int>, val trust: BooleanArray)
    private val prepMemo = java.util.Collections.synchronizedMap(java.util.WeakHashMap<FuelSession, Prep>())

    private fun finiteMed(x: List<Double>): Double {
        val f = x.filter { it.isFinite() }.toDoubleArray()
        return if (f.isEmpty()) Double.NaN else Stats.med(f)
    }

    private fun movingGph(s: FuelSession): Double? {
        var cnt = 0
        var fu = 0.0
        var dt = 0.0
        for (i in 1 until s.t.size) if (s.spd[i] > 5) {
            cnt++
            fu += s.used[i] - s.used[i - 1]
            dt += s.t[i] - s.t[i - 1]
        }
        if (cnt < 120) return null
        return fu / max(dt, 1.0) * 3600
    }

    private fun prep(s: FuelSession): Prep {
        if (RollupCache.enabled) prepMemo[s]?.let { return it }
        val n = s.t.size
        val l = s.raw
        val fin = (0 until n).filter { l[it].isFinite() }
        val first = if (fin.isEmpty()) Double.NaN else finiteMed(fin.take(60).map { l[it] })
        val last = if (fin.isEmpty()) Double.NaN else finiteMed(fin.takeLast(60).map { l[it] })
        val cut = TreeSet<Int>()
        cut.add(0)
        val fi = (0 until n).filter { s.fill[it] }
        if (fi.isNotEmpty()) {
            cut.add(fi.first())
            cut.add(fi.last() + 1)
        }
        for (i in 60 until n - 60) {
            var mx = Double.NEGATIVE_INFINITY
            for (k in i - 60 until i + 60) mx = max(mx, s.spd[k])
            if (mx < 1.0) {
                val a = finiteMed((i - 60 until i).map { l[it] })
                val b = finiteMed((i until i + 60).map { l[it] })
                if (a.isFinite() && b.isFinite() && b - a >= ChunkFit.FILL_JUMP) {
                    cut.add(i)
                    break
                }
            }
        }
        val p = Prep(first, last, movingGph(s), cut.filter { it in 0 until n } + n, trusted(s))
        if (RollupCache.enabled) prepMemo[s] = p
        return p
    }

    private fun parts(sessions: List<FuelSession>): List<Work> {
        val preps = sessions.map { prep(it) }
        val gs = preps.mapNotNull { it.gph }.toDoubleArray()
        val ref = if (gs.isEmpty()) 0.0 else Stats.med(gs)
        val out = ArrayList<Work>()
        var prevEnd = Double.NaN
        for ((si, s) in sessions.withIndex()) {
            val pr = preps[si]
            val first = pr.first
            val between = prevEnd.isFinite() && first.isFinite() && first - prevEnd >= ChunkFit.FILL_JUMP
            val g = pr.gph
            val dropout = g != null && g < ChunkFit.FLOW_REL * ref
            val cuts = pr.cuts
            for (p in 0 until cuts.size - 1) {
                val a = cuts[p]
                val b = cuts[p + 1]
                val mv = (a until b).filter { s.spd[it] >= 5.0 }
                val moving = mv.isNotEmpty() && !dropout
                out.add(Work("${s.name}#$p", s, a, b, moving, p > 0 || between, if (moving) pr.trust else null))
            }
            if (pr.lastMed.isFinite()) prevEnd = pr.lastMed
        }
        return out
    }

    // ---------------- cut, noise, endpoints ----------------

    private class Draft(
        val start: Int,
        val end: Int,
        val over: Boolean,
        val f0: Double,
        val f1: Double,
        val dF: Double,
        val gapFrac: Double,
    )

    private class Ols(val n: Int, val intercept: Double, val slope: Double, val sxx: Double, val meanF: Double)

    private fun ols(used: DoubleArray, raw: DoubleArray, idx: IntArray): Ols {
        val n = idx.size
        if (n == 0) return Ols(0, Double.NaN, Double.NaN, 0.0, Double.NaN)
        var mf = 0.0
        var ml = 0.0
        for (i in idx) {
            mf += used[i]
            ml += raw[i]
        }
        mf /= n
        ml /= n
        var sxx = 0.0
        var sxy = 0.0
        for (i in idx) {
            val df = used[i] - mf
            sxx += df * df
            sxy += df * (raw[i] - ml)
        }
        val slope = if (sxx > 0.0) sxy / sxx else 0.0
        return Ols(n, ml - slope * mf, slope, sxx, mf)
    }

    private fun slopeOf(w: Work): Double {
        val trust = w.trust ?: return Double.NaN
        val idx = (w.a until w.b).filter { trust[it] }.toIntArray()
        if (idx.size < 2) return Double.NaN
        val sl = ols(w.s.used, w.s.raw, idx).slope
        return if (sl.isFinite()) sl else Double.NaN
    }

    /** Windows are exactly [END_MIN_READINGS] index-consecutive trusted samples spanning at most [END_WIN_S]. */
    private fun windows(s: FuelSession, trust: BooleanArray, a: Int, b: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        var i = a
        while (i < b) {
            if (!trust[i]) {
                i++
                continue
            }
            var j = i + 1
            while (j < b && trust[j]) j++
            val lastStart = j - END_MIN_READINGS
            if (lastStart >= i) {
                for (s0 in i..lastStart) {
                    val s1 = s0 + END_MIN_READINGS - 1
                    if (s.t[s1] - s.t[s0] <= END_WIN_S) out.add(intArrayOf(s0, s1))
                }
            }
            i = j
        }
        return out
    }

    private fun cutWalk(w: Work, rho: Double): List<Draft> {
        val s = w.s
        val trust = w.trust ?: return emptyList()
        if (!rho.isFinite()) {
            FsLog.w("FuelSmooth", "piece cut non-finite rho on ${w.key}")
            throw IllegalStateException("piece cut non-finite rho on ${w.key}")
        }
        val wins = windows(s, trust, w.a, w.b)
        if (wins.isEmpty()) return emptyList()
        val budget = MAX_SPAN * rho
        val out = ArrayList<Draft>()
        var k = 0
        while (k < wins.size) {
            val start = wins[k][0]
            val startEnd = wins[k][1]
            val f0 = s.used[start]
            val limit = f0 + budget
            val end: Int
            val over: Boolean
            if (s.used[startEnd] > limit) {
                end = startEnd
                over = true
            } else {
                var iStar = -1
                for (win in wins) {
                    if (win[1] >= startEnd && s.used[win[1]] <= limit && win[1] > iStar) iStar = win[1]
                }
                if (iStar < 0) {
                    FsLog.w("FuelSmooth", "piece cut found no window within ΔF for ${w.key} at $start")
                    throw IllegalStateException("piece cut found no window within ΔF for ${w.key} at $start")
                }
                var j = -1
                for (t in wins.indices) if (wins[t][0] >= iStar + 1) {
                    j = t
                    break
                }
                if (j < 0) {
                    end = iStar
                    over = false
                } else {
                    val jStart = wins[j][0]
                    var any = false
                    var allUntrusted = true
                    for (i in iStar + 1 until jStart) {
                        any = true
                        if (trust[i]) {
                            allUntrusted = false
                            break
                        }
                    }
                    if (any && allUntrusted) {
                        end = wins[j][1]
                        over = true
                    } else {
                        end = iStar
                        over = false
                    }
                }
            }
            val f1 = s.used[end]
            val dF = f1 - f0
            var gapFuel = 0.0
            for (i in start + 1..end) if (!trust[i]) gapFuel += max(0.0, s.used[i] - s.used[i - 1])
            val gapFrac = if (dF == 0.0) 0.0 else gapFuel / dF
            out.add(Draft(start, end, over, f0, f1, dF, gapFrac))
            var next = wins.size
            for (t in wins.indices) if (wins[t][0] >= end + 1) {
                next = t
                break
            }
            k = next
        }
        return out
    }

    private fun noise(works: List<Work>, drafts: List<List<Draft>>): Triple<Double, Double, Int> {
        val res = ArrayList<Double>()
        val taus = ArrayList<Double>()
        for (i in works.indices) {
            val w = works[i]
            val trust = w.trust ?: continue
            for (d in drafts[i]) {
                val idx = (d.start..d.end).filter { trust[it] }.toIntArray()
                if (idx.isEmpty()) continue
                val o = ols(w.s.used, w.s.raw, idx)
                val r = DoubleArray(idx.size) { k -> w.s.raw[idx[k]] - (o.intercept + o.slope * w.s.used[idx[k]]) }
                for (v in r) res.add(v)
                var st = 0
                for (k in 1..idx.size) {
                    if (k == idx.size || idx[k] != idx[k - 1] + 1) {
                        val len = k - st
                        if (len >= 10) taus.add(lagTau(r, st, len))
                        st = k
                    }
                }
            }
        }
        val sig: Double
        val tau: Double
        if (taus.isEmpty()) {
            sig = 1.0
            tau = 3.0
        } else {
            val arr = res.toDoubleArray()
            val m = Stats.med(arr)
            val mad = Stats.med(DoubleArray(arr.size) { abs(arr[it] - m) })
            sig = 1.4826 * mad
            tau = Stats.med(taus.toDoubleArray())
        }
        val nb = max(ceil(2.0 * tau * (sig / SE_TARGET) * (sig / SE_TARGET)).toInt(), 10)
        return Triple(sig, tau, nb)
    }

    private fun lagTau(x: DoubleArray, st: Int, len: Int): Double {
        val ma = (0 until len - 1).sumOf { x[st + it] } / (len - 1)
        val mb = (1 until len).sumOf { x[st + it] } / (len - 1)
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in 0 until len - 1) {
            val a = x[st + i] - ma
            val b = x[st + i + 1] - mb
            sab += a * b
            saa += a * a
            sbb += b * b
        }
        val den = sqrt(saa * sbb)
        val r1 = if (den > 0.0) sab / den else 0.0
        return -1.0 / ln(min(max(r1, 0.05), 0.99))
    }

    private fun finish(w: Work, d: Draft, part: Part, n: Int, method: EndpointMethod, sig: Double, tau: Double): Piece {
        val s = w.s
        val t0 = s.t[d.start]
        val t1 = s.t[d.end]
        val shell = Piece(
            "${part.key}#$n", part.key, t0, t1, d.f0, d.f1, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            0, 0, d.dF, d.gapFrac, false, d.over, method, s, d.start, d.end, sig, tau,
        )
        val a = endpoint(shell, Side.START, method)
        val b = endpoint(shell, Side.END, method)
        val weak = b.level - a.level > 0.0 && d.dF > 0.0
        return Piece(
            shell.key, shell.part, t0, t1, d.f0, d.f1, a.level, b.level, a.se, b.se, a.n, b.n,
            d.dF, d.gapFrac, weak, d.over, method, s, d.start, d.end, sig, tau,
        )
    }

    private fun sideSeconds(method: EndpointMethod): Double = when (method) {
        EndpointMethod.END_LINFIT_60, EndpointMethod.END_MEDIAN_60 -> 60.0
        EndpointMethod.END_LINFIT_120, EndpointMethod.END_MEDIAN_120 -> 120.0
        EndpointMethod.END_MEDIAN_30 -> 30.0
        else -> Double.NaN
    }

    private fun endpointOf(
        s: FuelSession,
        i0: Int,
        i1: Int,
        t0: Double,
        t1: Double,
        f0: Double,
        f1: Double,
        sigma: Double,
        tau: Double,
        side: Side,
        method: EndpointMethod,
    ): End {
        val trust = trusted(s)
        val m = (t0 + t1) / 2.0
        val idx = ArrayList<Int>()
        for (i in i0..i1) {
            if (!trust[i]) continue
            val t = s.t[i]
            val keep = when (method) {
                EndpointMethod.PIECE_FIT -> true
                EndpointMethod.PLUGIN_SMOOTHED ->
                    if (side == Side.START) t >= t0 && t < m else t >= m && t <= t1
                else -> {
                    val w = sideSeconds(method)
                    if (side == Side.START) t >= t0 && t < min(t0 + w, m) else t >= max(t1 - w, m) && t <= t1
                }
            }
            if (!keep) continue
            if (method == EndpointMethod.PLUGIN_SMOOTHED && !s.sm[i].isFinite()) continue
            idx.add(i)
        }
        if (idx.isEmpty()) return End(Double.NaN, Double.NaN, 0)
        val ii = idx.toIntArray()
        if (method == EndpointMethod.END_MEDIAN_30 || method == EndpointMethod.END_MEDIAN_60 ||
            method == EndpointMethod.END_MEDIAN_120 || method == EndpointMethod.PLUGIN_SMOOTHED
        ) {
            val vals = DoubleArray(ii.size) { k ->
                if (method == EndpointMethod.PLUGIN_SMOOTHED) s.sm[ii[k]] else s.raw[ii[k]]
            }
            val se = sigma * sqrt(2.0 * tau / ii.size)
            return End(Stats.med(vals), se, ii.size)
        }
        val o = ols(s.used, s.raw, ii)
        val fEnd = if (side == Side.START) f0 else f1
        val level = o.intercept + o.slope * fEnd
        val se = if (o.sxx == 0.0) sigma / sqrt(o.n.toDouble())
        else sigma * sqrt(1.0 / o.n + (fEnd - o.meanF) * (fEnd - o.meanF) / o.sxx)
        return End(level, se, o.n)
    }

    private fun gradient(v: DoubleArray, t: DoubleArray): DoubleArray {
        val n = v.size
        val g = DoubleArray(n)
        if (n <= 1) return g
        val d0 = t[1] - t[0]
        g[0] = if (d0 != 0.0) (v[1] - v[0]) / d0 else 0.0
        val dN = t[n - 1] - t[n - 2]
        g[n - 1] = if (dN != 0.0) (v[n - 1] - v[n - 2]) / dN else 0.0
        for (i in 1 until n - 1) {
            val h0 = t[i] - t[i - 1]
            val h1 = t[i + 1] - t[i]
            val den = h0 * h1 * (h0 + h1)
            g[i] = if (den == 0.0) 0.0 else (h0 * h0 * v[i + 1] + (h1 * h1 - h0 * h0) * v[i] - h1 * h1 * v[i - 1]) / den
        }
        return g
    }

    private fun convolveSame(a: DoubleArray, win: Int): DoubleArray {
        val n = a.size
        val out = DoubleArray(n)
        val half = win / 2
        val scale = 1.0 / win
        for (i in 0 until n) {
            var s = 0.0
            for (k in 0 until win) {
                val j = i + k - half
                if (j in 0 until n) s += a[j]
            }
            out[i] = s * scale
        }
        return out
    }

    private fun d(x: Double) = if (x.isFinite()) x.toString() else "null"

    private fun partLine(p: Part): String =
        "{\"type\":\"part\",\"v\":$ANALYSIS_VERSION,\"key\":\"${p.key}\",\"t0\":${d(p.t0)},\"t1\":${d(p.t1)}," +
            "\"Fstart\":${d(p.fStart)},\"fuel\":${d(p.fuel)},\"fillAtStart\":${p.fillAtStart},\"nb\":${p.nb}," +
            "\"sigma\":${d(p.sigma)},\"tau\":${d(p.tau)},\"rho\":${d(p.rho)}}\n"

    private fun pieceLine(c: Piece): String =
        "{\"type\":\"piece\",\"v\":$ANALYSIS_VERSION,\"key\":\"${c.key}\",\"part\":\"${c.part}\"," +
            "\"t0\":${d(c.t0)},\"t1\":${d(c.t1)},\"F0\":${d(c.f0)},\"F1\":${d(c.f1)},\"Ls\":${d(c.ls)},\"Le\":${d(c.le)}," +
            "\"seS\":${d(c.seS)},\"seE\":${d(c.seE)},\"nS\":${c.nS},\"nE\":${c.nE},\"dF\":${d(c.dF)},\"gapFrac\":${d(c.gapFrac)}," +
            "\"weak\":${c.weak},\"overSpan\":${c.overSpan},\"method\":\"${c.method.name}\"}\n"

    private fun num(line: String, k: String): Double {
        val m = Regex("\"$k\":\\s*(-?[0-9.eE+-]+|null)").find(line)
            ?: throw IllegalStateException("pieces.jsonl missing $k")
        val g = m.groupValues[1]
        if (g == "null") return Double.NaN
        return g.toDoubleOrNull() ?: throw IllegalStateException("pieces.jsonl bad $k ($g)")
    }

    private fun intNum(line: String, k: String): Int {
        val v = num(line, k)
        if (!v.isFinite()) throw IllegalStateException("pieces.jsonl non-finite $k")
        return v.toInt()
    }

    private fun flag(line: String, k: String): Boolean {
        if (line.contains("\"$k\":true")) return true
        if (line.contains("\"$k\":false")) return false
        throw IllegalStateException("pieces.jsonl missing $k")
    }

    private fun str(s: String, k: String): String? =
        Regex("\"$k\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(s)?.groupValues?.get(1)

    private fun methodOf(line: String): EndpointMethod {
        val name = str(line, "method") ?: throw IllegalStateException("pieces.jsonl piece missing method")
        return try {
            EndpointMethod.valueOf(name)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("pieces.jsonl unknown method $name")
        }
    }
}
