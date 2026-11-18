package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LevelTrustTest {
    private fun tmp(): File = Files.createTempDirectory("level-trust").toFile()

    @Test
    fun test114StaleBurnIsMaxOfFloorAndTankFraction() {
        assertEquals(0.5, LevelTrust.staleBurnGal(null), 0.0)
        assertEquals(0.5, LevelTrust.staleBurnGal(0.0), 0.0)
        assertEquals(0.5, LevelTrust.staleBurnGal(20.0), 0.0)
        assertEquals(0.594, LevelTrust.staleBurnGal(39.6), 0.0001)
        assertEquals(0.5, LevelTrust.staleBurnGal(33.0), 0.0001)
    }

    @Test
    fun test115StaleGuardRefreshesThenMarksStaleUntilTheLevelMoves() {
        val g = StaleLevelGuard()
        assertEquals(StaleLevelGuard.Verdict.OK, g.observe(51.66, 0.0, 39.6))
        assertEquals(StaleLevelGuard.Verdict.OK, g.observe(51.66, 0.4, 39.6))
        assertEquals(StaleLevelGuard.Verdict.REFRESH, g.observe(51.66, 0.6, 39.6))
        assertEquals(StaleLevelGuard.Verdict.STALE, g.observe(51.66, 0.7, 39.6))
        assertEquals(StaleLevelGuard.Verdict.STALE, g.observe(51.66, 3.73, 39.6))
        assertEquals(StaleLevelGuard.Verdict.OK, g.observe(50.0, 3.74, 39.6))
        assertEquals(StaleLevelGuard.Verdict.OK, g.observe(50.0, 4.0, 39.6))
    }

    @Test
    fun test116LastTrustedLevelSkipsFrozenStaleAndOutOfRange() {
        val dir = tmp()
        File(dir, "older.jsonl").writeText(
            """
            {"t":1,"lvl":40.0,"lvlSrc":"ford_16c1","ok":true,"why":null,"sm":40.0}
            {"t":2,"lvl":41.0,"lvlSrc":"ford_16c1","ok":true,"why":null,"sm":41.0}
            """.trimIndent() + "\n",
        )
        File(dir, "newer.jsonl").writeText(
            """
            {"t":3,"lvl":51.66,"lvlSrc":"ford_16c1","ok":false,"why":"frozen","sm":51.66}
            {"t":4,"lvl":51.66,"lvlSrc":"ford_16c1","ok":false,"why":"stale","sm":51.66}
            {"t":5,"lvl":131.12,"lvlSrc":"ford_16c1","ok":true,"why":null,"sm":131.12}
            {"t":6,"lvl":-1,"lvlSrc":"ford_16c1","ok":false,"why":"missing","sm":null}
            {"t":7,"lvl":22.0,"lvlSrc":"sae_012f","ok":true,"why":null,"sm":22.0}
            """.trimIndent() + "\n",
        )
        assertEquals(41.0, LevelTrust.lastTrustedLevel(dir, "ford_16c1")!!, 0.0)
        assertEquals(22.0, LevelTrust.lastTrustedLevel(dir, "sae_012f")!!, 0.0)
        assertNull(LevelTrust.lastTrustedLevel(dir, FuelDetect.NONE))
        assertNull(LevelTrust.lastTrustedLevel(dir, "toyota_7c0_2129"))
    }

    @Test
    fun test119CeilingReadingIsNeverStale() {
        // 2026-11-18 fill: raw pinned at 99.90 while the first gallons burned off the neck.
        val g = StaleLevelGuard()
        for (i in 0..40) assertEquals(StaleLevelGuard.Verdict.OK, g.observe(99.9, i * 0.05, 39.6))
        // Below the ceiling the guard works as before, counting burn from the last ceiling reading.
        assertEquals(StaleLevelGuard.Verdict.OK, g.observe(99.5, 2.0, 39.6))
        assertEquals(StaleLevelGuard.Verdict.OK, g.observe(99.5, 2.4, 39.6))
        assertEquals(StaleLevelGuard.Verdict.REFRESH, g.observe(99.5, 2.6, 39.6))
        // Count sources pass their own ceiling (unit-scaled); 99.9 counts on a 255-count sender is not full.
        val c = StaleLevelGuard()
        assertEquals(StaleLevelGuard.Verdict.OK, c.observe(99.9, 0.0, 39.6, FuelMath.SENDER_CEIL_PCT * 2.55))
        assertEquals(StaleLevelGuard.Verdict.REFRESH, c.observe(99.9, 0.6, 39.6, FuelMath.SENDER_CEIL_PCT * 2.55))
    }
}
