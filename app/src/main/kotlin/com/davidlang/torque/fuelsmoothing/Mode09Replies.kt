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
        // No speculative ISO-TP stream reconstruction, searching for marker substrings, or
        // concatenation of per-line ISO/KWP packets. Incomplete/foreign replies fail closed.
        if (lines.any { ObdReply.payloadAfter(it, "49$pid") == null }) return emptyList()
        return lines
    }

    fun vin(parts: List<String>?): String? {
        val replies = messages(parts, "02")
        if (replies.isEmpty()) return null
        val vins = replies.map { raw ->
            val hex = ObdReply.dataAfter(raw, "4902", 18) ?: return null
            if (hex.length != 36 || !hex.startsWith("01")) return null
            val value = hex.drop(2).chunked(2).map { it.toInt(16).toChar() }.joinToString("")
            if (!value.matches(Regex("[A-HJ-NPR-Z0-9]{17}")) || value.toSet().size < 2) return null
            value
        }.distinct()
        return vins.singleOrNull() // conflicting ECU VINs are NOT an automatic identity
    }
}
