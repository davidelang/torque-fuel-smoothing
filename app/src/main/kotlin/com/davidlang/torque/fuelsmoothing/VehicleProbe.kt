package com.davidlang.torque.fuelsmoothing

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * One-time Ford ID probe (and the Check vehicle button's read). Paced: one sendCommandGetResponse per
 * [GAP_MS] (5 s), at most [MAX_REQUESTS] requests and [MAX_TOTAL_MS] overall, so Torque's own polling is only
 * ever interrupted for one request at a time. Uses only modes Torque allows without "full permissions"
 * (01, 09, 22): no AT commands, no exclusive lock, no header change other than the per-request header argument.
 *
 * Every raw reply is kept (refusal codes included) for the private check ring; [stable] gives the values worth
 * keeping in fingerprint.json: class-only for data that changes (0101), payload for capability data (0100,
 * 011C), a short hash for identity payloads (0902, 22F1xx: may hold a VIN or part numbers).
 */
internal class VehicleProbe(
    val kind: String,
    val profile: String,
    val protocol: String?,
    val startMs: Long,
    val startMono: Long,
    withIdentity: Boolean = false,
    firstDelayMs: Long = 0L,
) {
    data class Step(val key: String, val header: String, val cmd: String)

    val id: String = "$kind-$startMs"
    private val queue = ArrayDeque(steps(protocol, withIdentity))
    private val reasked = HashSet<String>()
    private var nextMono = startMono + firstDelayMs.coerceAtLeast(0L)
    private var sent = 0
    val results = JSONArray()
    val classes = LinkedHashMap<String, String>()
    var finished = false
        private set
    var abortReason: String? = null
        private set

    /** True when a request may be sent now. */
    fun due(nowMono: Long): Boolean = !finished && nowMono >= nextMono

    /**
     * Sends at most one request (only when [due]); returns true once the probe is finished. [send] returns
     * Torque's reply parts or throws/returns null.
     */
    fun step(nowMono: Long, nowMs: Long, send: (String, String) -> List<String>?): Boolean {
        if (finished) return true
        if (nowMono - startMono > MAX_TOTAL_MS) return abort("time_cap")
        if (!due(nowMono)) return false
        val s = queue.removeFirstOrNull() ?: return finish()
        if (sent >= MAX_REQUESTS) return abort("request_cap")
        sent++
        nextMono = nowMono + GAP_MS
        val reply = runCatching { send(s.header, s.cmd) }
        val parts = reply.getOrNull().orEmpty().filter { it.length <= 512 }.take(64)
        val cls = if (reply.isFailure) "error:${reply.exceptionOrNull()?.javaClass?.simpleName ?: "call"}" else classify(s.cmd, parts)
        results.put(JSONObject().put("key", s.key).put("header", s.header).put("cmd", s.cmd).put("t", nowMs)
            .put("raw", JSONArray(parts)).put("class", cls))
        // 0100 is asked once more after an unusable reply (seen as 7F0100..11 on the van in 10 of 44 runs).
        if (s.cmd == "0100" && !cls.startsWith("data:") && reasked.add(s.key)) queue.addFirst(s)
        else classes[s.key] = cls
        return if (queue.isEmpty()) finish() else false
    }

    fun abort(reason: String): Boolean {
        abortReason = reason
        finished = true
        return true
    }

    private fun finish(): Boolean { finished = true; return true }

    /** Values stable enough for fingerprint.json, keyed "id:<step>". */
    fun stable(): Map<String, String> = classes.mapNotNull { (k, c) ->
        val v = when {
            !c.startsWith("data:") -> c
            k == "0101" -> "data"
            k == "0100" || k == "011C" -> c
            else -> "data#" + hash(c.removePrefix("data:"))
        }
        if (c.startsWith("error:")) null else "id:$k" to v
    }.toMap()

    fun json(): JSONObject = JSONObject().put("v", 2).put("id", id).put("kind", kind).put("t", startMs)
        .put("protocol", protocol ?: JSONObject.NULL).put("steps", results)
        .put("stable", JSONObject(stable())).put("aborted", abortReason ?: JSONObject.NULL)
        .put("source", "Torque.sendCommandGetResponse")

    companion object {
        const val GAP_MS = 5_000L
        const val MAX_TOTAL_MS = 120_000L
        const val MAX_REQUESTS = 14
        const val VERSION = "1"

        /** Request list for [protocol]. Mode 22 only where a physical header is known (PWM or 11-bit CAN). */
        fun steps(protocol: String?, withIdentity: Boolean): List<Step> {
            val pwm = protocol?.contains("J1850 PWM") == true
            val can = protocol?.contains("15765") == true
            val pcm = when { pwm -> "C410F1"; can -> "7E0"; else -> null }
            val abs = when { pwm -> "C428F1"; can -> "760"; else -> null }
            val out = ArrayList<Step>()
            if (withIdentity) out.add(Step("0902", "", "0902"))
            out.add(Step("0100", "", "0100"))
            out.add(Step("0101", "", "0101"))
            out.add(Step("011C", "", "011C"))
            if (pcm != null) {
                if (withIdentity) out.add(Step("2216C1", pcm, "2216C1"))
                for (did in listOf("F190", "F188", "F111", "F113")) out.add(Step("22$did", pcm, "22$did"))
            }
            // ABS presence: a physically addressed Mode 22 request answered (data or a refusal) only if it exists.
            if (abs != null) out.add(Step("ABS:22F190", abs, "22F190"))
            return out
        }

        /** "nodata", "nrc:XX", "data:<payload hex>", "none", "error:<word>", or "other:<hex prefix>". */
        fun classify(cmd: String, parts: List<String>): String {
            val text = parts.joinToString(" ").uppercase()
            if (parts.isEmpty() || text.isBlank()) return "none"
            if ("NO DATA" in text || "NODATA" in text) return "nodata"
            for (w in listOf("STOPPED", "ERROR", "UNABLE", "BUS", "CAN ERROR", "?")) if (w in text) return "error:" + w.trim()
            val hex = text.filter { it in "0123456789ABCDEF" }
            val svc = cmd.take(2).toIntOrNull(16) ?: return "other:" + hex.take(16)
            val pos = "%02X".format(svc + 0x40) + cmd.drop(2).uppercase()
            val at = hex.indexOf(pos)
            if (at >= 0) return "data:" + hex.substring(at + pos.length)
            // Negative response 7F <service> ... <NRC>: the refusal code is the last byte.
            if (hex.contains("7F" + cmd.take(2).uppercase())) return "nrc:" + hex.takeLast(2)
            return "other:" + hex.take(16)
        }

        private fun hash(s: String): String {
            val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            return d.take(4).joinToString("") { "%02x".format(it) } + "/" + (s.length / 2)
        }
    }
}
