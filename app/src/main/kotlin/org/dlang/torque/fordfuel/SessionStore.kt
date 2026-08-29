package org.dlang.torque.fordfuel

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class Sample(
    val t: Long,
    val rawPct: Double,
    val smoothedPct: Double,
    val fuelUsedGal: Double,
    val speed: Double,
    val fill: Boolean = false,
)

internal class SessionStore(context: Context, private val tank: Int = 1) {
    private val dir = File(context.filesDir, if (tank == 2) "sessions-t2" else "sessions").apply { mkdirs() }
    private val snapshotFile = File(
        context.filesDir,
        if (tank == 2) "last-session-end-t2.json" else "last-session-end.json",
    )

    init {
        if (snapshotFile.isFile) {
            Log.i(TAG, "last session-end snapshot (debug only): ${snapshotFile.readText()}")
        }
    }
    private var file: File? = null
    var sessionId: String? = null
        private set
    var fuelUsedOriginGal: Double? = null
        private set
    val samples = mutableListOf<Sample>()

    val isOpen: Boolean get() = file != null

    fun open(t0: Long = System.currentTimeMillis()): String {
        if (isOpen) close(t0)
        val id = "${t0}-${UUID.randomUUID().toString().take(8)}"
        sessionId = id
        fuelUsedOriginGal = null
        samples.clear()
        val f = File(dir, "$id.jsonl")
        file = f
        f.appendText(
            JSONObject().put("sessionId", id).put("t0", t0).put("event", "open").put("tank", tank).toString() + "\n",
        )
        return id
    }

    fun relativeFuelUsed(absoluteGal: Double): Double {
        val origin = fuelUsedOriginGal
        if (origin == null) {
            fuelUsedOriginGal = absoluteGal
            return 0.0
        }
        val rel = absoluteGal - origin
        return if (rel < 0.0) 0.0 else rel
    }

    fun append(sample: Sample) {
        val f = file ?: return
        samples.add(sample)
        val o = JSONObject()
            .put("t", sample.t)
            .put("rawPct", sample.rawPct)
            .put("smoothedPct", sample.smoothedPct)
            .put("fuelUsedGal", sample.fuelUsedGal)
            .put("speed", sample.speed)
            .put("fill", sample.fill)
            .put("tank", tank)
        f.appendText(o.toString() + "\n")
    }

    fun close(t: Long = System.currentTimeMillis()): List<Sample> {
        val f = file ?: return emptyList()
        val id = sessionId
        val copy = samples.toList()
        f.appendText(
            JSONObject().put("event", "close").put("t", t).put("n", copy.size).put("tank", tank).toString() + "\n",
        )
        if (copy.isNotEmpty()) {
            val last = copy.last()
            snapshotFile.writeText(
                JSONObject()
                    .put("sessionId", id)
                    .put("tank", tank)
                    .put("t", last.t)
                    .put("rawPct", last.rawPct)
                    .put("fuelUsedGal", last.fuelUsedGal)
                    .put("speed", last.speed)
                    .toString(),
            )
        }
        file = null
        sessionId = null
        fuelUsedOriginGal = null
        samples.clear()
        return copy
    }

    fun sessionCount(): Int = dir.listFiles { _, name -> name.endsWith(".jsonl") }?.size ?: 0

    companion object {
        private const val TAG = "FordFuel"
    }
}

internal object Units {
    fun toGallons(value: Double, unit: String): Double {
        val u = unit.lowercase()
        return when {
            u.contains("gal") -> value
            u == "l" || u == "liter" || u == "litre" || u.contains("lit") -> value * 0.264172052
            else -> value
        }
    }
}
