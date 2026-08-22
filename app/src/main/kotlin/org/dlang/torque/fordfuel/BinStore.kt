package org.dlang.torque.fordfuel

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import org.prowl.torque.remote.ITorqueService
import java.io.File

internal class BinStore(context: Context) {
    private val file = File(context.filesDir, "bins.json")
    val bins: MutableList<Bin> = mutableListOf()

    init {
        loadFile()
    }

    fun loadFromProfile(torque: ITorqueService) {
        val s = runCatching { torque.retrieveProfileData("${Pids.PREFIX}bins") }.getOrNull()
        if (s.isNullOrBlank()) return
        val parsed = parse(s)
        if (parsed.isNotEmpty()) {
            bins.clear()
            bins.addAll(parsed)
            saveFile()
        }
    }

    fun add(obs: List<Bin>) {
        if (obs.isEmpty()) return
        bins.addAll(obs)
        saveFile()
    }

    fun persist(torque: ITorqueService?) {
        saveFile()
        val body = serialize()
        if (torque != null) {
            runCatching { torque.storeInProfile("${Pids.PREFIX}bins", body, true) }
        }
    }

    private fun loadFile() {
        if (!file.isFile) return
        val parsed = parse(file.readText())
        bins.clear()
        bins.addAll(parsed)
        Log.i(TAG, "loaded ${bins.size} bins from ${file.absolutePath}")
    }

    private fun saveFile() {
        file.writeText(serialize())
    }

    fun serialize(): String {
        val arr = JSONArray()
        for (b in bins) {
            arr.put(JSONObject().put("lo", b.lo).put("hi", b.hi).put("gal", b.gal))
        }
        return arr.toString()
    }

    companion object {
        private const val TAG = "FordFuel"

        fun parse(s: String): List<Bin> {
            return runCatching {
                val arr = JSONArray(s)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val lo = o.optDouble("lo", Double.NaN)
                    val hi = o.optDouble("hi", Double.NaN)
                    val gal = o.optDouble("gal", Double.NaN)
                    if (lo.isNaN() || hi.isNaN() || gal.isNaN() || hi <= lo) null
                    else Bin(lo, hi, gal)
                }
            }.getOrDefault(emptyList())
        }
    }
}
