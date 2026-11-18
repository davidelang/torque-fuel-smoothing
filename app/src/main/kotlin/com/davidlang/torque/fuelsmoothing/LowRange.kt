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

    fun json(): JSONObject = JSONObject().put("bands", bands.json())
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
) {
    /**
     * Lower bound on fuel below the sender's 0% point (gal, counter): the most burned while held at a solid 0 in
     * any tank. Analysis only; never shown as Remaining/DTE. Null until a tank had a solid 0.
     */
    val reserveMinGal: Double?
        get() = reserves.mapNotNull { r -> r.optDouble("solidGal", Double.NaN).takeIf { it.isFinite() && it > 0.0 } }.maxOrNull()

    fun json(): JSONObject = JSONObject().put("v", 1).put("bands", bands.json()).put("tank", tank.json())
        .put("reserves", JSONArray(reserves)).put("reserveMinGal", reserveMinGal ?: JSONObject.NULL)
}

internal object LowRange {
    const val DIR = "lowrange"
    const val STATE = "state.json"
    const val MAX_RESERVES = 20
    /** A level back at or above this (SKEW) after low-range data, with no live fill seen, ends the tank (fill while away). */
    const val MISSED_FILL_PCT = 50.0

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
        BandNoise.fold(st.bands, tk.bands)
        if (tk.firstZeroMs != null || tk.solidGal > 0.0) {
            st.reserves.add(JSONObject().put("fillMs", nowMs).put("solidGal", tk.solidGal).put("sinceFirstZeroGal", tk.sinceFirstZeroGal))
            while (st.reserves.size > MAX_RESERVES) st.reserves.removeAt(0)
        }
        val summary = JSONObject().put("event", "tankClose").put("t", nowMs).put("why", why).put("tank", tk.json())
            .put("tankSd", JSONArray(learned.map { it ?: JSONObject.NULL }))
            .put("profileSd", JSONArray((0 until BandNoise.BANDS).map { Math.round(BandNoise.sd(st.bands, it * BandNoise.WIDTH_PCT) * 1000.0) / 1000.0 }))
            .put("reserveMinGal", st.reserveMinGal ?: JSONObject.NULL)
        st.tank = TankLow()
        return summary
    }
}
