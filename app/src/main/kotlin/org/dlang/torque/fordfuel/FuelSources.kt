package org.dlang.torque.fordfuel

internal data class FuelSource(
    val id: String,
    val label: String,
    val longName: String? = null,
    val modePid: String? = null,
    val header: String? = null,
    val litersToPct: Boolean = false,
)

internal object FuelSources {
    val catalog: List<FuelSource> = listOf(
        FuelSource("sae_012f", "SAE 012F Fuel Tank Level", modePid = "012F"),
        FuelSource("ford_16c1", "Ford FLI 16C1 (default)", longName = "[FORD]Fuel Level"),
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
}
