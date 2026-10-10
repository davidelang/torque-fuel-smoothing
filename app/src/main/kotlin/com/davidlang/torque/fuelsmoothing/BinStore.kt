package com.davidlang.torque.fuelsmoothing

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.prowl.torque.remote.ITorqueService
import java.io.File

internal class BinStore(
    private val context: Context,
    fileName: String = "bins.json",
    private val profileKey: String = "${Pids.PREFIX}bins",
) {
    private val file = File(context.filesDir, fileName)
    val bins: MutableList<Bin> = mutableListOf()
    var reserveGal: Double = 0.0
        private set

    init {
        loadFile()
    }

    fun loadFromProfile(torque: ITorqueService) {
        // v2 mapping starts empty; do not load raw×trip bins from profile.
    }

    fun add(obs: SessionObs) {
        if (obs.bins.isEmpty() && obs.reserveGal <= 0.0) return
        bins.addAll(obs.bins)
        if (obs.reserveGal > 0.0) reserveGal += obs.reserveGal
        saveFile()
    }

    fun persist(torque: ITorqueService?) {
        saveFile()
        val body = serialize()
        if (torque != null) {
            runCatching { torque.storeInProfile(profileKey, body, true) }
        }
    }

    fun rebuildFromJsonlOnce(): Boolean {
        val p = FixFuelPrefs.prefs(context)
        val key = if (file.name == "bins.json") KEY_V4 else "${KEY_V4}_${file.name}"
        if (p.getBoolean(key, false)) return false
        val sessionDir = sessionDirForBins()
        bins.clear()
        reserveGal = 0.0
        val files = sessionDir.listFiles { _, name -> name.endsWith(".jsonl") }?.sortedBy { it.name }.orEmpty()
        var n = 0
        for (f in files) {
            val samples = SessionStore.loadSmoothedSamples(f) ?: continue
            add(FuelMath.observationsFrom(samples))
            n++
        }
        saveFile()
        p.edit().putBoolean(key, true).apply()
        Log.i(TAG, "rebuilt ${file.name} from $n smoothed jsonl sessions bins=${bins.size} reserve=$reserveGal")
        return true
    }

    private fun sessionDirForBins(): File {
        val name = file.name
        return if (name == "bins.json") {
            File(context.filesDir, "sessions")
        } else {
            val id = name.removePrefix("bins-t").removeSuffix(".json")
            File(context.filesDir, "sessions-t$id")
        }
    }

    private fun loadFile() {
        if (!file.isFile) return
        val parsed = parse(file.readText())
        bins.clear()
        bins.addAll(parsed.bins)
        reserveGal = parsed.reserveGal
        Log.i(TAG, "loaded ${bins.size} bins reserve=$reserveGal from ${file.absolutePath}")
    }

    private fun saveFile() {
        file.writeText(serialize())
    }

    fun serialize(): String {
        val arr = JSONArray()
        for (b in bins) {
            arr.put(JSONObject().put("lo", b.lo).put("hi", b.hi).put("gal", b.gal))
        }
        return JSONObject().put("bins", arr).put("reserve", reserveGal).toString()
    }

    companion object {
        private const val TAG = "FuelSmooth"
        private const val KEY_V3 = "bins_v3_chunk_units"
        private const val KEY_V4 = "bins_v4_slosh"

        data class Parsed(val bins: List<Bin>, val reserveGal: Double)

        fun parse(s: String): Parsed {
            return runCatching {
                val trimmed = s.trim()
                if (trimmed.startsWith("[")) {
                    Parsed(parseArray(JSONArray(trimmed)), 0.0)
                } else {
                    val o = JSONObject(trimmed)
                    val arr = o.optJSONArray("bins") ?: JSONArray()
                    Parsed(parseArray(arr), o.optDouble("reserve", 0.0).coerceAtLeast(0.0))
                }
            }.getOrDefault(Parsed(emptyList(), 0.0))
        }

        private fun parseArray(arr: JSONArray): List<Bin> {
            return (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val lo = o.optDouble("lo", Double.NaN)
                val hi = o.optDouble("hi", Double.NaN)
                val gal = o.optDouble("gal", Double.NaN)
                if (lo.isNaN() || hi.isNaN() || gal.isNaN() || hi <= lo) null
                else Bin(lo, hi, gal)
            }
        }
    }
}
