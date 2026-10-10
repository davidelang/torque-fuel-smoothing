package com.davidlang.torque.fuelsmoothing

internal data class FuelSource(
    val id: String,
    val label: String,
    val longName: String? = null,
    val modePid: String? = null,
    val header: String? = null,
    val litersToPct: Boolean = false,
)

internal object FuelSources {
    /**
     * Level scale for liter-reporting sources: 100 "%" = 150 L. Covers the largest Toyota/Lexus
     * tanks (~144 L) while keeping the % thresholds (fill jump 25 → 37.5 L, drop hold 15 → 22.5 L,
     * near-empty 5 → 7.5 L) physically sensible.
     */
    const val LITERS_FULL_SCALE = 150.0

    val catalog: List<FuelSource> = listOf(
        FuelSource("sae_012f", "SAE 012F Fuel Tank Level", modePid = "012F"),
        FuelSource("ford_16c1", "Mode 22 16C1", longName = "[FORD]Fuel Level"),
        FuelSource("ford_f42f", "Ford Mode 22 F42F", longName = null, modePid = "22F42F"),
        FuelSource("gm_12c5", "GM Mode 22 12C5 %", modePid = "2212C5"),
        FuelSource("chrysler_21a2", "Chrysler 21A2 %", modePid = "21A2"),
        FuelSource("chrysler_avg", "Chrysler Fuel Level Average", modePid = "228126"),
        FuelSource("toyota_7c0_2129", "Toyota cluster 2129 (liters→%)", modePid = "2129", header = "7C0", litersToPct = true),
        FuelSource("custom_name", "Torque extra PID by long name"),
    )

    fun byId(id: String): FuelSource = catalog.firstOrNull { it.id == id } ?: catalog.first { it.id == "ford_16c1" }

    fun labels(): Array<String> = catalog.map { it.label }.toTypedArray()

    fun indexOf(id: String): Int {
        val i = catalog.indexOfFirst { it.id == id }
        return if (i >= 0) i else catalog.indexOfFirst { it.id == "ford_16c1" }
    }

    data class Read(
        val pct: Double?,
        val how: String,
        val missing: Boolean,
    )

    fun read(
        torque: org.prowl.torque.remote.ITorqueService,
        cfg: FixFuelConfig,
        names: Map<String, String>,
        getValue: (String) -> Double?,
    ): Read {
        val src = byId(cfg.sourceId)
        if (src.id == "sae_012f") {
            val resp = bus(torque, "", "012F") ?: bus(torque, "", "01 2F")
            val a = parseAAfter(resp, "412F")
            val pct = a?.let { it * 100.0 / 255.0 }
            return if (pct != null) Read(pct, "bus 012F A*100/255", false)
            else Read(null, "missing bus 012F (add extra PID set or ECU has no 012F)", true)
        }
        if (src.id == "custom_name") {
            val want = cfg.customName.trim()
            if (want.isEmpty()) return Read(null, "set custom long name", true)
            val id = names[want] ?: names.entries.firstOrNull { it.key.equals(want, ignoreCase = true) }?.value
            val v = id?.let(getValue)
            return if (v != null) Read(v, "name $want", false)
            else Read(null, "add extra PID set named $want", true)
        }
        src.longName?.let { ln ->
            val id = names[ln]
            val v = id?.let(getValue)
            if (v != null) return Read(v, "name $ln", false)
        }
        val cmd = src.modePid
        if (cmd != null) {
            val resp = bus(torque, src.header ?: "", cmd)
            val marker = positiveMarker(cmd)
            val a = parseAAfter(resp, marker)
            if (a != null) {
                var pct = a * 100.0 / 255.0
                if (src.litersToPct) {
                    // Cluster reports absolute liters. Map them onto a FIXED level scale that never
                    // depends on the user's tank guess or on learned capacity (either would change the
                    // level unit across history). FuelV2's map is scale-free, so it learns gallons per
                    // level unit from fuel burned; corrected % and remaining come from that model.
                    pct = (a * 100.0 / LITERS_FULL_SCALE).coerceAtMost(100.0)
                    return Read(pct, "bus ${src.header ?: ""} $cmd liters=$a (level = L/${LITERS_FULL_SCALE.toInt()})", false)
                }
                return Read(pct, "bus ${src.header ?: ""} $cmd", false)
            }
            return Read(null, "add extra PID set ${src.label}", true)
        }
        return Read(null, "missing ${src.label}", true)
    }

    private fun bus(t: org.prowl.torque.remote.ITorqueService, header: String, command: String): Array<String>? {
        return runCatching { t.sendCommandGetResponse(header, command) }.getOrNull()
    }

    private fun positiveMarker(modePid: String): String {
        val hex = modePid.replace(" ", "").uppercase()
        if (hex.startsWith("01") && hex.length >= 4) return "41" + hex.substring(2)
        if (hex.startsWith("22") && hex.length >= 6) return "62" + hex.substring(2)
        if (hex.startsWith("21") && hex.length >= 4) return "61" + hex.substring(2)
        return hex
    }

    private fun parseAAfter(resp: Array<String>?, marker: String): Int? {
        if (resp == null || resp.isEmpty()) return null
        for (line in resp) {
            val hex = ObdReply.payloadAfter(line, marker) ?: continue
            if (hex.length < 2) continue
            return hex.substring(0, 2).toIntOrNull(16)
        }
        return null
    }
}
