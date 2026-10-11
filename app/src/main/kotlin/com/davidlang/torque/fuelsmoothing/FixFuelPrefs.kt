package com.davidlang.torque.fuelsmoothing

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
    /** What the plugin feeds back into Torque's Fuel Level (012F). Default = previous behavior (corrected). */
    val feed012f: FeedMode = FeedMode.CORRECTED,
    /** Which map drives the primary Corrected/Remaining/DTE and the Corrected 012F feed. */
    val primaryAlgo: MapAlgo = MapAlgo.CHUNK,
    /** Stored values that failed validation: pref key -> raw stored text. Runtime uses defaults for these. */
    val invalid: Map<String, String> = emptyMap(),
) {
    val write012f: Boolean get() = feed012f != FeedMode.OFF

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
    const val KEY_SHOW_NOTICE = "show_fuel_notification"
    const val KEY_WRITE_012F = "write_012f"
    const val KEY_FEED_012F = "feed_012f"
    const val KEY_PRIMARY_ALGO = "primary_algo"

    fun mapAlgo(stored: String?): MapAlgo = MapAlgo.entries.firstOrNull { it.name == stored } ?: MapAlgo.CHUNK

    /** Primary output: the band result only when Band is chosen and a band model exists; otherwise chunk. */
    fun <T> primary(algo: MapAlgo, chunk: T, band: T?): T = if (algo == MapAlgo.BAND && band != null) band else chunk

    /** Stored mode, else the legacy checkbox (unchecked = OFF, checked/absent = CORRECTED). */
    fun feedMode(stored: String?, legacyChecked: Boolean?): FeedMode =
        FeedMode.entries.firstOrNull { it.name == stored } ?: if (legacyChecked == false) FeedMode.OFF else FeedMode.CORRECTED

    /** Value written to 012F, or null for no write. */
    fun feedValue(mode: FeedMode, smoothedPct: Double?, correctedPct: Double?): Double? = when (mode) {
        FeedMode.OFF -> null
        FeedMode.SMOOTHED -> smoothedPct?.takeIf { it.isFinite() }
        FeedMode.CORRECTED -> correctedPct?.takeIf { it.isFinite() }
    }
    const val KEY_FILL_ANCHOR = "fill_anchor_gal"
    const val KEY_SINCE_FILL = "since_fill_used_gal"

    const val DEFAULT_SOURCE = "ford_16c1"
    const val DEFAULT_SOURCE_T2 = "custom_name"
    const val ACTIVE_TANK1 = "1"
    const val ACTIVE_TANK2 = "2"
    const val ACTIVE_FROM_PID = "from_pid"
    const val DEFAULT_TAU = 0.0
    const val DEFAULT_SKEW = 0.05
    const val DEFAULT_JUMP = 25.0
    const val MAX_TANKS = 8

    const val MIN_JUMP = 10.0
    const val MAX_JUMP = 100.0
    const val MAX_TAU = 600.0
    const val MAX_SKEW = 10.0

    /** Smoothing time constant: finite, 0..MAX_TAU. */
    fun tauOk(v: Double?): Boolean = v != null && v.isFinite() && v >= 0.0 && v <= MAX_TAU

    /** Slew limit: finite and > 0 (0 would freeze the smoothed level). */
    fun skewOk(v: Double?): Boolean = v != null && v.isFinite() && v > 0.0 && v <= MAX_SKEW

    /** Fill jump: below MIN_JUMP any slosh while stopped would enter FILL. */
    fun jumpOk(v: Double?): Boolean = v != null && v.isFinite() && v >= MIN_JUMP && v <= MAX_JUMP

    /** Tank guess: blank (null) is allowed; otherwise finite and > 0. */
    fun tankOk(raw: String): Boolean {
        val t = raw.trim()
        if (t.isEmpty()) return true
        val v = t.toDoubleOrNull() ?: return false
        return v.isFinite() && v > 0.0
    }

    /** Corrupt stored value: log, record it in [invalid], and fall back to [def] for runtime only. */
    private fun checked(
        p: SharedPreferences,
        key: String,
        def: Double,
        ok: (Double?) -> Boolean,
        invalid: MutableMap<String, String>,
    ): Double {
        val raw = p.getString(key, def.toString()) ?: def.toString()
        val v = raw.trim().toDoubleOrNull()
        if (ok(v)) return v!!
        invalid[key] = raw
        android.util.Log.w("FuelSmooth", "stored $key=\"$raw\" is out of range; using $def until corrected in settings")
        return def
    }

    fun prefs(ctx: Context): SharedPreferences = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): FixFuelConfig {
        val p = prefs(ctx)
        val invalid = LinkedHashMap<String, String>()
        // Tank size is per profile (profile.json tankGal). The old shared setting seeds the active profile once.
        val root = ctx.filesDir
        val pid = ProfileStore.activeId(root)
        if (ProfileStore.profileField(root, pid, "tankGal") == null) {
            val legacy = p.getString(KEY_TANK, "")?.trim().orEmpty()
            if (legacy.isNotEmpty() && tankOk(legacy)) {
                ProfileStore.setTankEntered(root, pid, legacy)
                if (p.getBoolean(KEY_ESTIMATED, false)) ProfileStore.setProfileField(root, pid, "tankEstimated", "true")
            }
        }
        val (profTank, profEst) = ProfileStore.tankGuess(root, pid)
        val tankRaw = profTank ?: ""
        val tank = tankRaw.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
        if (!tankOk(tankRaw)) {
            invalid[KEY_TANK] = tankRaw
            android.util.Log.w("FuelSmooth", "stored $KEY_TANK=\"$tankRaw\" is invalid; treated as no tank guess")
        }
        val learnedRaw = p.getString(KEY_LEARNED, "") ?: ""
        val learned = learnedRaw.toDoubleOrNull()?.takeIf { it > 0.0 }
        return FixFuelConfig(
            sourceId = p.getString(KEY_SOURCE, DEFAULT_SOURCE) ?: DEFAULT_SOURCE,
            customName = p.getString(KEY_CUSTOM, "") ?: "",
            tauSec = checked(p, KEY_TAU, DEFAULT_TAU, ::tauOk, invalid),
            skewPctPerSec = checked(p, KEY_SKEW, DEFAULT_SKEW, ::skewOk, invalid),
            jumpPct = checked(p, KEY_JUMP, DEFAULT_JUMP, ::jumpOk, invalid),
            tankGal = tank,
            learnedUsable = learned,
            tankEstimated = profEst,
            sourceStatus = p.getString(KEY_STATUS, "") ?: "",
            extras = parseExtras(p.getString(KEY_EXTRAS, "[]") ?: "[]"),
            activeTank = normalizeActive(p.getString(KEY_ACTIVE, ACTIVE_TANK1) ?: ACTIVE_TANK1),
            selectPidName = p.getString(KEY_SELECT_PID, "") ?: "",
            showFuelNotification = p.getBoolean(KEY_SHOW_NOTICE, true),
            primaryAlgo = mapAlgo(p.getString(KEY_PRIMARY_ALGO, null)),
            feed012f = feedMode(p.getString(KEY_FEED_012F, null), if (p.contains(KEY_WRITE_012F)) p.getBoolean(KEY_WRITE_012F, true) else null),
            invalid = invalid,
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
        feed012f: FeedMode = FeedMode.CORRECTED,
        primaryAlgo: MapAlgo = MapAlgo.CHUNK,
    ) {
        val e = prefs(ctx).edit()
            .putString(KEY_SOURCE, sourceId)
            .putString(KEY_CUSTOM, customName)
            .putString(KEY_TAU, tauSec.toString())
            .putString(KEY_SKEW, skewPctPerSec.toString())
            .putString(KEY_JUMP, jumpPct.toString())
        // Per profile. A value typed by the user is a guess, not a learned estimate (unchanged value keeps the flag).
        val root = ctx.filesDir
        val pid = ProfileStore.activeId(root)
        val oldTank = ProfileStore.tankGuess(root, pid).first?.toDoubleOrNull()
        val newRaw = if (tankGal != null && tankGal.isFinite() && tankGal > 0.0) tankGal.toString() else ""
        if (oldTank == null || tankGal == null || kotlin.math.abs(oldTank - tankGal) >= 0.05) ProfileStore.setTankEntered(root, pid, newRaw)
        e.putString(KEY_EXTRAS, extrasToJson(extras))
            .putString(KEY_ACTIVE, normalizeActive(activeTank))
            .putString(KEY_SELECT_PID, selectPidName)
            .putBoolean(KEY_SHOW_NOTICE, showFuelNotification)
            .putString(KEY_FEED_012F, feed012f.name)
            .putString(KEY_PRIMARY_ALGO, primaryAlgo.name)
            .putBoolean(KEY_WRITE_012F, feed012f != FeedMode.OFF)
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

    fun fillAnchorGal(ctx: Context): Double? {
        val raw = prefs(ctx).getString(KEY_FILL_ANCHOR, "") ?: ""
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
    }

    fun sinceFillUsedGal(ctx: Context): Double {
        val raw = prefs(ctx).getString(KEY_SINCE_FILL, "") ?: ""
        return raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }

    /** Tank 1 only. Replaces the anchor and the burned-since-fill total together. */
    fun setFillLedger(ctx: Context, anchorGal: Double, sinceUsedGal: Double) {
        val used = if (sinceUsedGal.isFinite()) sinceUsedGal.coerceAtLeast(0.0) else 0.0
        val e = prefs(ctx).edit()
        if (anchorGal.isFinite() && anchorGal > 0.0) e.putString(KEY_FILL_ANCHOR, anchorGal.toString())
        else e.putString(KEY_FILL_ANCHOR, "")
        e.putString(KEY_SINCE_FILL, used.toString()).apply()
    }

    fun setSourceStatus2(ctx: Context, status: String) {
        setExtraSourceStatus(ctx, 2, status)
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
}

/** Feed-back of fuel level into Torque's 012F slot. */
enum class FeedMode(val label: String) {
    OFF("Off"),
    SMOOTHED("Smoothed %"),
    CORRECTED("Corrected % (linearized)"),
}

/** Gauge-map algorithm for the primary outputs. */
enum class MapAlgo(val label: String) {
    CHUNK("Chunk fit (default)"),
    BAND("Band fit (smooth curve)"),
}
