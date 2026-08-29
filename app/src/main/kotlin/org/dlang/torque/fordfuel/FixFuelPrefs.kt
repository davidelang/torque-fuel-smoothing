package org.dlang.torque.fordfuel

import android.content.Context
import android.content.SharedPreferences

internal data class FixFuelConfig(
    val sourceId: String,
    val customName: String,
    val tauSec: Double,
    val skewPctPerSec: Double,
    val jumpPct: Double,
    val tankGal: Double?,
    val learnedUsable: Double?,
    val tankEstimated: Boolean,
    val sourceStatus: String,
    val tank2Enabled: Boolean = false,
    val sourceId2: String = FixFuelPrefs.DEFAULT_SOURCE_T2,
    val customName2: String = "",
    val tankGal2: Double? = null,
    val learnedUsable2: Double? = null,
    val tankEstimated2: Boolean = false,
    val sourceStatus2: String = "",
    val activeTank: String = FixFuelPrefs.ACTIVE_TANK1,
    val selectPidName: String = "",
)

internal object FixFuelPrefs {
    const val PREFS = "fixfuel_prefs"
    const val KEY_SOURCE = "source_id"
    const val KEY_CUSTOM = "custom_name"
    const val KEY_TAU = "tau_sec"
    const val KEY_SKEW = "skew_pct_per_sec"
    const val KEY_JUMP = "jump_pct"
    const val KEY_TANK = "tank_gal"
    const val KEY_LEARNED = "learned_usable"
    const val KEY_ESTIMATED = "tank_estimated"
    const val KEY_STATUS = "source_status"
    const val KEY_LIVE = "live_status"
    const val KEY_T2_ON = "tank2_enabled"
    const val KEY_SOURCE2 = "source_id_2"
    const val KEY_CUSTOM2 = "custom_name_2"
    const val KEY_TANK2 = "tank_gal_2"
    const val KEY_LEARNED2 = "learned_usable_2"
    const val KEY_ESTIMATED2 = "tank_estimated_2"
    const val KEY_STATUS2 = "source_status_2"
    const val KEY_ACTIVE = "active_tank"
    const val KEY_SELECT_PID = "select_pid_name"

    const val DEFAULT_SOURCE = "ford_16c1"
    const val DEFAULT_SOURCE_T2 = "custom_name"
    const val ACTIVE_TANK1 = "tank1"
    const val ACTIVE_TANK2 = "tank2"
    const val ACTIVE_FROM_PID = "from_pid"
    const val DEFAULT_TAU = 0.0
    const val DEFAULT_SKEW = 0.05
    const val DEFAULT_JUMP = 25.0

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): FixFuelConfig {
        val p = prefs(ctx)
        val tankRaw = p.getString(KEY_TANK, "") ?: ""
        val tank = tankRaw.toDoubleOrNull()?.takeIf { it > 0.0 }
        val learnedRaw = p.getString(KEY_LEARNED, "") ?: ""
        val learned = learnedRaw.toDoubleOrNull()?.takeIf { it > 0.0 }
        return FixFuelConfig(
            sourceId = p.getString(KEY_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE,
            customName = p.getString(KEY_CUSTOM, "") ?: "",
            tauSec = p.getString(KEY_TAU, DEFAULT_TAU.toString())?.toDoubleOrNull() ?: DEFAULT_TAU,
            skewPctPerSec = p.getString(KEY_SKEW, DEFAULT_SKEW.toString())?.toDoubleOrNull() ?: DEFAULT_SKEW,
            jumpPct = p.getString(KEY_JUMP, DEFAULT_JUMP.toString())?.toDoubleOrNull() ?: DEFAULT_JUMP,
            tankGal = tank,
            learnedUsable = learned,
            tankEstimated = p.getBoolean(KEY_ESTIMATED, false),
            sourceStatus = p.getString(KEY_STATUS, "") ?: "",
            tank2Enabled = p.getBoolean(KEY_T2_ON, false),
            sourceId2 = p.getString(KEY_SOURCE2, DEFAULT_SOURCE_T2) ?: DEFAULT_SOURCE_T2,
            customName2 = p.getString(KEY_CUSTOM2, "") ?: "",
            tankGal2 = (p.getString(KEY_TANK2, "") ?: "").toDoubleOrNull()?.takeIf { it > 0.0 },
            learnedUsable2 = (p.getString(KEY_LEARNED2, "") ?: "").toDoubleOrNull()?.takeIf { it > 0.0 },
            tankEstimated2 = p.getBoolean(KEY_ESTIMATED2, false),
            sourceStatus2 = p.getString(KEY_STATUS2, "") ?: "",
            activeTank = p.getString(KEY_ACTIVE, ACTIVE_TANK1) ?: ACTIVE_TANK1,
            selectPidName = p.getString(KEY_SELECT_PID, "") ?: "",
        )
    }

    fun save(
        ctx: Context,
        sourceId: String,
        customName: String,
        tauSec: Double,
        skewPctPerSec: Double,
        jumpPct: Double,
        tankGal: Double?,
        tank2Enabled: Boolean = false,
        sourceId2: String = DEFAULT_SOURCE_T2,
        customName2: String = "",
        tankGal2: Double? = null,
        activeTank: String = ACTIVE_TANK1,
        selectPidName: String = "",
    ) {
        val e = prefs(ctx).edit()
            .putString(KEY_SOURCE, sourceId)
            .putString(KEY_CUSTOM, customName)
            .putString(KEY_TAU, tauSec.toString())
            .putString(KEY_SKEW, skewPctPerSec.toString())
            .putString(KEY_JUMP, jumpPct.toString())
        if (tankGal != null && tankGal > 0.0) e.putString(KEY_TANK, tankGal.toString())
        else e.putString(KEY_TANK, "")
        e.putBoolean(KEY_T2_ON, tank2Enabled)
            .putString(KEY_SOURCE2, sourceId2)
            .putString(KEY_CUSTOM2, customName2)
        if (tankGal2 != null && tankGal2 > 0.0) e.putString(KEY_TANK2, tankGal2.toString())
        else e.putString(KEY_TANK2, "")
        e.putString(KEY_ACTIVE, activeTank)
            .putString(KEY_SELECT_PID, selectPidName)
        e.apply()
    }

    fun setLive(ctx: Context, line: String) {
        prefs(ctx).edit().putString(KEY_LIVE, line).apply()
    }

    fun live(ctx: Context): String = prefs(ctx).getString(KEY_LIVE, "") ?: ""

    fun setSourceStatus(ctx: Context, status: String) {
        prefs(ctx).edit().putString(KEY_STATUS, status).apply()
    }

    fun setLearnedUsable(ctx: Context, gal: Double) {
        prefs(ctx).edit()
            .putString(KEY_LEARNED, gal.toString())
            .putString(KEY_TANK, gal.toString())
            .putBoolean(KEY_ESTIMATED, true)
            .apply()
    }

    fun setLearnedUsable2(ctx: Context, gal: Double) {
        prefs(ctx).edit()
            .putString(KEY_LEARNED2, gal.toString())
            .putString(KEY_TANK2, gal.toString())
            .putBoolean(KEY_ESTIMATED2, true)
            .apply()
    }

    fun setSourceStatus2(ctx: Context, status: String) {
        prefs(ctx).edit().putString(KEY_STATUS2, status).apply()
    }
}
