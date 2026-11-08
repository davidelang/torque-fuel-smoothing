package com.davidlang.torque.fuelsmoothing

import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Currency

/** Frozen contract SHA256 9bb7ae552c8cccc9b2a7d12c5c506587e5a0d35c3fb8f0c5d048578abe3e20f9. */
internal object FillBridgeProtocol {
    const val MAX_BYTES = 32768
    const val MAX_EVENTS = 16
    private const val MAX_EPOCH = 253402300799999L
    private val UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val HASH = Regex("[0-9a-f]{64}")
    private val DECIMAL = Regex("(?:0|[1-9][0-9]{0,17})(?:\\.[0-9]{0,8}[1-9])?")
    private val SOURCES = setOf("torque", "email", "ocr", "manual", "sync", "mixed", "legacy_unknown")
    private val POINTERS = setOf(
        "/vehicle/syncId", "/vehicle/vin", "/vehicle/name", "/vehicle/make", "/vehicle/model",
        "/vehicle/year", "/vehicle/displayUnits", "/occurredAtEpochMs", "/quantity", "/cost",
        "/fillExtent", "/economyIgnored", "/odometer", "/rawTorqueOdometer",
    )

    class EnvelopeError(val status: String, val batchId: String? = null) : IllegalArgumentException()
    data class Event(val id: String, val revision: Long, val hash: String, val payload: Payload?)
    data class Batch(val producer: String, val id: String, val events: List<Event>)
    data class Payload(val canonical: String, val vehicleId: String, val vin: String?, val occurredMs: Long, val operation: String)
    data class Result(val event: Event, val status: String, val currentRevision: Long? = null, val currentHash: String? = null)

    fun capabilities(version: String): String = FillBridgeJson.canonical(linkedMapOf(
        "aidlVersion" to 1L, "schemaVersions" to listOf(1L),
        "receiverPackage" to "com.davidlang.torque.fuelsmoothing", "receiverVersion" to version.takeWhile { it.code in 32..126 }.take(128),
        "maxEvents" to MAX_EVENTS.toLong(), "maxRequestBytes" to MAX_BYTES.toLong(), "maxResponseBytes" to MAX_BYTES.toLong(),
    ))

    fun parse(text: String): Batch {
        if (text.length > MAX_BYTES || text.toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw EnvelopeError("BATCH_TOO_LARGE")
        var batchId: String? = null
        try {
            val root = FillBridgeJson.obj(FillBridgeJson.parse(text))
            batchId = (root["batchId"] as? String)?.takeIf { UUID.matches(it) }
            FillBridgeJson.keys(root, "schemaVersion", "producer", "batchId", "events")
            val schema = FillBridgeJson.integer(root["schemaVersion"])
            if (schema != 1L) throw EnvelopeError("UNSUPPORTED_VERSION", batchId)
            val producer = FillBridgeJson.string(root["producer"]).also { requireValid(UUID.matches(it)) }
            val batch = batchId ?: throw FillBridgeJson.Invalid()
            val rows = root["events"] as? List<*> ?: throw FillBridgeJson.Invalid()
            if (rows.size > MAX_EVENTS) throw EnvelopeError("BATCH_TOO_LARGE", batch)
            requireValid(rows.isNotEmpty())
            val ids = hashSetOf<String>()
            val events = rows.map { value ->
                val row = FillBridgeJson.obj(value)
                FillBridgeJson.keys(row, "eventSyncId", "revision", "payloadHash", "payload")
                val id = id(row["eventSyncId"])
                requireValid(ids.add(id))
                val revision = FillBridgeJson.integer(row["revision"]).also { requireValid(it > 0) }
                val hash = FillBridgeJson.string(row["payloadHash"]).also { requireValid(HASH.matches(it)) }
                val payload = try { payload(row["payload"], hash) } catch (_: FillBridgeJson.Invalid) { null }
                Event(id, revision, hash, payload)
            }
            // Conservative worst-case ACK fit, BEFORE opening/applying the ledger.
            val worst = events.map { Result(it, "INVALID_EVENT", Long.MAX_VALUE, "f".repeat(64)) }
            if (response(batch, producer, worst).toByteArray(Charsets.UTF_8).size > MAX_BYTES) throw EnvelopeError("BATCH_TOO_LARGE", batch)
            return Batch(producer, batch, events)
        } catch (e: FillBridgeJson.Invalid) {
            throw EnvelopeError("INVALID_REQUEST", batchId)
        }
    }

    fun response(batch: String?, producer: String?, results: List<Result> = emptyList(), status: String = "OK"): String =
        FillBridgeJson.canonical(linkedMapOf(
            "schemaVersion" to 1L, "batchId" to batch, "envelopeStatus" to status,
            "results" to results.map { r -> linkedMapOf(
                "producer" to producer, "eventSyncId" to r.event.id, "revision" to r.event.revision,
                "payloadHash" to r.event.hash, "status" to r.status,
                "currentRevision" to r.currentRevision, "currentHash" to r.currentHash,
            ) },
        ))

    private fun payload(value: Any?, hash: String): Payload {
        val p = FillBridgeJson.obj(value)
        FillBridgeJson.keys(p, "operation", "vehicle", "occurredAtEpochMs", "quantity", "cost", "fillExtent",
            "economyIgnored", "odometer", "rawTorqueOdometer", "provenance", "voidReason")
        val operation = choice(p["operation"], setOf("upsert", "void"))
        val v = FillBridgeJson.obj(p["vehicle"])
        FillBridgeJson.keys(v, "syncId", "vin", "name", "make", "model", "year", "displayUnits")
        val vehicleId = id(v["syncId"])
        val vin = v["vin"]?.let { vin(FillBridgeJson.string(it)) }
        text(v["name"], 256); text(v["make"], 128); text(v["model"], 128)
        v["year"]?.let { requireValid(FillBridgeJson.integer(it) in 1000L..9999L) }
        choice(v["displayUnits"], setOf("imperial", "metric"))
        val occurred = epoch(p["occurredAtEpochMs"])
        p["quantity"]?.let {
            val q = FillBridgeJson.obj(it)
            FillBridgeJson.keys(q, "value", "unit")
            requireValid(FillBridgeJson.string(q["unit"]) == "L" && decimal(q["value"]) > BigDecimal.ZERO)
        }
        p["cost"]?.let {
            val c = FillBridgeJson.obj(it)
            FillBridgeJson.keys(c, "value", "currency")
            decimal(c["value"])
            val currency = FillBridgeJson.string(c["currency"])
            requireValid(Regex("[A-Z]{3}").matches(currency))
            try { Currency.getInstance(currency) } catch (_: IllegalArgumentException) { throw FillBridgeJson.Invalid() }
        }
        val extent = choice(p["fillExtent"], setOf("full", "partial", "unknown"))
        val ignored = FillBridgeJson.bool(p["economyIgnored"])
        val odo = p["odometer"]?.let {
            val o = FillBridgeJson.obj(it)
            FillBridgeJson.keys(o, "value", "unit", "source", "observedAtEpochMs")
            decimal(o["value"])
            requireValid(FillBridgeJson.string(o["unit"]) == "km")
            choice(o["source"], setOf("torque", "photo", "manual", "unknown"))
            o["observedAtEpochMs"]?.let { time -> epoch(time) }
            o
        }
        p["rawTorqueOdometer"]?.let {
            val raw = FillBridgeJson.obj(it)
            FillBridgeJson.keys(raw, "kilometers", "resolutionKm", "observedAtEpochMs")
            val km = decimal(raw["kilometers"])
            requireValid(km <= BigDecimal("429496729.5") && km.remainder(BigDecimal("0.1")).compareTo(BigDecimal.ZERO) == 0)
            requireValid(FillBridgeJson.string(raw["resolutionKm"]) == "0.1")
            val observed = epoch(raw["observedAtEpochMs"])
            requireValid(odo != null && odo["source"] == "torque" && odo["observedAtEpochMs"] == observed)
            requireValid(odo != null && decimal(odo["value"]).compareTo(km) == 0)
        }
        val provenance = FillBridgeJson.obj(p["provenance"])
        FillBridgeJson.keys(provenance, "originDeviceId", "sourceUpdatedAtEpochMs", "sourceKind", "occurrenceTimeQuality", "producerBuildAtResolution", "fields")
        provenance["originDeviceId"]?.let { id(it) }
        provenance["sourceUpdatedAtEpochMs"]?.let { epoch(it) }
        choice(provenance["sourceKind"], SOURCES)
        choice(provenance["occurrenceTimeQuality"], setOf("confirmed", "source_reported", "unknown"))
        text(provenance["producerBuildAtResolution"], 128)
        for ((path, data) in FillBridgeJson.obj(provenance["fields"])) {
            requireValid(path in POINTERS)
            val field = FillBridgeJson.obj(data)
            FillBridgeJson.keys(field, "source", "sourceRef", "observedAtEpochMs", "confirmed")
            choice(field["source"], SOURCES); text(field["sourceRef"], 256)
            field["observedAtEpochMs"]?.let { epoch(it) }
            field["confirmed"]?.let { FillBridgeJson.bool(it) }
        }
        if (operation == "void") {
            choice(p["voidReason"], setOf("deleted", "trip_marker"))
            requireValid(listOf("quantity", "cost", "odometer", "rawTorqueOdometer").all { p[it] == null })
            requireValid(extent == "unknown" && !ignored)
        } else requireValid(p["voidReason"] == null)
        val canonical = FillBridgeJson.canonical(p)
        val actual = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        requireValid(actual == hash)
        return Payload(canonical, vehicleId, vin, occurred, operation)
    }

    private fun epoch(value: Any?): Long = FillBridgeJson.integer(value).also { requireValid(it in 0L..MAX_EPOCH) }
    private fun id(value: Any?): String = FillBridgeJson.string(value).also { requireValid(it.length in 1..256 && it.all { c -> c in '!'..'~' }) }
    private fun text(value: Any?, maxBytes: Int) { if (value != null) requireValid(FillBridgeJson.string(value).toByteArray(Charsets.UTF_8).size <= maxBytes) }
    private fun decimal(value: Any?): BigDecimal {
        val s = FillBridgeJson.string(value)
        requireValid(DECIMAL.matches(s))
        return BigDecimal(s)
    }
    private fun choice(value: Any?, choices: Set<String>): String = FillBridgeJson.string(value).also { requireValid(it in choices) }
    private fun requireValid(ok: Boolean) { if (!ok) throw FillBridgeJson.Invalid() }

    private fun vin(s: String): String {
        requireValid(Regex("[A-HJ-NPR-Z0-9]{17}").matches(s))
        // ISO 3779 check digit where mandatory for North-American WMI regions.
        if (s[0] in "12345") {
            val letters = "ABCDEFGHJKLMNPRSTUVWXYZ"
            val values = intArrayOf(1,2,3,4,5,6,7,8,1,2,3,4,5,7,9,2,3,4,5,6,7,8,9)
            val weights = intArrayOf(8,7,6,5,4,3,2,10,0,9,8,7,6,5,4,3,2)
            val sum = s.indices.sumOf { i -> (if (s[i] in '0'..'9') s[i] - '0' else values[letters.indexOf(s[i])]) * weights[i] }
            requireValid(s[8] == if (sum % 11 == 10) 'X' else ('0'.code + sum % 11).toChar())
        }
        return s
    }
}
