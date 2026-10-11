package com.davidlang.torque.fuelsmoothing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FuelMathFillTest {
    private fun feed(m: FuelMath, raw: Double, speed: Double, t: Long): Double? {
        m.checkReanchor(raw, speed, t)
        return if (m.isValidSample(raw, true)) m.ingest(raw, speed, t) else { m.hold(); m.smoothed }
    }

    @Test
    fun fillWithPumpPauseIsOneFillEndingAtPlateau() {
        val m = FuelMath()
        var t = 0L
        repeat(30) { feed(m, 5.0, 0.0, t); t += 1000 }
        var lvl = 5.0
        var fills = 0
        var was = m.mode
        fun step(raw: Double, spd: Double) {
            feed(m, raw, spd, t); t += 1000
            if (m.mode == TrackMode.FILL && was != TrackMode.FILL) fills++
            was = m.mode
        }
        repeat(80) { lvl += 0.5; step(lvl, 0.0) }          // 5 -> 45
        repeat(40) { step(lvl, 0.0) }                       // pump limit pause
        repeat(100) { lvl = minOf(98.0, lvl + 0.5); step(lvl, 0.0) } // second authorisation -> 98
        repeat(30) { step(98.0, 0.0) }
        repeat(15) { step(97.0, 20.0) }                     // drive away
        assertEquals(1, fills)
        assertEquals(TrackMode.SKEW, m.mode)
        assertEquals(98.0, m.smoothed!!, 0.6)
    }

    @Test
    fun shortGlitchSpikeIsNotAFillAndDoesNotFreeze() {
        val m = FuelMath()
        var t = 0L
        repeat(30) { feed(m, 26.0, 0.0, t); t += 1000 }
        repeat(15) { feed(m, 96.0, 0.0, t); t += 1000 }
        assertTrue(m.mode != TrackMode.FILL)
        repeat(10) { feed(m, 26.0, 0.0, t); t += 1000 }
        assertEquals(26.0, m.smoothed!!, 1.0)
    }

    @Test
    fun stuckHighLevelReanchorsAfterSustainedLowReadings() {
        val m = FuelMath()
        var t = 0L
        m.ingest(96.0, 0.0, t)
        repeat(40) { t += 1000; feed(m, 26.0, 0.0, t) }
        assertEquals(26.0, m.smoothed!!, 0.5)
    }
}
