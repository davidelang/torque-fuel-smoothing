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
    private var bound = false
    private var fordPid: String? = null
    private var speedPid: String? = null
    private var fuelUsedPid: String? = null
    private var fuelUsedUnit: String = "gal"
    private var mpgPid: String? = null
    private var mpgUnit: String = "mpg"
    private var lastRawPct: Double? = null
    private var lastAuthPct: Double? = null
    private var lastSliderPct: Double? = null
    private var lastEcuConnected: Boolean? = null
    private var sliderWritable: Boolean? = null
    private var nameplateGal: Double? = null

    private val poll = object : Runnable {
        override fun run() {
            sampleOnce()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            torque = ITorqueService.Stub.asInterface(service)
            Log.i(TAG, "bound Torque API ${runCatching { torque?.version }.getOrNull()}")
            resolvePids()
            runCatching { binStore.loadFromProfile(torque!!) }
            nameplateGal = TankSeed.seed(torque!!)
            registerPluginPids(torque!!)
            handler.removeCallbacks(poll)
            handler.post(poll)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Torque disconnected")
            closeSession()
            torque = null
            lastEcuConnected = null
            handler.removeCallbacks(poll)
            handler.postDelayed({ bindTorque() }, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sessions = SessionStore(this)
        binStore = BinStore(this)
        ensureChannel()
        goForeground("Binding to Torque…")
        bindTorque()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OBD_CONNECTED -> onEcu(true)
            ACTION_OBD_DISCONNECTED -> onEcu(false)
        }
        goForeground(statusText())
        if (!bound) bindTorque()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        closeSession()
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        torque = null
        isRunning = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bindTorque() {
        val intent = Intent().setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService")
        val ok = runCatching {
            bindService(intent, connection, BIND_AUTO_CREATE)
        }.getOrDefault(false)
        bound = ok
        if (!ok) {
            Log.w(TAG, "bindService failed; retry")
            handler.postDelayed({ bindTorque() }, 3000)
        }
    }

    private fun resolvePids() {
        val t = torque ?: return
        fordPid = runCatching { Pids.resolveFordFuel(t) }.getOrNull()
        speedPid = runCatching { Pids.pickExisting(t, Pids.SPEED) }.getOrNull() ?: Pids.SPEED
        fuelUsedPid = runCatching {
            Pids.pickExisting(t, Pids.FUEL_USED_TRIP, Pids.FUEL_USED_SESSION)
        }.getOrNull()
        fuelUsedPid?.let { id ->
            val info = runCatching { t.getPIDInformation(arrayOf(id))?.firstOrNull() }.getOrNull()
            fuelUsedUnit = Pids.csvUnit(info)
        }
        mpgPid = runCatching { Pids.pickExisting(t, Pids.LONG_TERM_MPG) }.getOrNull() ?: Pids.LONG_TERM_MPG
        mpgPid?.let { id ->
            val info = runCatching { t.getPIDInformation(arrayOf(id))?.firstOrNull() }.getOrNull()
            mpgUnit = Pids.csvUnit(info)
        }
        Log.i(
            TAG,
            "pids ford=${fordPid ?: "UNRESOLVED"} speed=$speedPid fuelUsed=$fuelUsedPid unit=$fuelUsedUnit mpg=$mpgPid",
        )
    }

    private fun sampleOnce() {
        val t = torque ?: return
        val ecu = runCatching { t.isConnectedToECU }.getOrNull()
        if (ecu != null && ecu != lastEcuConnected) {
            onEcu(ecu)
        }
        if (fordPid == null) resolvePids()
        val pid = fordPid ?: return
        val raw = readDouble(t, pid)
        val valid = math.isValidSample(raw, ecu)
        if (!valid) {
            math.hold()
            val held = math.smoothed
            if (held != null) {
                publishHeld(t, raw, held)
            }
            Log.i(TAG, "track mode=hold raw=$raw smoothed=$held ecu=$ecu")
            return
        }
        val live = raw!!
        lastRawPct = live
        if (ecu == true && !sessions.isOpen) {
            openSession()
        }
        val speed = speedPid?.let { readDouble(t, it) } ?: 0.0
        if (sessions.isOpen) {
            val absUsed = fuelUsedPid?.let { readDouble(t, it) }
            val relUsed = if (absUsed == null) {
                0.0
            } else {
                sessions.relativeFuelUsed(Units.toGallons(absUsed, fuelUsedUnit))
            }
            sessions.append(
                Sample(
                    t = System.currentTimeMillis(),
                    rawPct = live,
                    fuelUsedGal = relUsed,
                    speed = speed,
                ),
            )
        }
        val smoothed = math.ingestEma(live)
        publishFromSmoothed(t, live, smoothed)
        Log.i(TAG, "track mode=${math.mode} raw=$live smoothed=$smoothed")
    }

    private fun publishHeld(t: ITorqueService, raw: Double?, held: Double) {
        publishFromSmoothed(t, raw ?: held, held)
    }

    private fun publishFromSmoothed(t: ITorqueService, raw: Double, smoothed: Double) {
        val bins = binStore.bins.toList()
        val auth = smoothed
        lastAuthPct = auth
        val remain = math.remainingGal(smoothed, bins, nameplateGal, binStore.reserveGal)
        val mpgRaw = mpgPid?.let { readDouble(t, it) }
        val mpg = mpgRaw?.let { FuelMath.mpgFrom(it, mpgUnit) } ?: 0.0
        val dte = math.dteMiles(remain, mpg)
        publish(t, raw, smoothed, auth, remain, dte)
        maybeSyncSlider(t, auth)
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
            t.setPIDInformation(Pids.PLUGIN_FORD, "PFord", "%", 100f, 0f, 0f, null)
            t.setPIDInformation(Pids.PLUGIN_SMOOTHED, "SmFuel", "%", 100f, 0f, 0f, null)
            t.setPIDInformation(Pids.PLUGIN_REMAINING, "GalLeft", "gal", remainingMax(), 0f, 0f, null)
            t.setPIDInformation(Pids.PLUGIN_DTE, "pDTE", "mi", 500f, 0f, 0f, null)
        }.onFailure { Log.w(TAG, "registerPluginPids: ${it.message}") }
        Log.i(TAG, "registered ${Pids.PLUGIN_SMOOTHED}, ${Pids.PLUGIN_REMAINING}, ${Pids.PLUGIN_DTE}, ${Pids.PLUGIN_FORD}")
    }

    private fun publish(
        t: ITorqueService,
        raw: Double,
        smoothed: Double,
        auth: Double,
        remain: Double,
        dte: Double,
    ) {
        runCatching {
            t.setPIDInformation(Pids.PLUGIN_FORD, "PFord", "%", 100f, 0f, raw.toFloat(), null)
            t.setPIDInformation(Pids.PLUGIN_SMOOTHED, "SmFuel", "%", 100f, 0f, smoothed.toFloat(), null)
            t.setPIDInformation(Pids.PLUGIN_REMAINING, "GalLeft", "gal", remainingMax(), 0f, remain.toFloat(), null)
            t.setPIDInformation(Pids.PLUGIN_DTE, "pDTE", "mi", 500f, 0f, dte.toFloat(), null)
            t.setPIDInformation(Pids.FORD_FUEL_NAME, "Fuel", "%", 100f, 0f, auth.toFloat(), null)
        }
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
        val id = sessions.open()
        Log.i(TAG, "session open $id files=${sessions.sessionCount()}")
    }

    private fun closeSession() {
        if (!sessions.isOpen) return
        val closed = sessions.close()
        val obs = FuelMath.observationsFrom(closed)
        binStore.add(obs)
        binStore.persist(torque)
        Log.i(
            TAG,
            "session close samples=${closed.size} obs=${obs.bins.size} reserve+=${obs.reserveGal} " +
                "bins=${binStore.bins.size} reserve=${binStore.reserveGal} files=${sessions.sessionCount()}",
        )
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
            if (p == null) append("Waiting for [FORD]Fuel Level")
            else append("Ford ${"%.1f".format(p)}% auth ${"%.1f".format(lastAuthPct ?: p)}%")
            if (sid != null) append(" sess ${sid.takeLast(8)}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ford fuel plugin", NotificationManager.IMPORTANCE_LOW),
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

        @Volatile
        var isRunning: Boolean = false
    }
}
