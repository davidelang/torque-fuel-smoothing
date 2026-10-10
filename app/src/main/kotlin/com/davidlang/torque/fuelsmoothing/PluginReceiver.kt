package com.davidlang.torque.fuelsmoothing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class PluginReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action ?: return
            if (ignoreSender(action)) return
            Log.i(TAG, "onReceive $action")
            val start = Intent(context, PluginService::class.java).setAction(action)
            if (PluginService.isRunning) {
                context.startService(start)
                return
            }
            if (!shouldStartFgs(action)) {
                Log.i(TAG, "service not running; skip $action")
                return
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(start)
                } else {
                    context.startService(start)
                }
            } catch (t: IllegalStateException) {
                Log.w(TAG, "FGS start denied: ${t.javaClass.simpleName} ${t.message}")
                if (Build.VERSION.SDK_INT >= 26) TapStartNotice.post(context)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "onReceive ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun ignoreSender(action: String): Boolean {
        if (Build.VERSION.SDK_INT < 34 || !torqueAction(action)) return false
        val from = sentFromPackage
        if (from != null && from != "org.prowl.torque") {
            Log.i(TAG, "ignore $action from $from")
            return true
        }
        return false
    }

    companion object {
        private const val TAG = "FuelSmooth"

        private fun torqueAction(action: String): Boolean {
            return action == PluginService.ACTION_PID_QUERY ||
                action == PluginService.ACTION_PID_PLUGIN ||
                action == PluginService.ACTION_OBD_CONNECTED ||
                action == PluginService.ACTION_OBD_DISCONNECTED
        }

        private fun shouldStartFgs(action: String): Boolean {
            return action == PluginService.ACTION_PID_QUERY ||
                action == PluginService.ACTION_PID_PLUGIN ||
                action == PluginService.ACTION_OBD_CONNECTED ||
                action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED
        }
    }
}

internal object TapStartNotice {
    const val CHANNEL_ID = "fuel_tap"
    const val NOTIF_ID = 351

    fun post(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = notificationManager(context) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Fuel Smoothing",
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
        val launch = Intent(context, PluginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getActivity(
            context,
            0,
            launch,
            piFlags,
        )
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val n = b.setContentTitle(context.getString(R.string.tap_to_start))
            .setContentText(context.getString(R.string.tap_to_start_text))
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID, n)
    }

    fun cancel(context: Context) {
        notificationManager(context)?.cancel(NOTIF_ID)
    }

    private fun notificationManager(context: Context): NotificationManager? {
        return if (Build.VERSION.SDK_INT >= 23) {
            context.getSystemService(NotificationManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        }
    }
}
