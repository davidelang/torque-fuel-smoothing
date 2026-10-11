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

    /** Our own key (written by older builds, read only now) holds gallons. */
    private fun parseOwnGallons(raw: String?): Double? {
        val s = raw?.trim() ?: return null
        parseLitersOrGal(s)?.let { return it }
        val v = s.toDoubleOrNull() ?: return null
        return if (v > 0.0 && v.isFinite()) v else null
    }

    /**
     * Torque's own vehicle-profile capacity (tankCapacity in the .tdv). Torque stores it in liters
     * regardless of display units (van: 132.4894123 = 35.0 gal; honda: 56.7811767 = 15.0 gal), so a
     * bare number under Torque's key is liters. An explicit unit still wins. Used only as a seed.
     */
    fun parseTorqueCapacity(raw: String?): Double? {
        parseLitersOrGal(raw)?.let { return it }
        val v = raw?.trim()?.toDoubleOrNull() ?: return null
        if (v <= 0.0 || !v.isFinite()) return null
        return v * Units.GAL_PER_LITER
    }

    fun probe(t: ITorqueService): Pair<String, Double>? {
        for (k in KEYS) {
            val s = runCatching { t.getSettingString(k, "") }.getOrNull()
            val p = runCatching { t.retrieveProfileData(k) }.getOrNull()
            FsLog.i(TAG, "tank seed probe $k setting='${s ?: "null"}' profile='${p ?: "null"}'")
            parseTorqueCapacity(p)?.let { return "retrieveProfileData:$k" to it }
            parseTorqueCapacity(s)?.let { return "getSettingString:$k" to it }
        }
        return null
    }

    fun loadPersisted(t: ITorqueService): Double? {
        return parseOwnGallons(runCatching { t.retrieveProfileData(PROFILE_KEY) }.getOrNull())
    }

    // Nothing is ever written back to Torque (David): the tank size lives in the plugin's profile only.

    fun seed(t: ITorqueService): Double? {
        val probed = probe(t)
        if (probed != null) {
            FsLog.i(TAG, "tank seed gal=${probed.second} from ${probed.first}")
            return probed.second
        }
        val stored = loadPersisted(t)
        if (stored != null) {
            FsLog.i(TAG, "tank seed gal=$stored from persisted plugin profile")
            return stored
        }
        FsLog.i(TAG, "tank seed none")
        return null
    }
}
