package com.davidlang.torque.fuelsmoothing

import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Band algorithm (kept alongside ChunkFit): continuous piecewise-linear g (gal per %) on knots every 5 %
 * from 0 to 95 %, fitted to the stored chunks (integral of g over [Le, Ls] = chunk fuel) with
 * inverse-noise + Huber weights, a second-difference roughness penalty whose strength is chosen by
 * leave-one-drive-out, burn constraints (logged fuel within a tank) as soft one-sided rows, and a positive
 * floor relative to the data scale. No monotonic assumption. Mirrors sandbox/code-review/band_review.py
 * fit_prop/lodo (reference: sandbox/research/chunk-study/ref_bandfit.py).
 */
internal object BandFit {
    const val VERSION = 1
    const val FILE = "model-band.json"
    val KNOTS: DoubleArray = DoubleArray(20) { it * 5.0 }
    val LAMBDAS = doubleArrayOf(0.01, 0.03, 0.1, 0.3, 1.0, 3.0, 10.0, 30.0, 100.0, 300.0, 1000.0)
    const val FLOOR_REL = 0.15
    const val HUBER_K = 2.0
    const val ITERS = 10
    const val QUAD = 41

    /** λ is re-chosen by LODO only every [LAMBDA_EVERY] new drives or when the chunk count moves by >= [LAMBDA_CHUNK_REL]. */
    const val LAMBDA_EVERY = 5
    const val LAMBDA_CHUNK_REL = 0.2

    class Result(
        val g: DoubleArray, val lambda: Double, val lodoRms: Double, val v95: Double, val capToZero: Double, val chunks: Int, val activeCons: Int,
        val lambdaDrives: Int = 0, val lambdaChunks: Int = 0,
    ) {
        fun v(level: Double) = vAt(level, g)
    }

    /** Row of integral weights over [a, b] on the knot values (41-point trapezoid, x clamped to 0..95). */
    fun basis(a0: Double, b0: Double): DoubleArray {
        val a = min(a0, b0); val b = max(a0, b0)
        val row = DoubleArray(KNOTS.size)
        if (b <= a) return row
        val h = (b - a) / (QUAD - 1)
        for (i in 0 until QUAD) {
            var wi = h; if (i == 0 || i == QUAD - 1) wi *= 0.5
            val x = min(max(a + i * h, 0.0), 95.0)
            val k = min((x / 5.0).toInt(), KNOTS.size - 2)
            val f = (x - KNOTS[k]) / 5.0
            row[k] += wi * (1 - f); row[k + 1] += wi * f
        }
        return row
    }

    fun vAt(level: Double, g: DoubleArray): Double {
        val r = basis(0.0, level); var s = 0.0
        for (k in g.indices) s += r[k] * g[k]
        return s
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double { var s = 0.0; for (i in a.indices) s += a[i] * b[i]; return s }

    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
        val n = b.size; val m = Array(n) { a[it].copyOf() }; val v = b.copyOf()
        for (c in 0 until n) {
            var p = c
            for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[p][c])) p = r
            val t = m[p]; m[p] = m[c]; m[c] = t; val tv = v[p]; v[p] = v[c]; v[c] = tv
            for (r in c + 1 until n) { val f = m[r][c] / m[c][c]; if (f == 0.0) continue; for (k in c until n) m[r][k] -= f * m[c][k]; v[r] -= f * v[c] }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) { var s = v[r]; for (k in r + 1 until n) s -= m[r][k] * x[k]; x[r] = s / m[r][r] }
        return x
    }

    fun fit(ch: List<ChunkFit.Chunk>, ps: List<ChunkFit.Part>, lam: Double): Pair<DoubleArray, Int> {
        val nk = KNOTS.size
        if (ch.isEmpty()) return DoubleArray(nk) to 0
        val a = ch.map { basis(it.le, it.ls) }
        val y = DoubleArray(ch.size) { if (ch[it].ls >= ch[it].le) ch[it].dF else -ch[it].dF }
        var sy = 0.0; var sa = 0.0
        for (i in y.indices) { sy += abs(y[i]); sa += abs(a[i].sum()) }
        val g0 = max(sy / max(sa, 1e-9), 1e-6)
        val w = DoubleArray(y.size) { val se = max(ch[it].seEnd, 0.2); 1.0 / (2 * (0.33 * se * g0) * (0.33 * se * g0) + (0.03 * abs(y[it])) * (0.03 * abs(y[it]))) }
        val medW = ChunkFit.med(w)
        val (cons, _) = ChunkFit.epochs(ps, ch)
        val cr = cons.map { basis(it[1], it[0]) }; val cb = cons.map { it[2] }
        // P = lam * median(w) * D2'D2
        val p = Array(nk) { DoubleArray(nk) }
        for (r in 0 until nk - 2) {
            val idx = intArrayOf(r, r + 1, r + 2); val cf = doubleArrayOf(1.0, -2.0, 1.0)
            for (i in 0..2) for (j in 0..2) p[idx[i]][idx[j]] += lam * medW * cf[i] * cf[j]
        }
        val hub = DoubleArray(y.size) { 1.0 }
        var g = DoubleArray(nk) { g0 }
        val act = BooleanArray(cons.size)
        repeat(ITERS) {
            val m = Array(nk) { p[it].copyOf() }; val v = DoubleArray(nk)
            for (r in y.indices) {
                val ww = w[r] * hub[r]
                for (i in 0 until nk) { if (a[r][i] == 0.0) continue; v[i] += a[r][i] * ww * y[r]; for (j in 0 until nk) m[i][j] += a[r][i] * ww * a[r][j] }
            }
            if (act.any { it }) {
                val wc = medW * 10
                for (c in cons.indices) if (act[c]) for (i in 0 until nk) { v[i] += wc * cr[c][i] * cb[c]; for (j in 0 until nk) m[i][j] += wc * cr[c][i] * cr[c][j] }
            }
            var tr = 0.0; for (i in 0 until nk) tr += m[i][i]
            for (i in 0 until nk) m[i][i] += 1e-9 * tr
            g = solve(m, v).let { s -> DoubleArray(nk) { max(s[it], FLOOR_REL * g0) } }
            for (c in cons.indices) if (dot(cr[c], g) < cb[c] - 1e-9) act[c] = true
            val rs = DoubleArray(y.size) { abs(y[it] - dot(a[it], g)) * sqrt(w[it]) }
            val s = 1.4826 * ChunkFit.med(rs) + 1e-12
            for (i in y.indices) hub[i] = min(1.0, HUBER_K * s / max(rs[i], 1e-12))
        }
        return g to act.count { it }
    }

    private fun drive(key: String) = key.substringBefore('#')

    /** Leave-one-drive-out RMS of chunk fuel for a given lambda. */
    fun lodo(ch: List<ChunkFit.Chunk>, ps: List<ChunkFit.Part>, lam: Double): Double {
        var se = 0.0; var n = 0
        for (d in ch.map { drive(it.key) }.toSortedSet()) {
            val test = ch.filter { it.key.startsWith(d) && abs(it.ls - it.le) >= 0.5 }
            if (test.isEmpty()) continue
            val (g, _) = fit(ch.filter { !it.key.startsWith(d) }, ps.filter { !it.key.startsWith(d) }, lam)
            for (c in test) {
                val s = if (c.ls >= c.le) 1.0 else -1.0
                val e = s * dot(basis(c.le, c.ls), g) - c.dF
                se += e * e; n++
            }
        }
        return if (n == 0) Double.POSITIVE_INFINITY else sqrt(se / n)
    }

    fun drives(ch: List<ChunkFit.Chunk>) = ch.map { drive(it.key) }.toSet().size

    /** Cached choice (λ, LODO RMS, drives, chunks) is reused when the data grew less than the policy allows. */
    fun reuse(cached: DoubleArray?, ch: List<ChunkFit.Chunk>): Boolean {
        if (cached == null || cached.size < 4) return false
        val d = drives(ch); val n = ch.size
        return d < cached[2] + LAMBDA_EVERY && abs(n - cached[3]) < LAMBDA_CHUNK_REL * cached[3]
    }

    fun run(ps: List<ChunkFit.Part>, ch: List<ChunkFit.Chunk>, cached: DoubleArray? = null): Result? {
        if (ch.size < 2) return null
        var best = LAMBDAS[0]; var bestR = Double.POSITIVE_INFINITY
        var lamD = drives(ch); var lamN = ch.size
        if (reuse(cached, ch)) { best = cached!![0]; bestR = cached[1]; lamD = cached[2].toInt(); lamN = cached[3].toInt() }
        else for (l in LAMBDAS) { val r = lodo(ch, ps, l); if (r < bestR) { bestR = r; best = l } }
        val (g, act) = fit(ch, ps, best)
        val v95 = vAt(95.0, g)
        val (_, plats) = ChunkFit.epochs(ps, ch)
        val pl = plats.map { max(it[1] - (v95 - vAt(it[0], g)), 0.0) }
        val cap = v95 + if (pl.isNotEmpty()) ChunkFit.med(pl.toDoubleArray()) else 0.07 * v95
        return Result(g, best, bestR, v95, cap, ch.size, act, lamD, lamN)
    }

    private fun d(x: Double) = if (x.isFinite()) x.toString() else "null"

    fun json(r: Result, nowMs: Long): String =
        "{\"version\":$VERSION,\"source\":\"band\",\"timestamp\":$nowMs,\"knots\":${KNOTS.joinToString(",", "[", "]") { d(it) }}," +
            "\"g\":${r.g.joinToString(",", "[", "]") { d(it) }},\"lambda\":${d(r.lambda)},\"lodoRms\":${d(r.lodoRms)},\"v95\":${d(r.v95)}," +
            "\"capToZero\":${d(r.capToZero)},\"chunks\":${r.chunks},\"activeConstraints\":${r.activeCons}," +
            "\"lambdaDrives\":${r.lambdaDrives},\"lambdaChunks\":${r.lambdaChunks}}"

    /** (λ, LODO RMS, drives, chunks) recorded at the last λ choice, or null. */
    fun cachedLambda(dir: File): DoubleArray? = runCatching {
        val t = File(dir, FILE).takeIf { it.isFile }?.readText() ?: return null
        fun n(k: String) = Regex("\"$k\":(-?[0-9.eE+-]+)").find(t)?.groupValues?.get(1)?.toDouble()
        doubleArrayOf(n("lambda")!!, n("lodoRms")!!, n("lambdaDrives")!!, n("lambdaChunks")!!).takeIf { it[2] > 0 && it[3] > 0 }
    }.getOrNull()

    /** (g at knots, capToZero) from model-band.json, or null. */
    fun load(dir: File): Pair<DoubleArray, Double>? {
        val f = File(dir, FILE)
        if (!f.isFile) return null
        val t = f.readText()
        val g = Regex("\"g\":\\[([^\\]]*)\\]").find(t)?.groupValues?.get(1)?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() } ?: return null
        val cap = Regex("\"capToZero\":(-?[0-9.eE+-]+)").find(t)?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
        if (g.size != KNOTS.size || !(cap > 0)) return null
        return g.toDoubleArray() to cap
    }

    /** Refit from all stored chunks and write model-band.json when the result changed. */
    fun update(dir: File): Result? = runCatching {
        val (ps, ch) = ChunkFit.load(dir)
        val r = run(ps, ch, cachedLambda(dir)) ?: return@runCatching null
        val f = File(dir, FILE); val js = json(r, System.currentTimeMillis())
        val strip = { x: String -> x.replace(Regex("\"timestamp\":[0-9]+"), "") }
        if (!f.isFile || strip(f.readText()) != strip(js)) AtomicFile.write(f, js)
        runCatching {
            android.util.Log.i("FuelSmooth", "fit band v95=${"%.2f".format(r.v95)} cap=${"%.2f".format(r.capToZero)} lambda=${r.lambda} lodoRms=${"%.3f".format(r.lodoRms)} chunks=${r.chunks} active=${r.activeCons}")
        }
        r
    }.onFailure { e -> runCatching { android.util.Log.w("FuelSmooth", "band fit failed; keeping $FILE: ${e.message}") } }.getOrNull()
}
