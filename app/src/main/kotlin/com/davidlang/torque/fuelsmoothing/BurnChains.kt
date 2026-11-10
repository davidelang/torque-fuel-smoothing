package com.davidlang.torque.fuelsmoothing

import java.io.File
import java.util.Locale

/**
 * Analysis-only burn chains (chains.json in the tank directory), rebuilt from stored sessions at every rollup.
 *
 * A chain is a stretch with no fuel added: the tank holds at least [Chain.burnedGal] between [Chain.startPct]
 * and [Chain.endPct]. It does not need to start full or end empty. Live fill detection (the `fill` sample flag)
 * is never read here; adds are found from the levels themselves:
 *
 * - Levels are at-speed medians only (speed >= [AT_SPEED_KMH], [SETTLE_S] after any slower moment, finite
 *   level; `lvl` -1 / ok:false rows are already NaN from [FuelV2.parseSession]). Parked readings are never
 *   compared, because parking attitude alone can move this gauge by more than a fill threshold.
 * - Each session is split into at-speed runs at stops of [STOP_SPLIT_S] or more. A run has a first and last
 *   level (median of up to [MED_N] trusted samples at each end).
 * - Next run starts at least jumpPct (FuelMath.ANALYSIS_JUMP_PCT, 5 %) above the previous run's last level, across a session boundary or
 *   across an in-session stop of at least [STOP_SPLIT_S] stopped seconds: fuel was added. The chain closes,
 *   and a new one starts at that new level (a partial add is just a new start, not a full mark).
 * - Next session starts at least jumpPct below the previous one's last level: unrecorded driving. The drop
 *   is added to [Chain.gapPct]. A long parked pause with little change is not a gap.
 * - Sessions with no at-speed run (parked only) still add their recorded fuel but are never compared.
 */
internal object BurnChains {
    const val FILE = "chains.json"
    const val VERSION = 1
    const val AT_SPEED_KMH = 8.0
    const val STOP_KMH = Pieces.STOP_KMH
    const val SETTLE_S = Pieces.SETTLE_S
    const val STOP_SPLIT_S = 60.0
    const val MED_N = 60
    const val MIN_RUN = 20

    data class Chain(
        val startPct: Double,
        val endPct: Double,
        val burnedGal: Double,
        val gapPct: Double,
        val gaps: Int,
        val t0Ms: Long,
        val t1Ms: Long,
        val sessions: Int,
        val startedBy: String,
        val endedBy: String,
        val risePct: Double,
    ) {
        val spanPct: Double get() = startPct - endPct
        /** No unrecorded driving inside: usable as a hard lower bound (tank >= burnedGal over this span). */
        val hard: Boolean get() = gapPct == 0.0
    }

    private class Run(
        val s: Int, val firstPct: Double, val lastPct: Double, val iFirst: Int, val iLast: Int,
        /** Stopped seconds between the previous run of the same session and this one; NaN for a session's first run. */
        val stoppedBefore: Double,
    )

    fun build(sessions: List<FuelSession>, jumpPct: Double = FuelMath.ANALYSIS_JUMP_PCT): List<Chain> {
        val ss = sessions.filter { it.t.isNotEmpty() }.sortedBy { it.t.first() }
        val runs = ArrayList<Run>()
        for ((k, s) in ss.withIndex()) runs.addAll(runsOf(k, s))
        val out = ArrayList<Chain>()
        if (runs.isEmpty()) return out
        var start = runs[0]
        var startedBy = "history"
        var low = start
        var gap = 0.0
        var gaps = 0
        fun close(endedBy: String, rise: Double) {
            out.add(
                Chain(
                    startPct = start.firstPct,
                    endPct = low.lastPct,
                    burnedGal = burn(ss, start.s, start.iFirst, low.s, low.iLast),
                    gapPct = gap,
                    gaps = gaps,
                    t0Ms = (ss[start.s].t[start.iFirst] * 1000.0).toLong(),
                    t1Ms = (ss[low.s].t[low.iLast] * 1000.0).toLong(),
                    sessions = low.s - start.s + 1,
                    startedBy = startedBy,
                    endedBy = endedBy,
                    risePct = rise,
                ),
            )
        }
        for (k in 1 until runs.size) {
            val prev = runs[k - 1]
            val r = runs[k]
            val between = r.s != prev.s
            val d = r.firstPct - prev.lastPct
            val stopped = between || (r.stoppedBefore.isFinite() && r.stoppedBefore >= STOP_SPLIT_S)
            if (d >= jumpPct && stopped) {
                close("rise", d)
                start = r
                startedBy = "rise"
                low = r
                gap = 0.0
                gaps = 0
                continue
            }
            if (between && -d >= jumpPct) {
                gap += -d
                gaps++
            }
            if (r.lastPct <= low.lastPct) low = r
        }
        close("open", 0.0)
        return out
    }

    private fun runsOf(k: Int, s: FuelSession): List<Run> {
        val n = s.t.size
        val ok = BooleanArray(n)
        var movingSince = Double.NaN
        for (i in 0 until n) {
            val v = s.spd[i]
            if (!v.isFinite() || v < AT_SPEED_KMH) {
                movingSince = Double.NaN
                continue
            }
            if (movingSince.isNaN()) movingSince = s.t[i]
            val l = s.raw[i]
            ok[i] = l.isFinite() && l >= 0.0 && l <= 100.0 && s.t[i] - movingSince >= SETTLE_S
        }
        val groups = ArrayList<IntArray>()
        val stops = ArrayList<Double>()
        var cur = ArrayList<Int>()
        // Stopped seconds since the last kept run; NaN before this session's first kept run.
        var pending = Double.NaN
        var curStop = Double.NaN
        var lastOk = -1
        for (i in 0 until n) {
            if (!ok[i]) continue
            if (lastOk >= 0 && s.t[i] - s.t[lastOk] >= STOP_SPLIT_S) {
                var st = 0.0
                for (j in lastOk until i) if (!s.spd[j].isFinite() || s.spd[j] < STOP_KMH) st += s.t[j + 1] - s.t[j]
                if (cur.size >= MIN_RUN) {
                    groups.add(cur.toIntArray()); stops.add(curStop)
                    pending = st
                } else if (pending.isFinite()) {
                    pending += st
                }
                cur = ArrayList()
            }
            if (cur.isEmpty()) curStop = pending
            cur.add(i)
            lastOk = i
        }
        if (cur.size >= MIN_RUN) { groups.add(cur.toIntArray()); stops.add(curStop) }
        return groups.mapIndexed { gi, g ->
            val m = minOf(MED_N, g.size)
            val head = DoubleArray(m) { s.raw[g[it]] }
            val tail = DoubleArray(m) { s.raw[g[g.size - m + it]] }
            Run(k, med(head), med(tail), g[m / 2], g[g.size - 1 - m / 2], stops[gi])
        }
    }

    /** Recorded fuel from (sa, ia) to (sb, ib), summed per session; counter drops and missing values add nothing. */
    private fun burn(ss: List<FuelSession>, sa: Int, ia: Int, sb: Int, ib: Int): Double {
        var total = 0.0
        for (k in sa..sb) {
            val u = ss[k].used
            if (u.isEmpty()) continue
            val a = if (k == sa) ia else 0
            val b = if (k == sb) ib else u.size - 1
            var prev = Double.NaN
            for (i in a..b) {
                val x = u[i]
                if (!x.isFinite()) continue
                if (prev.isFinite() && x > prev) total += x - prev
                prev = x
            }
        }
        return total
    }

    private fun med(x: DoubleArray): Double {
        val v = x.sorted()
        if (v.isEmpty()) return Double.NaN
        val h = v.size / 2
        return if (v.size % 2 == 1) v[h] else (v[h - 1] + v[h]) / 2.0
    }

    private fun f(x: Double, p: Int = 2): String = String.format(Locale.US, "%.${p}f", x)

    fun json(chains: List<Chain>, jumpPct: Double): String {
        val b = StringBuilder()
        b.append("{\"version\":").append(VERSION).append(",\"jumpPct\":").append(f(jumpPct, 1)).append(",\"chains\":[")
        chains.forEachIndexed { i, c ->
            if (i > 0) b.append(',')
            b.append("\n{\"startPct\":").append(f(c.startPct, 1))
            b.append(",\"endPct\":").append(f(c.endPct, 1))
            b.append(",\"spanPct\":").append(f(c.spanPct, 1))
            b.append(",\"burnedGal\":").append(f(c.burnedGal))
            b.append(",\"gapPct\":").append(f(c.gapPct, 1))
            b.append(",\"gaps\":").append(c.gaps)
            b.append(",\"hard\":").append(c.hard)
            b.append(",\"t0\":").append(c.t0Ms)
            b.append(",\"t1\":").append(c.t1Ms)
            b.append(",\"sessions\":").append(c.sessions)
            b.append(",\"startedBy\":\"").append(c.startedBy).append('"')
            b.append(",\"endedBy\":\"").append(c.endedBy).append('"')
            b.append(",\"risePct\":").append(f(c.risePct, 1))
            b.append('}')
        }
        b.append("\n]}\n")
        return b.toString()
    }

    /** Rebuilds [FILE] in [dir] from [sessions]. Returns the chains written. */
    fun update(dir: File, sessions: List<FuelSession>, jumpPct: Double = FuelMath.ANALYSIS_JUMP_PCT): List<Chain> {
        val chains = build(sessions, jumpPct)
        AtomicFile.write(File(dir, FILE), json(chains, jumpPct))
        return chains
    }
}
