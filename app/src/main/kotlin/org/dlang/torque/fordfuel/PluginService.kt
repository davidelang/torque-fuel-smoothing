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
    private var torque: ITorqueService? = null
    private var bound = false
    private var fordPid: String? = null
    private var lastRawPct: Double? = null

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
            handler.removeCallbacks(poll)
            handler.post(poll)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Torque disconnected")
            torque = null
            handler.removeCallbacks(poll)
            handler.postDelayed({ bindTorque() }, 2000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        goForeground("Binding to Torque…")
        bindTorque()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(statusText())
        if (!bound) bindTorque()
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        torque = null
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
        Log.i(TAG, "Ford FLI pid=${fordPid ?: "UNRESOLVED"}")
    }

    private fun sampleOnce() {
        val t = torque ?: return
        if (fordPid == null) resolvePids()
        val pid = fordPid ?: return
        val raw = readDouble(t, pid) ?: return
        if (raw < 0.0 || raw > 100.0) return
        lastRawPct = raw
        runCatching {
            t.setPIDInformation(
                Pids.PLUGIN_FORD,
                "PFord",
                "%",
                100f,
                0f,
                raw.toFloat(),
                null,
            )
        }
        goForeground("Ford FLI ${"%.1f".format(raw)}%")
    }

    private fun readDouble(t: ITorqueService, pid: String): Double? {
        val v = runCatching { t.getPIDValuesAsDouble(arrayOf(pid)) }.getOrNull()
        val d = v?.firstOrNull() ?: return null
        if (d.isNaN() || d.isInfinite()) return null
        return d
    }

    private fun statusText(): String {
        val p = lastRawPct
        return if (p == null) "Waiting for [FORD]Fuel Level" else "Ford FLI ${"%.1f".format(p)}%"
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Ford fuel plugin", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun goForeground(text: String) {
        val n = notification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, n)
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
    }
}
