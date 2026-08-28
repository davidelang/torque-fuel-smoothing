package org.dlang.torque.fordfuel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.prowl.torque.remote.ITorqueService

class PluginService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sessions: SessionStore
    private lateinit var binStore: BinStore
    private val math = FuelMath()
    private var torque: ITorqueService? = null
    /** True only after onServiceConnected, not merely bindService returning true. */
    private var bound = false
    private var bindRequested = false
    private var fordPid: String? = null
    private var speedPid: String? = null
    private var fuelUsedPid: String? = null
    private var fuelUsedUnit: String = "gal"
    private var galHrPid: String? = null
    private var galMinPid: String? = null
    private var sessionUsedGal: Double = 0.0
    private var lastRateMs: Long? = null
    private var speedUnit: String = "mph"
    private var mpgPid: String? = null
    private var mpgUnit: String = "mpg"
    private var lastRawPct: Double? = null
    private var lastAuthPct: Double? = null
    private var lastSliderPct: Double? = null
    private var lastEcuConnected: Boolean? = null
    private var sliderWritable: Boolean? = null
    private var nameplateGal: Double? = null
    private var pidNames: Map<String, String> = emptyMap()
    private var cfg: FixFuelConfig = FixFuelConfig(
        sourceId = FixFuelPrefs.DEFAULT_SOURCE,
        customName = "",
        tauSec = FixFuelPrefs.DEFAULT_TAU,
        skewPctPerSec = FixFuelPrefs.DEFAULT_SKEW,
        jumpPct = FixFuelPrefs.DEFAULT_JUMP,
        tankGal = null,
        learnedUsable = null,
        tankEstimated = false,
        sourceStatus = "",
    )

    private val poll = object : Runnable {
        override fun run() {
            sampleOnce()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            torque = ITorqueService.Stub.asInterface(service)
            bound = true
            bindRequested = true
            Log.i(TAG, "bound Torque API ${runCatching { torque?.version }.getOrNull()}")
            handler.removeCallbacks(poll)
            handler.post { afterBindWork() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Torque disconnected; not auto-creating")
            closeSession()
            torque = null
            bound = false
            bindRequested = false
            lastEcuConnected = null
            handler.removeCallbacks(poll)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sessions = SessionStore(this)
        binStore = BinStore(this)
        ensureChannel()
        TapStartNotice.cancel(this)
        goForeground("Binding to Torque…")
        bindTorque()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OBD_CONNECTED -> {
                onEcu(true)
                bindTorque()
            }
            ACTION_PID_QUERY, ACTION_PID_PLUGIN -> bindTorque()
            ACTION_OBD_DISCONNECTED -> onEcu(false)
        }
        goForeground(statusText())
        if (intent?.action.isNullOrEmpty()) {
            bindTorque()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        closeSession()
        if (bindRequested) {
            runCatching { unbindService(connection) }
        }
        bindRequested = false
        bound = false
        torque = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bindTorque() {
        if (bound || bindRequested) return
        val flags = 0
        Log.i(TAG, "bindTorque flags=$flags (no BIND_AUTO_CREATE)")
        val ok = runCatching {
            bindService(intentForTorque(), connection, flags)
        }.getOrDefault(false)
        if (ok) {
            bindRequested = true
            Log.i(TAG, "bindService ok (waiting onServiceConnected; flags=$flags)")
        } else {
            bindRequested = false
            Log.i(TAG, "bindService fail flags=$flags (Torque not running; will not create)")
        }
    }

    private fun intentForTorque(): Intent {
        return Intent().setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService")
    }

    private fun afterBindWork() {
        val t = torque ?: return
        resolvePids()
        runCatching { binStore.loadFromProfile(t) }
        nameplateGal = TankSeed.seed(t)
        registerPluginPids(t)
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    private fun resolvePids() {
        val t = torque ?: return
        val r = runCatching { Pids.resolve(t) }.getOrNull() ?: return
        fordPid = r.ford
        speedPid = r.speed
        if (r.speedUnit.isNotEmpty()) speedUnit = r.speedUnit
        fuelUsedPid = r.fuelUsed
        if (r.fuelUsedUnit.isNotEmpty()) fuelUsedUnit = r.fuelUsedUnit
        galHrPid = r.galHr ?: Pids.GAL_PER_HOUR
        galMinPid = r.galMin ?: Pids.GAL_PER_MIN
        mpgPid = r.mpg
        if (r.mpgUnit.isNotEmpty()) mpgUnit = r.mpgUnit
        pidNames = r.names
        Log.i(
            TAG,
            "pids ford=${fordPid ?: "UNRESOLVED"} speed=$speedPid fuelUsed=$fuelUsedPid unit=$fuelUsedUnit mpg=$mpgPid names=${pidNames.size}",
        )
    }

    private fun sampleOnce() {
        cfg = FixFuelPrefs.load(this)
        nameplateGal = cfg.learnedUsable ?: cfg.tankGal
        val t = torque ?: return
        val ecu = runCatching { t.isConnectedToECU }.getOrNull()
        if (ecu != null && ecu != lastEcuConnected) {
            onEcu(ecu)
        }
        if (pidNames.isEmpty()) resolvePids()
        val src = FuelSources.read(t, cfg, pidNames) { id -> readDouble(t, id) }
        if (src.how != cfg.sourceStatus) {
            FixFuelPrefs.setSourceStatus(this, src.how)
        }
        Log.i(TAG, "source ${cfg.sourceId} ${src.how}")
        val raw = src.pct
        val valid = math.isValidSample(raw, ecu) && !src.missing
        if (!valid) {
            math.hold()
            val held = math.smoothed
            if (held != null) {
                publishHeld(t, raw, held)
            }
            Log.i(TAG, "track mode=hold raw=$raw smoothed=$held ecu=$ecu src=${src.how}")
            return
        }
        val live = raw!!
        lastRawPct = live
        if (ecu == true && !sessions.isOpen) {
            openSession()
        }
        val speedRaw = speedPid?.let { readDouble(t, it) } ?: 0.0
        val speedMph = FuelMath.toMph(speedRaw, speedUnit)
        val now = System.currentTimeMillis()
        val before = math.smoothed
        val prevMode = math.mode
        val smoothed = math.ingest(live, speedMph, nowMs = now, tauSec = cfg.tauSec, skewPctPerSec = cfg.skewPctPerSec, jumpPct = cfg.jumpPct)
        if (math.mode == TrackMode.FILL && prevMode != TrackMode.FILL) {
            val delta = live - (before ?: live)
            Log.i(TAG, "fill start Δ=${"%.1f".format(delta)} raw=$live smoothedWas=${before} speedMph=${"%.1f".format(speedMph)}")
        }
        if (prevMode == TrackMode.FILL && math.mode != TrackMode.FILL) {
            Log.i(TAG, "fill end raw=$live smoothed=$smoothed speedMph=${"%.1f".format(speedMph)}")
        }
        if (sessions.isOpen) {
            integrateGalHr(t, now)
            sessions.append(
                Sample(
                    t = now,
                    rawPct = live,
                    smoothedPct = smoothed,
                    fuelUsedGal = sessionUsedGal,
                    speed = speedRaw,
                    fill = math.mode == TrackMode.FILL,
                ),
            )
        }
        publishFromSmoothed(t, live, smoothed)
        val line = "track mode=${math.mode} raw=$live smoothed=$smoothed speedMph=${"%.1f".format(speedMph)}"
        Log.i(TAG, line)
        FixFuelPrefs.setLive(this, line)
    }

    private fun publishHeld(t: ITorqueService, raw: Double?, held: Double) {
        publishFromSmoothed(t, raw ?: held, held)
    }

    private fun publishFromSmoothed(t: ITorqueService, raw: Double, smoothed: Double) {
        val bins = binStore.bins.toList()
        val usable = cfg.learnedUsable ?: cfg.tankGal
        val remain = math.remainingGal(smoothed, bins, usable, binStore.reserveGal)
        val corrected = math.correctedPct(smoothed, remain, usable, bins.isEmpty())
        lastAuthPct = corrected
        val mpgRaw = mpgPid?.let { readDouble(t, it) }
        val mpg = mpgRaw?.let { FuelMath.mpgFrom(it, mpgUnit) } ?: 0.0
        val dte = math.dteMiles(remain, mpg)
        publish(t, raw, smoothed, corrected, remain, dte)
        maybeSyncSlider(t, corrected)
        goForeground(statusText())
    }

    private fun onEcu(connected: Boolean) {
        if (lastEcuConnected == connected) return
        lastEcuConnected = connected
        if (connected) {
            openSession()
        } else {
            closeSession()
        }
    }

    private fun registerPluginPids(t: ITorqueService) {
        runCatching {
            publishFour(t, 0.0, 0.0, 0.0, 0.0)
        }.onFailure { Log.w(TAG, "registerPluginPids: ${it.message}") }
        Log.i(TAG, "registered ${Pids.PLUGIN_SMOOTHED}, ${Pids.PLUGIN_CORRECTED}, ${Pids.PLUGIN_REMAINING}, ${Pids.PLUGIN_DTE}")
    }

    private fun publish(
        t: ITorqueService,
        raw: Double,
        smoothed: Double,
        corrected: Double,
        remain: Double,
        dte: Double,
    ) {
        runCatching { publishFour(t, smoothed, corrected, remain, dte) }
    }

    private fun publishFour(
        t: ITorqueService,
        smoothed: Double,
        corrected: Double,
        remain: Double,
        dte: Double,
    ) {
        val rmax = remainingMax()
        t.setPIDInformation(Pids.PLUGIN_SMOOTHED, "SmFuel", "%", 100f, 0f, smoothed.toFloat(), null)
        t.setPIDInformation(Pids.PLUGIN_CORRECTED, "CorrFuel", "%", 100f, 0f, corrected.toFloat(), null)
        t.setPIDInformation(Pids.PLUGIN_REMAINING, "GalLeft", "gal", rmax, 0f, remain.toFloat(), null)
        t.setPIDInformation(Pids.PLUGIN_DTE, "pDTE", "mi", 500f, 0f, dte.toFloat(), null)
        t.setPIDInformation(Pids.ALIAS_SMOOTHED, "SmFuel", "%", 100f, 0f, smoothed.toFloat(), null)
        t.setPIDInformation(Pids.ALIAS_FORD, "PFord", "%", 100f, 0f, corrected.toFloat(), null)
        t.setPIDInformation(Pids.ALIAS_REMAINING, "GalLeft", "gal", rmax, 0f, remain.toFloat(), null)
        t.setPIDInformation(Pids.ALIAS_DTE, "pDTE", "mi", 500f, 0f, dte.toFloat(), null)
    }

    private fun maybeSyncSlider(t: ITorqueService, auth: Double) {
        if (!FuelMath.crossedOnePercent(lastSliderPct, auth)) return
        lastSliderPct = auth
        runCatching {
            t.storeInProfile("${Pids.PREFIX}authoritativePct", auth.toString(), true)
        }
        val rc = runCatching { t.storeInProfile("tankUsed", auth.toString(), true) }.getOrNull()
        if (sliderWritable == null) {
            sliderWritable = rc == 0
            Log.i(
                TAG,
                "tankUsed storeInProfile rc=$rc writable=$sliderWritable " +
                    "(public API historically cannot write Torque's slider; Overrides PID + setPIDInformation is the DTE path)",
            )
        }
    }

    private fun openSession() {
        if (sessions.isOpen) closeSession()
        lastSliderPct = null
        sessionUsedGal = 0.0
        lastRateMs = null
        val id = sessions.open()
        Log.i(TAG, "session open $id files=${sessions.sessionCount()}")
    }

    private fun closeSession() {
        if (!sessions.isOpen) return
        val closed = sessions.close()
        val obs = FuelMath.observationsFrom(closed)
        binStore.add(obs)
        binStore.persist(torque)
        maybeLearnUsable()
        Log.i(
            TAG,
            "session close samples=${closed.size} obs=${obs.bins.size} reserve+=${obs.reserveGal} " +
                "bins=${binStore.bins.size} reserve=${binStore.reserveGal} files=${sessions.sessionCount()}",
        )
    }

    private fun integrateGalHr(t: ITorqueService, now: Long) {
        val last = lastRateMs
        lastRateMs = now
        if (last == null) return
        val dtSec = ((now - last).coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.001)
        val hr = readDouble(t, galHrPid ?: Pids.GAL_PER_HOUR)
        val gph = if (hr != null && hr.isFinite() && hr >= 0.0) {
            hr
        } else {
            val gmin = readDouble(t, galMinPid ?: Pids.GAL_PER_MIN)
            if (gmin != null && gmin.isFinite() && gmin >= 0.0) gmin * 60.0 else return
        }
        sessionUsedGal += gph * (dtSec / 3600.0)
    }

    private fun maybeLearnUsable() {
        val bins = binStore.bins.toList()
        val span = FuelMath.coverageSpan(bins)
        if (bins.size < 2 || span < 20.0) return
        val usable = math.remainingGal(100.0, bins, cfg.tankGal, binStore.reserveGal)
        if (usable <= 0.0) return
        FixFuelPrefs.setLearnedUsable(this, usable)
        cfg = FixFuelPrefs.load(this)
        nameplateGal = cfg.learnedUsable ?: cfg.tankGal
        Log.i(TAG, "learned usable gal=$usable coverageSpan=$span")
    }

    private fun remainingMax(): Float {
        val n = nameplateGal
        return if (n != null && n > 0.0) n.toFloat() else 50f
    }

    private fun readDouble(t: ITorqueService, pid: String): Double? {
        val v = runCatching { t.getPIDValuesAsDouble(arrayOf(pid)) }.getOrNull()
        val d = v?.firstOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return d
    }

    private fun statusText(): String {
        val p = lastRawPct
        val sid = sessions.sessionId
        return buildString {
            if (p == null) append("Waiting for fuel source")
            else append("raw ${"%.1f".format(p)}% corr ${"%.1f".format(lastAuthPct ?: p)}%")
            if (sid != null) append(" sess ${sid.takeLast(8)}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fix Fuel", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun goForeground(text: String) {
        try {
            val n = notification(text)
            when {
                Build.VERSION.SDK_INT >= 34 ->
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= 29 ->
                    startForeground(NOTIF_ID, n, 0)
                else -> startForeground(NOTIF_ID, n)
            }
            TapStartNotice.cancel(this)
        } catch (t: Throwable) {
            Log.w(TAG, "goForeground ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun notification(text: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "FordFuel"
        private const val CHANNEL_ID = "fordfuel"
        private const val NOTIF_ID = 350
        private const val POLL_MS = 1000L
        const val ACTION_OBD_CONNECTED = "org.prowl.torque.OBD_CONNECTED"
        const val ACTION_OBD_DISCONNECTED = "org.prowl.torque.OBD_DISCONNECTED"
        const val ACTION_PID_QUERY = "org.prowl.torque.PID_QUERY"
        const val ACTION_PID_PLUGIN = "org.prowl.torque.PID_PLUGIN"

        @Volatile
        var isRunning: Boolean = false
    }
}
