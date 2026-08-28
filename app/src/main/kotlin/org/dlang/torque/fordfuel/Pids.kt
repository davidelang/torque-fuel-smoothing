package org.dlang.torque.fordfuel

import org.prowl.torque.remote.ITorqueService

internal object Pids {
    const val PREFIX = "org.dlang.torque.fordfuel."
    const val FORD_FUEL_NAME = "[FORD]Fuel Level"
    const val FORD_FUEL_STORED = "2234049"
    const val SPEED = "0d"
    const val FUEL_USED_TRIP = "ff125c"
    const val FUEL_USED_SESSION = "ff1271"
    const val LONG_TERM_MPG = "ff5201"
    const val DTE = "ff126a"

    const val PLUGIN_SMOOTHED = "[FixFuel] Smoothed Fuel Level"
    const val PLUGIN_CORRECTED = "[FixFuel] Corrected Fuel Level"
    const val PLUGIN_REMAINING = "[FixFuel] Fuel Remaining"
    const val PLUGIN_DTE = "[FixFuel] Plugin DTE"
    const val ALIAS_FORD = "[OldFord] Ford Fuel"
    const val ALIAS_SMOOTHED = "[OldFord] Smoothed Fuel Level"
    const val ALIAS_REMAINING = "[OldFord] Fuel Remaining"
    const val ALIAS_DTE = "[OldFord] Plugin DTE"

    fun csvName(info: String?): String {
        if (info.isNullOrEmpty()) return ""
        return info.split(',').firstOrNull()?.trim().orEmpty()
    }

    fun csvUnit(info: String?): String {
        if (info.isNullOrEmpty()) return ""
        val parts = info.split(',')
        return if (parts.size >= 3) parts[2].trim() else ""
    }

    data class Resolved(
        val ford: String?,
        val speed: String,
        val fuelUsed: String?,
        val mpg: String,
        val speedUnit: String,
        val fuelUsedUnit: String,
        val mpgUnit: String,
    )

    fun resolve(torque: ITorqueService): Resolved {
        val all = torque.listAllPIDs() ?: emptyArray()
        val infos = if (all.isEmpty()) emptyArray() else torque.getPIDInformation(all) ?: emptyArray()
        val n = minOf(all.size, infos.size)
        var fordByName: String? = null
        for (i in 0 until n) {
            if (csvName(infos[i]) == FORD_FUEL_NAME) {
                fordByName = all[i]
                break
            }
        }
        val storedPrefix = FORD_FUEL_STORED.lowercase()
        val ford = fordByName
            ?: all.firstOrNull { it.equals(FORD_FUEL_STORED, ignoreCase = true) || it.lowercase().startsWith(storedPrefix) }
        fun pick(vararg ids: String): String? {
            val lower = all.map { it.lowercase() }
            for (id in ids) {
                val i = lower.indexOfFirst { it == id.lowercase() || it.startsWith(id.lowercase()) }
                if (i >= 0) return all[i]
            }
            return null
        }
        fun unitOf(id: String?): String {
            if (id == null) return ""
            val i = all.indexOfFirst { it == id }
            return if (i >= 0 && i < infos.size) csvUnit(infos[i]) else ""
        }
        val speed = pick(SPEED) ?: SPEED
        val fuelUsed = pick(FUEL_USED_TRIP, FUEL_USED_SESSION)
        val mpg = pick(LONG_TERM_MPG) ?: LONG_TERM_MPG
        return Resolved(
            ford = ford,
            speed = speed,
            fuelUsed = fuelUsed,
            mpg = mpg,
            speedUnit = unitOf(speed),
            fuelUsedUnit = unitOf(fuelUsed),
            mpgUnit = unitOf(mpg),
        )
    }
}
