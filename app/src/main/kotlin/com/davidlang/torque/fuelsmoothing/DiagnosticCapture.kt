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
    private data class Spec(val pid: String, val bytes: Int, val unit: String)
    private val specs = listOf(Spec("A6", 4, "km"), Spec("9D", 4, "g/s"),
        Spec("46", 1, "degC"), Spec("42", 2, "V"), Spec("5C", 1, "degC"), Spec("05", 1, "degC"))
    // At most one request/5s: A6 and 9D each ~15s, each context PID ~60s.
    private val slots = listOf("A6", "9D", "46", "A6", "9D", "42", "A6", "9D", "5C", "A6", "9D", "05")
    private class Session(val profile: String, val tank: Int, val id: String, val torqueName: String,
        val file: File, val protocol: String, val bitmaps: Map<String, String>) {
        var slot = 0
        var nextMs = 0L
        var opened = false // worker only; no file writes on the sampling thread
        val failures = java.util.concurrent.ConcurrentHashMap<String, Int>()
    }
    @Volatile private var current: Session? = null
    @Volatile private var closed = false
    @Volatile private var failed = false
    private val busy = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "FuelSmooth-diagnostics").apply { isDaemon = true } }

    fun start(profile: String, tank: Int, id: String, torqueName: String, dir: File,
        protocol: String?, bitmaps: Map<String, String>) {
        if (closed || failed) return
        current = Session(profile, tank, id, torqueName, File(File(dir, "diagnostics"), "$id.jsonl"),
            protocol.orEmpty(), bitmaps.toMap())
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
            val spec = specs.firstOrNull { it.pid == pid } ?: error("unknown diagnostic schedule entry")
            val queuedMs = System.currentTimeMillis()
            worker.execute {
                try {
                    if (closed || current !== s) return@execute
                    if (!s.opened) {
                        val caps = JSONObject()
                        for (candidate in specs) caps.put(candidate.pid, support(s, candidate.pid))
                        write(s, JSONObject().put("event", "open").put("t", queuedMs).put("v", 1)
                            .put("app", BuildConfig.VERSION_NAME).put("profileId", s.profile).put("tank", s.tank)
                            .put("sessionId", s.id).put("protocol", s.protocol).put("bitmaps", JSONObject(s.bitmaps))
                            .put("capabilitySource", "saved_identity_fingerprint").put("capabilities", caps).put("policy", "advertised_CAN_only_one_request_per_5s")
                            .put("volumeIndependence", "unknown"))
                        s.opened = true
                    }
                    // Do not add diagnostic bus traffic to the older J1850 van, or guess unknown capabilities.
                    if (!s.protocol.contains("15765") || support(s, spec.pid) != "advertised" ||
                        (s.failures[spec.pid] ?: 0) >= 3) return@execute
                    val started = System.currentTimeMillis()
                    val startedMono = SystemClock.elapsedRealtime()
                    val row = JSONObject().put("event", "obd").put("queuedMs", queuedMs).put("t", started)
                        .put("pid", "01" + spec.pid).put("source", "Torque.sendCommandGetResponse")
                        .put("header", "").put("unit", spec.unit).put("context", context)
                        .put("valid", false).put("values", JSONObject.NULL)
                    var status = "error"
                    try {
                        val selected = torque.getVehicleProfileInformation()?.firstOrNull()?.trim()
                        if (current !== s || selected != s.torqueName || !torque.isConnectedToECU) {
                            status = "connection_or_profile_changed"
                        } else {
                            val replies = torque.sendCommandGetResponse("", "01" + spec.pid)?.toList().orEmpty()
                            val same = current === s && !closed && torque.isConnectedToECU &&
                                torque.getVehicleProfileInformation()?.firstOrNull()?.trim() == s.torqueName
                            if (!same) {
                                status = "retired_session" // never attribute a late reply to another vehicle
                            } else {
                                val marker = "41" + spec.pid
                                // Save only matching Mode01 hex replies, not arbitrary adapter/identity text.
                                val matching = replies.filter { it.length <= 128 && ObdReply.payloadAfter(it, marker) != null }.take(8)
                                row.put("rawReplies", JSONArray(matching))
                                val payloads = matching.mapNotNull { ObdReply.dataAfter(it, marker, spec.bytes) }
                                row.put("payloads", JSONArray(payloads))
                                status = when {
                                    matching.isEmpty() && replies.any { it.contains("NO DATA", true) } -> "no_data"
                                    matching.isEmpty() -> "no_matching_reply"
                                    payloads.size != 1 -> "ambiguous_reply"
                                    payloads.single().length != spec.bytes * 2 -> "invalid_length"
                                    else -> {
                                        row.put("values", decode(spec, payloads.single())).put("valid", true)
                                            .put("validity", "decoded_not_physical_or_independence_validation")
                                        "decoded"
                                    }
                                }
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
                    }
                    if (status != "decoded") s.failures.merge(spec.pid, 1) { a, b -> a + b }
                    write(s, row.put("status", status).put("replyMs", System.currentTimeMillis())
                        .put("elapsedMs", SystemClock.elapsedRealtime() - startedMono)
                        .put("sessionCurrent", current === s && !closed)
                        .put("attemptFailures", s.failures[spec.pid] ?: 0))
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

    private fun support(s: Session, pid: String): String {
        val n = pid.toInt(16)
        val base = ((n - 1) / 32) * 32
        val key = "01" + base.toString(16).uppercase().padStart(2, '0')
        val mask = s.bitmaps[key]?.toLongOrNull(16) ?: return "unknown"
        return if ((mask and (1L shl (32 - (n - base)))) != 0L) "advertised" else "unsupported"
    }

    private fun decode(spec: Spec, hex: String): JSONObject {
        val b = hex.chunked(2).map { it.toInt(16) }
        return when (spec.pid) {
            "A6" -> JSONObject().put("odometerKm", hex.toLong(16) / 10.0)
            // J1979 9D: four bytes AB (engine) and CD (vehicle), both /50 g/s; NOT a support byte.
            "9D" -> JSONObject().put("engineFuelGps", (b[0] * 256 + b[1]) / 50.0)
                .put("vehicleFuelGps", (b[2] * 256 + b[3]) / 50.0)
            "42" -> JSONObject().put("controlModuleV", (b[0] * 256 + b[1]) / 1000.0)
            "46" -> JSONObject().put("ambientAirC", b[0] - 40)
            "5C" -> JSONObject().put("engineOilC", b[0] - 40)
            else -> JSONObject().put("engineCoolantC", b[0] - 40)
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
