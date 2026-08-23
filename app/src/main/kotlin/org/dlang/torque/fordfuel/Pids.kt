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

    const val PLUGIN_FORD = "[E350] Plugin Ford Fuel"
    const val PLUGIN_SMOOTHED = "[E350] Smoothed Fuel Level"
    const val PLUGIN_REMAINING = "[E350] Ford Fuel Remaining"
    const val PLUGIN_DTE = "[E350] Plugin DTE"

    fun csvName(info: String?): String {
        if (info.isNullOrEmpty()) return ""
        return info.split(',').firstOrNull()?.trim().orEmpty()
    }

    fun csvUnit(info: String?): String {
        if (info.isNullOrEmpty()) return ""
        val parts = info.split(',')
        return if (parts.size >= 3) parts[2].trim() else ""
    }

    fun findByName(torque: ITorqueService, wanted: String): String? {
        val all = torque.listAllPIDs() ?: return null
        if (all.isEmpty()) return null
        val infos = torque.getPIDInformation(all) ?: return null
        val n = minOf(all.size, infos.size)
        for (i in 0 until n) {
            if (csvName(infos[i]).equals(wanted, ignoreCase = false)) {
                return all[i]
            }
        }
        return null
    }

    fun resolveFordFuel(torque: ITorqueService): String? {
        findByName(torque, FORD_FUEL_NAME)?.let { return it }
        val all = torque.listAllPIDs() ?: return null
        if (all.any { it.equals(FORD_FUEL_STORED, ignoreCase = true) }) {
            return FORD_FUEL_STORED
        }
        val prefix = FORD_FUEL_STORED.lowercase()
        return all.firstOrNull { it.lowercase().startsWith(prefix) }
    }

    fun pickExisting(torque: ITorqueService, vararg ids: String): String? {
        val all = torque.listAllPIDs() ?: return null
        val lower = all.map { it.lowercase() }
        for (id in ids) {
            val i = lower.indexOfFirst { it == id.lowercase() || it.startsWith(id.lowercase()) }
            if (i >= 0) return all[i]
        }
        return null
    }
}
