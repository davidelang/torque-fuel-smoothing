package com.davidlang.torque.fuelsmoothing

import org.prowl.torque.remote.ITorqueService

internal object Pids {
    val PREFIX: String get() = BuildConfig.APPLICATION_ID + "."
    const val FORD_FUEL_NAME = "[FORD]Fuel Level"
    const val FORD_FUEL_STORED = "2234049"
    const val SPEED = "0d"
    const val RPM = "0c"
    const val MAF = "10"
    const val FUEL_USED_TRIP = "ff1271" // Torque "Fuel used (trip)"; ff125c is "Fuel cost (trip)"
    const val FUEL_COST_TRIP = "ff125c"
    const val GAL_PER_HOUR = "ff125d"
    const val GAL_PER_MIN = "ff125a"
    const val LONG_TERM_MPG = "ff5201"
    const val ECU_FUEL_RATE = "015e"

    /** SAE J1979 015E (L/h) and Torque's calculated flow PIDs: liters internally, regardless of display. */
    fun isStandardFlowPid(pid: String): Boolean =
        pid.lowercase().removePrefix("0x").let { it == GAL_PER_HOUR || it == GAL_PER_MIN || it == ECU_FUEL_RATE || it == "5e" }
    const val DTE = "ff126a"

    const val PLUGIN_SMOOTHED = "[FuelSmooth] Smoothed Fuel Level"
    // *_REMAINING and *_DTE (and the extra-tank remaining/dte names) are retired: never registered or written.
    // Torque's own remaining/DTE come from the level fed onto 012F, Torque's tank size and its long-term mpg.
    const val PLUGIN_BAND_CORRECTED = "[FuelSmooth] Corrected % (band)"
    const val PLUGIN_BAND_REMAINING = "[FuelSmooth] Remaining (band)"
    const val PLUGIN_BAND_DTE = "[FuelSmooth] DTE (band)"
    const val MODE_BAND_CORRECTED = "ffd012"
    const val MODE_BAND_REMAINING = "ffd013"
    const val MODE_BAND_DTE = "ffd014"
    const val PLUGIN_KALMAN_CORRECTED = "[FuelSmooth] Corrected % (kalman)"
    const val PLUGIN_KALMAN_REMAINING = "[FuelSmooth] Remaining (kalman)"
    const val PLUGIN_KALMAN_DTE = "[FuelSmooth] DTE (kalman)"
    const val MODE_KALMAN_CORRECTED = "ffd015"
    const val MODE_KALMAN_REMAINING = "ffd016"
    const val MODE_KALMAN_DTE = "ffd017"
    const val PLUGIN_CHUNK_CORRECTED = "[FuelSmooth] Corrected % (chunk)"
    const val PLUGIN_CHUNK_REMAINING = "[FuelSmooth] Remaining (chunk)"
    const val PLUGIN_CHUNK_DTE = "[FuelSmooth] DTE (chunk)"
    const val MODE_CHUNK_CORRECTED = "ffd018"
    const val MODE_CHUNK_REMAINING = "ffd019"
    const val MODE_CHUNK_DTE = "ffd01a"
    const val PLUGIN_TANK_CHUNK = "[FuelSmooth] Tank size (chunk)"
    const val PLUGIN_TANK_BAND = "[FuelSmooth] Tank size (band)"
    const val PLUGIN_TANK_KALMAN = "[FuelSmooth] Tank size (kalman)"
    const val MODE_TANK_CHUNK = "ffd01b"
    const val MODE_TANK_BAND = "ffd01c"
    const val MODE_TANK_KALMAN = "ffd01d"
    const val PLUGIN_T2_SMOOTHED = "[FuelSmooth] Tank2 Smoothed"
    const val PLUGIN_T2_CORRECTED = "[FuelSmooth] Tank2 Corrected"
    const val PLUGIN_T2_REMAINING = "[FuelSmooth] Tank2 Remaining"
    const val PLUGIN_T2_DTE = "[FuelSmooth] Tank2 DTE"
    const val MODE_SMOOTHED = "ffd001"

    enum class PluginPidKind { PERCENT, VOLUME }

    /**
     * One Torque extra-PID row. Register, publish and invalidate must use this exact
     * long name, short name, unit and min/max so Torque keeps a single entry per output.
     * Volume [unit] is pinned at registration ([volumeUnit]); a unit change can mint a new entry.
     */
    data class PluginPid(
        val name: String,
        val shortName: String,
        val modeAndPid: String,
        val kind: PluginPidKind,
        val percentMax: Float = 100f,
    )

    /** Tank-size gauge max: fixed so a learned-size change does not mint a new Torque entry. */
    const val TANK_SIZE_MAX_GAL = 100f

    fun volumeUnit(prefersLiters: Boolean): String = if (prefersLiters) "l" else "gal"

    fun volumeMax(prefersLiters: Boolean): Float =
        if (prefersLiters) TANK_SIZE_MAX_GAL * Units.LITERS_PER_GAL.toFloat() else TANK_SIZE_MAX_GAL

    fun tank1Outputs(): List<PluginPid> = listOf(
        PluginPid(PLUGIN_SMOOTHED, "SmFuel", MODE_SMOOTHED, PluginPidKind.PERCENT),
        PluginPid(PLUGIN_BAND_CORRECTED, "CorrBand", MODE_BAND_CORRECTED, PluginPidKind.PERCENT),
        PluginPid(PLUGIN_KALMAN_CORRECTED, "CorrKal", MODE_KALMAN_CORRECTED, PluginPidKind.PERCENT),
        PluginPid(PLUGIN_CHUNK_CORRECTED, "CorrChk", MODE_CHUNK_CORRECTED, PluginPidKind.PERCENT),
        PluginPid(PLUGIN_TANK_CHUNK, "TankChk", MODE_TANK_CHUNK, PluginPidKind.VOLUME),
        PluginPid(PLUGIN_TANK_BAND, "TankBand", MODE_TANK_BAND, PluginPidKind.VOLUME),
        PluginPid(PLUGIN_TANK_KALMAN, "TankKal", MODE_TANK_KALMAN, PluginPidKind.VOLUME),
    )

    fun extraOutputs(id: Int): List<PluginPid> {
        val n = extraPidNames(id)
        return listOf(
            PluginPid(n.smoothed, n.shortSm, extraModePid(id, 1), PluginPidKind.PERCENT),
            PluginPid(n.corrected, n.shortCorr, extraModePid(id, 2), PluginPidKind.PERCENT),
        )
    }

    fun allOutputs(extraIds: Iterable<Int> = emptyList()): List<PluginPid> =
        tank1Outputs() + extraIds.flatMap { extraOutputs(it) }

    fun output(name: String, extraIds: Iterable<Int> = emptyList()): PluginPid? =
        allOutputs(extraIds).firstOrNull { it.name == name }

    /** Retired Remaining/DTE long names. Never registered or written (a write would re-create them in Torque). */
    fun retiredNames(extraIds: Iterable<Int> = emptyList()): List<String> {
        val tank1 = listOf(
            PLUGIN_BAND_REMAINING, PLUGIN_BAND_DTE,
            PLUGIN_KALMAN_REMAINING, PLUGIN_KALMAN_DTE,
            PLUGIN_CHUNK_REMAINING, PLUGIN_CHUNK_DTE,
        )
        return tank1 + extraIds.flatMap { id ->
            val n = extraPidNames(id)
            listOf(n.remaining, n.dte)
        }
    }

    fun extraModePid(id: Int, which: Int): String {
        check(id in 2..8 && which in 1..4)
        return "ffd0$id$which"
    }

    data class ExtraPidNames(
        val smoothed: String,
        val corrected: String,
        val remaining: String,
        val dte: String,
        val shortSm: String,
        val shortCorr: String,
        val shortGal: String,
        val shortDte: String,
    )

    fun extraPidNames(id: Int): ExtraPidNames {
        check(id >= 2) { "extra PIDs start at tank 2" }
        if (id == 2) {
            return ExtraPidNames(
                PLUGIN_T2_SMOOTHED,
                PLUGIN_T2_CORRECTED,
                PLUGIN_T2_REMAINING,
                PLUGIN_T2_DTE,
                "T2Sm",
                "T2Corr",
                "T2Gal",
                "T2DTE",
            )
        }
        return ExtraPidNames(
            "[FuelSmooth] Tank${id} Smoothed",
            "[FuelSmooth] Tank${id} Corrected",
            "[FuelSmooth] Tank${id} Remaining",
            "[FuelSmooth] Tank${id} DTE",
            "T${id}Sm",
            "T${id}Corr",
            "T${id}Gal",
            "T${id}DTE",
        )
    }

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
        val galHr: String?,
        val galMin: String?,
        val mpg: String,
        val fuelUsedUnit: String,
        val galHrUnit: String,
        val galMinUnit: String,
        val mpgUnit: String,
        val names: Map<String, String>,
    )

    fun resolve(torque: ITorqueService): Resolved {
        val all = torque.listAllPIDs() ?: emptyArray()
        val infos = if (all.isEmpty()) emptyArray() else torque.getPIDInformation(all) ?: emptyArray()
        val n = minOf(all.size, infos.size)
        val names = LinkedHashMap<String, String>()
        var fordByName: String? = null
        for (i in 0 until n) {
            val nm = csvName(infos[i])
            if (nm.isNotEmpty() && nm !in names) names[nm] = all[i]
            if (nm == FORD_FUEL_NAME && fordByName == null) fordByName = all[i]
        }
        val storedPrefix = FORD_FUEL_STORED.lowercase()
        val ford = fordByName
            ?: all.firstOrNull { it.equals(FORD_FUEL_STORED, ignoreCase = true) }
            ?: all.firstOrNull { it.lowercase().startsWith(storedPrefix) }
        fun pick(vararg ids: String): String? {
            val lower = all.map { it.lowercase() }
            for (id in ids) {
                val i = lower.indexOf(id.lowercase())
                if (i >= 0) return all[i]
            }
            for (id in ids) {
                val i = lower.indexOfFirst { it.startsWith(id.lowercase()) }
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
        val fuelUsed = pick(FUEL_USED_TRIP)
        val galHr = pick(GAL_PER_HOUR)
        val galMin = pick(GAL_PER_MIN)
        val mpg = pick(LONG_TERM_MPG) ?: LONG_TERM_MPG
        return Resolved(
            ford = ford,
            speed = speed,
            fuelUsed = fuelUsed,
            galHr = galHr,
            galMin = galMin,
            mpg = mpg,
            fuelUsedUnit = unitOf(fuelUsed),
            galHrUnit = unitOf(galHr),
            galMinUnit = unitOf(galMin),
            mpgUnit = unitOf(mpg),
            names = names,
        )
    }
}
