package org.dlang.torque.fordfuel

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        tv.text = getString(R.string.app_name) + "\n\n" + getString(R.string.help_body)
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
        requestGrants()
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

    private fun requestGrants() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            }
        }
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm != null && pm.isIgnoringBatteryOptimizations(packageName)
        if (!ignoring) {
            val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
            if (!prefs.getBoolean(KEY_BATTERY_ASKED, false)) {
                prefs.edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
                try {
                    val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (t: Throwable) {
                    Log.w(TAG, "battery exemption intent ignored: ${t.javaClass.simpleName} ${t.message}")
                }
            }
        }
    }

    companion object {
        private const val TAG = "FordFuel"
        private const val PAD = 48
        private const val REQ_NOTIF = 3501
        private const val PREFS = "fordfuel_grants"
        private const val KEY_BATTERY_ASKED = "battery_asked"
    }
}
