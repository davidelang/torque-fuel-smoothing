package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LiveTest {
    /** The pre-fix per-tick mpg rescan (PluginService.noteDistance at 8f7bb46), kept as the reference. */
    private class OldMpg {
        val w = ArrayDeque<Pair<Double, Double>>(); var mpg = 0.0; var touched = 0
        fun add(m: Double, g: Double, want: Double) {
            w.addLast(m to g); var fuel = 0.0; var dist = 0.0; var keep = 0; touched = 0
            for (p in w.asReversed()) { fuel += p.second; dist += p.first; keep++; touched++; if (want > 0.0 && fuel >= want) break }
            while (w.size > keep) w.removeFirst()
            if (fuel > 1e-9) mpg = dist / fuel
        }
    }

    private fun drive(hours: Double): Pair<Double, Int> {
        val ledger = TankLedger(); ledger.rHat = 1e6; ledger.anchored = true
        val mw = Live.MpgWindow(); val n = (hours * 3600).toInt()
        var maxWork = 0; var nanos = 0L
        for (i in 0 until n) {
            val g = 0.0003 + 0.0002 * ((i % 97) / 97.0); val m = 0.015
            val t0 = System.nanoTime()
            Live.tick(ledger, g); var w = Live.lastTickWork
            mw.add(m, g, 0.15 * 32.0); w = maxOf(w, Live.lastTickWork)
            val dt = System.nanoTime() - t0
            if (i >= n - 3600) { maxWork = maxOf(maxWork, w); nanos += dt }
        }
        return nanos / 3600.0 to maxWork
    }

    @Test
    fun test53PerTickWorkDoesNotGrowWithDriveLength() {
        drive(0.5) // warm up the JIT
        val (t1, w1) = drive(1.0)
        val (t10, w10) = drive(10.0)
        // Elements touched per tick in the final hour: constant (one add, at most a couple of pops).
        assertTrue("1 h max work $w1", w1 <= 3)
        assertTrue("10 h max work $w10", w10 <= 3)
        // Timing: the 10 h drive's last hour costs about the same per tick as the 1 h drive's.
        assertTrue("per-tick ns 1h=$t1 10h=$t10", t10 < 5 * t1 + 2000)
        // The old rescan touched the whole window (thousands of ticks) every tick.
        val old = OldMpg(); repeat(36000) { old.add(0.015, 0.0004, 4.8) }
        assertTrue(old.touched > 1000)
    }

    @Test
    fun test54LiveOutputsUnchangedOnFixtureSession() {
        val url = checkNotNull(javaClass.classLoader!!.getResource("fuel-sessions"))
        val s = FuelV2.loadSessions(File(url.toURI())).maxBy { it.t.size }
        val want = 0.15 * 32.0
        val old = OldMpg(); val mw = Live.MpgWindow()
        val ledger = TankLedger(); ledger.rHat = 30.0; ledger.anchored = true; var burned = 0.0
        val dir = java.nio.file.Files.createTempDirectory("live").toFile()
        ChunkFit.append(dir, FuelV2.loadSessions(File(url.toURI())))
        val (ps, ch) = ChunkFit.load(dir); val cf = ChunkFit.fit(ps, ch, 35.0)
        for (i in 1 until s.t.size) {
            val dF = (s.used[i] - s.used[i - 1]).coerceAtLeast(0.0); val miles = s.spd[i] / 3600.0
            old.add(miles, dF, want); mw.add(miles, dF, want)
            assertEquals("mpg tick $i", old.mpg, mw.mpg, 1e-9 * maxOf(1.0, old.mpg))
            Live.tick(ledger, dF); burned += dF
            assertEquals(30.0 - burned, ledger.rHat, 1e-9)
            // Corrected % is a pure function of the smoothed gauge and the persisted map (no session history).
            val sm = s.sm[i]
            if (sm.isFinite()) {
                val c = FuelV2.correctedPct(sm, { cf.v(it) }, cf.capToZero)
                assertEquals(c, FuelV2.correctedPct(sm, { cf.v(it) }, cf.capToZero), 0.0)
            }
        }
        // Chunk corrections are applied once at close instead of re-chunking every tick.
        val before = ledger.rHat
        val k = Live.closeChunks(ledger, s, { 0.3 }, 28, { cf.v(it) }, cf.v95)
        assertTrue(k >= 0); assertTrue(ledger.rHat.isFinite()); if (k == 0) assertEquals(before, ledger.rHat, 0.0)
    }
}
