package org.dlang.torque.fordfuel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal data class ExtraTank(
    val id: Int,
    val sourceId: String = FixFuelPrefs.DEFAULT_SOURCE_T2,
    val customName: String = "",
    val tankGal: Double? = null,
    val learnedUsable: Double? = null,
    val tankEstimated: Boolean = false,
    val sourceStatus: String = "",
)

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
    val extras: List<ExtraTank> = emptyList(),
    val activeTank: String = FixFuelPrefs.ACTIVE_TANK1,
    val selectPidName: String = "",
    val showFuelNotification: Boolean = true,
) {
    val tank2Enabled: Boolean get() = extras.any { it.id == 2 }
    private val extra2: ExtraTank? get() = extras.firstOrNull { it.id == 2 }
    val sourceId2: String get() = extra2?.sourceId ?: FixFuelPrefs.DEFAULT_SOURCE_T2
    val customName2: String get() = extra2?.customName ?: ""
    val tankGal2: Double? get() = extra2?.tankGal
    val learnedUsable2: Double? get() = extra2?.learnedUsable
    val tankEstimated2: Boolean get() = extra2?.tankEstimated ?: false
    val sourceStatus2: String get() = extra2?.sourceStatus ?: ""

    fun extra(id: Int): ExtraTank? = extras.firstOrNull { it.id == id }
}

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
    const val KEY_EXTRAS = "extra_tanks"
    const val KEY_EXTRAS_MIGRATED = "extra_tanks_migrated"
    const val KEY_SHOW_NOTICE = "show_fuel_notification"

    const val DEFAULT_SOURCE = "ford_16c1"
    const val DEFAULT_SOURCE_T2 = "custom_name"
    const val ACTIVE_TANK1 = "1"
    const val ACTIVE_TANK2 = "2"
    const val ACTIVE_FROM_PID = "from_pid"
    const val DEFAULT_TAU = 0.0
    const val DEFAULT_SKEW = 0.05
    const val DEFAULT_JUMP = 25.0
    const val MAX_TANKS = 8

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): FixFuelConfig {
        val p = prefs(ctx)
        migrateOnce(p)
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
            extras = parseExtras(p.getString(KEY_EXTRAS, "[]") ?: "[]"),
            activeTank = normalizeActive(p.getString(KEY_ACTIVE, ACTIVE_TANK1) ?: ACTIVE_TANK1),
            selectPidName = p.getString(KEY_SELECT_PID, "") ?: "",
            showFuelNotification = p.getBoolean(KEY_SHOW_NOTICE, true),
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
        extras: List<ExtraTank>,
        activeTank: String = ACTIVE_TANK1,
        selectPidName: String = "",
        showFuelNotification: Boolean = true,
    ) {
        val e = prefs(ctx).edit()
            .putString(KEY_SOURCE, sourceId)
            .putString(KEY_CUSTOM, customName)
            .putString(KEY_TAU, tauSec.toString())
            .putString(KEY_SKEW, skewPctPerSec.toString())
            .putString(KEY_JUMP, jumpPct.toString())
        if (tankGal != null && tankGal > 0.0) e.putString(KEY_TANK, tankGal.toString())
        else e.putString(KEY_TANK, "")
        e.putString(KEY_EXTRAS, extrasToJson(extras))
            .putString(KEY_ACTIVE, normalizeActive(activeTank))
            .putString(KEY_SELECT_PID, selectPidName)
            .putBoolean(KEY_SHOW_NOTICE, showFuelNotification)
            .putBoolean(KEY_EXTRAS_MIGRATED, true)
        e.apply()
    }

    fun nextExtraId(extras: List<ExtraTank>): Int {
        val maxId = extras.maxOfOrNull { it.id } ?: 1
        return maxOf(maxId, 1) + 1
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
        setLearnedUsableExtra(ctx, 2, gal)
    }

    fun setSourceStatus2(ctx: Context, status: String) {
        setExtraSourceStatus(ctx, 2, status)
    }

    fun setLearnedUsableExtra(ctx: Context, id: Int, gal: Double) {
        if (id <= 1) {
            setLearnedUsable(ctx, gal)
            return
        }
        val p = prefs(ctx)
        val extras = parseExtras(p.getString(KEY_EXTRAS, "[]") ?: "[]").map { t ->
            if (t.id == id) t.copy(learnedUsable = gal, tankGal = gal, tankEstimated = true) else t
        }
        p.edit().putString(KEY_EXTRAS, extrasToJson(extras)).apply()
    }

    fun setExtraSourceStatus(ctx: Context, id: Int, status: String) {
        if (id <= 1) {
            setSourceStatus(ctx, status)
            return
        }
        val p = prefs(ctx)
        val extras = parseExtras(p.getString(KEY_EXTRAS, "[]") ?: "[]").map { t ->
            if (t.id == id) t.copy(sourceStatus = status) else t
        }
        p.edit().putString(KEY_EXTRAS, extrasToJson(extras)).apply()
    }

    fun extrasToJson(list: List<ExtraTank>): String {
        val arr = JSONArray()
        for (t in list.sortedBy { it.id }) {
            val o = JSONObject()
                .put("id", t.id)
                .put("sourceId", t.sourceId)
                .put("customName", t.customName)
                .put("estimated", t.tankEstimated)
                .put("sourceStatus", t.sourceStatus)
            if (t.tankGal != null && t.tankGal > 0.0) o.put("tankGal", t.tankGal)
            else o.put("tankGal", "")
            if (t.learnedUsable != null && t.learnedUsable > 0.0) o.put("learnedUsable", t.learnedUsable)
            else o.put("learnedUsable", "")
            arr.put(o)
        }
        return arr.toString()
    }

    fun parseExtras(raw: String): List<ExtraTank> {
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optInt("id", 0)
                if (id < 2 || id > MAX_TANKS) return@mapNotNull null
                ExtraTank(
                    id = id,
                    sourceId = o.optString("sourceId", DEFAULT_SOURCE_T2).ifBlank { DEFAULT_SOURCE_T2 },
                    customName = o.optString("customName", ""),
                    tankGal = o.optString("tankGal", "").toDoubleOrNull()?.takeIf { it > 0.0 }
                        ?: o.optDouble("tankGal", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                    learnedUsable = o.optString("learnedUsable", "").toDoubleOrNull()?.takeIf { it > 0.0 }
                        ?: o.optDouble("learnedUsable", Double.NaN).takeIf { it.isFinite() && it > 0.0 },
                    tankEstimated = o.optBoolean("estimated", false),
                    sourceStatus = o.optString("sourceStatus", ""),
                )
            }.distinctBy { it.id }.sortedBy { it.id }
        }.getOrDefault(emptyList())
    }

    fun normalizeActive(raw: String): String {
        return when (raw) {
            "tank1", ACTIVE_TANK1 -> ACTIVE_TANK1
            "tank2", ACTIVE_TANK2 -> ACTIVE_TANK2
            ACTIVE_FROM_PID -> ACTIVE_FROM_PID
            else -> raw.toIntOrNull()?.takeIf { it in 1..MAX_TANKS }?.toString() ?: ACTIVE_TANK1
        }
    }

    private fun migrateOnce(p: SharedPreferences) {
        if (p.getBoolean(KEY_EXTRAS_MIGRATED, false)) return
        val extras = mutableListOf<ExtraTank>()
        if (p.getBoolean(KEY_T2_ON, false)) {
            extras.add(
                ExtraTank(
                    id = 2,
                    sourceId = p.getString(KEY_SOURCE2, DEFAULT_SOURCE_T2) ?: DEFAULT_SOURCE_T2,
                    customName = p.getString(KEY_CUSTOM2, "") ?: "",
                    tankGal = (p.getString(KEY_TANK2, "") ?: "").toDoubleOrNull()?.takeIf { it > 0.0 },
                    learnedUsable = (p.getString(KEY_LEARNED2, "") ?: "").toDoubleOrNull()?.takeIf { it > 0.0 },
                    tankEstimated = p.getBoolean(KEY_ESTIMATED2, false),
                    sourceStatus = p.getString(KEY_STATUS2, "") ?: "",
                ),
            )
        }
        p.edit()
            .putString(KEY_EXTRAS, extrasToJson(extras))
            .putString(KEY_ACTIVE, normalizeActive(p.getString(KEY_ACTIVE, "tank1") ?: "tank1"))
            .putBoolean(KEY_EXTRAS_MIGRATED, true)
            .apply()
    }
}
