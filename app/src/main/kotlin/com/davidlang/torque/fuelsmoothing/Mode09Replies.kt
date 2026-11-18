package com.davidlang.torque.fuelsmoothing

/** Bounded, conservative Service09 framing shared by vehicle identity and diagnostic capture.
 * Numbered ELM blocks have no ECU address: accept ONE declared, complete block only. Plain
 * complete replies remain separate (including any address); never join different ECU streams.
 */
internal object Mode09Replies {
    fun messages(parts: List<String>?, pid: String): List<String> {
        if (parts.isNullOrEmpty() || parts.size > 64 || parts.any { it.length > 512 }) return emptyList()
        val lines = parts.flatMap { it.lines() }.map { it.trim().uppercase() }.filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.size > 64) return emptyList()
        val numbered = Regex("^([0-9A-F]{1,2}):\\s*([0-9A-F ]+)$")
        if (lines.any { ':' in it }) {
            val lengths = lines.filter { it.matches(Regex("[0-9A-F]{3}")) }
            if (lengths.size != 1) return emptyList()
            val size = lengths.single().toInt(16)
            if (size !in 3..512) return emptyList()
            val chunks = linkedMapOf<Int, String>()
            for (line in lines.filter { it != lengths.single() }) {
                val m = numbered.matchEntire(line) ?: return emptyList()
                val index = m.groupValues[1].toInt(16)
                val data = m.groupValues[2].replace(" ", "")
                if (data.length % 2 != 0 || data.isEmpty() || chunks.put(index, data) != null) return emptyList()
            }
            if (chunks.isEmpty() || chunks.keys.sorted() != (0 until chunks.size).toList()) return emptyList()
            for ((index, data) in chunks) {
                val expected = if (index == 0) 12 else 14
                if (data.length > expected || (index < chunks.size - 1 && data.length != expected)) return emptyList()
            }
            val hex = chunks.toSortedMap().values.joinToString("")
            if (hex.length < size * 2 || hex.length - size * 2 > 14) return emptyList()
            // Declared length is authoritative; only known CAN padding may follow it.
            if (hex.drop(size * 2).chunked(2).any { it !in setOf("00", "55", "AA", "CC") }) return emptyList()
            val message = hex.take(size * 2)
            return if (message.startsWith("49$pid")) listOf(message) else emptyList()
        }
        // No speculative ISO-TP stream reconstruction or searching for marker substrings. Per-line ISO/KWP
        // packets are returned as is; only [vin] joins them, and only as one complete numbered set.
        // Incomplete/foreign replies fail closed.
        if (lines.any { ObdReply.payloadAfter(it, "49$pid") == null }) return emptyList()
        return lines
    }

    fun vin(parts: List<String>?): String? {
        val replies = messages(parts, "02")
        if (replies.isEmpty()) return null
        // Pre-CAN (J1850 / ISO 9141 / KWP): the VIN arrives as numbered per-line packets "49 02 0N" + up to 4 bytes.
        if (replies.all { (ObdReply.payloadAfter(it, "4902")?.length ?: Int.MAX_VALUE) <= LEGACY_MAX_PAYLOAD_HEX }) {
            return legacyVin(replies)
        }
        val vins = replies.map { raw ->
            val hex = ObdReply.dataAfter(raw, "4902", 18) ?: return null
            if (hex.length != 36 || !hex.startsWith("01")) return null
            val value = hex.drop(2).chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            if (!validVin(value)) return null
            value
        }.distinct()
        return vins.singleOrNull() // conflicting ECU VINs are NOT an automatic identity
    }

    /** Same VIN checks for every path: 17 VIN characters (no I/O/Q), not all one character. */
    private fun validVin(value: String): Boolean =
        value.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) && value.toSet().size >= 2

    /** Packets in one pre-CAN VIN set (J1979: 5 x 4 bytes = 3 fill bytes + 17 characters). */
    const val LEGACY_VIN_PACKETS = 5
    /** Sequence byte + at most 4 data bytes after "4902". */
    private const val LEGACY_MAX_PAYLOAD_HEX = 10

    /**
     * Pre-CAN VIN from per-line packets, accepted ONLY as one complete numbered set: exactly packets
     * 1..LEGACY_VIN_PACKETS, each number exactly once (any repeat, even with the same data, may be a second ECU:
     * rejected), no gaps, no other lines. Packets may arrive in any order (each carries its number; a complete
     * unique set is unambiguous). Packets 1..4 carry 4 bytes, the last 1..4. Leading 00 fill (at most 3 bytes,
     * packet 1 only) is dropped; exactly 17 bytes must remain and pass [validVin].
     */
    private fun legacyVin(replies: List<String>): String? {
        if (replies.size != LEGACY_VIN_PACKETS) return null
        val byNumber = HashMap<Int, String>()
        for (raw in replies) {
            val p = ObdReply.payloadAfter(raw, "4902") ?: return null
            if (p.length % 2 != 0 || p.length < 4) return null
            val n = p.take(2).toInt(16)
            if (byNumber.put(n, p.drop(2)) != null) return null
        }
        if (byNumber.keys != (1..LEGACY_VIN_PACKETS).toSet()) return null
        for (n in 1 until LEGACY_VIN_PACKETS) if (byNumber.getValue(n).length != 8) return null
        val bytes = (1..LEGACY_VIN_PACKETS).joinToString("") { byNumber.getValue(it) }.chunked(2)
        var fill = 0
        while (fill < 3 && bytes[fill] == "00") fill++
        val body = bytes.drop(fill)
        if (body.size != 17) return null
        val value = body.map { it.toInt(16).toChar() }.joinToString("")
        return value.takeIf { validVin(it) }
    }
}
