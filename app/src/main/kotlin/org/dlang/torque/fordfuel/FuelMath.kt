package org.dlang.torque.fordfuel

import kotlin.math.abs

internal data class Bin(
    val lo: Double,
    val hi: Double,
    val gal: Double,
)

internal class FuelMath {
    var ema: Double? = null
        private set

    fun reset() {
        ema = null
    }

    fun smooth(raw: Double, alpha: Double = ALPHA): Double {
        val prev = ema
        val y = if (prev == null) raw else alpha * raw + (1.0 - alpha) * prev
        ema = y
        return y
    }

    fun remainingGal(pct: Double, bins: List<Bin>): Double {
        val p = pct.coerceIn(0.0, 100.0)
        val hit = covering(p, bins)
        if (hit.isEmpty()) return p / 100.0 * Units.TANK_GAL
        return hit.map { b ->
            val span = (b.hi - b.lo).coerceAtLeast(1e-6)
            val frac = ((p - b.lo) / span).coerceIn(0.0, 1.0)
            b.lo / 100.0 * Units.TANK_GAL + frac * b.gal
        }.average()
    }

    fun mappedPct(smoothed: Double, bins: List<Bin>): Double {
        if (bins.isEmpty()) return smoothed
        if (covering(smoothed, bins).isEmpty()) return smoothed
        return (remainingGal(smoothed, bins) / Units.TANK_GAL * 100.0).coerceIn(0.0, 100.0)
    }

    fun dteMiles(remainingGal: Double, mpg: Double): Double {
        if (mpg <= 0.0 || mpg.isNaN()) return 0.0
        return remainingGal * mpg
    }

    companion object {
        const val ALPHA = 0.25

        fun covering(p: Double, bins: List<Bin>): List<Bin> {
            return bins.filter { b -> p >= b.lo && p < b.hi || (p == 100.0 && p == b.hi) }
        }

        fun observationsFrom(samples: List<Sample>): List<Bin> {
            if (samples.size < 2) return emptyList()
            val out = mutableListOf<Bin>()
            var a = 0
            for (b in 1 until samples.size) {
                val rise = samples[b].rawPct - samples[b - 1].rawPct
                if (rise > 2.0) {
                    a = b
                    continue
                }
                val drop = samples[a].rawPct - samples[b].rawPct
                val used = samples[b].fuelUsedGal - samples[a].fuelUsedGal
                if (drop >= 1.0 && used >= 0.05) {
                    out.add(Bin(lo = samples[b].rawPct, hi = samples[a].rawPct, gal = used))
                    a = b
                }
            }
            return out
        }

        fun mpgFrom(value: Double, unit: String): Double {
            val u = unit.lowercase()
            return when {
                u.contains("l/100") || u.contains("l100") -> if (value > 0.0) 235.215 / value else 0.0
                u.contains("km/l") || u.contains("kpl") -> value * 2.35215
                else -> value
            }
        }

        fun crossedOnePercent(prev: Double?, now: Double): Boolean {
            if (prev == null) return true
            return abs(now - prev) >= 1.0
        }
    }
}
