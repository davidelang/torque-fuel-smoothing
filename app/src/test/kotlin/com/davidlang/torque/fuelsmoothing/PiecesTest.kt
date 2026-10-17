package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PiecesTest {
    private fun tmp(): File = Files.createTempDirectory("pieces").toFile()

    private fun sess(
        name: String,
        n: Int,
        usedAt: (Int) -> Double = { 0.002 * it },
        rawAt: (Int) -> Double = { i -> 90.0 - usedAt(i) / 0.25 },
        spdAt: (Int) -> Double = { 60.0 },
        fillAt: (Int) -> Boolean = { false },
    ): FuelSession {
        val t = DoubleArray(n) { it.toDouble() }
        val used = DoubleArray(n) { usedAt(it) }
        val raw = DoubleArray(n) { rawAt(it) }
        val spd = DoubleArray(n) { spdAt(it) }
        val fill = BooleanArray(n) { fillAt(it) }
        return FuelSession(name, t, raw, raw.copyOf(), used, spd, fill)
    }

    /** 1 Hz, ρ = 0.25, ΔF = 0.25. Indices in [nan] have a missing raw level. */
    private fun falling(n: Int, usedAt: (Int) -> Double = { 0.002 * it }, nan: (Int) -> Boolean = { false }): FuelSession =
        sess("s", n, usedAt, { i -> if (nan(i)) Double.NaN else 90.0 - usedAt(i) / 0.25 })

    @Test
    fun test87TrustMaskEqualsLegacySteady() {
        val n = 1700
        val raw = DoubleArray(n) { 80.0 - it * 0.01 }
        for (i in 1500 until 1560) raw[i] = raw[1500]
        raw[800] = Double.NaN
        val spd = DoubleArray(n) { i ->
            when {
                i in 600 until 660 -> 0.0
                i == 1200 -> 40.0
                else -> 60.0
            }
        }
        val s = FuelSession(
            "t87",
            DoubleArray(n) { it.toDouble() },
            raw,
            raw.copyOf(),
            DoubleArray(n) { it * 0.001 },
            spd,
            BooleanArray(n),
        )
        val a = ChunkFit.steady(s)
        val b = Pieces.trusted(s)
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals("mask i=$i", a[i], b[i])
        assertTrue((0 until ChunkFit.SETTLE_S.toInt()).none { b[it] })
        assertTrue(b[100])
        assertTrue((660 until 660 + ChunkFit.SETTLE_S.toInt()).none { b[it] })
        assertTrue(b[660 + ChunkFit.SETTLE_S.toInt() + 5])
        assertTrue((1199..1200 + FuelV2.HARD_HOLD).none { b[it] })
        assertFalse(b[800])
        assertTrue((1500 until 1560).none { b[it] })
        assertTrue(b[1600])
    }

    @Test
    fun test88aGapInsideDeltaFStepOneLandsPastGapNoOverSpan() {
        val p = Pieces.cut(listOf(falling(161) { it in 41..50 })).second.first { it.t0 == 25.0 }
        assertEquals(150.0, p.t1, 0.0)
        assertFalse(p.overSpan)
        assertTrue(p.gapFrac > 0.0)
    }

    @Test
    fun test88bTrustedPastDeltaFStopsAtIStarNoNextWindow() {
        val p = Pieces.cut(listOf(falling(191) { it in 156..170 })).second.first { it.t0 == 25.0 }
        assertEquals(150.0, p.t1, 0.0)
        assertFalse(p.overSpan)
    }

    @Test
    fun test88cContinuousTrustedRunNeverOverSpan() {
        val pcs = Pieces.cut(listOf(falling(400))).second
        assertTrue(pcs.isNotEmpty())
        assertTrue(pcs.all { !it.overSpan })
        assertEquals(150.0, pcs.first().t1, 0.0)
    }

    @Test
    fun test89CutWalkF0F1dFNoOverlapShortRunsOutside() {
        val pcs = Pieces.cut(listOf(falling(400))).second
        assertEquals(pcs[0].f1 - pcs[0].f0, pcs[0].dF, 1e-12)
        for (k in 1 until pcs.size) {
            assertTrue("start ${pcs[k].t0} overlaps end ${pcs[k - 1].t1}", pcs[k].t0 >= pcs[k - 1].t1 + 1.0 - 1e-9)
            assertEquals(pcs[k].f1 - pcs[k].f0, pcs[k].dF, 1e-12)
        }
        val (parts, none) = Pieces.cut(listOf(sess("short", 39)))
        assertTrue(none.isEmpty())
        assertEquals(13, parts.single().nb)
        assertEquals(1, Pieces.blockMedians(parts.single(), parts.single().nb).size)
    }

    @Test
    fun test90CutsIndependentOfEndpointMethod() {
        val s = listOf(falling(400))
        val a = Pieces.cut(s, method = EndpointMethod.PIECE_FIT).second
        val b = Pieces.cut(s, method = EndpointMethod.END_MEDIAN_30).second
        assertEquals(a.size, b.size)
        for (i in a.indices) {
            assertEquals(a[i].t0, b[i].t0, 0.0)
            assertEquals(a[i].t1, b[i].t1, 0.0)
            assertEquals(a[i].f0, b[i].f0, 0.0)
            assertEquals(a[i].f1, b[i].f1, 0.0)
            assertEquals(a[i].overSpan, b[i].overSpan)
        }
    }

    @Test
    fun test91WeakFlaggedStoredAndFitted() {
        val s = sess("rise", 400, rawAt = { i -> 20.0 + (0.002 * i) / 0.25 })
        val dir = tmp()
        Pieces.store(dir, listOf(s))
        val p = Pieces.load(dir).second.first()
        assertTrue(p.dF > 0.0)
        assertTrue(p.weak)
        assertTrue(p.ls.isFinite() && p.le.isFinite())
    }

    @Test
    fun test92VersionBumpArchivesAndReprocessesKeepsC0() {
        assertEquals(2, Pieces.ANALYSIS_VERSION)
        assertEquals(EndpointMethod.PIECE_FIT, Pieces.ENDPOINT)
        assertTrue(Pieces.FIT_WEAK)
        val dir = tmp()
        val raw = File(dir, "raw")
        raw.mkdirs()
        val src = File(checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions")).toURI())
        val fixtures = src.listFiles()!!.filter { it.name.endsWith(".jsonl") }
        fixtures.take(3).forEach { it.copyTo(File(raw, it.name)) }
        File(dir, "segments.jsonl").writeText("{\"type\":\"part\",\"key\":\"old\"}\n")
        File(dir, "pieces.jsonl").writeText("")
        File(dir, "kalman-state.txt").writeText("legacy\n")
        File(dir, "model.json").writeText("{\"old\":true}")
        File(dir, "model-band.json").writeText("{}")
        File(dir, "model-kalman.json").writeText("{\"c0\":41.5}")
        ProfileStore.rollup(dir, 35.0)
        for (name in listOf(
            "segments.jsonl", "pieces.jsonl", "kalman-state.txt", "model.json", "model-band.json", "model-kalman.json",
        )) {
            assertTrue(name, File(dir, "$name.v1").isFile)
        }
        assertTrue(File(dir, "model-kalman.json.v1").readText().contains("41.5"))
        assertEquals("2", File(dir, "analysis.version").readText().trim())
        val c0 = Regex("\"c0\":(-?[0-9.eE+-]+)").find(File(dir, "model-kalman.json").readText())!!.groupValues[1].toDouble()
        assertEquals(41.5, c0, 1e-9)
        assertTrue(File(dir, "rollup.sig").isFile)
        assertFalse(File(dir, "rollup.sig.v1").exists())
        val sig = File(dir, "rollup.sig").readText()
        ProfileStore.rollup(dir, 35.0)
        assertEquals(sig, File(dir, "rollup.sig").readText())
        assertFalse(File(dir, "segments.jsonl.v2").exists())
        assertFalse(File(dir, "segments.jsonl.v1.2").exists())
        assertFalse(File(dir, "model.json.v2").exists())

        val dirEq = tmp()
        File(dirEq, "raw").mkdirs()
        fixtures.take(1).forEach { it.copyTo(File(dirEq, "raw/${it.name}")) }
        File(dirEq, "analysis.version").writeText("2")
        File(dirEq, "segments.jsonl").writeText("MARKER\n")
        ProfileStore.rollup(dirEq, 35.0)
        assertTrue(File(dirEq, "segments.jsonl").readText().contains("MARKER"))
        assertFalse(File(dirEq, "segments.jsonl.v1").exists())
        assertFalse(File(dirEq, "segments.jsonl.v2").exists())

        val dirOpen = tmp()
        val openName = "9000-open"
        val f = File(dirOpen, "raw/$openName.jsonl")
        f.parentFile!!.mkdirs()
        val n = 500
        val sb = StringBuilder()
        for (i in 0 until n) {
            val fill = i >= 400
            val spd = if (fill) 0.0 else 60.0
            val used = i * 0.002
            val lvl = 80.0 - used / 0.3
            sb.append("{\"t\":${i * 1000},\"rawPct\":$lvl,\"smoothedPct\":$lvl,\"fuelUsedGal\":$used,\"speed\":$spd,\"fill\":$fill}\n")
        }
        f.writeText(sb.toString())
        val bytes = f.length()
        val loaded = FuelV2.loadSessions(File(dirOpen, "raw"), caps = mapOf(openName to bytes))
        assertEquals(listOf("$openName#0", "$openName#1"), ChunkFit.segmentAll(loaded).first.map { it.key })
        ProfileStore.fillUpdate(dirOpen, 35.0, openName, bytes)
        val seg = File(dirOpen, "segments.jsonl").readText()
        assertTrue(seg, seg.contains("\"$openName#0\""))
        assertFalse(seg, seg.contains("\"$openName#1\""))
    }

    @Test
    fun test93NextWindowIsPastDeltaF() {
        val used3 = { i: Int ->
            when {
                i <= 150 -> 0.002 * i
                i <= 200 -> 0.002 * 150 + (i - 150) * (1.0 / 50.0)
                else -> 0.002 * 150 + 1.0 + (i - 200) * 0.002
            }
        }
        val rule3 = Pieces.cut(listOf(falling(217, used3) { it in 151..200 })).second.first { it.t0 == 25.0 }
        assertEquals(216.0, rule3.t1, 0.0)
        assertTrue(rule3.overSpan)
        assertTrue(rule3.gapFrac > 0.0)
        val rule1 = Pieces.cut(listOf(falling(100, { 0.02 * it }))).second.first()
        assertEquals(25.0, rule1.t0, 0.0)
        assertEquals(40.0, rule1.t1, 0.0)
        assertTrue(rule1.overSpan)
    }
}
