package org.dlang.torque.fordfuel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PluginReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            val action = intent?.action ?: return
            Log.i(TAG, "onReceive $action")
            if (!PluginService.isRunning) {
                Log.i(TAG, "PluginService not running; skip start (open Ford E-350 Fuel once)")
                return
            }
            context.startService(Intent(context, PluginService::class.java).setAction(action))
        } catch (t: Throwable) {
            Log.w(TAG, "onReceive ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    companion object {
        private const val TAG = "FordFuel"
    }
}
