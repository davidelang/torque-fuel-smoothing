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
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var sourceSpinner: Spinner
    private lateinit var customName: EditText
    private lateinit var tau: EditText
    private lateinit var skew: EditText
    private lateinit var jump: EditText
    private lateinit var tank: EditText
    private lateinit var live: TextView
    private lateinit var estimated: TextView
    private lateinit var tank2On: CheckBox
    private lateinit var source2Spinner: Spinner
    private lateinit var customName2: EditText
    private lateinit var tank2: EditText
    private lateinit var estimated2: TextView
    private lateinit var activeSpinner: Spinner
    private lateinit var selectPid: EditText
    private var loading = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = PAD
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        val title = TextView(this)
        title.text = getString(R.string.app_name)
        title.textSize = 22f
        col.addView(title)

        val help = TextView(this)
        help.text = getString(R.string.help_body)
        help.textSize = 14f
        col.addView(help)

        col.addView(label("Fuel source"))
        sourceSpinner = Spinner(this)
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FuelSources.labels())
        col.addView(sourceSpinner)

        col.addView(label("Custom long name (if extra PID by name)"))
        customName = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(customName)

        col.addView(label("Smoothing time τ seconds (0 = no EMA)"))
        tau = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(tau)

        col.addView(label("Max skew %/s"))
        skew = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(skew)

        col.addView(label("Fill jump % (speed ≈ 0)"))
        jump = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(jump)

        col.addView(label("Tank gallons (typed until estimated)"))
        tank = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(tank)

        estimated = TextView(this)
        estimated.textSize = 14f
        col.addView(estimated)

        live = TextView(this)
        live.textSize = 14f
        col.addView(live)

        val t2help = TextView(this)
        t2help.text = getString(R.string.tank2_help)
        t2help.textSize = 14f
        t2help.setPadding(0, 32, 0, 8)
        col.addView(t2help)

        tank2On = CheckBox(this)
        tank2On.text = "Enable tank 2 (independent sender / extra PID)"
        col.addView(tank2On)

        col.addView(label("Tank 2 fuel source"))
        source2Spinner = Spinner(this)
        source2Spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FuelSources.labels())
        col.addView(source2Spinner)

        col.addView(label("Tank 2 custom long name (e.g. [FixFuel] Tank2 Level)"))
        customName2 = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(customName2)

        col.addView(label("Tank 2 gallons"))
        tank2 = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(tank2)

        estimated2 = TextView(this)
        estimated2.textSize = 14f
        col.addView(estimated2)

        col.addView(label("Active tank (gal/hr charged here)"))
        activeSpinner = Spinner(this)
        activeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            arrayOf("Tank 1", "Tank 2", "From PID"),
        )
        col.addView(activeSpinner)

        col.addView(label("Select-PID long name (0/1) if active = From PID"))
        selectPid = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(selectPid)

        val watch = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!loading) persist()
            }
        }
        customName.addTextChangedListener(watch)
        tau.addTextChangedListener(watch)
        skew.addTextChangedListener(watch)
        jump.addTextChangedListener(watch)
        tank.addTextChangedListener(watch)
        customName2.addTextChangedListener(watch)
        tank2.addTextChangedListener(watch)
        selectPid.addTextChangedListener(watch)
        tank2On.setOnCheckedChangeListener { _, _ -> if (!loading) persist() }
        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loading) persist()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        val spin2 = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!loading) persist()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        source2Spinner.onItemSelectedListener = spin2
        activeSpinner.onItemSelectedListener = spin2

        scroll.addView(col)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val types = android.view.WindowInsets.Type.statusBars() or WindowInsets.Type.captionBar()
                val bars = insets.getInsets(types)
                col.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            }
            insets
        }
        setContentView(scroll)
        scroll.requestApplyInsets()
        loadIntoForm()
        loading = false
    }

    override fun onStart() {
        super.onStart()
        requestGrants()
        loading = true
        loadIntoForm()
        loading = false
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

    override fun onPause() {
        persist()
        super.onPause()
    }

    private fun loadIntoForm() {
        val c = FixFuelPrefs.load(this)
        sourceSpinner.setSelection(FuelSources.indexOf(c.sourceId))
        if (customName.text.toString() != c.customName) customName.setText(c.customName)
        setIfBlankOrInit(tau, c.tauSec.toString())
        setIfBlankOrInit(skew, c.skewPctPerSec.toString())
        setIfBlankOrInit(jump, c.jumpPct.toString())
        val tankStr = c.tankGal?.toString() ?: ""
        if (tank.text.toString() != tankStr && !tank.isFocused) tank.setText(tankStr)
        estimated.text = if (c.tankEstimated) "Tank shown is estimated usable from mapping." else ""
        val st = c.sourceStatus
        val lv = FixFuelPrefs.live(this)
        live.text = listOf(st, lv).filter { it.isNotBlank() }.joinToString("\n")
        tank2On.isChecked = c.tank2Enabled
        source2Spinner.setSelection(FuelSources.indexOf(c.sourceId2))
        if (customName2.text.toString() != c.customName2) customName2.setText(c.customName2)
        val t2 = c.tankGal2?.toString() ?: ""
        if (tank2.text.toString() != t2 && !tank2.isFocused) tank2.setText(t2)
        estimated2.text = if (c.tankEstimated2) "Tank 2 shown is estimated usable from mapping." else ""
        activeSpinner.setSelection(
            when (c.activeTank) {
                FixFuelPrefs.ACTIVE_TANK2 -> 1
                FixFuelPrefs.ACTIVE_FROM_PID -> 2
                else -> 0
            },
        )
        if (selectPid.text.toString() != c.selectPidName) selectPid.setText(c.selectPidName)
    }

    private fun setIfBlankOrInit(e: EditText, v: String) {
        if (!e.isFocused && (e.text.isNullOrBlank() || e.tag == null)) {
            e.setText(v)
            e.tag = "inited"
        }
    }

    private fun persist() {
        val src = FuelSources.catalog.getOrNull(sourceSpinner.selectedItemPosition)?.id ?: FixFuelPrefs.DEFAULT_SOURCE
        FixFuelPrefs.save(
            this,
            sourceId = src,
            customName = customName.text.toString().trim(),
            tauSec = tau.text.toString().toDoubleOrNull() ?: FixFuelPrefs.DEFAULT_TAU,
            skewPctPerSec = skew.text.toString().toDoubleOrNull() ?: FixFuelPrefs.DEFAULT_SKEW,
            jumpPct = jump.text.toString().toDoubleOrNull() ?: FixFuelPrefs.DEFAULT_JUMP,
            tankGal = tank.text.toString().toDoubleOrNull(),
            tank2Enabled = tank2On.isChecked,
            sourceId2 = FuelSources.catalog.getOrNull(source2Spinner.selectedItemPosition)?.id ?: FixFuelPrefs.DEFAULT_SOURCE_T2,
            customName2 = customName2.text.toString().trim(),
            tankGal2 = tank2.text.toString().toDoubleOrNull(),
            activeTank = when (activeSpinner.selectedItemPosition) {
                1 -> FixFuelPrefs.ACTIVE_TANK2
                2 -> FixFuelPrefs.ACTIVE_FROM_PID
                else -> FixFuelPrefs.ACTIVE_TANK1
            },
            selectPidName = selectPid.text.toString().trim(),
        )
    }

    private fun label(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 13f
        t.setPadding(0, 24, 0, 4)
        return t
    }

    private fun edit(input: Int): EditText {
        val e = EditText(this)
        e.inputType = input
        e.setSingleLine()
        return e
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
            val prefs = getSharedPreferences("fordfuel_grants", MODE_PRIVATE)
            if (!prefs.getBoolean("battery_asked", false)) {
                prefs.edit().putBoolean("battery_asked", true).apply()
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
    }
}
