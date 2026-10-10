package com.davidlang.torque.fuelsmoothing

import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

internal class FuelSession(
    val name: String,
    val t: DoubleArray,
    val raw: DoubleArray,
    val sm: DoubleArray,
    val used: DoubleArray,
    val spd: DoubleArray,
    val fill: BooleanArray,
)

internal class FuelChunk(
    val g: Int,
    val f: Double,
    val l: Double,
    val n: Int,
    val t: Double,
)

internal class FuelModel(
    val edges: DoubleArray,
    val g: DoubleArray,
    val gUnc: DoubleArray,
    val se: DoubleArray,
    val active: IntArray,
    val sigmaQ: Double,
    val tau: Double,
    val nMin: Int,
    val chunks: Int,
    val chunksByName: Map<String, Int>,
    val dropped: IntArray,
    val fills: List<String>,
    val capToZero: Double,
    val json: String,
) {
    fun v(level: Double): Double = FuelV2.vAt(level, edges, g)

    fun v95(): Double = v(FuelV2.L_HI)
}

internal class TankLedger {
    var rHat: Double = Double.NaN
    var anchored: Boolean = false
}

internal data class TankPublish(
    val rHat: Double,
    val remaining: Double,
    val correctedPct: Double,
    val mpgRecent: Double,
    val dte: Double,
    val dteGauge0: Double,
)

/**
 * Scale-free tank map. Replay follows run2.py: segment.fix_units, then algo2 inverse fit.
 * Logged speed is compared as stored. Live callers convert to km/h before the gates.
 */
internal object FuelV2 {
    const val V_MIN = 30.0
    const val A_WIN = 5
    const val A_MAX = 0.5
    const val A_HARD = 1.5
    const val HARD_HOLD = 10
    const val FROZEN_S = 60
    const val C_PCT = 1.0
    const val C_MAX_PCT = 5.0
    const val SE_TARGET = 0.7
    const val CELL = 2.5
    const val E_MIN = 10.0
    const val G_MIN_GROUPS = 3
    const val Z_MERGE = 1.0
    const val MAX_W = 25.0
    const val L_LO = 0.5
    const val L_HI = 95.0
    const val U_MIN = 0.06
    const val H_MIN = 0.2
    const val LAM_SMOOTH = 0.5
    const val LAM_PRIOR = 0.02
    const val HUBER_K = 1.5
    const val INV_ITERS = 5
    private const val IRLS = 6
    private const val LITER_FACTOR = 0.264172052
    const val KM_PER_MILE = 1.609344

    fun loadSessions(dir: File): List<FuelSession> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyList()
        val out = ArrayList<FuelSession>()
        for (file in files.sortedBy { it.name }) {
            val t = ArrayList<Double>()
            val raw = ArrayList<Double>()
            val sm = ArrayList<Double>()
            val used = ArrayList<Double>()
            val spd = ArrayList<Double>()
            val fill = ArrayList<Boolean>()
            file.forEachLine { line ->
                val s = line.trim()
                if (s.isEmpty() || s.contains("\"event\"") || !s.contains("\"t\"")) return@forEachLine
                val ms = num(s, "t") ?: return@forEachLine
                t.add(ms / 1000.0)
                val rawPct = num(s, "rawPct") ?: num(s, "lvl") ?: Double.NaN
                raw.add(rawPct)
                sm.add(num(s, "smoothedPct") ?: num(s, "sm") ?: Double.NaN)
                used.add(num(s, "fuelUsedGal") ?: num(s, "fuelGal") ?: 0.0)
                spd.add(num(s, "speed") ?: num(s, "spd") ?: 0.0)
                fill.add(
                    s.contains("\"fill\":true") || s.contains("\"fill\": true") || s.contains("\"mode\":\"FILL\""),
                )
            }
            if (t.size < 2) continue
            out.add(
                FuelSession(
                    name = file.name.removeSuffix(".jsonl"),
                    t = t.toDoubleArray(),
                    raw = raw.toDoubleArray(),
                    sm = sm.toDoubleArray(),
                    used = used.toDoubleArray(),
                    spd = spd.toDoubleArray(),
                    fill = fill.toBooleanArray(),
                ),
            )
        }
        return out
    }

    fun replay(sessions: List<FuelSession>, scale: Double, t0: Double): FuelModel {
        val s = sessions.map { copyScaled(fixUnits(it), scale) }
        val noiseOn = if (s.size > 60) s.subList(51, s.size) else s.drop(13.coerceAtMost(s.size))
        val (sigQ, tau) = noiseStats(noiseOn)
        val (nMin, iqrMax) = derived(sigQ, tau)
        val ref = refGph(s)
        val ok = BooleanArray(s.size) { flowOkRel(s[it], ref) }
        val dropped = ArrayList<Int>()
        for (i in ok.indices) if (!ok[i]) dropped.add(i)
        var chunks = build(s, ok, { t0 / 100.0 }, nMin, iqrMax)
        var fit = fitAll(chunks, t0, emptyList())
        val gpp = { level: Double -> densityAt(level, fit.edges, fit.g) }
        chunks = build(s, ok, gpp, nMin, iqrMax)
        val fe = Array(s.size) { fillEnd(s[it]) }
        val ep = epochs(ok, fe)
        val cons = constraints(s, chunks, fe, ep)
        fit = fitAll(chunks, t0, cons)
        val cap = capToZero(s, chunks, fe, fit)
        val byName = HashMap<String, Int>()
        var nch = 0
        val fills = ArrayList<String>()
        for (k in s.indices) {
            val n = chunks[k]?.size ?: 0
            byName[s[k].name] = n
            nch += n
            if (fe[k] != null) fills.add(s[k].name)
        }
        val json = modelJson(fit, cap, sigQ, tau, nMin, nch)
        return FuelModel(
            edges = fit.edges,
            g = fit.g,
            gUnc = fit.gUnc,
            se = fit.se,
            active = fit.active,
            sigmaQ = sigQ,
            tau = tau,
            nMin = nMin,
            chunks = nch,
            chunksByName = byName,
            dropped = dropped.toIntArray(),
            fills = fills,
            capToZero = cap,
            json = json,
        )
    }

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
            spd[i] >= V_MIN && abs(a5[i]) <= A_MAX && !block[i] && !fill[i]
        }
    }

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

    fun chunksSf(
        s: FuelSession,
        gpp: (Double) -> Double,
        nMin: Int,
        iqrMax: Double,
    ): List<FuelChunk> = chunksOf(s, gpp, nMin, iqrMax)

    fun gpp(level: Double, edges: DoubleArray, g: DoubleArray): Double = densityAt(level, edges, g)

    /** Nameplate, else 100× median ΔF/ΔL on the first drives spanning ≥ 5%, else 30. */
    fun chooseT0(nameplate: Double?, sessions: List<FuelSession>): Double {
        if (nameplate != null && nameplate > 0.0) return nameplate
        val ratios = ArrayList<Double>()
        for (s in sessions) {
            if (s.sm.isEmpty() || s.used.isEmpty()) continue
            var lo = s.sm[0]
            var hi = s.sm[0]
            for (v in s.sm) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            if (hi - lo < 5.0) continue
            val dL = s.sm.first() - s.sm.last()
            val dF = s.used.last() - s.used.first()
            if (dL > 0.5 && dF > 0.0) ratios.add(dF / dL)
        }
        if (ratios.isEmpty()) return 30.0
        return 100.0 * median(ratios.toDoubleArray())
    }

    fun fuelSuspect(session: FuelSession, peers: List<FuelSession>): Boolean {
        return !flowOkRel(session, refGph(peers))
    }

    fun vAt(level: Double, edges: DoubleArray, g: DoubleArray): Double {
        var sum = 0.0
        for (k in g.indices) {
            val w = edges[k + 1] - edges[k]
            sum += (level - edges[k]).coerceIn(0.0, w) * g[k]
        }
        return sum
    }

    fun stepSample(state: TankLedger, dF: Double) {
        if (state.anchored && dF.isFinite()) state.rHat -= dF
    }

    fun stepFill(state: TankLedger, level: Double, levelAfter: Double, v: (Double) -> Double, v95: Double, hEst: Double) {
        state.rHat = if (level >= L_HI) v95 + hEst else v(levelAfter)
        state.anchored = true
    }

    fun stepChunk(state: TankLedger, level: Double, since: Double, vLevel: Double, v95: Double) {
        if (!state.anchored) return
        when {
            level > L_LO && level < L_HI -> state.rHat += 0.2 * ((vLevel - since) - state.rHat)
            level >= L_HI -> state.rHat = max(state.rHat, v95 - since)
            level <= L_LO -> state.rHat = min(state.rHat, 0.0)
        }
    }

    fun publish(state: TankLedger, cap: Double, reserve: Double, mpgRecent: Double): TankPublish {
        val r = if (state.rHat.isFinite()) state.rHat else 0.0
        val remaining = r + reserve
        val corrected = if (cap > 0.0) (100.0 * r / cap).coerceIn(0.0, 100.0) else 0.0
        return TankPublish(r, remaining, corrected, mpgRecent, remaining * mpgRecent, r * mpgRecent)
    }

    fun reserveGal(nameplate: Double?, cap: Double, rObs: Double): Double {
        return if (nameplate != null && nameplate > 0.0) max(rObs, nameplate - cap) else rObs
    }

    private class Fit(
        val edges: DoubleArray,
        val g: DoubleArray,
        val gUnc: DoubleArray,
        val se: DoubleArray,
        val active: IntArray,
    )

    private fun copyScaled(s: FuelSession, scale: Double): FuelSession {
        val used = DoubleArray(s.used.size) { s.used[it] * scale }
        return FuelSession(s.name, s.t, s.raw, s.sm, used, s.spd, s.fill)
    }

    /** segment.fix_units. No date cutoff. Idle is more than 60 steps, not seconds. */
    private fun fixUnits(s: FuelSession): FuelSession {
        val n = s.t.size
        if (n < 2) return s
        var idleDu = 0.0
        var idleDt = 0.0
        var idleN = 0
        for (i in 1 until n) {
            val d = s.t[i] - s.t[i - 1]
            if (s.spd[i] < 1.0 && d < 2.0) {
                idleDu += s.used[i] - s.used[i - 1]
                idleDt += d
                idleN++
            }
        }
        val idle = if (idleN > 60) idleDu / max(idleDt, 1.0) * 3600.0 else null
        val h = (s.t[n - 1] - s.t[0]) / 3600.0
        val g = s.used[n - 1] - s.used[0]
        val scale = (idle != null && idle > 2.0) || (idle == null && h > 0.0 && g / h > 8.0)
        if (!scale) return s
        val used = DoubleArray(n) { s.used[it] * LITER_FACTOR }
        return FuelSession(s.name, s.t, s.raw, s.sm, used, s.spd, s.fill)
    }

    private fun noiseStats(list: List<FuelSession>): Pair<Double, Double> {
        val sds = ArrayList<Double>()
        val taus = ArrayList<Double>()
        for (s in list) {
            val q = quietMask(s.spd, s.t, s.fill)
            val idx = ArrayList<Int>()
            for (i in q.indices) if (q[i]) idx.add(i)
            var start = 0
            for (p in 0..idx.size) {
                val split = p == idx.size || (p > 0 && idx[p] - idx[p - 1] > 1)
                if (!split) continue
                val len = p - start
                if (len >= 120) {
                    val x = DoubleArray(len) { s.raw[idx[start + it]] }
                    val t = DoubleArray(len) { it.toDouble() }
                    detrend(t, x)
                    sds.add(stdPop(x))
                    taus.add(tauOf(x))
                }
                start = p
            }
        }
        if (sds.isEmpty()) return 1.1 to 3.5
        return median(sds.toDoubleArray()) to median(taus.toDoubleArray())
    }

    private fun tauOf(x: DoubleArray): Double {
        val invE = exp(-1.0)
        for (lag in 1 until 60) {
            val a = corr(x, lag)
            if (a < invE) return lag.toDouble()
        }
        return 60.0
    }

    private fun derived(sig: Double, tau: Double): Pair<Int, Double> {
        val nMin = ceil(2.0 * tau * sq(1.25 * sig / SE_TARGET)).toInt()
        return max(nMin, 10) to 4.0 * sig
    }

    private fun refGph(list: List<FuelSession>): Double {
        val rates = ArrayList<Double>()
        for (s in list) {
            var fast = 0
            for (v in s.spd) if (v > 5.0) fast++
            if (fast <= 120) continue
            var du = 0.0
            var dt = 0.0
            for (i in 1 until s.t.size) {
                if (s.spd[i] > 5.0) {
                    du += s.used[i] - s.used[i - 1]
                    dt += s.t[i] - s.t[i - 1]
                }
            }
            if (dt > 0.0) rates.add(du / dt * 3600.0)
        }
        if (rates.isEmpty()) return 1.0
        return median(rates.toDoubleArray())
    }

    private fun flowOkRel(s: FuelSession, ref: Double): Boolean {
        var n = 0
        var du = 0.0
        var dt = 0.0
        for (i in 1 until s.t.size) {
            if (s.spd[i] > 5.0) {
                n++
                du += s.used[i] - s.used[i - 1]
                dt += s.t[i] - s.t[i - 1]
            }
        }
        if (n < 120) return true
        if (dt <= 0.0) return true
        return du / dt * 3600.0 > 0.25 * ref
    }

    private fun build(
        sessions: List<FuelSession>,
        ok: BooleanArray,
        gpp: (Double) -> Double,
        nMin: Int,
        iqrMax: Double,
    ): Array<List<FuelChunk>?> {
        return Array(sessions.size) { k ->
            if (!ok[k]) null else chunksOf(sessions[k], gpp, nMin, iqrMax)
        }
    }

    private fun chunksOf(
        s: FuelSession,
        gpp: (Double) -> Double,
        nMin: Int,
        iqrMax: Double,
    ): List<FuelChunk> {
        val q0 = quietMask(s.spd, s.t, s.fill)
        val frozen = frozenMask(s.raw, s.spd)
        val q = BooleanArray(s.t.size) { q0[it] && !frozen[it] }
        val out = ArrayList<FuelChunk>()
        val cur = ArrayList<Int>()
        var grp = 0
        var f0: Double? = null
        var fillSeen = false
        fun emit() {
            if (cur.size >= nMin) {
                val lv = DoubleArray(cur.size) { s.raw[cur[it]] }
                val iqr = percentile(lv, 75.0) - percentile(lv, 25.0)
                if (iqr <= iqrMax) {
                    val ff = DoubleArray(cur.size) { s.used[cur[it]] }
                    val tt = DoubleArray(cur.size) { s.t[cur[it]] }
                    out.add(FuelChunk(grp, median(ff), median(lv), cur.size, median(tt)))
                }
            }
        }
        for (i in s.used.indices) {
            if (s.fill[i]) {
                fillSeen = true
                continue
            }
            if (fillSeen) {
                emit()
                cur.clear()
                f0 = null
                grp++
                fillSeen = false
            }
            if (f0 == null) f0 = s.used[i]
            val ref = when {
                out.isNotEmpty() -> out.last().l
                cur.isNotEmpty() -> median(DoubleArray(cur.size) { s.raw[cur[it]] })
                else -> s.raw[i]
            }
            val burn = s.used[i] - (f0 ?: s.used[i])
            val unit = gpp(ref)
            if ((burn >= C_PCT * unit && cur.size >= nMin) || burn >= C_MAX_PCT * unit) {
                emit()
                cur.clear()
                f0 = s.used[i]
            }
            if (q[i]) cur.add(i)
        }
        emit()
        for (k in 1 until out.size) {
            if (out[k].l - out[k - 1].l >= 10.0 && out[k].g == out[k - 1].g) {
                for (j in k until out.size) {
                    val c = out[j]
                    out[j] = FuelChunk(c.g + 1, c.f, c.l, c.n, c.t)
                }
            }
        }
        return out
    }

    private fun fillEnd(s: FuelSession): Int? {
        val r = s.raw
        val n = r.size
        val flags = ArrayList<Int>()
        for (i in s.fill.indices) if (s.fill[i]) flags.add(i)
        if (flags.isNotEmpty()) {
            val i0 = flags.first()
            val i1 = flags.last()
            val pre = if (i0 > 0) median(r.copyOfRange(max(0, i0 - 30), i0)) else Double.NaN
            val post = if (i1 + 1 < n) median(r.copyOfRange(i1 + 1, min(n, i1 + 31))) else Double.NaN
            if (pre.isNaN() || post - pre >= 10.0) return i1
            return null
        }
        if (n < 120) return null
        for (i in 60 until n - 60) {
            if (r[i] - r[i - 1] < 5.0) continue
            var parked = true
            for (j in i - 60 until i + 60) {
                if (s.spd[j] >= 1.0) {
                    parked = false
                    break
                }
            }
            if (!parked) continue
            val rise = median(r.copyOfRange(i, i + 60)) - median(r.copyOfRange(i - 60, i))
            if (rise >= 10.0) return min(i + 60, n - 1)
        }
        return null
    }

    private fun epochs(ok: BooleanArray, fe: Array<Int?>): IntArray {
        val ep = IntArray(ok.size) { -1 }
        var cur = -1
        for (k in ok.indices) {
            if (!ok[k]) continue
            if (fe[k] != null) cur += 1 else if (cur < 0) cur = 0
            ep[k] = cur
        }
        return ep
    }

    private data class FL(val f: DoubleArray, val l: DoubleArray, val k: Int)

    private fun groupsOf(chunks: Array<List<FuelChunk>?>): List<FL> {
        val g = ArrayList<FL>()
        for (k in chunks.indices) {
            val cs = chunks[k] ?: continue
            val ids = cs.map { it.g }.toSortedSet()
            for (id in ids) {
                val cc = cs.filter { it.g == id }
                g.add(FL(DoubleArray(cc.size) { cc[it].f }, DoubleArray(cc.size) { cc[it].l }, k))
            }
        }
        return g
    }

    private fun fitAll(chunks: Array<List<FuelChunk>?>, t0: Double, cons: List<Triple<Double, Double, Double>>): Fit {
        val groups = groupsOf(chunks)
        val levels = groups.filter { it.l.size > 1 }.map { it.l }
        val (cells, e, gc) = evidence(levels)
        val edges = countMerge(cells, e, gc)
        return significanceMergeInv(groups.map { it.f to it.l }, edges, t0, cons)
    }

    private fun evidence(groups: List<DoubleArray>): Triple<DoubleArray, DoubleArray, IntArray> {
        val cells = ArrayList<Double>()
        var x = 0.0
        while (x <= L_HI + 1e-9) {
            cells.add(x)
            x += CELL
        }
        val c = cells.toDoubleArray()
        val nc = c.size - 1
        val e = DoubleArray(nc)
        val g = IntArray(nc)
        for (L in groups) {
            if (L.isEmpty()) continue
            var lo = L[0]
            var hi = L[0]
            for (v in L) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            lo = max(lo, L_LO)
            hi = min(hi, L_HI)
            for (i in 0 until nc) {
                val ov = (min(c[i + 1], hi) - max(c[i], lo)).coerceAtLeast(0.0)
                e[i] += ov
                if (ov > 0.0) g[i]++
            }
        }
        return Triple(c, e, g)
    }

    private fun countMerge(cells: DoubleArray, eIn: DoubleArray, gIn: IntArray): DoubleArray {
        val edges = ArrayList<Double>()
        edges.add(cells[0])
        var e = 0.0
        var gmax = 0
        for (c in eIn.indices) {
            e += eIn[c]
            gmax = max(gmax, gIn[c])
            val w = cells[c + 1] - edges.last()
            if ((e >= E_MIN && gmax >= G_MIN_GROUPS) || w >= MAX_W) {
                edges.add(cells[c + 1])
                e = 0.0
                gmax = 0
            }
        }
        if (edges.last() < cells.last()) {
            if (edges.size > 1) edges[edges.lastIndex] = cells.last() else edges.add(cells.last())
        }
        return edges.toDoubleArray()
    }

    private fun significanceMergeInv(
        groups: List<Pair<DoubleArray, DoubleArray>>,
        edges0: DoubleArray,
        t0: Double,
        cons: List<Triple<Double, Double, Double>>,
    ): Fit {
        var edges = edges0
        val limit = edges.size
        var fit = fitInv(groups, edges, t0, cons)
        for (step in 0 until limit) {
            fit = fitInv(groups, edges, t0, cons)
            if (edges.size <= 3) return fit
            val z = DoubleArray(fit.gUnc.size - 1)
            for (i in z.indices) {
                val wsum = (edges[i + 1] - edges[i]) + (edges[i + 2] - edges[i + 1])
                z[i] = if (wsum > MAX_W) {
                    Double.POSITIVE_INFINITY
                } else {
                    abs(fit.gUnc[i + 1] - fit.gUnc[i]) / sqrt(sq(fit.se[i]) + sq(fit.se[i + 1]))
                }
            }
            var j = 0
            for (i in 1 until z.size) if (z[i] < z[j]) j = i
            if (z[j] >= Z_MERGE) return fit
            val next = DoubleArray(edges.size - 1)
            var p = 0
            for (i in edges.indices) if (i != j + 1) next[p++] = edges[i]
            edges = next
        }
        return fitInv(groups, edges, t0, cons)
    }

    private fun fitInv(
        groupsIn: List<Pair<DoubleArray, DoubleArray>>,
        edges: DoubleArray,
        t0: Double,
        cons: List<Triple<Double, Double, Double>>,
    ): Fit {
        val s0 = t0 / 100.0
        val wEdge = DoubleArray(edges.size - 1) { edges[it + 1] - edges[it] }
        val k = wEdge.size
        var u = DoubleArray(k) { 1.0 }
        val grp = ArrayList<Pair<DoubleArray, DoubleArray>>()
        for ((f, l) in groupsIn) {
            val keep = ArrayList<Int>()
            for (i in l.indices) if (l[i] > L_LO && l[i] < L_HI) keep.add(i)
            if (keep.size < 2) continue
            var lo = l[keep[0]]
            var hi = lo
            for (i in keep) {
                if (l[i] < lo) lo = l[i]
                if (l[i] > hi) hi = l[i]
            }
            if (hi - lo < 1.0) continue
            grp.add(
                DoubleArray(keep.size) { f[keep[it]] / s0 } to DoubleArray(keep.size) { l[keep[it]] },
            )
        }
        var mLast = ident(k)
        var wLast = DoubleArray(0)
        var rLast = DoubleArray(0)
        var bLast = DoubleArray(0)
        for (outer in 0 until INV_ITERS) {
            val cap = DoubleArray(k) { u[it] * wEdge[it] }
            val qk = DoubleArray(k + 1)
            for (i in 0 until k) qk[i + 1] = qk[i] + cap[i]
            val rows = ArrayList<DoubleArray>()
            val rhs = ArrayList<Double>()
            for ((f, l) in grp) {
                val vl = DoubleArray(l.size) { basisDot(l[it], edges, u) }
                val cmed = median(DoubleArray(l.size) { vl[it] + f[it] })
                val psi = Array(l.size) { i ->
                    val q = cmed - f[i]
                    DoubleArray(k) { j -> (q - qk[j]).coerceIn(0.0, cap[j]) }
                }
                val meanPsi = DoubleArray(k)
                for (row in psi) for (j in 0 until k) meanPsi[j] += row[j]
                for (j in 0 until k) meanPsi[j] /= psi.size
                var meanL = 0.0
                for (v in l) meanL += v
                meanL /= l.size
                for (i in l.indices) {
                    rows.add(DoubleArray(k) { j -> psi[i][j] - meanPsi[j] })
                    rhs.add(l[i] - meanL)
                }
            }
            val a = rows.toTypedArray()
            val b = rhs.toDoubleArray()
            val dtd = gram(diffEye(k))
            val p = zeros(k, k)
            for (i in 0 until k) {
                for (j in 0 until k) p[i][j] = 4.0 * LAM_SMOOTH * dtd[i][j]
                p[i][i] += LAM_PRIOR
            }
            var w = DoubleArray(b.size) { 1.0 }
            var h = DoubleArray(k) { 1.0 }
            var resid = DoubleArray(b.size)
            var m = p
            for (irls in 0 until IRLS) {
                m = ataWeighted(a, w)
                for (i in 0 until k) for (j in 0 until k) m[i][j] += p[i][j]
                val atb = atWeighted(a, w, b)
                for (i in 0 until k) atb[i] += LAM_PRIOR
                val eye = ident(k)
                val hMin = DoubleArray(k) { H_MIN }
                h = qp(m, atb, eye, hMin).first
                resid = DoubleArray(b.size)
                val ah = matVec(a, h)
                for (i in b.indices) resid[i] = b[i] - ah[i]
                val sc = 1.4826 * median(DoubleArray(resid.size) { abs(resid[it]) }) + 1e-9
                w = DoubleArray(resid.size) { i ->
                    val aw = abs(resid[i])
                    if (aw == 0.0) 1.0 else min(1.0, HUBER_K * sc / aw)
                }
            }
            mLast = m
            wLast = w
            rLast = resid
            bLast = b
            val uNew = DoubleArray(k) { i ->
                val inv = if (h[i] > 1e-15) 1.0 / h[i] else Double.POSITIVE_INFINITY
                max(inv, U_MIN)
            }
            var delta = 0.0
            for (i in 0 until k) delta = max(delta, abs(uNew[i] - u[i]))
            u = uNew
            if (delta < 1e-4) break
        }
        val nObs = bLast.size
        var wr2 = 0.0
        for (i in rLast.indices) wr2 += wLast[i] * rLast[i] * rLast[i]
        val sig2 = wr2 / max(nObs - k, 1).toDouble()
        val covH = invMat(mLast) ?: ident(k)
        for (i in 0 until k) for (j in 0 until k) covH[i][j] *= sig2
        val jdiag = DoubleArray(k) { -u[it] * u[it] }
        val covU = zeros(k, k)
        for (i in 0 until k) for (j in 0 until k) covU[i][j] = jdiag[i] * covH[i][j] * jdiag[j]
        val mi = zeros(k, k)
        for (i in 0 until k) {
            for (j in 0 until k) mi[i][j] = covU[i][j]
            mi[i][i] += 1e-9
        }
        val miInv = invMat(mi) ?: ident(k)
        val target = matVec(miInv, u)
        val cRows = ArrayList<DoubleArray>()
        val cRhs = ArrayList<Double>()
        for (i in 0 until k) {
            val row = DoubleArray(k)
            row[i] = 1.0
            cRows.add(row)
            cRhs.add(U_MIN)
        }
        for ((la, lb, burn) in cons) {
            val row = DoubleArray(k)
            addBasis(la, edges, row, 1.0)
            addBasis(lb, edges, row, -1.0)
            cRows.add(row)
            cRhs.add(burn / s0)
        }
        val (uC, act) = qp(miInv, target, cRows.toTypedArray(), cRhs.toDoubleArray())
        val active = ArrayList<Int>()
        for (aIdx in act) if (aIdx >= k) active.add(aIdx - k)
        return Fit(
            edges = edges.copyOf(),
            g = DoubleArray(k) { uC[it] * s0 },
            gUnc = DoubleArray(k) { u[it] * s0 },
            se = DoubleArray(k) { sqrt(covU[it][it].coerceAtLeast(0.0)) * s0 },
            active = active.toIntArray(),
        )
    }

    private fun constraints(
        sessions: List<FuelSession>,
        chunks: Array<List<FuelChunk>?>,
        fe: Array<Int?>,
        ep: IntArray,
    ): List<Triple<Double, Double, Double>> {
        val epochs = ep.filter { it >= 0 }.toSortedSet()
        val out = ArrayList<Triple<Double, Double, Double>>()
        for (e in epochs) {
            val ks = ArrayList<Int>()
            for (k in ep.indices) if (ep[k] == e) ks.add(k)
            if (ks.isEmpty() || fe[ks[0]] == null) continue
            var fCum = 0.0
            var a: Pair<Double, Double>? = null
            var best: Pair<Double, Double>? = null
            var done = false
            for (k in ks) {
                if (done) break
                val s = sessions[k]
                val f0 = if (fe[k] != null) s.used[fe[k]!!] - s.used[0] else 0.0
                val cs = chunks[k] ?: emptyList()
                for (c in cs) {
                    if (c.f < f0) continue
                    val fc = fCum + c.f - f0
                    if (a == null && c.l < L_HI) a = c.l to fc
                    if (a != null && c.l > L_LO && c.l < L_HI && (best == null || c.l < best!!.first)) {
                        best = c.l to fc
                    }
                }
                if (a != null) {
                    var z = -1
                    for (i in s.sm.indices) {
                        if (s.sm[i] <= L_LO && s.used[i] - s.used[0] >= f0) {
                            z = i
                            break
                        }
                    }
                    if (z >= 0) {
                        best = 0.0 to (fCum + s.used[z] - s.used[0] - f0)
                        done = true
                    }
                }
                val tot = s.used.last() - s.used[0]
                fCum += tot - f0
            }
            if (a != null && best != null && a.first - best.first > 20.0) {
                out.add(Triple(a.first, best.first, best.second - a.second))
            }
        }
        return out
    }

    private fun capToZero(
        sessions: List<FuelSession>,
        chunks: Array<List<FuelChunk>?>,
        fe: Array<Int?>,
        fit: Fit,
    ): Double {
        val ep = epochs(BooleanArray(sessions.size) { chunks[it] != null || fe[it] != null }, fe)
        // epochs() skips !ok. Rebuild ok from fe/chunks the same way as the fit: ok sessions are those passed to epochs originally.
        // Caller passes fe for all sessions; non-ok sessions have null chunks and should be skipped.
        // Recompute with the same rule as replay: a session is ok when chunks entry is non-null OR it was kept with zero chunks.
        // Zero-chunk ok sessions have empty list, not null. Null means dropped.
        val ok = BooleanArray(sessions.size) { chunks[it] != null }
        val epochsId = epochs(ok, fe)
        val v95 = vAt(L_HI, fit.edges, fit.g)
        val plateaus = ArrayList<Double>()
        var bestTerm = v95
        val ids = epochsId.filter { it >= 0 }.toSortedSet()
        for (e in ids) {
            val ks = ArrayList<Int>()
            for (k in epochsId.indices) if (epochsId[k] == e) ks.add(k)
            if (ks.isEmpty() || fe[ks[0]] == null) continue
            var fCum = 0.0
            var a: Pair<Double, Double>? = null
            var best: Pair<Double, Double>? = null
            var done = false
            for (k in ks) {
                if (done) break
                val s = sessions[k]
                val f0 = if (fe[k] != null) s.used[fe[k]!!] - s.used[0] else 0.0
                for (c in chunks[k] ?: emptyList()) {
                    if (c.f < f0) continue
                    val fc = fCum + c.f - f0
                    if (a == null && c.l < L_HI) a = c.l to fc
                    if (a != null && c.l > L_LO && c.l < L_HI && (best == null || c.l < best!!.first)) {
                        best = c.l to fc
                    }
                }
                if (a != null) {
                    for (i in s.sm.indices) {
                        if (s.sm[i] <= L_LO && s.used[i] - s.used[0] >= f0) {
                            best = 0.0 to (fCum + s.used[i] - s.used[0] - f0)
                            done = true
                            break
                        }
                    }
                }
                fCum += (s.used.last() - s.used[0]) - f0
            }
            if (a != null) {
                val vChunk = vAt(a.first, fit.edges, fit.g)
                plateaus.add(a.second - (v95 - vChunk))
            }
            if (best != null) {
                val term = best.second + vAt(best.first, fit.edges, fit.g)
                if (term > bestTerm) bestTerm = term
            }
        }
        val hEst = if (plateaus.isEmpty()) {
            0.07 * v95
        } else {
            median(plateaus.takeLast(5).toDoubleArray())
        }
        return max(v95 + hEst, bestTerm)
    }

    private fun densityAt(level: Double, edges: DoubleArray, g: DoubleArray): Double {
        if (g.isEmpty()) return 0.0
        if (level >= L_HI) return g.last()
        var i = searchRight(edges, level) - 1
        if (i < 0) i = 0
        if (i > g.lastIndex) i = g.lastIndex
        return g[i]
    }

    private fun searchRight(a: DoubleArray, v: Double): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] > v) hi = mid else lo = mid + 1
        }
        return lo
    }

    private fun basisDot(level: Double, edges: DoubleArray, u: DoubleArray): Double {
        var sum = 0.0
        for (k in u.indices) {
            val w = edges[k + 1] - edges[k]
            sum += (level - edges[k]).coerceIn(0.0, w) * u[k]
        }
        return sum
    }

    private fun addBasis(level: Double, edges: DoubleArray, row: DoubleArray, sign: Double) {
        for (k in row.indices) {
            val w = edges[k + 1] - edges[k]
            row[k] += sign * (level - edges[k]).coerceIn(0.0, w)
        }
    }

    private fun qp(
        m: Array<DoubleArray>,
        v: DoubleArray,
        c: Array<DoubleArray>,
        rhs: DoubleArray,
    ): Pair<DoubleArray, IntArray> {
        val k = v.size
        val act = ArrayList<Int>()
        var x = solve(m, v) ?: DoubleArray(k)
        val limit = 4 * (k + rhs.size) + 10
        for (iter in 0 until limit) {
            val viol = DoubleArray(c.size)
            for (j in c.indices) {
                var s = -rhs[j]
                val row = c[j]
                for (i in 0 until k) s += row[i] * x[i]
                viol[j] = s
            }
            val order = viol.indices.sortedWith(compareBy<Int> { viol[it] }.thenBy { it })
            val cand = ArrayList<Int>()
            for (j in order) if (viol[j] < -1e-9 && j !in act) cand.add(j)
            if (cand.isNotEmpty()) act.add(cand[0])
            val nAct = act.size
            val sol = if (nAct == 0) {
                solve(m, v) ?: lstsqDiag(m, v)
            } else {
                val dim = k + nAct
                val kkt = zeros(dim, dim)
                val b = DoubleArray(dim)
                for (i in 0 until k) {
                    for (j in 0 until k) kkt[i][j] = m[i][j]
                    b[i] = v[i]
                }
                for (p in 0 until nAct) {
                    val row = c[act[p]]
                    for (i in 0 until k) {
                        kkt[i][k + p] = -row[i]
                        kkt[k + p][i] = row[i]
                    }
                    b[k + p] = rhs[act[p]]
                }
                solve(kkt, b) ?: lstsqDiag(kkt, b)
            }
            x = sol.copyOfRange(0, k)
            if (nAct > 0) {
                val lam = sol.copyOfRange(k, sol.size)
                var minI = 0
                for (i in 1 until lam.size) if (lam[i] < lam[minI]) minI = i
                if (lam[minI] < -1e-9) {
                    act.removeAt(minI)
                    continue
                }
            }
            if (cand.isEmpty()) break
        }
        return x to act.toIntArray()
    }

    private fun modelJson(fit: Fit, cap: Double, sig: Double, tau: Double, nMin: Int, nch: Int): String {
        return buildString {
            append("{\"active\":").append(fit.active.joinToString(prefix = "[", postfix = "]"))
            append(",\"capToZero\":").append(cap.toString())
            append(",\"chunks\":").append(nch)
            append(",\"edges\":").append(fit.edges.joinToString(prefix = "[", postfix = "]") { it.toString() })
            append(",\"g\":").append(fit.g.joinToString(prefix = "[", postfix = "]") { it.toString() })
            append(",\"gUnc\":").append(fit.gUnc.joinToString(prefix = "[", postfix = "]") { it.toString() })
            append(",\"nMin\":").append(nMin)
            append(",\"se\":").append(fit.se.joinToString(prefix = "[", postfix = "]") { it.toString() })
            append(",\"sigmaQ\":").append(sig.toString())
            append(",\"tau\":").append(tau.toString())
            append('}')
        }
    }

    private fun gradient(v: DoubleArray, t: DoubleArray): DoubleArray {
        val n = v.size
        val g = DoubleArray(n)
        if (n == 0) return g
        if (n == 1) return g
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

    private fun detrend(t: DoubleArray, x: DoubleArray) {
        val n = x.size
        var mt = 0.0
        var mx = 0.0
        for (i in 0 until n) {
            mt += t[i]
            mx += x[i]
        }
        mt /= n
        mx /= n
        var vt = 0.0
        var cov = 0.0
        for (i in 0 until n) {
            val dt = t[i] - mt
            vt += dt * dt
            cov += dt * (x[i] - mx)
        }
        val slope = if (vt == 0.0) 0.0 else cov / vt
        val intercept = mx - slope * mt
        for (i in 0 until n) x[i] -= intercept + slope * t[i]
    }

    private fun stdPop(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var m = 0.0
        for (v in x) m += v
        m /= x.size
        var s = 0.0
        for (v in x) s += sq(v - m)
        return sqrt(s / x.size)
    }

    private fun corr(x: DoubleArray, lag: Int): Double {
        val n = x.size - lag
        if (n <= 1) return 1.0
        var mx = 0.0
        var my = 0.0
        for (i in 0 until n) {
            mx += x[i]
            my += x[i + lag]
        }
        mx /= n
        my /= n
        var vx = 0.0
        var vy = 0.0
        var c = 0.0
        for (i in 0 until n) {
            val dx = x[i] - mx
            val dy = x[i + lag] - my
            vx += dx * dx
            vy += dy * dy
            c += dx * dy
        }
        if (vx == 0.0 || vy == 0.0) return Double.NaN
        return c / sqrt(vx * vy)
    }

    private fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sorted()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    private fun percentile(x: DoubleArray, q: Double): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sorted()
        if (s.size == 1) return s[0]
        val idx = (s.size - 1) * q / 100.0
        val lo = floor(idx).toInt()
        val hi = ceil(idx).toInt()
        if (lo == hi) return s[lo]
        val w = idx - lo
        return s[lo] * (1.0 - w) + s[hi] * w
    }

    private fun sq(x: Double) = x * x

    private fun zeros(r: Int, c: Int) = Array(r) { DoubleArray(c) }

    private fun ident(n: Int): Array<DoubleArray> {
        val a = zeros(n, n)
        for (i in 0 until n) a[i][i] = 1.0
        return a
    }

    private fun diffEye(k: Int): Array<DoubleArray> {
        if (k <= 1) return zeros(0, k.coerceAtLeast(0))
        val d = zeros(k - 1, k)
        for (i in 0 until k - 1) {
            d[i][i] = -1.0
            d[i][i + 1] = 1.0
        }
        return d
    }

    private fun gram(d: Array<DoubleArray>): Array<DoubleArray> {
        if (d.isEmpty()) return zeros(if (d.isEmpty()) 0 else d[0].size, 0)
        val cols = if (d.isEmpty()) 0 else d[0].size
        val g = zeros(cols, cols)
        for (row in d) {
            for (a in 0 until cols) for (b in 0 until cols) g[a][b] += row[a] * row[b]
        }
        return g
    }

    private fun matVec(a: Array<DoubleArray>, x: DoubleArray): DoubleArray {
        val y = DoubleArray(a.size)
        for (i in a.indices) {
            var s = 0.0
            val row = a[i]
            for (j in x.indices) s += row[j] * x[j]
            y[i] = s
        }
        return y
    }

    private fun atWeighted(a: Array<DoubleArray>, w: DoubleArray, b: DoubleArray): DoubleArray {
        if (a.isEmpty()) return DoubleArray(0)
        val n = a[0].size
        val y = DoubleArray(n)
        for (i in a.indices) {
            val wi = w[i] * b[i]
            val row = a[i]
            for (j in 0 until n) y[j] += row[j] * wi
        }
        return y
    }

    private fun ataWeighted(a: Array<DoubleArray>, w: DoubleArray): Array<DoubleArray> {
        if (a.isEmpty()) return zeros(0, 0)
        val n = a[0].size
        val m = zeros(n, n)
        for (i in a.indices) {
            val row = a[i]
            val wi = w[i]
            for (j in 0 until n) {
                val rij = row[j] * wi
                for (k in 0 until n) m[j][k] += rij * row[k]
            }
        }
        return m
    }

    private fun solve(aIn: Array<DoubleArray>, bIn: DoubleArray): DoubleArray? {
        val n = bIn.size
        if (n == 0) return DoubleArray(0)
        if (aIn.size != n || aIn[0].size != n) return null
        val a = Array(n) { i ->
            val row = DoubleArray(n + 1)
            for (j in 0 until n) row[j] = aIn[i][j]
            row[n] = bIn[i]
            row
        }
        for (col in 0 until n) {
            var piv = col
            var best = abs(a[col][col])
            for (r in col + 1 until n) {
                val v = abs(a[r][col])
                if (v > best) {
                    best = v
                    piv = r
                }
            }
            if (best < 1e-14) return null
            if (piv != col) {
                val tmp = a[piv]
                a[piv] = a[col]
                a[col] = tmp
            }
            val div = a[col][col]
            for (r in col + 1 until n) {
                val f = a[r][col] / div
                if (f == 0.0) continue
                for (c in col..n) a[r][c] -= f * a[col][c]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = a[i][n]
            for (c in i + 1 until n) s -= a[i][c] * x[c]
            val d = a[i][i]
            if (abs(d) < 1e-18) return null
            x[i] = s / d
        }
        return x
    }

    private fun lstsqDiag(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size
        val aug = Array(n) { i -> a[i].copyOf() }
        for (i in 0 until n) aug[i][i] += 1e-10
        return solve(aug, b) ?: DoubleArray(n)
    }

    private fun invMat(a: Array<DoubleArray>): Array<DoubleArray>? {
        val n = a.size
        if (n == 0) return zeros(0, 0)
        val inv = zeros(n, n)
        for (j in 0 until n) {
            val e = DoubleArray(n)
            e[j] = 1.0
            invCol(a, e)?.let { col ->
                for (i in 0 until n) inv[i][j] = col[i]
            } ?: return null
        }
        return inv
    }

    private fun invCol(a: Array<DoubleArray>, e: DoubleArray): DoubleArray? = solve(a, e)

    private fun num(line: String, key: String): Double? {
        val token = "\"$key\""
        val i = line.indexOf(token)
        if (i < 0) return null
        val colon = line.indexOf(':', i + token.length)
        if (colon < 0) return null
        var p = colon + 1
        while (p < line.length && line[p].isWhitespace()) p++
        if (p >= line.length) return null
        val start = p
        if (line[p] == '-') p++
        val mark = p
        while (p < line.length && (line[p].isDigit() || line[p] == '.' || line[p] == 'e' || line[p] == 'E' || line[p] == '+' || line[p] == '-')) {
            if ((line[p] == '+' || line[p] == '-') && p > mark && line[p - 1] != 'e' && line[p - 1] != 'E') break
            p++
        }
        if (p == start || (p == start + 1 && line[start] == '-')) return null
        return line.substring(start, p).toDoubleOrNull()
    }
}
