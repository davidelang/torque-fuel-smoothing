package com.davidlang.torque.fuelsmoothing

import android.util.Log
import org.prowl.torque.remote.ITorqueService

internal object TankSeed {
    val PROFILE_KEY: String get() = Pids.PREFIX + "nameplateGal"
    private const val TAG = "FuelSmooth"
    private val KEYS = arrayOf("tankCapacity", "profileTankCapacity")

    fun parseLitersOrGal(raw: String?): Double? {
        val v = raw?.trim()?.toDoubleOrNull() ?: return null
        if (v <= 0.0 || v.isNaN() || v.isInfinite()) return null
        return if (v > 80.0) v * 0.264172052 else v
    }

    fun probe(t: ITorqueService): Pair<String, Double>? {
        for (k in KEYS) {
            val s = runCatching { t.getSettingString(k, "") }.getOrNull()
            parseLitersOrGal(s)?.let { return "getSettingString:$k" to it }
            val i = runCatching { t.getSettingInt(k, -1) }.getOrNull() ?: -1
            if (i > 0) parseLitersOrGal(i.toString())?.let { return "getSettingInt:$k" to it }
            val p = runCatching { t.retrieveProfileData(k) }.getOrNull()
            parseLitersOrGal(p)?.let { return "retrieveProfileData:$k" to it }
        }
        return null
    }

    fun loadPersisted(t: ITorqueService): Double? {
        return parseLitersOrGal(runCatching { t.retrieveProfileData(PROFILE_KEY) }.getOrNull())
    }

    fun persist(t: ITorqueService, gal: Double) {
        runCatching { t.storeInProfile(PROFILE_KEY, gal.toString(), true) }
    }

    fun seed(t: ITorqueService): Double? {
        val probed = probe(t)
        if (probed != null) {
            persist(t, probed.second)
            Log.i(TAG, "tank seed gal=${probed.second} from ${probed.first}")
            return probed.second
        }
        val stored = loadPersisted(t)
        if (stored != null) {
            Log.i(TAG, "tank seed gal=$stored from persisted plugin profile")
            return stored
        }
        Log.i(TAG, "tank seed none")
        return null
    }
}
