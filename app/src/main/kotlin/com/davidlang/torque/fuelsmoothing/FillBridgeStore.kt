package com.davidlang.torque.fuelsmoothing

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import java.io.File
import java.io.IOException

/**
 * Receipt transport only: immutable packets, stream heads/tombstones, stable VIN mappings and
 * occurrence-indexed NOT_CONSUMED validation scopes. No fitter or live since-fill consumer yet.
 * Kept under files/ so supported snapshots include it. Every handle closes before DataLock release:
 * snapshot/import can safely copy/replace it without dangling SQLite handles or WAL sidecars.
 */
internal object FillBridgeStore {
    private const val MAX_PACKETS = 10000L
    private const val MAX_PAYLOAD_BYTES = 16L * 1024L * 1024L
    private var tokens = 12
    private var refillAt = 0L

    /** Process-wide burst12, refill1/5sec. Busy/storage failures never promise durability. */
    @Synchronized private fun admitted(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (refillAt == 0L) refillAt = now
        val refill = (now - refillAt).coerceAtLeast(0L) / 5000L
        if (refill > 0L) {
            tokens = (tokens + refill.coerceAtMost(12L).toInt()).coerceAtMost(12)
            refillAt = now
        }
        if (tokens == 0) return false
        tokens--
        return true
    }

    fun submit(root: File, batch: FillBridgeProtocol.Batch): String {
        fun retry() = FillBridgeProtocol.response(batch.id, batch.producer, status = "RETRY_LATER")
        if (!admitted() || !ProfileStore.DataLock.tryLock()) return retry()
        try {
            val dir = File(root, "fillbridge")
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("fillbridge directory unavailable")
            val results = SQLiteDatabase.openOrCreateDatabase(File(dir, "ledger.sqlite"), null).use { db ->
                // DELETE mode + FULL commit; no WAL requiring a separate export checkpoint.
                db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() }
                db.execSQL("PRAGMA synchronous=FULL")
                schema(db)
                db.beginTransaction()
                try {
                    val counts = db.rawQuery("SELECT count(*),coalesce(sum(length(CAST(payload AS BLOB))),0) FROM packets", null).use {
                        it.moveToFirst(); longArrayOf(it.getLong(0), it.getLong(1))
                    }
                    val rows = batch.events.map { event ->
                        if (event.payload == null) FillBridgeProtocol.Result(event, "INVALID_EVENT")
                        else apply(db, root, batch.producer, event, counts)
                    }
                    db.setTransactionSuccessful()
                    rows
                } finally {
                    // All ACCEPTED/CONFLICT/DUPLICATE outcomes returned only after commit succeeds.
                    db.endTransaction()
                }
            }
            return FillBridgeProtocol.response(batch.id, batch.producer, results)
        } catch (_: Exception) {
            // An ambiguous commit/close failure is safe to retry with exactly the same tuple.
            return retry()
        } finally {
            ProfileStore.DataLock.unlock()
        }
    }

    private fun schema(db: SQLiteDatabase) {
        if (db.version != 0 && db.version != 1) throw IOException("unknown fillbridge schema")
        if (db.version == 1) return
        db.beginTransaction()
        try {
            db.execSQL("""CREATE TABLE packets (
                producer TEXT NOT NULL,event_id TEXT NOT NULL,revision INTEGER NOT NULL,hash TEXT NOT NULL,
                payload TEXT NOT NULL,first_received_ms INTEGER NOT NULL,profile_id TEXT,quarantine TEXT,
                PRIMARY KEY(producer,event_id,revision,hash))""")
            db.execSQL("""CREATE TABLE heads (
                producer TEXT NOT NULL,event_id TEXT NOT NULL,revision INTEGER NOT NULL,hash TEXT NOT NULL,
                profile_id TEXT,occurred_ms INTEGER NOT NULL,operation TEXT NOT NULL,quarantine TEXT,
                PRIMARY KEY(producer,event_id))""")
            db.execSQL("CREATE INDEX heads_event ON heads(event_id)")
            db.execSQL("""CREATE TABLE vehicle_map (
                producer TEXT NOT NULL,vehicle_id TEXT NOT NULL,profile_id TEXT NOT NULL,vin TEXT NOT NULL,
                PRIMARY KEY(producer,vehicle_id))""")
            db.execSQL("""CREATE TABLE pending_validation (
                id INTEGER PRIMARY KEY,producer TEXT NOT NULL,event_id TEXT NOT NULL,revision INTEGER NOT NULL,
                hash TEXT NOT NULL,profile_id TEXT,occurred_ms INTEGER NOT NULL,reason TEXT NOT NULL,
                consumption_status TEXT NOT NULL CHECK(consumption_status='NOT_CONSUMED'))""")
            db.execSQL("CREATE INDEX pending_occurrence ON pending_validation(profile_id,occurred_ms)")
            db.execSQL("CREATE TABLE bridge_meta (name TEXT PRIMARY KEY,value TEXT NOT NULL)")
            db.execSQL("INSERT INTO bridge_meta VALUES ('contract_sha256','9bb7ae552c8cccc9b2a7d12c5c506587e5a0d35c3fb8f0c5d048578abe3e20f9')")
            db.execSQL("INSERT INTO bridge_meta VALUES ('analysis_consumer','NOT_IMPLEMENTED')")
            db.version = 1
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private data class Head(val revision: Long, val hash: String, val profile: String?, val occurred: Long, val quarantine: String?)
    private data class Association(val profile: String?, val reason: String?)

    private fun head(db: SQLiteDatabase, producer: String, id: String): Head? = db.rawQuery(
        "SELECT revision,hash,profile_id,occurred_ms,quarantine FROM heads WHERE producer=? AND event_id=?",
        arrayOf(producer, id),
    ).use {
        if (!it.moveToFirst()) null else Head(it.getLong(0), it.getString(1), it.getString(2), it.getLong(3), it.getString(4))
    }

    private fun apply(db: SQLiteDatabase, root: File, producer: String, event: FillBridgeProtocol.Event, counts: LongArray): FillBridgeProtocol.Result {
        val payload = event.payload ?: return FillBridgeProtocol.Result(event, "INVALID_EVENT")
        val exists = db.rawQuery(
            "SELECT 1 FROM packets WHERE producer=? AND event_id=? AND revision=? AND hash=?",
            arrayOf(producer, event.id, event.revision.toString(), event.hash),
        ).use { it.moveToFirst() }
        if (exists) return FillBridgeProtocol.Result(event, "DUPLICATE")
        val old = head(db, producer, event.id)
        if (old != null && event.revision < old.revision) return FillBridgeProtocol.Result(event, "STALE", old.revision, old.hash)
        val bytes = payload.canonical.toByteArray(Charsets.UTF_8).size.toLong()
        if (counts[0] >= MAX_PACKETS || counts[1] + bytes > MAX_PAYLOAD_BYTES) return FillBridgeProtocol.Result(event, "RETRY_LATER")
        val fork = old != null && event.revision == old.revision
        val newNamespaceCollision = db.rawQuery("SELECT 1 FROM heads WHERE event_id=? AND producer<>? LIMIT 1", arrayOf(event.id, producer)).use { it.moveToFirst() }
        val association = when {
            newNamespaceCollision -> Association(null, "PRODUCER_CHANGED")
            fork -> Association(null, "REVISION_CONFLICT")
            else -> associate(db, root, producer, payload)
        }
        val received = System.currentTimeMillis()
        val packet = ContentValues().apply {
            put("producer", producer); put("event_id", event.id); put("revision", event.revision); put("hash", event.hash)
            put("payload", payload.canonical); put("first_received_ms", received)
            put("profile_id", association.profile); put("quarantine", association.reason)
        }
        db.insertOrThrow("packets", null, packet)
        counts[0]++; counts[1] += bytes
        // Both old and new occurrence scopes are invalidated, never receipt-time "fill now".
        if (old != null) pending(db, producer, event, old.profile, old.occurred, "REPLACED_OR_CONFLICTED")
        pending(db, producer, event, association.profile, payload.occurredMs, association.reason ?: "RECEIPT_CHANGED")
        if (fork) {
            // Keep the original head hash/evidence. Conflict makes this head noncontributing until
            // a genuinely higher local revision arrives; the competing body remains immutable.
            val change = ContentValues().apply { putNull("profile_id"); put("quarantine", "REVISION_CONFLICT") }
            db.update("heads", change, "producer=? AND event_id=?", arrayOf(producer, event.id))
            return FillBridgeProtocol.Result(event, "CONFLICT", old!!.revision, old.hash)
        }
        val current = ContentValues().apply {
            put("producer", producer); put("event_id", event.id); put("revision", event.revision); put("hash", event.hash)
            put("profile_id", association.profile); put("occurred_ms", payload.occurredMs)
            put("operation", payload.operation); put("quarantine", association.reason)
        }
        db.insertWithOnConflict("heads", null, current, SQLiteDatabase.CONFLICT_REPLACE).also {
            if (it == -1L) throw IOException("fillbridge head commit failed")
        }
        return FillBridgeProtocol.Result(event, "ACCEPTED")
    }

    private fun pending(db: SQLiteDatabase, producer: String, event: FillBridgeProtocol.Event, profile: String?, occurred: Long, reason: String) {
        db.insertOrThrow("pending_validation", null, ContentValues().apply {
            put("producer", producer); put("event_id", event.id); put("revision", event.revision); put("hash", event.hash)
            put("profile_id", profile); put("occurred_ms", occurred); put("reason", reason)
            put("consumption_status", "NOT_CONSUMED")
        })
    }

    /** Only a unique privately retained VIN or a previously VIN-established stable local mapping.
     * Names/display choices never select a profile; no active-profile or settings-profile fallback.
     */
    private fun associate(db: SQLiteDatabase, root: File, producer: String, payload: FillBridgeProtocol.Payload): Association =
        synchronized(ProfileStore.PROFILE_LOCK) {
            val mapped = db.rawQuery("SELECT profile_id,vin FROM vehicle_map WHERE producer=? AND vehicle_id=?", arrayOf(producer, payload.vehicleId)).use {
                if (!it.moveToFirst()) null else it.getString(0) to it.getString(1)
            }
            if (mapped != null) {
                val marker = File(ProfileStore.profileDir(root, mapped.first), "profile.json")
                val stored = if (marker.isFile) Fingerprint.load(marker.parentFile!!).vin else null
                if (stored != mapped.second || (payload.vin != null && payload.vin != mapped.second)) {
                    return@synchronized Association(null, "VEHICLE_MAPPING_MISMATCH")
                }
                return@synchronized Association(mapped.first, null)
            }
            val vin = payload.vin ?: return@synchronized Association(null, "UNKNOWN_VEHICLE")
            // Connection identity is retained in fingerprint.json, not stamped into profile.json.
            // Fingerprint.loadAll also supports pre-fingerprint profile.json VINs.
            val hits = Fingerprint.loadAll(root).filterValues { it.vin == vin }.keys.toList()
            val match = hits.singleOrNull() ?: return@synchronized Association(null, if (hits.isEmpty()) "UNKNOWN_VEHICLE" else "AMBIGUOUS_VEHICLE")
            db.insertOrThrow("vehicle_map", null, ContentValues().apply {
                put("producer", producer); put("vehicle_id", payload.vehicleId); put("profile_id", match); put("vin", vin)
            })
            Association(match, null)
        }
}
