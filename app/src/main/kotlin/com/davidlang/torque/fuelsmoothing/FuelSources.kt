package com.davidlang.torque.fuelsmoothing

internal data class FuelSource(
    val id: String,
    val label: String,
    val longName: String? = null,
    val modePid: String? = null,
    val header: String? = null,
    val litersToPct: Boolean = false,
    val twoByteOk: Boolean = false,
)

internal object FuelSources {
    val catalog: List<FuelSource> = listOf(
        FuelSource("none", "None yet (Detect runs on connect)"),
        FuelSource("sae_012f", "SAE 012F Fuel Tank Level", modePid = "012F"),
        FuelSource("ford_16c1", "Mode 22 16C1", longName = "[FORD]Fuel Level"),
        FuelSource("ford_f42f", "Ford Mode 22 F42F", longName = null, modePid = "22F42F"),
        FuelSource("gm_12c5", "GM Mode 22 12C5 %", modePid = "2212C5", twoByteOk = true),
        FuelSource("chrysler_21a2", "Chrysler 21A2 %", modePid = "21A2"),
        FuelSource("chrysler_avg", "Chrysler Fuel Level Average", modePid = "228126"),
        FuelSource("toyota_7c0_2129", "Toyota cluster 2129 (half-litre counts)", modePid = "2129", header = "7C0", litersToPct = true),
        FuelSource("custom_name", "Torque extra PID by long name"),
    )

    fun byId(id: String): FuelSource = catalog.firstOrNull { it.id == id } ?: catalog.first { it.id == "none" }

    fun labels(): Array<String> = catalog.map { it.label }.toTypedArray()

    fun indexOf(id: String): Int {
        val i = catalog.indexOfFirst { it.id == id }
        return if (i >= 0) i else catalog.indexOfFirst { it.id == "none" }
    }

    data class Read(
        val pct: Double?,
        val how: String,
        val missing: Boolean,
        /** Raw litres for litre-reporting sources (logged as lvlL). */
        val liters: Double? = null,
        /** GM 12C5 two-byte form (needs agreement with 012F before detection trusts it). */
        val twoByte: Boolean = false,
    )

    fun read(
        torque: org.prowl.torque.remote.ITorqueService,
        cfg: FixFuelConfig,
        names: Map<String, String>,
        getValue: (String) -> Double?,
    ): Read {
        val src = byId(cfg.sourceId)
        if (src.id == "none") return Read(null, "no fuel source yet (Detect)", true)
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
            val data = dataAfter(resp, marker, if (src.twoByteOk) 2 else 1)
            if (data != null && data.length >= 2) {
                if (src.litersToPct) {
                    // Toyota cluster: A counts half litres. Level = litres as % of the profile's tank size.
                    return toyotaLevel(data.substring(0, 2).toInt(16), "bus ${src.header ?: ""} $cmd")
                }
                if (src.twoByteOk) {
                    val pct = gmLevel(data) ?: return Read(null, "bad reply ${src.label}", true)
                    // A 2-byte form is trusted at detection only after it agrees with 012F (FuelDetect).
                    return Read(pct, "bus ${src.header ?: ""} $cmd ${minOf(data.length / 2, 2)}B", false, twoByte = data.length >= 4)
                }
                return Read(data.substring(0, 2).toInt(16) * 100.0 / 255.0, "bus ${src.header ?: ""} $cmd", false)
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

    private fun dataAfter(resp: Array<String>?, marker: String, minBytes: Int = 1): String? =
        resp?.firstNotNullOfOrNull { ObdReply.dataAfter(it, marker, minBytes)?.takeIf { d -> d.length >= 2 } }

    /**
     * Toyota/Lexus cluster 7C0 2129 counts half litres (A/2 L; Prado and Prius C threads, see FuelSources docs).
     * The level is the count on a FIXED scale (255 counts = 127.5 L = 100 %), independent of any tank size,
     * so the model learns gallons per level unit exactly as for % sources; the litres are logged per sample.
     */
    const val TOYOTA_L_PER_COUNT = 0.5
    const val TOYOTA_FULL_COUNTS = 255.0

    fun toyotaLevel(count: Int, how: String): Read {
        val liters = count * TOYOTA_L_PER_COUNT
        return Read(count * 100.0 / TOYOTA_FULL_COUNTS, "$how count=$count liters=$liters", false, liters = liters)
    }

    /** GM 12C5: 1 byte = A*100/255; 2 bytes = (A*256+B)*100/65535. Longer replies use the first 2 bytes. */
    fun gmLevel(data: String): Double? {
        val b = data.chunked(2).mapNotNull { if (it.length == 2) it.toIntOrNull(16) else null }
        return when {
            b.isEmpty() -> null
            b.size == 1 -> b[0] * 100.0 / 255.0
            else -> (b[0] * 256 + b[1]) * 100.0 / 65535.0
        }
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
