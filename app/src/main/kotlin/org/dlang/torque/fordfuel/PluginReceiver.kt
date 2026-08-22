package org.dlang.torque.fordfuel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

class PluginReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "onReceive $action")
        val start = Intent(context, PluginService::class.java).setAction(action)
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(start)
        } else {
            context.startService(start)
        }
    }

    companion object {
        private const val TAG = "FordFuel"
    }
}
