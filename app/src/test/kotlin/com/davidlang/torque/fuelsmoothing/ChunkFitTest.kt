package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.abs

class ChunkFitTest {
    private fun fixtures(): List<FuelSession> {
        val url = checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions"))
        return FuelV2.loadSessions(File(url.toURI()))
    }

    private fun tmp(): File = Files.createTempDirectory("chunkfit").toFile()

    /** Synthetic steady drive: speed 60, level falls linearly with fuel (density gpp), small noise. */
    private fun drive(name: String, t0: Double, l0: Double, gal: Double, gpp: Double, fillAt: Int = -1, refill: Double = 0.0): FuelSession {
        val n = (gal / 0.0009).toInt()
        val t = DoubleArray(n) { t0 + it }
        val used = DoubleArray(n) { it * 0.0009 }
        var jump = 0.0
        val raw = DoubleArray(n) { i ->
            if (i == fillAt) jump = refill
            l0 + jump - used[i] / gpp + 0.8 * (((i * 7919) % 13) / 6.0 - 1.0)
        }
        val spd = DoubleArray(n) { i -> if (fillAt >= 0 && i in fillAt - 20 until fillAt + 50) 0.0 else 60.0 }
        val fill = BooleanArray(n) { i -> fillAt >= 0 && i in fillAt until fillAt + 30 }
        return FuelSession(name, t, raw, raw.copyOf(), used, spd, fill)
    }

    @Test
    fun test23SegmentationIsDeterministic() {
        val s = fixtures()
        val a = tmp(); val b = tmp()
        ChunkFit.append(a, s); ChunkFit.append(b, s)
        val ta = File(a, ChunkFit.FILE).readText()
        assertEquals(ta, File(b, ChunkFit.FILE).readText())
        // Append-only and deduplicated: a second pass adds nothing.
        assertEquals(0, ChunkFit.append(a, s))
        assertEquals(ta, File(a, ChunkFit.FILE).readText())
    }

    @Test
    fun test24ChunksNeverCrossDrivesOrFills() {
        val s1 = drive("1000-a", 0.0, 80.0, 6.0, 0.30, fillAt = 3000, refill = 20.0)
        val s2 = drive("2000-b", 100000.0, 94.0, 4.0, 0.30)   // starts 10+ % above where 1000-a ended -> fill between
        val (ps, ch) = ChunkFit.segmentAll(listOf(s1, s2))
        val keys = ps.map { it.key }
        assertTrue(keys.toString(), keys.containsAll(listOf("1000-a#0", "1000-a#1", "1000-a#2", "2000-b#0")))
        val fillT = s1.t[3000]
        for (c in ch) {
            val p = ps.first { it.key == c.key }
            assertTrue(c.t0 >= p.t0 && c.t1 <= p.t1)
            if (c.key.startsWith("1000-a")) assertTrue(c.t1 < fillT || c.t0 > fillT)
        }
        assertTrue(ps.first { it.key == "1000-a#2" }.fillAtStart)
        assertTrue(ps.first { it.key == "2000-b#0" }.fillAtStart)
        // An open session only stores parts completed by a fill.
        val open = ChunkFit.segmentAll(listOf(s1), openName = "1000-a").first.map { it.key }
        assertEquals(listOf("1000-a#0", "1000-a#1"), open)
    }

    @Test
    fun test25RobustWeightingLimitsOneBadDrive() {
        val good = (0 until 6).map { drive("${3000 + it}-g", it * 1e5, 90.0 - 10 * it, 6.0, 0.30) }
        val base = ChunkFit.segmentAll(good).let { (p, c) -> ChunkFit.fit(p, c, null) }
        // One loaded/sloped drive whose level falls twice as fast per gallon.
        val bad = good + drive("3900-x", 9e5, 70.0, 6.0, 0.15)
        val (pb, cb) = ChunkFit.segmentAll(bad)
        val robust = ChunkFit.fit(pb, cb, null)
        val plain = ChunkFit.fit(pb, cb, null, huberK = Double.POSITIVE_INFINITY)
        val dR = abs(robust.v(70.0) - robust.v(30.0) - (base.v(70.0) - base.v(30.0)))
        val dP = abs(plain.v(70.0) - plain.v(30.0) - (base.v(70.0) - base.v(30.0)))
        assertTrue("robust=$dR plain=$dP", dR <= dP + 1e-9)
        assertTrue("robust shift $dR", dR < 0.15 * (base.v(70.0) - base.v(30.0)))
    }

    @Test
    fun test26PersistedModelLoadsAtStartup() {
        val dir = tmp()
        val raw = File(dir, "raw"); raw.mkdirs()
        val src = File(checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions")).toURI())
        src.listFiles()!!.filter { it.name.endsWith(".jsonl") }.forEach { it.copyTo(File(raw, it.name)) }
        ProfileStore.rollup(dir, 35.0)
        val text = File(dir, "model.json").readText()
        assertTrue(text, text.contains("\"version\":${ChunkFit.VERSION}") && text.contains("\"timestamp\":") && text.contains("\"source\":\"chunks\""))
        assertTrue(File(dir, "model-raw.json").isFile)
        val (edges, g, cap) = checkNotNull(ProfileStore.storedMap(dir))
        val (ps, ch) = ChunkFit.load(dir)
        val r = ChunkFit.fit(ps, ch, 35.0)
        assertEquals(r.v95, FuelV2.vAt(95.0, edges, g), 1e-9)
        assertEquals(r.capToZero, cap, 1e-9)
        // Rollup again with no new sessions: store unchanged, same map.
        val before = File(dir, ChunkFit.FILE).readText()
        ProfileStore.rollup(dir, 35.0)
        assertEquals(before, File(dir, ChunkFit.FILE).readText())
        assertEquals(r.v95, FuelV2.vAt(95.0, edges, checkNotNull(ProfileStore.storedMap(dir)).second), 1e-9)
    }

    @Test
    fun test27ParityWithPythonReference() {
        // python3 sandbox/research/chunk-study/ref_chunkfit.py app/src/test/resources/fuel-sessions
        val dir = tmp()
        ChunkFit.append(dir, fixtures())
        val (ps, ch) = ChunkFit.load(dir)
        val r = ChunkFit.fit(ps, ch, null)
        assertEquals(87, ps.size)
        assertEquals(54, ch.size)
        assertEquals(29, r.groups)
        val ref = doubleArrayOf(0.1173251483073062, 0.11873091581785829, 0.12715701691693557, 0.14215045343570423, 0.15890164610581375, 0.1852145572903725, 0.2080618831183091, 0.4245792893052299, 0.40246785838267046, 0.2749926546389111, 0.23879294309794966, 0.2439652308121886, 0.49318784394671955, 0.3488147882794675, 0.3802058961492376, 0.425314951631795, 0.49120472377671337, 0.6229272146904896, 0.6486288723182294)
        for (k in ref.indices) assertEquals("g[$k]", ref[k], r.g[k], 0.005)
        assertEquals(30.263119440109506, r.v95, 0.05)
        assertEquals(31.254848755384163, r.capToZero, 0.05)
    }

    /** Drive with a stop at 600-660 s and a hard brake at 1200 s. */
    private fun stopAndBrake(): FuelSession {
        val n = 2000
        val t = DoubleArray(n) { it.toDouble() }
        val spd = DoubleArray(n) { i -> when { i in 600 until 660 -> 0.0; i == 1200 -> 40.0; else -> 60.0 } }
        val used = DoubleArray(n) { it * 0.0009 }
        val raw = DoubleArray(n) { 80.0 - used[it] / 0.3 }
        return FuelSession("5000-s", t, raw, raw.copyOf(), used, spd, BooleanArray(n))
    }

    @Test
    fun test28LevelUntrustedAfterStopsAndHardBrakingButFuelCounts() {
        val s = stopAndBrake()
        val ok = ChunkFit.steady(s)
        // Settle window after the stop: first SETTLE_S s of driving are missing, then trusted.
        assertTrue((660 until 660 + ChunkFit.SETTLE_S.toInt()).none { ok[it] })
        assertTrue(ok[660 + ChunkFit.SETTLE_S.toInt() + 5])
        // Hard brake (20 km/h in 1 s) blanks the level for HARD_HOLD s.
        assertTrue((1199..1200 + FuelV2.HARD_HOLD).none { ok[it] })
        // Startup (from a standstill at t=0 is not needed: driving from sample 0) still settles.
        assertTrue((0 until ChunkFit.SETTLE_S.toInt()).none { ok[it] })
        // Raw level is untouched (mask only), and fuel through the masked spans still counts.
        assertEquals(80.0 - s.used[1200] / 0.3, s.raw[1200], 1e-12)
        val (ps, _) = ChunkFit.segmentAll(listOf(s))
        assertEquals(s.used.last() - s.used.first(), ps.sumOf { it.fuel }, 1e-9)
    }

    @Test
    fun test29MaskedSpansNeverFeedChunksButFuelSpansThem() {
        val s = stopAndBrake()
        val (_, ch) = ChunkFit.segmentAll(listOf(s))
        val ok = ChunkFit.steady(s)
        assertTrue(ch.isNotEmpty())
        // Chunk endpoints are block medians of trusted samples; fuel inside a chunk includes masked seconds.
        val totalMaskedMoving = (0 until s.t.size).count { !ok[it] && s.spd[it] >= 5.0 }
        assertTrue(totalMaskedMoving > ChunkFit.SETTLE_S.toInt())
        for (c in ch) assertTrue(c.dF > 0.0 && c.ls > c.le)
    }

    @Test
    fun test30ChunkSpansHardBrakingWindowAndIncludesItsFuel() {
        val n = 3000
        val t = DoubleArray(n) { it.toDouble() }
        val spd = DoubleArray(n) { i -> if (i == 1500) 40.0 else 60.0 }
        val used = DoubleArray(n) { it * 0.0009 }
        // Density changes right at the brake, which would tempt a breakpoint inside the masked window.
        val raw = DoubleArray(n) { i -> if (i < 1500) 90.0 - used[i] / 0.3 else 90.0 - used[1500] / 0.3 - (used[i] - used[1500]) / 0.2 }
        val s = FuelSession("6000-b", t, raw, raw.copyOf(), used, spd, BooleanArray(n))
        val (ps, ch) = ChunkFit.segmentAll(listOf(s))
        assertEquals(1, ps.size)
        val masked = 1499.0..(1500.0 + FuelV2.HARD_HOLD)
        for (c in ch) assertTrue("endpoint in masked window: ${c.t0}..${c.t1}", c.t0 !in masked && c.t1 !in masked)
        val span = ch.firstOrNull { it.t0 < 1499.0 && it.t1 > 1500.0 + FuelV2.HARD_HOLD }
        assertTrue("no chunk spans the brake: ${ch.map { it.t0 to it.t1 }}", span != null)
        // Its fuel is the cumulative integral across the window (masked seconds included).
        val i0 = span!!.t0.toInt(); val i1 = span.t1.toInt()
        assertEquals(used[i1] - used[i0], span.dF, 0.002)
        assertTrue(span.dF > (FuelV2.HARD_HOLD + 1) * 0.0009)
    }

    @Test
    fun test31LongDriveSpanning30PercentYieldsSeveralChunks() {
        // One linear drive 85% -> 55% (9 gal at 0.3 gal/%), with a hard brake mid-way.
        val s = drive("7000-l", 0.0, 85.0, 9.0, 0.30)
        val spd = s.spd.copyOf(); spd[5000] = 40.0
        val d = FuelSession(s.name, s.t, s.raw, s.sm, s.used, spd, s.fill)
        val (ps, ch) = ChunkFit.segmentAll(listOf(d))
        assertEquals(1, ps.size)
        assertTrue("chunks=${ch.size}", ch.size >= 3)
        for (c in ch) assertTrue("span ${c.ls - c.le}", abs(c.ls - c.le) <= ChunkFit.MAX_SPAN + 1.0)
        val masked = 4999.0..(5000.0 + FuelV2.HARD_HOLD)
        for (c in ch) assertTrue(c.t0 !in masked && c.t1 !in masked)
        // Pieces tile the drive in order.
        for (k in 1 until ch.size) assertTrue(ch[k].t0 > ch[k - 1].t1 - 1e-9 || ch[k].f0 >= ch[k - 1].f1 - 1e-9)
    }

    @Test
    fun test39CorrectedPercentIsInvariantToFuelScale() {
        val base = fixtures()
        fun fitScaled(k: Double): ChunkFit.Result {
            val s = base.map { FuelSession(it.name, it.t, it.raw, it.sm, DoubleArray(it.used.size) { i -> it.used[i] * k }, it.spd, it.fill) }
            val (ps, ch) = ChunkFit.segmentAll(s)
            return ChunkFit.fit(ps, ch, 35.0)
        }
        val a = fitScaled(1.0)
        for (k in doubleArrayOf(0.5, 2.0)) {
            val b = fitScaled(k)
            // Gallons scale with k ...
            assertEquals(k * a.v95, b.v95, 0.02 * k * a.v95)
            // ... but corrected % (map gal / map capToZero) does not.
            for (lvl in doubleArrayOf(5.0, 20.0, 40.0, 60.0, 80.0, 95.0)) {
                val ca = FuelV2.correctedPct(lvl, { a.v(it) }, a.capToZero)
                val cb = FuelV2.correctedPct(lvl, { b.v(it) }, b.capToZero)
                assertEquals("k=$k lvl=$lvl", ca, cb, 0.5)
            }
        }
        // Exact for a pure global scale of the map itself.
        val v = { l: Double -> a.v(l) }
        assertEquals(FuelV2.correctedPct(50.0, v, a.capToZero), FuelV2.correctedPct(50.0, { 3.0 * v(it) }, 3.0 * a.capToZero), 1e-9)
    }
}
