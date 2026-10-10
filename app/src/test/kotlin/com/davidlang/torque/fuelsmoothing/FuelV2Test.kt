package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class FuelV2Test {
    @Test
    fun test01LiterFixSelectsTheDryRunConverts() {
        val want = dryRunConverts()
        assertEquals(35, want.size)
        val got = HashSet<String>()
        for (file in sessionFiles()) {
            val lines = file.readLines()
            if (LiterFix.decide(lines) == LiterFix.Action.CONVERT) got.add(file.name)
            val again = LiterFix.decide(LiterFix.convert(file.readText()).lines())
            assertFalse(file.name, again == LiterFix.Action.CONVERT)
        }
        assertEquals(want, got)
    }

    @Test
    fun test02NoiseOnLateSessions() {
        val m = model()
        assertEquals(1.07, m.sigmaQ, 0.05)
        assertEquals(3.5, m.tau, 0.5)
        assertEquals(26.0, m.nMin.toDouble(), 3.0)
    }

    @Test
    fun test03ChunksAndDropouts() {
        val m = model()
        assertEquals(174.0, m.chunks.toDouble(), 9.0)
        assertEquals(2, m.dropped.size)
        assertEquals(9, m.dropped[0])
        assertEquals(10, m.dropped[1])
        assertEquals(0, m.chunksByName["1787952461833-7466d84c"])
    }

    @Test
    fun test04Fills() {
        val fills = model().fills.map { it.substringBefore('-') }.toSet()
        assertTrue(fills.contains("1787879457840"))
        assertTrue(fills.contains("1789153076236"))
        assertTrue(fills.contains("1791492225601"))
        assertFalse(fills.contains("1788825685693"))
    }

    @Test
    fun test05Edges() {
        val m = model()
        val expect = doubleArrayOf(0.0, 17.5, 35.0, 42.5, 62.5, 70.0, 95.0)
        assertTrue("edges ${m.edges.toList()} V95 ${m.v95()}", edgesNear(m.edges, expect))
        assertEquals(29.2, m.v95(), 0.6)
    }

    @Test
    fun test06InverseFit() {
        val m = model()
        assertEquals(29.2, m.v(95.0) - m.v(0.0), 0.6)
        assertEquals(2.8, m.v(20.0), 0.4)
        assertEquals(11.0, m.v(50.0), 0.8)
    }

    @Test
    fun test07Constraints() {
        val m = model()
        assertTrue(m.v(94.3) - m.v(0.0) >= 27.47)
        assertTrue(m.v(94.1) - m.v(17.0) >= 23.59)
        assertEquals(0, m.active.size)
    }

    @Test
    fun test08Capacity() {
        assertTrue("cap ${model().capToZero}", model().capToZero >= 30.3)
    }

    @Test
    fun test09Scale() {
        val full = model().v95()
        val small = FuelV2.replay(sessions(), 0.43, 15.0)
        val ratio = small.v95() / (0.43 * full)
        assertEquals(1.0, ratio, 0.03)
        assertEquals(12.290, small.v95(), 12.290 * 0.03)
    }

    @Test
    fun test10DeterministicJson() {
        val a = FuelV2.replay(sessions(), 1.0, 35.0).json
        val b = FuelV2.replay(sessions(), 1.0, 35.0).json
        assertEquals(a, b)
    }

    @Test
    fun test11RawLogMonotonicFrozenAndHeld() {
        val fill = RawLog()
        fill.addDelta(1.0)
        fill.sample(1_000L, 40.0, "ford_fli_pwm", 1.0, "gal/h", 10.0, "mph", true, true, null, "SKEW", 40.0)
        fill.addDelta(1.0)
        fill.sample(2_000L, 55.0, "ford_fli_pwm", 1.0, "gal/h", 0.0, "mph", true, true, null, "FILL", 55.0)
        fill.event("fillEnd", 2_000L)
        fill.addDelta(0.5)
        fill.sample(3_000L, 90.0, "ford_fli_pwm", 0.5, "gal/h", 0.0, "mph", true, true, null, "SKEW", 90.0)
        val gals = ArrayList<Double>()
        for (line in fill.finish()) {
            if (!line.contains("\"fuelGal\"")) continue
            val v = line.substringAfter("\"fuelGal\":").substringBefore(",").toDouble()
            gals.add(v)
        }
        assertEquals(listOf(1.0, 2.0, 2.5), gals)
        for (i in 1 until gals.size) assertTrue(gals[i] >= gals[i - 1])

        val frozen = RawLog()
        for (i in 0 until 70) {
            val spd = if (i < 30) 10.0 else 0.0
            frozen.sample(1_000L + i, 40.0, "src", null, "", spd, "", true, true, null, "SKEW", 40.0)
        }
        val frozenLines = frozen.finish()
        val samples = frozenLines.filter { it.contains("\"lvl\"") }
        assertEquals(70, samples.size)
        assertTrue(samples.all { it.contains("\"ok\":false") && it.contains("\"why\":\"frozen\"") })
        assertTrue(frozenLines.any { it.contains("\"event\":\"frozen\"") })

        val held = RawLog()
        held.sample(5_000L, 20.0, "src", null, "", 0.0, "", false, false, "held:drop>15", "HOLD", 30.0)
        val heldLine = held.finish().single { it.contains("\"lvl\"") }
        assertTrue(heldLine.contains("\"ok\":false"))
        assertTrue(heldLine.contains("\"why\":\"held:drop>15\""))
    }

    private fun serviceSource(): String {
        val candidates = listOf(
            File("src/main/kotlin/com/davidlang/torque/fuelsmoothing/PluginService.kt"),
            File("app/src/main/kotlin/com/davidlang/torque/fuelsmoothing/PluginService.kt"),
        )
        return candidates.first { it.isFile }.readText()
    }

    private fun edgesNear(got: DoubleArray, expect: DoubleArray): Boolean {
        if (got.size == expect.size) {
            var differ = 0
            for (i in got.indices) if (abs(got[i] - expect[i]) > 0.05) differ++
            return differ <= 1
        }
        if (abs(got.size - expect.size) > 1) return false
        val small = if (got.size < expect.size) got else expect
        val large = if (got.size < expect.size) expect else got
        var i = 0
        var skips = 0
        for (v in large) {
            if (i < small.size && abs(small[i] - v) <= 0.05) i++ else skips++
        }
        return i == small.size && skips <= 1
    }

    private fun scaleCurrent(s: FuelSession): FuelSession {
        val h = (s.t.last() - s.t.first()) / 3600.0
        val g = s.used.last() - s.used.first()
        if (h >= 1.0 / 60.0 && g > 0.0 && g / h > 8.0) {
            val used = DoubleArray(s.used.size) { s.used[it] * LiterFix.FACTOR }
            return FuelSession(s.name, s.t, s.raw, s.sm, used, s.spd, s.fill)
        }
        return s
    }

    private fun dryRunConverts(): Set<String> {
        val text = javaClass.classLoader.getResourceAsStream("fix_liters_dryrun.txt")!!.bufferedReader().readText()
        val names = HashSet<String>()
        for (line in text.lineSequence()) {
            if (!line.startsWith("convert")) continue
            val path = line.trim().split(Regex("\\s+")).getOrNull(1) ?: continue
            names.add(File(path).name)
        }
        return names
    }

    private fun sessionFiles(): List<File> = resourceDir("fuel-sessions-raw").listFiles { f ->
        f.isFile && f.name.endsWith(".jsonl")
    }!!.sortedBy { it.name }

    private fun resourceDir(name: String): File {
        val url = checkNotNull(javaClass.classLoader.getResource(name)) { name }
        return File(url.toURI())
    }

    companion object {
        private var cached: List<FuelSession>? = null
        private var cachedModel: FuelModel? = null

        private fun sessions(): List<FuelSession> {
            cached?.let { return it }
            val dir = File(
                checkNotNull(FuelV2Test::class.java.classLoader.getResource("fuel-sessions")).toURI(),
            )
            val loaded = FuelV2.loadSessions(dir)
            check(loaded.size == 77) { "kept ${loaded.size}, expected 77" }
            cached = loaded
            return loaded
        }

        private fun model(): FuelModel {
            cachedModel?.let { return it }
            val m = FuelV2.replay(sessions(), 1.0, 35.0)
            cachedModel = m
            return m
        }
    }
}
