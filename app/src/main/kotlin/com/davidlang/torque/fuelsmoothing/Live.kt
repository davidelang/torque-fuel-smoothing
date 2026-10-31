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
