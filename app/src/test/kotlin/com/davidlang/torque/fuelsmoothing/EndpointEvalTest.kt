package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Offline replay of every endpoint method and both Kalman inputs.
 * Runs only when -Dfuelsmoothing.evalDir is forwarded into this JVM.
 */
class EndpointEvalTest {
    @Test
    fun test98Report() {
        val evalProp = System.getProperty("fuelsmoothing.evalDir")
        Assume.assumeTrue("skipped: set -Dfuelsmoothing.evalDir", !evalProp.isNullOrBlank())
        val started = LocalDateTime.now()
        val stamp = started.format(STAMP)
        val root = repoRoot()
        val evalDir = File(evalProp)
        if (!evalDir.isDirectory) throw AssertionError("evalDir is not a directory: ${evalDir.absolutePath}")
        val report = File(root, "sandbox/research/endpoint-eval-$stamp.md").absoluteFile
        val progress = File(report.parentFile, "endpoint-eval-$stamp.progress.txt")
        report.parentFile.mkdirs()
        fun note(msg: String) {
            val line = "${LocalDateTime.now()} $msg"
            synchronized(progress) { progress.appendText(line + "\n") }
            System.err.println(line)
        }
        note("load $evalDir")
        val (sessions, skipped) = loadSessions(evalDir)
        val names = sessions.map { it.name }
        note("sessions=${sessions.size} skipped=${skipped.size}")
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceIn(1, 8))
        try {
            val cuts = LinkedHashMap<EndpointMethod, Pair<List<Pieces.Part>, List<Pieces.Piece>>>()
            for (method in EndpointMethod.values()) {
                val t0 = System.nanoTime()
                cuts[method] = Pieces.cut(sessions, method = method)
                val (ps, pcs) = cuts[method]!!
                note("cut $method parts=${ps.size} pieces=${pcs.size} ${(System.nanoTime() - t0) / 1e9}s")
            }
            val baseParts = cuts.getValue(EndpointMethod.PIECE_FIT).first
            for (method in EndpointMethod.values()) {
                val ps = cuts.getValue(method).first
                if (ps.size != baseParts.size) throw AssertionError("part count changed with $method")
                for (i in ps.indices) {
                    val a = ps[i]
                    val b = baseParts[i]
                    if (a.key != b.key || a.t0 != b.t0 || a.nb != b.nb || a.sigma != b.sigma || a.fuel != b.fuel) {
                        throw AssertionError("part geometry changed with $method at ${a.key}")
                    }
                }
            }
            val partsByDrive0 = byDriveParts(baseParts, names)
            val overlap = drivesOverlap(partsByDrive0)
            if (overlap) note("drives overlap in part t0; kalman replays in part-time order")
            val blockRows = rows(baseParts, emptyMap(), Kalman.Input.BLOCK_MEDIANS)
            val blockByDrive = byDriveRows(blockRows, names)
            note("block-medians kalman drives=${blockByDrive.size} overlap=$overlap")
            val blockSeries = kalmanSeries(blockByDrive, overlap)
            note("block-medians series done")
            val blockLodo = pmap(names.size, pool) { drop -> kalmanLodoOne(blockByDrive, drop, overlap) }
            note("block-medians lodo done")
            val blockState = replay(if (overlap) blockRows.sortedWith(ROW_ORDER) else blockRows)

            val fits = LinkedHashMap<Pair<EndpointMethod, Boolean>, FitSide>()
            val endSeries = LinkedHashMap<Pair<EndpointMethod, Boolean>, DoubleArray>()
            val endLodo = LinkedHashMap<Pair<EndpointMethod, Boolean>, DoubleArray>()
            val endAbs = LinkedHashMap<Pair<EndpointMethod, Boolean>, Double>()
            for (method in EndpointMethod.values()) {
                val (parts, allPieces) = cuts.getValue(method)
                for (includeWeak in booleanArrayOf(true, false)) {
                    val pcs = if (includeWeak) allPieces else allPieces.filter { !(it.weak && !it.overSpan) }
                    val key = method to includeWeak
                    note("fit $method includeWeak=$includeWeak pieces=${pcs.size}")
                    fits[key] = fitSide(parts, pcs, names, pool)
                    val byPart = pcs.groupBy { it.part }
                    val erows = rows(parts, byPart, Kalman.Input.PIECE_ENDPOINTS)
                    val eby = byDriveRows(erows, names)
                    endSeries[key] = kalmanSeries(eby, overlap)
                    note("kalman endpoints series $method includeWeak=$includeWeak")
                    endLodo[key] = pmap(names.size, pool) { drop -> kalmanLodoOne(eby, drop, overlap) }.toDoubleArray()
                    val st = replay(if (overlap) erows.sortedWith(ROW_ORDER) else erows)
                    endAbs[key] = residMed(pcs) { st.g(it) }
                    note("kalman endpoints lodo $method includeWeak=$includeWeak")
                }
            }
            val sb = StringBuilder()
            sb.append("# Endpoint eval\n\n")
            sb.append("- stamp: ").append(stamp).append('\n')
            sb.append("- drives: ").append(sessions.size)
            sb.append(" (parsed captures with at least two samples, ordered by earliest sample time)\n")
            sb.append("- skipped raw files: ")
            if (skipped.isEmpty()) sb.append("none") else sb.append(skipped.joinToString(", "))
            sb.append('\n')
            sb.append("- corpus: ").append(evalDir.absolutePath).append('\n')
            sb.append("- defaults: ENDPOINT=").append(Pieces.ENDPOINT.name)
            sb.append(" INPUT=").append(Kalman.INPUT.name)
            sb.append(" FIT_WEAK=").append(Pieces.FIT_WEAK)
            sb.append(" ANALYSIS_VERSION=").append(Pieces.ANALYSIS_VERSION).append('\n')
            sb.append("- gapP90: linear quantile at 0.9, index 0.9*(n-1)\n")
            sb.append("- tank SD: population SD (sqrt of mean squared deviation) of the last min(20, n) per-drive values. ")
            sb.append("Chunk and band record capToZero. Kalman records State.tank after that drive's parts, prior ")
            sb.append(Kalman.DEFAULT_C0).append(", nameplate null.\n")
            sb.append("- lodo SD: population SD of one tank per drive, refit with that whole capture removed. ")
            sb.append("Kalman rebuilds from a fresh state and does not reuse done-keys.\n")
            sb.append("- absMed: median of |G(Ls)-G(Le)-dF| on the full-corpus map for that arm\n")
            sb.append("- band misses: BandFit.run returned null (fewer than 2 pieces). Those series values are NaN.\n")
            sb.append("- drives overlap in part t0: ").append(overlap).append("\n\n")
            sb.append("| method | kalman | includeWeak | pieces | weak | overSpan | singlePieceParts | ")
            sb.append("gapMed | gapP90 | gapMax | chunkTankSd | bandTankSd | kalmanTankSd | missingEnds | ")
            sb.append("chunkLodoSd | bandLodoSd | kalmanLodoSd | chunkAbsMed | bandAbsMed | kalmanAbsMed | ")
            sb.append("bandPrefixMiss | bandLodoMiss |\n")
            sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n")
            for (method in EndpointMethod.values()) {
                for (input in arrayOf(Kalman.Input.BLOCK_MEDIANS, Kalman.Input.PIECE_ENDPOINTS)) {
                    for (includeWeak in booleanArrayOf(true, false)) {
                        val fit = fits.getValue(method to includeWeak)
                        val kSeries: DoubleArray
                        val kLodo: DoubleArray
                        val kAbs: Double
                        if (input == Kalman.Input.BLOCK_MEDIANS) {
                            kSeries = blockSeries
                            kLodo = blockLodo.toDoubleArray()
                            kAbs = residMed(
                                if (includeWeak) cuts.getValue(method).second
                                else cuts.getValue(method).second.filter { !(it.weak && !it.overSpan) },
                            ) { blockState.g(it) }
                        } else {
                            val key = method to includeWeak
                            kSeries = endSeries.getValue(key)
                            kLodo = endLodo.getValue(key)
                            kAbs = endAbs.getValue(key)
                        }
                        sb.append("| ").append(method.name)
                        sb.append(" | ").append(input.name)
                        sb.append(" | ").append(includeWeak)
                        sb.append(" | ").append(fit.pieces)
                        sb.append(" | ").append(fit.weak)
                        sb.append(" | ").append(fit.overSpan)
                        sb.append(" | ").append(fit.single)
                        sb.append(" | ").append(fmt(fit.gapMed))
                        sb.append(" | ").append(fmt(fit.gapP90))
                        sb.append(" | ").append(fmt(fit.gapMax))
                        sb.append(" | ").append(fmt(tailSd(fit.chunkSeries)))
                        sb.append(" | ").append(fmt(tailSd(fit.bandSeries)))
                        sb.append(" | ").append(fmt(tailSd(kSeries)))
                        sb.append(" | ").append(fit.missing)
                        sb.append(" | ").append(fmt(popSd(fit.chunkLodo)))
                        sb.append(" | ").append(fmt(popSd(fit.bandLodo)))
                        sb.append(" | ").append(fmt(popSd(kLodo)))
                        sb.append(" | ").append(fmt(fit.chunkAbs))
                        sb.append(" | ").append(fmt(fit.bandAbs))
                        sb.append(" | ").append(fmt(kAbs))
                        sb.append(" | ").append(fit.bandPrefixMiss)
                        sb.append(" | ").append(fit.bandLodoMiss)
                        sb.append(" |\n")
                    }
                }
            }
            report.writeText(sb.toString())
            note("wrote ${report.absolutePath}")
            assertTrue(report.isFile && report.length() > 0)
            assertEquals(EndpointMethod.PIECE_FIT, Pieces.ENDPOINT)
            assertEquals(Kalman.Input.BLOCK_MEDIANS, Kalman.INPUT)
            assertTrue(Pieces.FIT_WEAK)
            assertEquals(4, Pieces.ANALYSIS_VERSION)
            progress.delete()
        } finally {
            pool.shutdownNow()
        }
    }

    private fun fitSide(
        parts: List<Pieces.Part>,
        pieces: List<Pieces.Piece>,
        names: List<String>,
        pool: ExecutorService,
    ): FitSide {
        val gaps = DoubleArray(pieces.size) { pieces[it].gapFrac }
        if (gaps.any { !it.isFinite() }) throw AssertionError("non-finite gapFrac")
        val byPart = pieces.groupingBy { it.part }.eachCount()
        var missing = 0
        for (c in pieces) {
            if (c.nS == 0) missing++
            if (c.nE == 0) missing++
        }
        val partsBy = byDriveParts(parts, names)
        val piecesBy = byDrivePieces(pieces, names)
        val n = names.size
        val partPrefix = ArrayList<List<Pieces.Part>>(n)
        val piecePrefix = ArrayList<List<Pieces.Piece>>(n)
        val accP = ArrayList<Pieces.Part>()
        val accC = ArrayList<Pieces.Piece>()
        for (i in 0 until n) {
            accP.addAll(partsBy[i])
            accC.addAll(piecesBy[i])
            partPrefix.add(accP.toList())
            piecePrefix.add(accC.toList())
        }
        val partLodo = ArrayList<List<Pieces.Part>>(n)
        val pieceLodo = ArrayList<List<Pieces.Piece>>(n)
        for (drop in 0 until n) {
            val ps = ArrayList<Pieces.Part>()
            val cs = ArrayList<Pieces.Piece>()
            for (i in 0 until n) if (i != drop) {
                ps.addAll(partsBy[i])
                cs.addAll(piecesBy[i])
            }
            partLodo.add(ps)
            pieceLodo.add(cs)
        }
        val both = pmap(4 * n, pool) { job ->
            val kind = job / n
            val i = job % n
            when (kind) {
                0 -> chunkCap(partPrefix[i], piecePrefix[i])
                1 -> chunkCap(partLodo[i], pieceLodo[i])
                2 -> bandCap(partPrefix[i], piecePrefix[i])
                else -> bandCap(partLodo[i], pieceLodo[i])
            }
        }
        val chunkSeries = both.subList(0, n).toDoubleArray()
        val chunkLodo = both.subList(n, 2 * n).toDoubleArray()
        val bandSeries = both.subList(2 * n, 3 * n).toDoubleArray()
        val bandLodo = both.subList(3 * n, 4 * n).toDoubleArray()
        val (fullPs, fullCh) = ChunkFit.fromPieces(parts, pieces)
        val chunkFit = ChunkFit.fit(fullPs, fullCh, null)
        val bandFit = BandFit.run(parts, pieces, null)
        if (bandFit == null && pieces.size >= 2) {
            throw AssertionError("band refit returned null pieces=${pieces.size}")
        }
        return FitSide(
            pieces = pieces.size,
            weak = pieces.count { it.weak },
            overSpan = pieces.count { it.overSpan },
            single = parts.count { byPart[it.key] == 1 },
            gapMed = Stats.med(gaps),
            gapP90 = p90(gaps),
            gapMax = if (gaps.isEmpty()) 0.0 else gaps.max(),
            missing = missing,
            chunkSeries = chunkSeries,
            chunkLodo = chunkLodo,
            chunkAbs = residMed(pieces) { ChunkFit.vAt(it, chunkFit.g) },
            bandSeries = bandSeries,
            bandLodo = bandLodo,
            bandAbs = if (bandFit == null) Double.NaN else residMed(pieces) { BandFit.vAt(it, bandFit.g) },
            bandPrefixMiss = bandSeries.count { !it.isFinite() },
            bandLodoMiss = bandLodo.count { !it.isFinite() },
        )
    }

    private fun chunkCap(parts: List<Pieces.Part>, pieces: List<Pieces.Piece>): Double {
        val (ps, ch) = ChunkFit.fromPieces(parts, pieces)
        return ChunkFit.fit(ps, ch, null).capToZero
    }

    /** Null band refit (fewer than 2 pieces) is NaN, not a substitute tank. */
    private fun bandCap(parts: List<Pieces.Part>, pieces: List<Pieces.Piece>): Double =
        BandFit.run(parts, pieces, null)?.capToZero ?: Double.NaN

    private fun residMed(pieces: List<Pieces.Piece>, g: (Double) -> Double): Double {
        val xs = ArrayList<Double>(pieces.size)
        for (c in pieces) {
            if (!c.ls.isFinite() || !c.le.isFinite() || !c.dF.isFinite()) continue
            xs.add(abs(g(c.ls) - g(c.le) - c.dF))
        }
        if (xs.isEmpty()) return Double.NaN
        return Stats.med(xs.toDoubleArray())
    }

    private fun rows(
        parts: List<Pieces.Part>,
        piecesByPart: Map<String, List<Pieces.Piece>>,
        input: Kalman.Input,
    ): List<Kalman.PartRows> {
        val out = ArrayList<Kalman.PartRows>(parts.size)
        for (p in parts) {
            val session = p.key.substringBefore('#')
            if (!p.rho.isFinite()) {
                out.add(Kalman.PartRows(p.key, session, p.t0, p.fuel, p.fillAtStart, doubleArrayOf(), doubleArrayOf(), doubleArrayOf()))
                continue
            }
            val ls = ArrayList<Double>()
            val fs = ArrayList<Double>()
            val ses = ArrayList<Double>()
            when (input) {
                Kalman.Input.BLOCK_MEDIANS -> for (b in Pieces.blockMedians(p, p.nb)) {
                    ls.add(b[1]); fs.add(b[2]); ses.add(b[3])
                }
                Kalman.Input.PIECE_ENDPOINTS -> {
                    val pcs = piecesByPart[p.key].orEmpty().sortedWith(compareBy({ it.t0 }, { it.t1 }, { it.key }))
                    for (c in pcs) {
                        if (c.nS == 0 || c.nE == 0) continue
                        ls.add(c.ls); fs.add(c.f0 - p.fStart); ses.add(c.seS)
                        ls.add(c.le); fs.add(c.f1 - p.fStart); ses.add(c.seE)
                    }
                }
            }
            out.add(
                Kalman.PartRows(
                    p.key, session, p.t0, p.fuel, p.fillAtStart,
                    ls.toDoubleArray(), fs.toDoubleArray(), ses.toDoubleArray(),
                ),
            )
        }
        return out
    }

    private fun replay(rows: List<Kalman.PartRows>): Kalman.State {
        val st = Kalman.State(Kalman.DEFAULT_C0)
        for (r in rows) st.apply(r)
        return st
    }

    private fun kalmanSeries(rowsByDrive: List<List<Kalman.PartRows>>, overlap: Boolean): DoubleArray {
        val n = rowsByDrive.size
        val series = DoubleArray(n)
        if (!overlap) {
            val st = Kalman.State(Kalman.DEFAULT_C0)
            for (i in 0 until n) {
                for (r in rowsByDrive[i]) st.apply(r)
                series[i] = st.tank
            }
            return series
        }
        val all = ArrayList<Kalman.PartRows>()
        for (i in 0 until n) {
            all.addAll(rowsByDrive[i])
            series[i] = replay(all.sortedWith(ROW_ORDER)).tank
        }
        return series
    }

    private fun kalmanLodoOne(rowsByDrive: List<List<Kalman.PartRows>>, drop: Int, overlap: Boolean): Double {
        val kept = ArrayList<Kalman.PartRows>()
        for (i in rowsByDrive.indices) if (i != drop) kept.addAll(rowsByDrive[i])
        val ordered = if (overlap) kept.sortedWith(ROW_ORDER) else kept
        return replay(ordered).tank
    }

    private fun byDriveParts(parts: List<Pieces.Part>, names: List<String>): List<List<Pieces.Part>> {
        val m = parts.groupBy { it.key.substringBefore('#') }
        return names.map { name -> m[name].orEmpty().sortedWith(compareBy({ it.t0 }, { it.key })) }
    }

    private fun byDrivePieces(pieces: List<Pieces.Piece>, names: List<String>): List<List<Pieces.Piece>> {
        val m = pieces.groupBy { it.part.substringBefore('#') }
        return names.map { name -> m[name].orEmpty().sortedWith(compareBy({ it.t0 }, { it.t1 }, { it.key })) }
    }

    private fun byDriveRows(rows: List<Kalman.PartRows>, names: List<String>): List<List<Kalman.PartRows>> {
        val m = rows.groupBy { it.session }
        return names.map { name -> m[name].orEmpty().sortedWith(ROW_ORDER) }
    }

    private fun drivesOverlap(partsByDrive: List<List<Pieces.Part>>): Boolean {
        var prevMax = Double.NEGATIVE_INFINITY
        for (ps in partsByDrive) {
            if (ps.isEmpty()) continue
            val minT = ps.minOf { it.t0 }
            if (minT < prevMax) return true
            prevMax = maxOf(prevMax, ps.maxOf { it.t0 })
        }
        return false
    }

    private fun <T> pmap(n: Int, pool: ExecutorService, f: (Int) -> T): List<T> {
        val futs = ArrayList<java.util.concurrent.Future<T>>(n)
        for (i in 0 until n) futs.add(pool.submit(Callable { f(i) }))
        val out = ArrayList<T>(n)
        for (fut in futs) {
            try {
                out.add(fut.get())
            } catch (e: ExecutionException) {
                pool.shutdownNow()
                val c = e.cause ?: e
                if (c is Error) throw c
                if (c is RuntimeException) throw c
                throw IllegalStateException(c)
            }
        }
        return out
    }

    private fun loadSessions(root: File): Pair<List<FuelSession>, List<String>> {
        val profiles = File(root, "profiles")
        if (!profiles.isDirectory) throw AssertionError("no profiles dir under ${root.absolutePath}")
        val raws = profiles.listFiles()?.filter { it.isDirectory }?.map { File(it, "raw") }?.filter { it.isDirectory }
            .orEmpty().sortedBy { it.path }
        if (raws.isEmpty()) throw AssertionError("no profiles/*/raw under ${root.absolutePath}")
        val files = ArrayList<File>()
        val sessions = ArrayList<FuelSession>()
        for (raw in raws) {
            files.addAll(FuelV2.sessionFiles(raw))
            sessions.addAll(FuelV2.loadSessions(raw))
        }
        if (sessions.isEmpty()) throw AssertionError("no sessions under ${root.absolutePath}")
        if (sessions.any { '#' in it.name }) throw AssertionError("session name contains #")
        val dup = sessions.groupBy { it.name }.filter { it.value.size > 1 }.keys
        if (dup.isNotEmpty()) throw AssertionError("duplicate session names: ${dup.sorted()}")
        val loaded = sessions.map { it.name }.toSet()
        val skipped = files.map { it.name.removeSuffix(".jsonl") }.filter { it !in loaded }.sorted()
        return sessions.sortedWith(compareBy({ it.t.min() }, { it.name })) to skipped
    }

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        val cwd = d!!.path
        while (d != null) {
            if (File(d, "settings.gradle.kts").isFile || File(d, "settings.gradle").isFile) return d
            d = d.parentFile
        }
        throw AssertionError("repo root not found from cwd $cwd")
    }

    /** Population SD, sqrt(mean((x - mean)^2)). */
    private fun popSd(xs: DoubleArray): Double {
        if (xs.isEmpty()) throw AssertionError("empty tank series")
        var m = 0.0
        for (x in xs) m += x
        m /= xs.size
        var s = 0.0
        for (x in xs) {
            val d = x - m
            s += d * d
        }
        return sqrt(s / xs.size)
    }

    private fun tailSd(xs: DoubleArray): Double {
        val k = min(20, xs.size)
        return popSd(xs.copyOfRange(xs.size - k, xs.size))
    }

    /** Linear quantile at 0.9. Empty matches Stats.med and returns 0. */
    private fun p90(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        val s = x.sorted()
        val rank = 0.9 * (s.size - 1)
        val i = rank.toInt()
        val f = rank - i
        return if (i >= s.lastIndex) s.last() else s[i] * (1.0 - f) + s[i + 1] * f
    }

    private fun fmt(x: Double): String = if (x.isFinite()) String.format(Locale.US, "%.6f", x) else "NaN"

    private class FitSide(
        val pieces: Int,
        val weak: Int,
        val overSpan: Int,
        val single: Int,
        val gapMed: Double,
        val gapP90: Double,
        val gapMax: Double,
        val missing: Int,
        val chunkSeries: DoubleArray,
        val chunkLodo: DoubleArray,
        val chunkAbs: Double,
        val bandSeries: DoubleArray,
        val bandLodo: DoubleArray,
        val bandAbs: Double,
        val bandPrefixMiss: Int,
        val bandLodoMiss: Int,
    )

    companion object {
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
        private val ROW_ORDER = compareBy<Kalman.PartRows>({ it.t0 }, { it.key })
    }
}
