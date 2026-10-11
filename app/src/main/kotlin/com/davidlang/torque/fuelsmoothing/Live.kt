package com.davidlang.torque.fuelsmoothing

/**
 * Per-tick live state: O(1) in drive length. No session-wide copies, segmentation or refits happen per tick;
 * those run only at session close / ECU disconnect / fill (ProfileStore.rollup, fillUpdate, [Live.closeChunks]).
 */
internal object Live {
    /** Elements touched by the last per-tick call (test hook: must not grow with session length). */
    @Volatile var lastTickWork: Int = 0

    /** Per-tick ledger step: subtract this tick's burn. */
    fun tick(ledger: TankLedger, dF: Double) {
        FuelV2.stepSample(ledger, dF)
        lastTickWork = 1
    }

    /**
     * Recent-mpg window: the shortest suffix of (miles, gal) ticks holding >= want gal (all ticks while short),
     * kept with running sums. Same result as rescanning the window each tick, amortized O(1).
     */
    class MpgWindow {
        private val q = java.util.ArrayDeque<DoubleArray>()
        private var miles = 0.0
        private var gal = 0.0
        var mpg = 0.0
            private set
        val size get() = q.size

        fun add(m: Double, g: Double, want: Double) {
            var work = 1
            q.addLast(doubleArrayOf(m, g)); miles += m; gal += g
            if (want > 0.0) {
                while (q.size > 1 && gal - q.peekFirst()!![1] >= want) {
                    val f = q.pollFirst()!!; miles -= f[0]; gal -= f[1]; work++
                }
            }
            if (gal > 1e-9) mpg = miles / gal
            lastTickWork = work
        }

        fun clear() { q.clear(); miles = 0.0; gal = 0.0 }
    }

    /**
     * Session close only: the ledger corrections from the drive's chunks, applied once (formerly re-chunked
     * every tick). [fuelNow] is the session fuel at close.
     */
    fun closeChunks(ledger: TankLedger, session: FuelSession, gpp: (Double) -> Double, nMin: Int, v: (Double) -> Double, v95: Double): Int {
        if (session.t.size < 2) return 0
        val fuelNow = session.used.last()
        val chunks = FuelV2.chunksSf(session, gpp, nMin, 4.4)
        for (c in chunks) FuelV2.stepChunk(ledger, c.l, fuelNow - c.f, v(c.l), v95)
        return chunks.size
    }
}
