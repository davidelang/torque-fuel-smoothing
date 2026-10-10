package com.davidlang.torque.fuelsmoothing

import android.util.Log
import org.prowl.torque.remote.ITorqueService

internal object TankSeed {
    val PROFILE_KEY: String get() = Pids.PREFIX + "nameplateGal"
    private const val TAG = "FuelSmooth"
    private val KEYS = arrayOf("tankCapacity", "profileTankCapacity")

    private val WITH_UNIT = Regex("^([0-9]+(?:\\.[0-9]+)?)\\s*([A-Za-z]+)\\.?$")

    /**
     * Gallons from a capacity string. The unit must be explicit ("70 L", "18.5 gal").
     * A bare number is unknown and returns null; no magnitude-based liters guess.
     */
    fun parseLitersOrGal(raw: String?): Double? {
        val m = WITH_UNIT.matchEntire(raw?.trim() ?: return null) ?: return null
        val v = m.groupValues[1].toDoubleOrNull() ?: return null
        if (v <= 0.0 || v.isNaN() || v.isInfinite()) return null
        return when (m.groupValues[2].lowercase()) {
            "gal", "gals", "gallon", "gallons" -> v
            "l", "lt", "ltr", "liter", "liters", "litre", "litres" -> v * Units.GAL_PER_LITER
            else -> null
        }
    }

    /** Our own key is always written by persist() in gallons. */
    private fun parseOwnGallons(raw: String?): Double? {
        val s = raw?.trim() ?: return null
        parseLitersOrGal(s)?.let { return it }
        val v = s.toDoubleOrNull() ?: return null
        return if (v > 0.0 && v.isFinite()) v else null
    }

    fun probe(t: ITorqueService): Pair<String, Double>? {
        for (k in KEYS) {
            val s = runCatching { t.getSettingString(k, "") }.getOrNull()
            parseLitersOrGal(s)?.let { return "getSettingString:$k" to it }
            val p = runCatching { t.retrieveProfileData(k) }.getOrNull()
            parseLitersOrGal(p)?.let { return "retrieveProfileData:$k" to it }
        }
        return null
    }

    fun loadPersisted(t: ITorqueService): Double? {
        return parseOwnGallons(runCatching { t.retrieveProfileData(PROFILE_KEY) }.getOrNull())
    }

    fun persist(t: ITorqueService, gal: Double) {
        runCatching { t.storeInProfile(PROFILE_KEY, "$gal gal", true) }
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
