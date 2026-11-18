package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LowRangeModeTest {
    @Test
    fun test142DataModeTurnsOnBelow20OrA60sRawMedianBelow15() {
        val st = LowRangeState()
        assertFalse(LowRange.maybeActivate(st, enabled = true, smoothed = 25.0, rawMedian60 = 16.0, nowMs = 1L))
        assertFalse(LowRange.maybeActivate(st, enabled = false, smoothed = 10.0, rawMedian60 = null, nowMs = 1L))
        assertTrue(LowRange.maybeActivate(st, enabled = true, smoothed = 19.9, rawMedian60 = null, nowMs = 5L))
        assertEquals(5L, st.tank.modeSinceMs)
        assertFalse(LowRange.maybeActivate(st, enabled = true, smoothed = 10.0, rawMedian60 = null, nowMs = 6L)) // already on
        val raw = LowRangeState()
        assertTrue(LowRange.maybeActivate(raw, enabled = true, smoothed = 30.0, rawMedian60 = 14.0, nowMs = 7L))
        val done = LowRangeState(tanksLeft = 0)
        assertFalse(LowRange.maybeActivate(done, enabled = true, smoothed = 5.0, rawMedian60 = 2.0, nowMs = 8L))
    }

    @Test
    fun test143FillCountsDownOnlyTanksTheModeLoggedAndWritesASummary() {
        val dir = Files.createTempDirectory("lowmode").toFile()
        val st = LowRangeState()
        LowRange.maybeActivate(st, true, 12.0, null, 10L)
        st.tank.polls = 240L
        st.tank.fresh = 60L
        st.tank.modeSec = 120.0
        val s1 = LowRange.closeTank(st, 100L, "fill")
        LowRange.appendSummary(dir, s1)
        assertEquals(LowRange.TANKS - 1, st.tanksLeft)
        assertFalse(st.active)
        assertTrue(s1.getBoolean("lowRangeMode"))
        assertEquals(0.5, s1.getJSONObject("tank").getDouble("levelHz"), 1e-12)
        // A tank that never went low does not count.
        val s2 = LowRange.closeTank(st, 200L, "fill")
        LowRange.appendSummary(dir, s2)
        assertEquals(LowRange.TANKS - 1, st.tanksLeft)
        assertFalse(s2.getBoolean("lowRangeMode"))
        for (i in 0 until 5) {
            LowRange.maybeActivate(st, true, 5.0, null, 300L + i)
            LowRange.closeTank(st, 400L + i, "fill")
        }
        assertEquals(0, st.tanksLeft)
        assertEquals(2, File(LowRange.dir(dir), LowRange.SUMMARIES).readLines().count { it.isNotBlank() })
    }

    @Test
    fun test144ModeStateSurvivesARestart() {
        val dir = Files.createTempDirectory("lowmode2").toFile()
        val st = LowRangeState()
        LowRange.maybeActivate(st, true, 8.0, null, 10L)
        st.tank.sessions.add("1795000000000-abcd1234")
        st.tank.polls = 3L
        LowRange.save(dir, st)
        val back = LowRange.load(dir)
        assertTrue(back.active)
        assertEquals(LowRange.TANKS, back.tanksLeft)
        assertEquals(10L, back.tank.modeSinceMs)
        assertEquals(listOf("1795000000000-abcd1234"), back.tank.sessions)
        assertEquals(3L, back.tank.polls)
    }
}
