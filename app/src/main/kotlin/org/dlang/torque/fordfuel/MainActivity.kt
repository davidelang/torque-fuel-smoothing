package org.dlang.torque.fordfuel

import android.app.Activity
import android.os.Bundle
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
}
