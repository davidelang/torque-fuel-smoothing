package com.davidlang.torque.fuelsmoothing

import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Trusted fuel-level samples for VIN-less auto-match, and a live guard for a source that stays
 * frozen while fuel is clearly being used (as after the 2026-11-12 mid-drive upgrade, when
 * ford_16c1 stuck at 51.66% for 48 miles / 3.73 gal).
 *
 * The mid-drive freeze was not reproduced here with certainty. The named [FORD]Fuel Level path
 * reads Torque's cached value; the auto-match refresh probe was not running during that 82-minute
 * stretch. This guard asks Torque to refresh the PID and then treats the source as stale if the
 * level still does not move.
 */
internal object LevelTrust {
    const val STALE_MIN_GAL = 0.5
    const val STALE_FRAC = 0.015
    const val LEVEL_EPS = 0.05
    const val MAX_PCT = 110.0
    const val WHY_FROZEN = "frozen"
    const val WHY_STALE = "stale"
    const val WHY_MISSING = "missing"

    /** 1.5% of a learned tank, but never less than 0.5 gal. With no size, 0.5 gal. */
    fun staleBurnGal(tankGal: Double?): Double {
        val tank = tankGal?.takeIf { it.isFinite() && it > 0.0 }
        return if (tank == null) STALE_MIN_GAL else max(STALE_MIN_GAL, STALE_FRAC * tank)
    }

    fun isTrustedWhy(why: String?): Boolean {
        val w = why?.trim().orEmpty()
        return w.isEmpty() || (w != WHY_FROZEN && w != WHY_STALE && w != WHY_MISSING)
    }

    fun isTrustedLevel(sm: Double): Boolean = sm.isFinite() && sm > 0.0 && sm <= MAX_PCT

    /**
     * Last trusted smoothed tank level for [sourceId], newest raw sessions first.
     * Skips ok:false, frozen/stale/missing why, the wrong source, and out-of-range sm.
     */
    fun lastTrustedLevel(rawDir: File, sourceId: String, takeFiles: Int = 40): Double? {
        if (sourceId.isBlank() || sourceId == FuelDetect.NONE) return null
        val files = rawDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.name }?.take(takeFiles) ?: return null
        for (f in files) {
            val lines = runCatching { f.readLines() }.getOrNull() ?: continue
            for (line in lines.asReversed()) {
                if (!line.startsWith("{\"t\":")) continue
                val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                if (o.optString("lvlSrc") != sourceId) continue
                if (!o.optBoolean("ok", true)) continue
                if (!isTrustedWhy(o.optString("why", ""))) continue
                val sm = o.optDouble("sm", Double.NaN)
                if (isTrustedLevel(sm)) return sm
            }
        }
        return null
    }
}

internal class StaleLevelGuard {
    var lastLevel: Double? = null
        private set
    var usedAtChange: Double = 0.0
        private set
    var refreshAsked: Boolean = false
        private set
    var logged: Boolean = false

    enum class Verdict { OK, REFRESH, STALE }

    fun observe(level: Double, usedGal: Double, tankGal: Double?): Verdict {
        if (!level.isFinite()) return Verdict.OK
        val prev = lastLevel
        if (prev == null || abs(level - prev) >= LevelTrust.LEVEL_EPS) {
            lastLevel = level
            usedAtChange = usedGal
            refreshAsked = false
            logged = false
            return Verdict.OK
        }
        if (usedGal - usedAtChange < LevelTrust.staleBurnGal(tankGal)) return Verdict.OK
        if (!refreshAsked) {
            refreshAsked = true
            return Verdict.REFRESH
        }
        return Verdict.STALE
    }

    fun reset() {
        lastLevel = null
        usedAtChange = 0.0
        refreshAsked = false
        logged = false
    }
}
