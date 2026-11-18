package com.davidlang.torque.fuelsmoothing

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One tank's low-range evidence (since the last fill). */
internal class TankLow {
    /** Steady-driving noise learned this tank; folded into the profile bands at the fill. */
    var bands = BandNoise.Acc()
    /** First sample with the smoothed level below FuelMath.LOW_RANGE_PCT this tank. */
    var lowSinceMs: Long? = null
    /** First valid raw 0 this tank, and the counter burn after it. */
    var firstZeroMs: Long? = null
    var sinceFirstZeroGal = 0.0
    /** Driving while held at a solid 0: counter gallons, seconds, km, and how many times it was entered. */
    var solidGal = 0.0
    var solidSec = 0.0
    var solidKm = 0.0
    var solidEntries = 0
    var solidEnterMs: Long? = null
    var minSmoothed: Double? = null
    /** Low-range data mode this tank: when it turned on, level polls / fresh level updates seen, seconds on, sessions. */
    var modeSinceMs: Long? = null
    var polls = 0L
    var fresh = 0L
    var modeSec = 0.0
    val sessions: MutableList<String> = ArrayList()

    fun json(): JSONObject = JSONObject().put("bands", bands.json())
        .put("modeSinceMs", modeSinceMs ?: JSONObject.NULL).put("polls", polls).put("fresh", fresh).put("modeSec", modeSec)
        .put("sessions", JSONArray(sessions))
        .put("levelHz", if (modeSec > 0.0) fresh / modeSec else JSONObject.NULL)
        .put("lowSinceMs", lowSinceMs ?: JSONObject.NULL).put("firstZeroMs", firstZeroMs ?: JSONObject.NULL)
        .put("sinceFirstZeroGal", sinceFirstZeroGal).put("solidGal", solidGal).put("solidSec", solidSec)
        .put("solidKm", solidKm).put("solidEntries", solidEntries).put("solidEnterMs", solidEnterMs ?: JSONObject.NULL)
        .put("minSmoothed", minSmoothed ?: JSONObject.NULL)

    companion object {
        fun parse(o: JSONObject?): TankLow {
            val t = TankLow()
            if (o == null) return t
            fun long(k: String): Long? = if (o.isNull(k) || !o.has(k)) null else o.optLong(k)
            fun dbl(k: String): Double = o.optDouble(k, 0.0).takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            t.bands = BandNoise.Acc.parse(o.optJSONObject("bands"))
            t.lowSinceMs = long("lowSinceMs")
            t.firstZeroMs = long("firstZeroMs")
            t.sinceFirstZeroGal = dbl("sinceFirstZeroGal")
            t.solidGal = dbl("solidGal")
            t.solidSec = dbl("solidSec")
            t.solidKm = dbl("solidKm")
            t.solidEntries = o.optInt("solidEntries", 0).coerceAtLeast(0)
            t.solidEnterMs = long("solidEnterMs")
            t.minSmoothed = if (o.isNull("minSmoothed") || !o.has("minSmoothed")) null else o.optDouble("minSmoothed").takeIf { it.isFinite() }
            t.modeSinceMs = long("modeSinceMs")
            t.polls = o.optLong("polls", 0L).coerceAtLeast(0L)
            t.fresh = o.optLong("fresh", 0L).coerceAtLeast(0L)
            t.modeSec = dbl("modeSec")
            o.optJSONArray("sessions")?.let { a -> for (i in 0 until a.length()) a.optString(i, "").takeIf { it.isNotEmpty() }?.let { t.sessions.add(it) } }
            return t
        }
    }
}

/** Per-profile low-range state: profiles/<id>/lowrange/state.json. */
internal class LowRangeState(
    /** Folded steady-noise bands (the seed is in [BandNoise.SEED]). */
    var bands: BandNoise.Acc = BandNoise.Acc(),
    var tank: TankLow = TankLow(),
    /** One entry per closed tank that reached a raw 0: fillMs, solidGal, sinceFirstZeroGal (last MAX_RESERVES). */
    val reserves: MutableList<JSONObject> = ArrayList(),
    /** Low-range data mode: tanks still to log (counts down at each fill of a tank where it was on), and on now. */
    var tanksLeft: Int = LowRange.TANKS,
    var active: Boolean = false,
) {
    /**
     * Lower bound on fuel below the sender's 0% point (gal, counter): the most burned while held at a solid 0 in
     * any tank. Analysis only; never shown as Remaining/DTE. Null until a tank had a solid 0.
     */
    val reserveMinGal: Double?
        get() = reserves.mapNotNull { r -> r.optDouble("solidGal", Double.NaN).takeIf { it.isFinite() && it > 0.0 } }.maxOrNull()

    fun json(): JSONObject = JSONObject().put("v", 1).put("bands", bands.json()).put("tank", tank.json())
        .put("reserves", JSONArray(reserves)).put("reserveMinGal", reserveMinGal ?: JSONObject.NULL)
        .put("tanksLeft", tanksLeft).put("active", active)
}

internal object LowRange {
    const val DIR = "lowrange"
    const val STATE = "state.json"
    const val MAX_RESERVES = 20
    /** A level back at or above this (SKEW) after low-range data, with no live fill seen, ends the tank (fill while away). */
    const val MISSED_FILL_PCT = 50.0
    const val SUMMARIES = "summaries.jsonl"
    /** Global switch (settings), on by default. Off stops the data mode only; the low-range smoothing stays. */
    const val KEY_MODE = "low_range_mode"
    /** Tanks the data mode logs per vehicle. */
    const val TANKS = 3
    /** Data mode turns on below this smoothed level, or when the 60 s raw median is below MODE_RAW_PCT. */
    const val MODE_SMOOTHED_PCT = 20.0
    const val MODE_RAW_PCT = 15.0
    const val MODE_RAW_MS = 60_000L
    /** Level poll period while on, when the level is a Torque PID read (no extra bus traffic) and sampling is quick. */
    const val FAST_POLL_MS = 500L
    /** A sample slower than this falls back to the normal 1 s poll for the next tick (do not stall Torque). */
    const val FAST_POLL_BUDGET_MS = 150L
    const val RATE_LOG_MS = 60_000L
    const val MAX_SESSIONS = 100

    fun summariesFile(profileDir: File): File = File(dir(profileDir), SUMMARIES)

    /** Turns the data mode on (switch on, tanks left). Returns true when it changed. */
    fun maybeActivate(st: LowRangeState, enabled: Boolean, smoothed: Double, rawMedian60: Double?, nowMs: Long): Boolean {
        if (!enabled || st.active || st.tanksLeft <= 0) return false
        if (!(smoothed < MODE_SMOOTHED_PCT) && !(rawMedian60 != null && rawMedian60 < MODE_RAW_PCT)) return false
        st.active = true
        if (st.tank.modeSinceMs == null) st.tank.modeSinceMs = nowMs
        return true
    }

    /** One closed-tank summary line (every fill); the file is append-only. */
    fun appendSummary(profileDir: File, summary: JSONObject) {
        val f = summariesFile(profileDir)
        f.parentFile?.mkdirs()
        f.appendText(summary.toString() + "\n")
    }

    fun dir(profileDir: File): File = File(profileDir, DIR)
    fun stateFile(profileDir: File): File = File(dir(profileDir), STATE)

    fun load(profileDir: File): LowRangeState {
        val f = stateFile(profileDir)
        val o = if (f.isFile) runCatching { JSONObject(f.readText()) }.getOrNull() else null
        val st = LowRangeState()
        if (o == null) return st
        st.bands = BandNoise.Acc.parse(o.optJSONObject("bands"))
        st.tank = TankLow.parse(o.optJSONObject("tank"))
        o.optJSONArray("reserves")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { st.reserves.add(it) } }
        st.tanksLeft = o.optInt("tanksLeft", TANKS).coerceIn(0, TANKS)
        st.active = o.optBoolean("active", false)
        return st
    }

    fun save(profileDir: File, st: LowRangeState) {
        val f = stateFile(profileDir)
        f.parentFile?.mkdirs()
        AtomicFile.write(f, st.json().toString())
    }

    /**
     * A fill (or a missed fill) ends the tank: its noise is folded into the profile bands, a tank that reached a raw
     * 0 adds a reserve entry, and a fresh tank starts. Returns the closed tank's summary.
     */
    fun closeTank(st: LowRangeState, nowMs: Long, why: String): JSONObject {
        val tk = st.tank
        val learned = BandNoise.learned(tk.bands)
        val wasMode = tk.modeSinceMs != null
        if (wasMode) st.tanksLeft = (st.tanksLeft - 1).coerceAtLeast(0)
        st.active = false
        BandNoise.fold(st.bands, tk.bands)
        if (tk.firstZeroMs != null || tk.solidGal > 0.0) {
            st.reserves.add(JSONObject().put("fillMs", nowMs).put("solidGal", tk.solidGal).put("sinceFirstZeroGal", tk.sinceFirstZeroGal))
            while (st.reserves.size > MAX_RESERVES) st.reserves.removeAt(0)
        }
        val summary = JSONObject().put("event", "tankClose").put("t", nowMs).put("why", why).put("tank", tk.json())
            .put("tankSd", JSONArray(learned.map { it ?: JSONObject.NULL }))
            .put("profileSd", JSONArray((0 until BandNoise.BANDS).map { Math.round(BandNoise.sd(st.bands, it * BandNoise.WIDTH_PCT) * 1000.0) / 1000.0 }))
            .put("reserveMinGal", st.reserveMinGal ?: JSONObject.NULL)
            .put("lowRangeMode", wasMode).put("tanksLeft", st.tanksLeft)
        st.tank = TankLow()
        return summary
    }
}
