package org.dlang.torque.fordfuel

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowInsets
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = getString(R.string.app_name) +
            "\n\nEnable this plugin in Torque → Settings → Plugins.\n" +
            "Then set extra PID [FORD]Fuel Level as Overrides PID for Fuel Level (From Engine ECU)."
        tv.textSize = 16f
        tv.setPadding(PAD, PAD, PAD, PAD)
        tv.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val types = WindowInsets.Type.statusBars() or WindowInsets.Type.captionBar()
                val bars = insets.getInsets(types)
                v.setPadding(PAD + bars.left, PAD + bars.top, PAD + bars.right, PAD + bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    PAD + insets.systemWindowInsetLeft,
                    PAD + insets.systemWindowInsetTop,
                    PAD + insets.systemWindowInsetRight,
                    PAD + insets.systemWindowInsetBottom,
                )
            }
            insets
        }
        setContentView(tv)
        tv.requestApplyInsets()
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
        private const val PAD = 48
    }
}
