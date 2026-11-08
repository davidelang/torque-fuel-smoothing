package com.davidlang.torque.fuelsmoothing

import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import org.prowl.torque.remote.ITorqueService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Trial-only sidecars. Never feeds a fitter, burn gate, or the raw level/frozen-run writer. */
internal class DiagnosticCapture {
    private data class Spec(val pid: String, val bytes: Int, val unit: String, val mode: String = "01") {
        val key: String get() = if (mode == "01") pid else mode + pid
        val command: String get() = mode + pid
    }
    private val specs = listOf(Spec("A6", 4, "km"), Spec("31", 2, "km"), Spec("9D", 4, "g/s"),
        Spec("46", 1, "degC"), Spec("42", 2, "V"), Spec("5C", 1, "degC"), Spec("05", 1, "degC"),
        Spec("C7", 2, "km"), Spec("21", 2, "km"), Spec("30", 1, "count"), Spec("4D", 2, "min"), Spec("4E", 2, "min"), Spec("1F", 2, "s"),
        Spec("00", 4, "bitmap", "09"), Spec("04", 16, "ASCII", "09"),
        Spec("06", 4, "hex", "09"), Spec("0A", 20, "ASCII", "09"),
        // CARB HD OBD table verifies090D=ENGINE serial, NOT ECU hardware serial.
        // Layout not independently verified: collect bounded positive raw evidence only.
        Spec("0D", 0, "raw_hex", "09"))
    // One bus request/5s remains the ceiling. A6->31 separated by one slot, not simultaneous.
    // A6/31/9D nominal20s each. Slow context round-robin; Mode09 succeeds once/session,
    // with at most3 attempts/item and capability discovery before any substantive Mode09 query.
    private val slots = listOf("A6", "31", "9D", "slow")
    private val slowSlots = listOf("C7", "4E", "30", "21", "1F", "46", "42", "5C", "05", "4D")
    // First two failures use ordinary slots; sustained failures back off, never hot-loop.
    private val retryDelaysMs = longArrayOf(0L, 0L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L)
    private class Session(val profile: String, val tank: Int, val id: String, val torqueName: String,
        val file: File, val protocol: String, val bitmaps: Map<String, String>, val mode09Known: Boolean, val pending: Boolean) {
        var slot = 0
        var slowSlot = 0 // worker-confined
        var mode09Mask: Long? = null
        val mode09Done = HashSet<String>()
        val mode09Attempts = HashMap<String, Int>()
        var a6Reference: JSONObject? = null
        var nextMs = 0L
        var opened = false // worker only; no file writes on the sampling thread
        val failures = java.util.concurrent.ConcurrentHashMap<String, Int>() // cumulative, for existing readers
        // Worker-confined, per-session/per-PID state; deadlines use monotonic elapsed time.
        val consecutiveFailures = HashMap<String, Int>()
        val retryAfterMs = HashMap<String, Long>()
    }
    @Volatile private var current: Session? = null
    @Volatile private var closed = false
    @Volatile private var failed = false
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "FuelSmooth-diagnostics").apply { isDaemon = true } }

    fun start(profile: String, tank: Int, id: String, torqueName: String, dir: File,
        protocol: String?, bitmaps: Map<String, String>, mode09Known: Boolean = false, pending: Boolean = false) {
        if (closed || failed) return
        current = Session(profile, tank, id, torqueName, File(File(dir, "diagnostics"), "$id.jsonl"),
            protocol.orEmpty(), bitmaps.toMap(), mode09Known, pending)
    }

    fun end(id: String?) { if (current?.id == id) current = null }
    fun shutdown() {
        closed = true
        current = null
        try { worker.shutdownNow() }
        catch (e: Exception) { disableAfterFailure(e) }
        finally { busy.set(false) }
    }

    /** Optional diagnostics fail closed for this service lifetime, never core recording. */
    fun disableAfterFailure(e: Exception) {
        if (failed) return
        failed = true
        current = null
        // Logging must not turn diagnostic error handling into a sampler failure either.
        try {
            FsLog.warn("FuelSmooth", "diagnosticCapture", "diagnostics disabled until service restart: ${e.javaClass.simpleName}")
        } catch (_: Exception) { /* recording must continue even if logging fails */ }
    }

    /** Called after normal sampling; context is a snapshot, not a fresh extra sensor query. */
    fun tick(torque: ITorqueService, context: JSONObject) {
        val s = current ?: return
        val mono = SystemClock.elapsedRealtime()
        if (closed || failed || mono < s.nextMs || !busy.compareAndSet(false, true)) return
        var submitted = false
        try {
            s.nextMs = mono + 5_000L
            // Select once, outside the search predicate; keep the cursor bounded across wraps.
            val pid = slots[s.slot]
            s.slot = (s.slot + 1) % slots.size
            val queuedMs = System.currentTimeMillis()
            worker.execute {
                try {
                    if (closed || current !== s) return@execute
                    if (!s.opened) {
                        val caps = JSONObject()
                        for (candidate in specs) caps.put(candidate.key, support(s, candidate))
                        write(s, JSONObject().put("event", "open").put("t", queuedMs).put("v", 2)
                            .put("app", BuildConfig.VERSION_NAME).put("profileId", if (s.pending) JSONObject.NULL else s.profile).put("tank", s.tank)
                            .put("recordingScope", if (s.pending) "quarantine" else "physical_vehicle_profile")
                            .put("candidateProfileId", if (s.pending) s.profile else JSONObject.NULL)
                            .put("quarantineId", if (s.pending) (s.file.parentFile?.parentFile?.parentFile?.name ?: JSONObject.NULL) else JSONObject.NULL)
                            .put("sessionId", s.id).put("protocol", s.protocol).put("bitmaps", JSONObject(s.bitmaps))
                            .put("capabilitySource", "current_connection_identity_plus_session_0900").put("capabilities", caps)
                            .put("policy", "CAN_advertised_only_except_0900_discovery_after_known_mode09")
                            .put("maxRequestsPer5s", 1).put("mode09AttemptsMax", 3)
                            .put("a6_31_9dNominalPeriodMs", 20000).put("mode09Known", s.mode09Known)
                            .put("counterWarning", "reset_epoch_and_wrap_saturation_unknown_no_automatic_offset")
                            .put("volumeIndependence", "unknown")
                            .put("retryPolicy", "consecutive_3_backoff_30_60_120_240_300s"))
                        s.opened = true
                    }
                    // Do not add diagnostic bus traffic to the older J1850 van, or guess unknown capabilities.
                    if (!s.protocol.contains("15765")) return@execute
                    val spec = if (pid == "slow") nextSlow(s) else specs.firstOrNull { it.key == pid }
                    if (spec == null || !eligible(s, spec)) return@execute
                    val started = System.currentTimeMillis()
                    val startedMono = SystemClock.elapsedRealtime()
                    val row = JSONObject().put("event", "obd").put("queuedMs", queuedMs).put("t", started)
                        .put("pid", spec.command).put("source", "Torque.sendCommandGetResponse")
                        .put("header", "").put("unit", spec.unit).put("context", context)
                        .put("support", support(s, spec)).put("ecuAttribution", "raw_headers_only_no_cross_reply_join")
                        .put("mode09Bitmap", s.mode09Mask?.toString(16)?.uppercase()?.padStart(8, '0') ?: JSONObject.NULL)
                        .put("valid", false).put("values", JSONObject.NULL)
                    if (spec.mode == "01" && spec.pid in setOf("31", "C7", "21", "30", "4D", "4E", "1F")) {
                        row.put("resolution", 1).put("encodedMin", 0).put("encodedMax", if (spec.pid == "30") 255 else 65535)
                            .put("counterEpoch", "unverified_reset_wrap_saturation_not_inferred")
                    }
                    var status = "error"
                    try {
                        val selected = torque.getVehicleProfileInformation()?.firstOrNull()?.trim()
                        if (current !== s || selected != s.torqueName || !torque.isConnectedToECU) {
                            status = "connection_or_profile_changed"
                        } else {
                            if (spec.mode == "09") s.mode09Attempts[spec.key] = (s.mode09Attempts[spec.key] ?: 0) + 1
                            val replies = torque.sendCommandGetResponse("", spec.command)?.toList().orEmpty()
                            val same = current === s && !closed && torque.isConnectedToECU &&
                                torque.getVehicleProfileInformation()?.firstOrNull()?.trim() == s.torqueName
                            if (!same) {
                                status = "retired_session" // never attribute a late reply to another vehicle
                            } else {
                                if (spec.pid == "31" && spec.mode == "01") {
                                    val ref = s.a6Reference
                                    val age = ref?.let { started - it.getLong("observedMs") }
                                    row.put("a6Reference", if (age != null && age in 0L..10000L) ref else JSONObject.NULL)
                                        .put("a6ReferenceAgeMs", age ?: JSONObject.NULL)
                                        .put("offsetCalibration", "not_performed_not_simultaneous_ECU_pair")
                                }
                                status = if (spec.mode == "09") capture09(s, spec, replies, torque, row)
                                    else capture01(s, spec, replies, row, started)

                            }
                        }
                    } catch (e: Exception) {
                        row.put("errorType", e.javaClass.simpleName) // no exception message/vehicle identifiers
                    }
                    if (current !== s || closed) {
                        status = "retired_session"
                        row.put("valid", false).put("values", JSONObject.NULL)
                        row.remove("rawReplies")
                        row.remove("payloads")
                        row.remove("decodedReplies")
                        row.remove("rawFrames")
                        row.remove("recombinedReplies")
                        row.remove("assembledReplies")
                        row.remove("a6Reference")
                    }
                    var retryDelayMs = 0L
                    if (current === s && !closed) {
                        if (status == "decoded" || status == "captured_raw") {
                            s.consecutiveFailures.remove(spec.key)
                            s.retryAfterMs.remove(spec.key)
                        } else if (status != "connection_or_profile_changed" && status != "retired_session") {
                            s.failures.merge(spec.key, 1) { a, b -> minOf(a, Int.MAX_VALUE - 1) + b }
                            val streak = minOf(s.consecutiveFailures[spec.key] ?: 0, Int.MAX_VALUE - 1) + 1
                            s.consecutiveFailures[spec.key] = streak
                            retryDelayMs = retryDelaysMs[minOf(streak - 1, retryDelaysMs.lastIndex)]
                            s.retryAfterMs[spec.key] = SystemClock.elapsedRealtime() + retryDelayMs
                        }
                    }
                    write(s, row.put("status", status).put("replyMs", System.currentTimeMillis())
                        .put("elapsedMs", SystemClock.elapsedRealtime() - startedMono)
                        .put("sessionCurrent", current === s && !closed)
                        .put("attemptFailures", s.failures[spec.key] ?: 0)
                        .put("consecutiveFailures", s.consecutiveFailures[spec.key] ?: 0)
                        .put("mode09Attempts", s.mode09Attempts[spec.key] ?: 0)
                        .put("mode09Complete", spec.key in s.mode09Done)
                        .put("mode09Exhausted", spec.mode == "09" && spec.key !in s.mode09Done && (s.mode09Attempts[spec.key] ?: 0) >= 3)
                        .put("retryDelayMs", retryDelayMs))
                } catch (e: Exception) {
                    if (current === s) disableAfterFailure(e)
                } finally {
                    busy.set(false)
                }
            }
            submitted = true
        } catch (e: Exception) {
            disableAfterFailure(e)
        } finally {
            // Accepted tasks release in their own finally; rejection/setup failure releases here.
            if (!submitted) busy.set(false)
        }
    }

    private fun eligible(s: Session, spec: Spec): Boolean {
        if (SystemClock.elapsedRealtime() < (s.retryAfterMs[spec.key] ?: 0L)) return false
        if (spec.mode == "09" && (spec.key in s.mode09Done || (s.mode09Attempts[spec.key] ?: 0) >= 3)) return false
        val cap = support(s, spec)
        return cap == "advertised" || cap == "discovery"
    }

    private fun nextSlow(s: Session): Spec? {
        // Small finite once/session identity prefix; no repeated reads after success.
        specs.firstOrNull { it.mode == "09" && eligible(s, it) }?.let { return it }
        repeat(slowSlots.size) {
            val key = slowSlots[s.slowSlot]
            s.slowSlot = (s.slowSlot + 1) % slowSlots.size
            val candidate = specs.firstOrNull { it.key == key }
            if (candidate != null && eligible(s, candidate)) return candidate
        }
        return null
    }

    private fun support(s: Session, spec: Spec): String {
        val n = spec.pid.toInt(16)
        val mask: Long
        val base: Int
        if (spec.mode == "09") {
            if (!s.mode09Known) return "unknown_no_prior_mode09_response"
            if (n == 0) return "discovery"
            base = 0
            mask = s.mode09Mask ?: return "unknown_waiting_for_0900"
        } else {
            base = ((n - 1) / 32) * 32
            val key = "01" + base.toString(16).uppercase().padStart(2, '0')
            mask = s.bitmaps[key]?.toLongOrNull(16) ?: return "unknown"
        }
        return if ((mask and (1L shl (32 - (n - base)))) != 0L) "advertised" else "unsupported"
    }

    private fun capture01(s: Session, spec: Spec, replies: List<String>, row: JSONObject, started: Long): String {
        val marker = "41" + spec.pid
        val matching = replies.filter { it.length <= 128 && ObdReply.payloadAfter(it, marker) != null }.take(8)
        row.put("rawReplies", JSONArray(matching)).put("replyLimitExceeded", replies.size > 8 || replies.any { it.length > 128 })
        val payloads = matching.mapNotNull { ObdReply.dataAfter(it, marker, spec.bytes) }
        row.put("payloads", JSONArray(payloads))
        val decoded = JSONArray()
        matching.forEachIndexed { index, raw ->
            val hex = ObdReply.dataAfter(raw, marker, spec.bytes)
            val valid = hex != null && hex.length == spec.bytes * 2
            val item = JSONObject().put("responseIndex", index).put("rawReply", raw)
                .put("payload", hex ?: JSONObject.NULL).put("valid", valid)
            item.put("values", if (valid) decode(spec, hex!!) else JSONObject.NULL)
            decoded.put(item)
        }
        row.put("decodedReplies", decoded)
        return when {
            matching.isEmpty() && replies.any { it.contains("NO DATA", true) } -> "no_data"
            matching.isEmpty() -> "no_matching_reply"
            replies.size > 8 || replies.any { it.length > 128 } -> "reply_limit_exceeded"
            matching.size != 1 || payloads.size != 1 -> "ambiguous_reply"
            payloads.single().length != spec.bytes * 2 -> "invalid_length"
            else -> {
                val values = decode(spec, payloads.single())
                row.put("values", values).put("valid", true)
                    .put("validity", "decoded_not_physical_or_independence_validation")
                if (spec.pid == "A6") s.a6Reference = JSONObject().put("observedMs", started)
                    .put("replyMs", System.currentTimeMillis()).put("odometerKm", values.getDouble("odometerKm"))
                "decoded"
            }
        }
    }

    private fun capture09(s: Session, spec: Spec, replies: List<String>, torque: ITorqueService, row: JSONObject): String {
        // Keep original framing AND API-recombined array, never concatenate different ECU responses.
        // Only bounded hex/framing is retained; arbitrary adapter text does not enter evidence files.
        val frames = replies.filter { it.length <= 512 && it.matches(Regex("[0-9A-Fa-f :\\r\\n]+")) }.take(64)
        row.put("rawFrames", JSONArray(frames))
        if (replies.size > 64 || replies.any { it.length > 512 }) return "reply_limit_exceeded"
        val combined = try { torque.recombineResponses(replies.toTypedArray())?.toList().orEmpty() }
            catch (e: Exception) { row.put("recombineErrorType", e.javaClass.simpleName); emptyList<String>() }
        if (current !== s || closed || !torque.isConnectedToECU ||
            torque.getVehicleProfileInformation()?.firstOrNull()?.trim() != s.torqueName) return "retired_session"
        // API recombination is retained as evidence only: actual Torque versions returned
        // shuffled numbered fragments. Shared strict framing also protects the identity path.
        row.put("recombinedReplies", JSONArray(combined.filter { it.length <= 512 && it.matches(Regex("[0-9A-Fa-f :\\r\\n]+")) }.take(64)))
        if (combined.size > 64 || combined.any { it.length > 512 }) return "reply_limit_exceeded"
        val coherent = Mode09Replies.messages(replies, spec.pid)
        row.put("assembledReplies", JSONArray(coherent))
        if (spec.pid == "0D") {
            val positives = coherent.filter { ObdReply.payloadAfter(it, "490D") != null }
            row.put("rawPositiveReplyCount", positives.size)
                .put("signal", "engine_serial_number_not_ECU_hardware_serial")
                .put("validity", "raw_only_ESN_layout_not_decoded_no_identity_adoption")
            if (positives.isEmpty()) return if (replies.any { it.contains("NO DATA", true) }) "no_data" else "no_matching_reply"
            s.mode09Done.add(spec.key)
            // Top-level valid stays false and values null: no serial value has been decoded.
            return "captured_raw"
        }
        val decoded = JSONArray()
        var mask = 0L
        for ((index, raw) in coherent.withIndex()) {
            val item = DiagnosticIdentityDecode.decode(spec.pid, raw) ?: continue
            item.put("responseIndex", index).put("rawReply", raw)
            decoded.put(item)
            if (spec.pid == "00") mask = mask or item.getString("supportedInfoTypesHex").toLong(16)
        }
        row.put("decodedReplies", decoded).put("recombinedCount", combined.size).put("assembledCount", coherent.size)
            .put("decodedCount", decoded.length()).put("allRepliesDecoded", coherent.isNotEmpty() && decoded.length() == coherent.size)
        if (decoded.length() == 0) return if (replies.any { it.contains("NO DATA", true) }) "no_data" else "unresolved_framing_or_length"
        if (spec.pid == "00") {
            s.mode09Mask = mask
            val caps = JSONObject()
            for (candidate in specs.filter { it.mode == "09" }) caps.put(candidate.key, support(s, candidate))
            row.put("capabilities", caps).put("supportAggregation", "OR_of_decodable_ECU_bitmaps_not_identity")
        }
        s.mode09Done.add(spec.key)
        row.put("values", decoded).put("valid", true)
            .put("validity", "decoded_per_reply_only_not_unique_vehicle_identity")
        return "decoded"
    }

    private fun decode(spec: Spec, hex: String): JSONObject {
        val b = hex.chunked(2).map { it.toInt(16) }
        return when (spec.pid) {
            "A6" -> JSONObject().put("odometerKm", hex.toLong(16) / 10.0)
            // J1979 9D: four bytes AB (engine) and CD (vehicle), both /50 g/s; NOT a support byte.
            "9D" -> JSONObject().put("engineFuelGps", (b[0] * 256 + b[1]) / 50.0)
                .put("vehicleFuelGps", (b[2] * 256 + b[3]) / 50.0)
            "42" -> JSONObject().put("controlModuleV", (b[0] * 256 + b[1]) / 1000.0)
            "C7" -> JSONObject().put("distanceSinceReflashOrReplacementKm", b[0] * 256 + b[1])
            "31" -> JSONObject().put("distanceSinceDtcClearKm", b[0] * 256 + b[1])
            "21" -> JSONObject().put("distanceWithMilOnKm", b[0] * 256 + b[1])
            "30" -> JSONObject().put("warmupsSinceDtcClear", b[0])
            "4D" -> JSONObject().put("runtimeWithMilOnMinutes", b[0] * 256 + b[1])
            "4E" -> JSONObject().put("timeSinceDtcClearMinutes", b[0] * 256 + b[1])
            "1F" -> JSONObject().put("runtimeSinceEngineStartSeconds", b[0] * 256 + b[1])
            "46" -> JSONObject().put("ambientAirC", b[0] - 40)
            "5C" -> JSONObject().put("engineOilC", b[0] - 40)
            "05" -> JSONObject().put("engineCoolantC", b[0] - 40)
            else -> error("unknown diagnostic decoder")
        }
    }

    private fun write(s: Session, row: JSONObject) {
        ProfileStore.DataLock.lock()
        try {
            val parent = s.file.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs()) error("diagnostics directory unavailable")
            s.file.appendText(row.toString() + "\n")
        } finally { ProfileStore.DataLock.unlock() }
    }
}
