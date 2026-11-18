package com.davidlang.torque.fuelsmoothing

import org.json.JSONObject
import kotlin.math.roundToLong

/**
 * Optional, off-by-default, short-term diagnostic: phone accelerometer and GPS heading/speed/height change from
 * Torque's internal PIDs, appended to the session diagnostics sidecar (diagnostics/<session>.jsonl) as
 * {"event":"sensors"} rows. Phone sensors only: no OBD bus traffic. Never feeds smoothing or any fit.
 *
 * PID ids: Torque wiki PluginDocumentation (https://wiki.torque-bhp.com/view/PluginDocumentation, "Torque
 * internal PIDs"): ff1001 GPS Speed, ff1007 GPS Bearing, ff1010 GPS Height, ff1220/1/2/3 Accelerometer X/Y/Z/Total,
 * ff1239 GPS Accuracy, ff123B GPS bearing. ff1220-ff1223 are confirmed on the phone's Torque: torqueConf.dat
 * log_pid31..34 = 16716320..16716323 "Acceleration Sensor(X/Y/Z axis, Total)", unit g.
 *
 * Location is never read or written: GPS longitude ff1005 and latitude ff1006 are forbidden. Height is written
 * only as the change from the first fix of the session (slope), never the absolute value, and bearing is rounded
 * to whole degrees.
 */
internal object SensorLog {
    const val KEY = "diag_sensor_log"
    const val MIN_INTERVAL_MS = 1_000L

    /** Output key -> Torque PID. Order is the request order. */
    val PIDS: List<Pair<String, String>> = listOf(
        "accXg" to "ff1220", "accYg" to "ff1221", "accZg" to "ff1222", "accTotalG" to "ff1223",
        "gpsBearingDeg" to "ff123b", "gpsBearing1007Deg" to "ff1007",
        "gpsSpeed" to "ff1001", "gpsHeight" to "ff1010", "gpsAccuracy" to "ff1239",
    )

    /** GPS longitude and latitude. Never requested, never logged. */
    val FORBIDDEN: Set<String> = setOf("ff1005", "ff1006")

    init {
        check(PIDS.none { it.second.lowercase() in FORBIDDEN })
    }

    fun request(): Array<String> = PIDS.map { it.second }.toTypedArray()

    class State {
        var lastMs: Long? = null
        var heightBase: Double? = null
    }

    fun due(st: State, nowMs: Long): Boolean {
        val last = st.lastMs
        return last == null || nowMs - last >= MIN_INTERVAL_MS || nowMs < last
    }

    /** One sidecar row from a getPIDValuesAsDouble(request()) reply; null when nothing usable came back. */
    fun row(nowMs: Long, values: DoubleArray?, st: State): JSONObject? {
        if (values == null || values.size != PIDS.size) return null
        st.lastMs = nowMs
        val o = JSONObject().put("event", "sensors").put("t", nowMs).put("source", "Torque.getPIDValuesAsDouble")
        var any = false
        for ((i, p) in PIDS.withIndex()) {
            val v = values[i]
            if (!v.isFinite()) continue
            when (p.first) {
                "gpsHeight" -> {
                    // A reported 0.0 is Torque's "no fix"; it is not used as the session base.
                    if (v == 0.0 && st.heightBase == null) continue
                    val base = st.heightBase ?: v.also { st.heightBase = it }
                    o.put("gpsHeightDelta", round1(v - base))
                }
                "gpsBearingDeg", "gpsBearing1007Deg" -> o.put(p.first, v.roundToLong())
                "accXg", "accYg", "accZg", "accTotalG" -> o.put(p.first, round3(v))
                else -> o.put(p.first, round1(v))
            }
            any = true
        }
        return if (any) o else null
    }

    private fun round1(v: Double) = (v * 10.0).roundToLong() / 10.0
    private fun round3(v: Double) = (v * 1000.0).roundToLong() / 1000.0
}
