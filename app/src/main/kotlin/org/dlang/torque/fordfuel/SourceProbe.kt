package org.dlang.torque.fordfuel

/**
 * Spec §3a rows 1–34. commandsFor emits only services 01, 09, 21, and 22.
 * Row 31 is listed and is not sent. canOnly rows are omitted unless the
 * protocol name contains 15765. No AT commands.
 */
internal object SourceProbe {
    data class Planned(
        val row: Int,
        val command: String,
        val header: String,
        val canOnly: Boolean,
        val minGapMs: Long,
    )

    private data class Spec(
        val row: Int,
        val gates: Set<String>,
        val command: String,
        val header: String,
        val canOnly: Boolean,
        val send: Boolean,
    )

    private val WMI = mapOf(
        "ford" to listOf("1FA", "1FB", "1FC", "1FD", "1FM", "1FT", "2FM", "3FA", "1LN", "5LM"),
        "lincoln" to listOf("1LN", "5LM", "2LN"),
        "mazda" to listOf("JM1", "JM3", "JMZ"),
        "toyota" to listOf("JT", "2T", "4T", "5TD"),
        "lexus" to listOf("JT", "2T"),
        "honda" to listOf("1HG", "2HG", "19X", "JHM", "5FN", "5J6"),
        "acura" to listOf("19U", "JH4", "5J8"),
        "nissan" to listOf("JN1", "JN8", "1N4", "1N6", "3N1", "5N1"),
        "infiniti" to listOf("JN1", "JNK", "5N1"),
        "hyundai" to listOf("KMH", "5NP", "5NM"),
        "kia" to listOf("KNA", "KND", "5XX"),
        "chevrolet" to listOf("1G1", "1GC", "2G1", "3G1"),
        "gmc" to listOf("1GT", "1GK", "2GT"),
        "buick" to listOf("1G4", "2G4"),
        "cadillac" to listOf("1G6", "2G6"),
        "gm" to listOf("1G", "2G", "3G"),
        "vw" to listOf("WVW", "3VW", "1VW", "WV1"),
        "volkswagen" to listOf("WVW", "3VW"),
        "audi" to listOf("WAU", "TRU", "WA1"),
        "skoda" to listOf("TMB"),
        "seat" to listOf("VSS"),
        "jeep" to listOf("1C4", "1J4", "1J8"),
        "ram" to listOf("1C6", "3C6"),
        "chrysler" to listOf("1C3", "2C3", "3C4"),
        "dodge" to listOf("1B3", "2B3", "1C3"),
        "fiat" to listOf("ZFA", "3C3"),
        "stellantis" to listOf("1C", "2C", "3C"),
        "subaru" to listOf("JF1", "JF2", "4S3", "4S4"),
        "bmw" to listOf("WBA", "WBS", "WBX", "4US", "5UX"),
        "mini" to listOf("WMW", "WMZ"),
        "mercedes" to listOf("WDB", "WDD", "W1N", "4JG"),
        "tesla" to listOf("5YJ", "7SA", "LRW"),
    )

    private val ROWS: List<Spec> = listOf(
        Spec(1, emptySet(), "01A6", "", false, true),
        Spec(2, emptySet(), "22F4A6", "7E0", true, true),
        Spec(3, emptySet(), "0917", "", false, true),
        Spec(3, emptySet(), "22F817", "7E0", true, true),
        Spec(4, emptySet(), "0131", "", false, true),
        Spec(4, emptySet(), "22F431", "7E0", true, true),
        Spec(5, emptySet(), "0130", "", false, true),
        Spec(5, emptySet(), "0121", "", false, true),
        Spec(5, emptySet(), "22F421", "7E0", true, true),
        Spec(6, setOf("ford", "lincoln"), "22F4A6", "7E0", true, true),
        Spec(7, setOf("ford"), "22404C", "720", true, true),
        Spec(8, setOf("ford"), "22DD01", "7E0", true, true),
        Spec(8, setOf("ford"), "22DD01", "726", true, true),
        Spec(8, setOf("ford"), "22DD01", "760", true, true),
        Spec(9, setOf("mazda"), "22404C", "720", true, true),
        Spec(9, setOf("mazda"), "226180", "720", true, true),
        Spec(10, setOf("toyota", "lexus"), "22F4A6", "7E0", true, true),
        Spec(11, setOf("toyota", "lexus"), "22DD01", "7E0", true, true),
        Spec(11, setOf("toyota", "lexus"), "22DD01", "720", true, true),
        Spec(11, setOf("toyota", "lexus"), "22DD01", "760", true, true),
        Spec(12, setOf("toyota", "lexus"), "2128", "7E0", true, true),
        Spec(13, setOf("toyota", "lexus"), "220103", "700", true, true),
        Spec(13, setOf("toyota", "lexus"), "220103", "701", true, true),
        Spec(13, setOf("toyota", "lexus"), "220103", "747", true, true),
        Spec(13, setOf("toyota", "lexus"), "220103", "7D2", true, true),
        Spec(14, setOf("toyota", "lexus", "subaru"), "2129", "7C0", true, true),
        Spec(15, setOf("honda", "acura"), "222660", "DA10", true, true),
        Spec(15, setOf("honda", "acura"), "222660", "DA0E", true, true),
        Spec(15, setOf("honda", "acura"), "222660", "DA1D", true, true),
        Spec(16, setOf("honda", "acura"), "227022", "DA60", true, true),
        Spec(17, setOf("honda", "acura"), "227029", "DA60", true, true),
        Spec(18, setOf("nissan", "infiniti"), "220E01", "743", true, true),
        Spec(19, setOf("nissan", "infiniti"), "2101", "743", true, true),
        Spec(20, setOf("hyundai", "kia"), "22B002", "7C6", true, true),
        Spec(21, setOf("hyundai", "kia"), "22ED03", "7E0", true, true),
        Spec(22, setOf("gm", "chevrolet", "gmc", "buick", "cadillac"), "22448F", "DA40", true, true),
        Spec(22, setOf("gm", "chevrolet", "gmc", "buick", "cadillac"), "22448F", "DA1A", true, true),
        Spec(23, setOf("gm", "chevrolet", "gmc", "buick", "cadillac"), "22005E", "7E0", true, true),
        Spec(23, setOf("gm", "chevrolet", "gmc", "buick", "cadillac"), "22009D", "7E0", true, true),
        Spec(23, setOf("gm", "chevrolet", "gmc", "buick", "cadillac"), "22005E", "DA11", true, true),
        Spec(24, setOf("vw", "volkswagen", "audi", "skoda", "seat"), "222203", "714", true, true),
        Spec(25, setOf("vw", "volkswagen", "audi", "skoda", "seat"), "22295A", "7E0", true, true),
        Spec(26, setOf("vw", "volkswagen", "audi", "skoda", "seat"), "221160", "7E0", true, true),
        Spec(27, setOf("vw", "volkswagen", "audi", "skoda", "seat"), "22113F", "7E0", true, true),
        Spec(27, setOf("vw", "volkswagen", "audi", "skoda", "seat"), "221519", "7E0", true, true),
        Spec(27, setOf("vw", "volkswagen", "audi", "skoda", "seat"), "2251D6", "7E0", true, true),
        Spec(28, setOf("stellantis", "jeep", "ram", "chrysler", "dodge", "fiat"), "22D49C", "6A6", true, true),
        Spec(28, setOf("stellantis", "jeep", "ram", "chrysler", "dodge", "fiat"), "22D49C", "6A2", true, true),
        Spec(28, setOf("stellantis", "jeep", "ram", "chrysler", "dodge", "fiat"), "22D49C", "6A8", true, true),
        Spec(29, setOf("ram"), "2214FA", "DA18F1", true, true),
        Spec(30, setOf("subaru"), "2210E5", "7E0", true, true),
        Spec(30, setOf("subaru"), "2210A5", "7A2", true, true),
        Spec(31, setOf("bmw", "mini"), "22D10D", "6F1", true, false),
        Spec(31, setOf("bmw", "mini"), "22558E", "6F1", true, false),
        Spec(32, setOf("mercedes"), "", "", true, false),
        Spec(33, setOf("tesla"), "", "", true, false),
        Spec(34, setOf("ford"), "", "", false, false),
    )

    fun commandsFor(vin: String?, make: String?, protocol: String?, driving: Boolean): List<Planned> {
        val gap = if (driving) 2000L else 0L
        val can = protocol?.contains("15765") == true
        val out = ArrayList<Planned>()
        for (row in ROWS) {
            if (!row.send || row.command.isEmpty()) continue
            val svc = row.command.take(2)
            if (svc != "01" && svc != "09" && svc != "21" && svc != "22") continue
            if (row.canOnly && !can) continue
            if (!gateOpen(row.gates, vin, make)) continue
            out.add(Planned(row.row, row.command, headerArg(row.header), row.canOnly, gap))
        }
        return out
    }

    /** Row 31 is in the table and contributes nothing to commandsFor. */
    fun rowIsListed(n: Int): Boolean = ROWS.any { it.row == n }

    fun headerArg(header: String): String {
        val hex = header.trim().uppercase()
        if (hex.length == 4 && hex.all { it in '0'..'9' || it in 'A'..'F' }) return "18${hex}F1"
        return hex
    }

    fun positive(response: String, command: String): Boolean {
        val hex = response.uppercase().replace(Regex("[^0-9A-F]"), "")
        val echo = when (command.take(2)) {
            "01" -> "41" + command.drop(2).take(2)
            "09" -> "49" + command.drop(2).take(2)
            "21" -> "61" + command.drop(2).take(2)
            "22" -> "62" + command.drop(2).take(4)
            else -> return false
        }
        return hex.contains(echo)
    }

    fun plausibleKm(km: Double): Boolean = km in 100.0..2_000_000.0

    private fun gateOpen(gates: Set<String>, vin: String?, make: String?): Boolean {
        if (gates.isEmpty()) return true
        val m = make?.lowercase().orEmpty()
        val vinUp = vin?.uppercase().orEmpty()
        for (g in gates) {
            if (m.contains(g)) return true
            val prefixes = WMI[g].orEmpty()
            if (prefixes.any { vinUp.startsWith(it) }) return true
        }
        return false
    }
}
