package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FsLogTest {
    @Test
    fun test55RoutineTicksLogOnlyStateChanges() {
        FsLog.reset()
        // One hour of 1 Hz ticks: same source, mode TRACK, a 20 s hold in the middle.
        var logged = 0
        for (i in 0 until 3600) {
            if (FsLog.changed("source", "ford_16c1 ok 1")) logged++
            val state = if (i in 1800 until 1820) "hold missing" else "mode TRACK"
            if (FsLog.changed("state1", state)) logged++
        }
        assertEquals(1 + 3, logged)   // source once; TRACK, hold, TRACK again
    }

    @Test
    fun test56RepeatingWarningsAreRateLimited() {
        FsLog.reset()
        var shown = 0; var lastSuppressed = -1
        for (s in 0 until 600) { val n = FsLog.allow("getPIDValues", s * 1000L); if (n != null) { shown++; lastSuppressed = n } }
        assertEquals(10, shown)            // once per 60 s over 10 min
        assertEquals(59, lastSuppressed)   // and it reports how many were folded in
        assertNull(FsLog.allow("getPIDValues", 600_000L - 1))
        assertEquals(0, FsLog.allow("other", 0L))
        assertEquals(false, FsLog.verbose) // off by default
    }
}
