package com.davidlang.torque.fuelsmoothing

import org.json.JSONArray
import org.json.JSONObject

/** CAN Service09 only. Strict item counts/widths; raw/recombined frames remain the primary evidence.
 * Never joins ECUs, flattens CALIDs/CVNs, labels ECU name as serial, or feeds Fingerprint.match.
 */
internal object DiagnosticIdentityDecode {
    fun decode(pid: String, raw: String): JSONObject? {
        val marker = "49$pid"
        val after = ObdReply.payloadAfter(raw, marker) ?: return null
        if (pid == "00") {
            val bits = ObdReply.dataAfter(raw, marker, 4) ?: return null
            if (bits.length != 8) return null
            return JSONObject().put("supportedInfoTypesHex", bits).put("valid", true)
        }
        if (after.length < 2) return null
        val count = after.take(2).toIntOrNull(16) ?: return null
        val width = when (pid) { "04" -> 16; "06" -> 4; "0A" -> 20; else -> return null }
        if (count !in 1..16 || (pid == "0A" && count != 1)) return null
        val hex = ObdReply.dataAfter(raw, marker, 1 + count * width) ?: return null
        if (hex.length != (1 + count * width) * 2) return null
        val items = JSONArray()
        for (item in hex.drop(2).chunked(width * 2)) {
            if (pid == "06") items.put(item) else {
                val bytes = item.chunked(2).map { it.toInt(16) }
                if (bytes.any { it != 0 && it !in 32..126 }) return null
                // J1979 ECU-name field: four-byte identifier, separator, fifteen-byte name.
                // The identifier may be NUL padded independently of the trailing name padding.
                fun textField(field: List<Int>): String? {
                    val end = field.indexOf(0).let { if (it < 0) field.size else it }
                    if (field.drop(end).any { it != 0 && it != 32 }) return null
                    return field.take(end).map { it.toChar() }.joinToString("").trimEnd().takeIf { it.isNotEmpty() }
                }
                val text = if (pid == "0A") {
                    val identifier = textField(bytes.take(4)) ?: return null
                    if (bytes[4] != 45) return null
                    val name = textField(bytes.drop(5)) ?: return null
                    "$identifier-$name"
                } else textField(bytes) ?: return null
                items.put(text)
            }
        }
        val key = when (pid) { "04" -> "calibrationIds"; "06" -> "calibrationVerificationNumbersHex"; else -> "ecuNames" }
        return JSONObject().put("itemCount", count).put(key, items).put("valid", true)
    }
}
