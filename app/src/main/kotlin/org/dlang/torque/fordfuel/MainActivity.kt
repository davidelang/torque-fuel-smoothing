package org.dlang.torque.fordfuel

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = getString(R.string.app_name) +
            "\n\nEnable this plugin in Torque → Settings → Plugins.\n" +
            "Then set extra PID [FORD]Fuel Level as Overrides PID for Fuel Level (From Engine ECU)."
        tv.textSize = 16f
        tv.setPadding(48, 48, 48, 48)
        setContentView(tv)
    }

    override fun onStart() {
        super.onStart()
        val start = Intent(this, PluginService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(start)
            } else {
                startService(start)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "start FGS ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    companion object {
        private const val TAG = "FordFuel"
    }
}
