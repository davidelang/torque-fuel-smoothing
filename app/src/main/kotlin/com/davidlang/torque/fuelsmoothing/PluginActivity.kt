package com.davidlang.torque.fuelsmoothing

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import java.io.File

class PluginActivity : Activity() {
    private lateinit var sourceSpinner: Spinner
    private lateinit var customName: EditText
    private lateinit var tau: EditText
    private lateinit var skew: EditText
    private lateinit var jump: EditText
    private lateinit var tank: EditText
    private lateinit var tankUnit: Spinner
    private var shownUnit = VolUnit.GAL
    private lateinit var live: TextView
    private lateinit var sizeChunk: TextView
    private lateinit var sizeBand: TextView
    private lateinit var sizeKalman: TextView
    private lateinit var fitChart: FitChart
    private lateinit var extrasCol: LinearLayout
    private lateinit var addBtn: Button
    private lateinit var showNotice: CheckBox
    private lateinit var feed012f: Spinner
    private lateinit var activeSpinner: Spinner
    private lateinit var selectPid: EditText
    private lateinit var profileNameView: TextView
    private lateinit var profileName: EditText
    private lateinit var settingsProfile: Spinner
    private var editProfileId = ""
    private var profileChoices: List<Pair<String, String>> = emptyList()
    private val editorPrefs by lazy { getSharedPreferences("profile_editor", MODE_PRIVATE) }

    private fun editProfileExists(): Boolean =
        ProfileStore.profiles(filesDir).any { it.id == editProfileId }

    private fun editingRecordingProfile(): Boolean =
        editProfileExists() && editProfileId == ProfileStore.activeId(filesDir)

    private fun refreshProfileChoices() {
        val profiles = ProfileStore.profiles(filesDir)
        if (profiles.none { it.id == editProfileId }) {
            editProfileId = profiles.firstOrNull { it.id == ProfileStore.activeId(filesDir) }?.id
                ?: profiles.firstOrNull()?.id.orEmpty()
            editorPrefs.edit().putString("profileId", editProfileId).apply()
            currentFocus?.clearFocus()
            odoMiles.setText("")
        }
        val choices = profiles.map { it.id to (ProfileStore.profileField(filesDir, it.id, "name") ?: it.id) }
        if (choices == profileChoices && settingsProfile.adapter != null) return
        profileChoices = choices
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            choices.map { "${it.second} (${it.first})" })
        settingsProfile.onItemSelectedListener = null
        settingsProfile.adapter = adapter
        settingsProfile.isEnabled = choices.isNotEmpty()
        settingsProfile.setSelection(choices.indexOfFirst { it.first == editProfileId }.coerceAtLeast(0))
        settingsProfile.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (loading || parent?.adapter !== adapter) return
                val next = choices.getOrNull(position)?.first ?: return
                if (next == editProfileId) return
                if (editProfileExists() && !persist()) {
                    settingsProfile.setSelection(choices.indexOfFirst { it.first == editProfileId })
                    return
                }
                currentFocus?.clearFocus()
                editProfileId = next
                editorPrefs.edit().putString("profileId", next).apply()
                odoMiles.setText("")
                loadIntoForm()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    private lateinit var vehicleMake: EditText
    private lateinit var detectBtn: Button
    private lateinit var confirmBtn: Button
    private lateinit var odoMiles: EditText
    private lateinit var gapText: TextView
    private lateinit var tipButton: Button
    private lateinit var tipChoices: LinearLayout
    private lateinit var thanksView: TextView
    private var billing: BillingClient? = null
    private val extraCards = mutableListOf<ExtraCard>()
    private val watch = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) {
            if (!loading) persist()
        }
    }
    private val spinListen = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (!loading) persist()
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }
    private var loading = true
    private var loadedSourceId = FuelDetect.NONE
    private val loadedExtraSource = HashMap<Int, String>()
    private val sourceChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            loading = true
            loadIntoForm()
            loading = false
        }
    }
    private val sourceListen = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (loading || !editProfileExists() || parent?.selectedItemPosition != position) return
            val src = FuelSources.catalog.getOrNull(position)?.id ?: return
            if (src == loadedSourceId) return
            FuelDetect.store(filesDir, editProfileId, src, src != FuelDetect.NONE)
            loadedSourceId = src
            PluginService.notifySourceChanged(this@PluginActivity)
            confirmBtn.visibility = View.GONE
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppStrings.install(this)
        editProfileId = savedInstanceState?.getString("editProfileId")
            ?: editorPrefs.getString("profileId", null)
            ?: ProfileStore.activeId(filesDir)
        val pad = PAD
        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(pad, pad, pad, pad)

        // Top: title + Help/About, then a compact status; settings follow immediately (explanations behind (i)).
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = android.view.Gravity.CENTER_VERTICAL
        val title = TextView(this)
        title.text = S("Fuel Smoothing for Torque")
        title.textSize = 20f
        title.primary()
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(title)
        head.addView(smallButton(S("Help")) { showPage(S("Help"), helpPage) })
        head.addView(smallButton(S("About")) { showPage(S("About"), aboutPage) })
        col.addView(head)

        statusLine = TextView(this)
        statusLine.textSize = 13f
        statusLine.primary()
        statusLine.maxLines = 2
        statusLine.ellipsize = android.text.TextUtils.TruncateAt.END
        col.addView(statusLine)

        if (!torqueInstalled()) {
            val need = TextView(this)
            need.text = S("Requires Torque Pro (paid). Torque Lite does not support plugins.")
            need.textSize = 14f
            need.primary()
            col.addView(need)
            col.addView(smallButton(S("Get Torque Pro")) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.prowl.torque")))
            })
        }

        // Help page (shown in a dialog).
        helpPage = LinearLayout(this)
        helpPage.orientation = LinearLayout.VERTICAL
        val help = TextView(this)
        help.text = S("Quick start\n 1. Install Torque Pro (the paid version; Torque Lite does not support plugins) and pair your OBD-II adapter.\n 2. Enable this plugin in Torque → Settings → Plugins.\n 3. Allow notifications, and set battery use to Unrestricted so Torque can start this plugin in the background.\n 4. In Torque, select the vehicle profile for the car you are driving. Data is kept per Torque profile and checked against the vehicle's VIN/ECU identity.\n 5. On a new profile the fuel source is detected when you connect; if asked, compare it with your dash gauge and tap Confirm. Confirm is on the setup page and in the notification. You can also pick it below.\n 6. Tank size is the starting size for the Chunk fit until it learns its own. The plugin never changes it; each fit's learned size is shown below it.\n 7. In Torque, add gauges for [FuelSmooth] Smoothed Fuel Level and each fit's Corrected %, Remaining, DTE and Tank size. DTE is Remaining times Torque's long-term mpg. A notice during a refit says that fit reads 1:1 until its refit finishes.\n 8. Feed fuel level back to Torque: Off, Smoothed, or Corrected from the Chunk, Band or Kalman fit onto your 012F gauge.\n 9. Fuel is counted only while the engine runs. The plugin runs only while Torque is connected to the ECU.\n 10. Keep driving and filling up; accuracy builds over several partial tanks. If a Wrong vehicle profile? notification appears, choose what to do with that drive.\n 11. All data stays on this phone. No GPS or location is recorded.")
        help.textSize = 14f
        help.primary()
        helpPage.addView(help)
        val manual = Button(this)
        manual.text = S("Full manual (online)")
        manual.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(S("https://play.landing.lang.hm/fuel-smoothing/manual/")))) }
        }
        helpPage.addView(manual)

        // About page (shown in a dialog): version, privacy, feedback, tip.
        aboutPage = LinearLayout(this)
        aboutPage.orientation = LinearLayout.VERTICAL
        val about = TextView(this)
        about.text = S("%1\$s\nVersion %2\$s (%3\$s)\nA plugin for Torque Pro. Not affiliated with Torque or Ian Hawkins.\nAll data stays on this phone; no GPS or location is recorded.\n\nLicense\nCopyright David Lang. All rights reserved.", S("Fuel Smoothing for Torque"), versionName(), versionCode()) + "\n\n" + S("Open source libraries\nThis app uses the libraries below. Their licenses are separate from the copyright above.\n\nGoogle Play Billing (com.android.billingclient:billing and billing-ktx 8.0.0)\nAndroid Software Development Kit License\nhttps://developer.android.com/studio/terms.html\n\nAndroidX activity 1.2.3, core 1.2.0, fragment 1.1.0, lifecycle 2.0.0 and 2.3.1, savedstate 1.1.0, versionedparcelable 1.1.0, arch.core 2.1.0, customview 1.0.0, loader 1.0.0, tracing 1.0.0, viewpager 1.0.0\nApache License 2.0\nhttps://www.apache.org/licenses/LICENSE-2.0\n\nkotlinx-coroutines-core 1.6.0\nApache License 2.0\nhttps://www.apache.org/licenses/LICENSE-2.0\n\nKotlin standard library\nApache License 2.0\nhttps://www.apache.org/licenses/LICENSE-2.0\n\nGoogle Play services (com.google.android.gms), required by Play Billing\nAndroid Software Development Kit License\nhttps://developer.android.com/studio/terms.html")
        about.textSize = 14f
        about.primary()
        aboutPage.addView(about)
        val privacy = Button(this)
        privacy.text = S("Privacy")
        privacy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(S("https://play.landing.lang.hm/fuel-smoothing/privacy/"))))
        }
        aboutPage.addView(privacy)
        val feedback = Button(this)
        feedback.text = S("Send feedback")
        feedback.setOnClickListener { sendFeedback() }
        aboutPage.addView(feedback)
        tipButton = Button(this)
        tipButton.text = S("Tip")
        tipButton.setOnClickListener { onTipClicked() }
        aboutPage.addView(tipButton)
        tipChoices = LinearLayout(this)
        tipChoices.orientation = LinearLayout.VERTICAL
        aboutPage.addView(tipChoices)
        thanksView = TextView(this)
        thanksView.textSize = 14f
        thanksView.primary()
        aboutPage.addView(thanksView)
        // Google Play Billing connects only when the user taps Tip, or to finish a recorded tip (no network use otherwise).
        TipReconciler.run(this, "app start")

        // ---- Settings ----
        col.addView(label(S("Edit profile (recording follows Torque)")))
        settingsProfile = Spinner(this)
        col.addView(settingsProfile)
        profileNameView = TextView(this)   // kept for refreshProfile; the name is in the status line
        col.addView(info(S("Profile name"), S("The name of this vehicle profile. Each Torque vehicle profile gets its own plugin profile, data and learned maps.")))
        profileName = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(profileName)
        col.addView(info(S("Make"), S("Vehicle make (e.g. Ford). Used only to choose which brand-specific fuel PIDs detection may try.")))
        vehicleMake = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(vehicleMake)

        col.addView(info(S("Fuel source"), S("Where the raw fuel level comes from. Detect (needs the ECU connected) tries the candidates read-only and asks you to confirm unless two sources agree. Confirm is on the setup page and in the notification. None = detect on the next connect.")))
        val srcRow = LinearLayout(this)
        srcRow.orientation = LinearLayout.HORIZONTAL
        sourceSpinner = Spinner(this)
        sourceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FuelSources.labels())
        sourceSpinner.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        srcRow.addView(sourceSpinner)
        detectBtn = smallButton(S("Detect")) {
            if (!editingRecordingProfile()) return@smallButton
            val i = Intent(this, PluginService::class.java).setAction(PluginService.ACTION_DETECT)
                .putExtra(PluginService.EXTRA_TARGET, editProfileId)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } catch (t: Throwable) {
                FsLog.w(TAG, "detect start ignored: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        srcRow.addView(detectBtn)
        confirmBtn = smallButton(S("Confirm")) {
            if (!editingRecordingProfile()) return@smallButton
            val i = Intent(this, PluginService::class.java)
                .setAction(PluginService.ACTION_CONFIRM_SOURCE)
                .putExtra(PluginService.EXTRA_TARGET, editProfileId)
                .putExtra(PluginService.EXTRA_SOURCE, loadedSourceId)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } catch (t: Throwable) {
                FsLog.w(TAG, "confirm start ignored: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        confirmBtn.visibility = View.GONE
        srcRow.addView(confirmBtn)
        col.addView(srcRow)

        col.addView(info(S("Custom long name"), S("Only for the source \"Torque extra PID by long name\": the exact long name of the Torque PID that reports the level.")))
        customName = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(customName)

        col.addView(info(S("Tank size"), S("Tank size is the starting size for the Chunk fit until it learns its own. The plugin never changes it; each fit's learned size is shown below it.")))
        val tankRow = LinearLayout(this)
        tankRow.orientation = LinearLayout.HORIZONTAL
        tank = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        tank.hint = S("blank = no starting size; each fit learns its own.")
        tank.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        tankRow.addView(tank)
        tankUnit = Spinner(this)
        tankUnit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, VolUnit.entries.map { S(it.label) })
        tankRow.addView(tankUnit)
        col.addView(tankRow)
        tankUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val u = VolUnit.entries[position.coerceIn(0, VolUnit.entries.size - 1)]
                if (loading || !editProfileExists() || u == shownUnit) return
                // Re-express the typed value in the new unit; the stored value stays in gallons.
                val gal = tank.text.toString().trim().toDoubleOrNull()?.let { shownUnit.toGal(it) }
                shownUnit = u
                ProfileStore.setProfileField(filesDir, editProfileId, "tankUnit", u.name)
                tank.setText(VolUnit.show(gal, u))
                persist()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        sizeChunk = sizeLine(col, 0xFF1565C0.toInt())
        sizeBand = sizeLine(col, 0xFF2E7D32.toInt())
        sizeKalman = sizeLine(col, 0xFFE65100.toInt())
        fitChart = FitChart(this)
        col.addView(fitChart)

        col.addView(info(S("Feed fuel level back to Torque (overrides 012F)"), S("Writes a value onto Torque's stock Fuel Level (012F) so existing gauges show it: Off, Smoothed, or Corrected from the Chunk, Band or Kalman fit. A fit that is not built yet writes the smoothed level.")))
        feed012f = Spinner(this)
        feed012f.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FeedMode.entries.map { S(it.label) })
        col.addView(feed012f)

        showNotice = CheckBox(this)
        showNotice.text = S("Show fuel level in notification")
        showNotice.primary()
        col.addView(showNotice)

        col.addView(info(S("Smoothing τ seconds"), S("Time constant of the smoothing filter (0 = no smoothing).")))
        tau = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(tau)
        col.addView(info(S("Max skew %/s"), S("Largest change per second the smoothed level may follow; slosh beyond this is ignored.")))
        skew = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(skew)
        col.addView(info(S("Fill jump %"), S("Rise in level while stopped that counts as a fill.")))
        jump = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(jump)

        col.addView(info(S("Odometer miles"), S("Optional: enter the odometer reading and Save pin to anchor distance for this profile.")))
        val odoRow = LinearLayout(this)
        odoRow.orientation = LinearLayout.HORIZONTAL
        odoMiles = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        odoMiles.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        odoRow.addView(odoMiles)
        odoRow.addView(smallButton(S("Save pin")) {
            if (!editProfileExists()) return@smallButton
            val miles = odoMiles.text.toString().toDoubleOrNull() ?: return@smallButton
            val id = editProfileId
            ProfileStore.addPin(filesDir, id, miles, System.currentTimeMillis())
        })
        col.addView(odoRow)

        col.addView(info(S("Additional tanks"), S("Add additional tanks as needed (stock tank 1 always stays). Do not switch senders onto the PCM FLI pin (false fill). Each extra tank needs its own Torque extra PID (MCU Mode 22, unique name — not 012F or Ford 16C1). Select-PID value is the 1-based tank id. Gal/hr is charged only to the active tank.")))
        extrasCol = LinearLayout(this)
        extrasCol.orientation = LinearLayout.VERTICAL
        col.addView(extrasCol)
        addBtn = Button(this)
        addBtn.text = S("Add additional tank")
        addBtn.setOnClickListener { addExtra() }
        col.addView(addBtn)
        col.addView(info(S("Active tank"), S("Which tank the fuel burn (gal/hr) is charged to. From PID: a Torque PID chooses it (1-based tank id).")))
        activeSpinner = Spinner(this)
        col.addView(activeSpinner)
        col.addView(info(S("Select-PID long name"), S("Only when Active tank = From PID: the long name of the Torque PID whose value is the tank id.")))
        selectPid = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(selectPid)

        val verbose = android.widget.CheckBox(this)
        verbose.text = S("Verbose logging (debug; per-second log lines)")
        verbose.primary()
        verbose.isChecked = FixFuelPrefs.prefs(this).getBoolean(FsLog.KEY_VERBOSE, false)
        verbose.setOnCheckedChangeListener { _, on ->
            FixFuelPrefs.prefs(this).edit().putBoolean(FsLog.KEY_VERBOSE, on).apply()
            FsLog.verbose = on
        }
        col.addView(verbose)

        // Details (below the settings).
        col.addView(label(S("Details")))
        live = TextView(this)
        live.textSize = 13f
        live.primary()
        col.addView(live)
        gapText = TextView(this)
        gapText.textSize = 12f
        gapText.primary()
        col.addView(gapText)

        customName.addTextChangedListener(watch)
        tau.addTextChangedListener(watch)
        skew.addTextChangedListener(watch)
        jump.addTextChangedListener(watch)
        tank.addTextChangedListener(watch)
        selectPid.addTextChangedListener(watch)
        profileName.addTextChangedListener(watch)
        vehicleMake.addTextChangedListener(watch)
        sourceSpinner.onItemSelectedListener = sourceListen
        activeSpinner.onItemSelectedListener = spinListen
        showNotice.setOnCheckedChangeListener { _, _ -> if (!loading) persist() }
        feed012f.onItemSelectedListener = spinListen

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

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("editProfileId", editProfileId)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        if (::detectBtn.isInitialized) {
            loading = true
            loadIntoForm()
            loading = false
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PluginService.ACTION_SOURCE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sourceChanged, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(sourceChanged, filter)
        }
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
            FsLog.w(TAG, "start FGS ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    override fun onPause() {
        persist()
        super.onPause()
    }

    override fun onStop() {
        unregisterReceiver(sourceChanged)
        super.onStop()
    }

    private fun loadIntoForm() {
        val prior = loading
        loading = true
        try {
            loadForm()
        } finally {
            loading = prior
        }
    }

    private fun loadForm() {
        refreshProfileChoices()
        val c = FixFuelPrefs.load(this, editProfileId, migrateLegacy = false)
        loadedSourceId = c.sourceId
        sourceSpinner.setSelection(FuelSources.indexOf(c.sourceId))
        confirmBtn.visibility = if (c.sourceId != FuelDetect.NONE && !c.sourceConfirmed) View.VISIBLE else View.GONE
        if (customName.text.toString() != c.customName && !customName.isFocused) customName.setText(c.customName)
        setIfBlankOrInit(tau, c.tauSec.toString())
        setIfBlankOrInit(skew, c.skewPctPerSec.toString())
        setIfBlankOrInit(jump, c.jumpPct.toString())
        shownUnit = VolUnit.of(ProfileStore.profileField(filesDir, editProfileId, "tankUnit"))
        tankUnit.setSelection(shownUnit.ordinal)
        val shownGal = tank.text.toString().trim().toDoubleOrNull()?.let { shownUnit.toGal(it) }
        val tankStr = if (shownGal != null && c.tankGal != null && kotlin.math.abs(shownGal - c.tankGal) < 0.05) tank.text.toString() else VolUnit.show(c.tankGal, shownUnit)
        if (tank.text.toString() != tankStr && !tank.isFocused) tank.setText(tankStr)
        showStoredInvalid(c)
        refreshSizes(c)
        val st = if (editingRecordingProfile()) c.sourceStatus else ""
        val lv = if (editingRecordingProfile()) FixFuelPrefs.live(this) else ""
        live.text = listOf(st, lv).filter { it.isNotBlank() }.joinToString("\n")
        syncExtraCards(c.extras)
        refreshActiveSpinner(c.activeTank)
        if (selectPid.text.toString() != c.selectPidName && !selectPid.isFocused) selectPid.setText(c.selectPidName)
        showNotice.isChecked = c.showFuelNotification
        feed012f.setSelection(c.feed012f.ordinal)
        refreshAddBtn()
        refreshProfile()
        refreshStatus(c)
    }

    private fun refreshProfile() {
        val id = editProfileId
        val name = ProfileStore.profileField(filesDir, id, "name") ?: S("Vehicle 1")
        profileNameView.text = name
        if (!profileName.isFocused && profileName.text.toString() != name) profileName.setText(name)
        val make = ProfileStore.profileField(filesDir, id, "make") ?: ""
        if (!vehicleMake.isFocused && vehicleMake.text.toString() != make) vehicleMake.setText(make)
        detectBtn.isEnabled = PluginService.ecuConnected && editingRecordingProfile()
        confirmBtn.isEnabled = editingRecordingProfile()
        val notice = File(filesDir, "profile-notice.txt")
        val gap = File(ProfileStore.profileDir(filesDir, id), "gap.txt")
        gapText.text = when {
            editingRecordingProfile() && notice.isFile && notice.readText().isNotBlank() -> notice.readText()
            gap.isFile -> gap.readText()
            else -> ""
        }
    }

    private fun syncExtraCards(extras: List<ExtraTank>) {
        val want = extras.sortedBy { it.id }.map { it.id }
        val have = extraCards.map { it.id }
        if (want != have) {
            extrasCol.removeAllViews()
            extraCards.clear()
            for (t in extras.sortedBy { it.id }) extraCards.add(buildCard(t))
        } else {
            extras.sortedBy { it.id }.forEachIndexed { i, t -> bindCard(extraCards[i], t) }
        }
    }

    private fun buildCard(t: ExtraTank): ExtraCard {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(0, 16, 0, 8)
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        val title = TextView(this)
        title.text = S("Tank %1\$d", t.id)
        title.textSize = 16f
        title.primary()
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val remove = Button(this)
        remove.text = S("Remove")
        head.addView(title)
        head.addView(remove)
        root.addView(head)
        root.addView(label(S("Tank %1\$d fuel source", t.id)))
        val source = Spinner(this)
        source.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FuelSources.labels())
        root.addView(source)
        root.addView(label(S("Tank %1\$d custom long name (e.g. [FuelSmooth] Tank%1\$d Level)", t.id)))
        val name = edit(InputType.TYPE_CLASS_TEXT)
        root.addView(name)
        root.addView(label(S("Tank %1\$d gallons", t.id)))
        val gal = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(gal)
        val card = ExtraCard(t.id, root, source, name, gal)
        bindCard(card, t)
        name.addTextChangedListener(watch)
        gal.addTextChangedListener(watch)
        source.onItemSelectedListener = extraSourceListen(card)
        remove.setOnClickListener { removeExtra(card) }
        extrasCol.addView(root)
        return card
    }

    private fun extraSourceListen(card: ExtraCard) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
            if (loading) return
            val src = FuelSources.catalog.getOrNull(position)?.id ?: return
            if (src == loadedExtraSource[card.id]) return
            FixFuelPrefs.setExtraSourceId(this@PluginActivity, card.id, src)
            loadedExtraSource[card.id] = src
        }
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun bindCard(card: ExtraCard, t: ExtraTank) {
        loadedExtraSource[card.id] = t.sourceId
        card.source.setSelection(FuelSources.indexOf(t.sourceId))
        if (card.customName.text.toString() != t.customName && !card.customName.isFocused) card.customName.setText(t.customName)
        val g = t.tankGal?.toString() ?: ""
        if (card.tank.text.toString() != g && !card.tank.isFocused) card.tank.setText(g)
    }

    private fun addExtra() {
        if (!canAddExtra()) return
        val id = FixFuelPrefs.nextExtraId(extraCards.map { ExtraTank(id = it.id) })
        FixFuelPrefs.setExtraSourceId(this, id, FixFuelPrefs.DEFAULT_SOURCE_T2)
        if (extraCards.none { it.id == id }) {
            loading = true
            try {
                extraCards.add(buildCard(ExtraTank(id = id, sourceId = FixFuelPrefs.DEFAULT_SOURCE_T2)))
            } finally {
                loading = false
            }
        }
        refreshAddBtn()
        refreshActiveSpinner(id.toString())
        persist()
    }

    private fun canAddExtra(): Boolean {
        if (1 + extraCards.size >= FixFuelPrefs.MAX_TANKS) return false
        val id = FixFuelPrefs.nextExtraId(extraCards.map { ExtraTank(id = it.id) })
        return id in 2..FixFuelPrefs.MAX_TANKS
    }

    private fun removeExtra(card: ExtraCard) {
        extrasCol.removeView(card.root)
        extraCards.removeAll { it.id == card.id }
        loadedExtraSource.remove(card.id)
        FixFuelPrefs.removeExtraTank(this, card.id)
        val keep = if (activeTankFromSpinner() == card.id.toString()) FixFuelPrefs.ACTIVE_TANK1 else activeTankFromSpinner()
        refreshAddBtn()
        refreshActiveSpinner(keep)
        persist()
    }

    private fun refreshAddBtn() {
        addBtn.visibility = if (canAddExtra()) View.VISIBLE else View.GONE
    }

    private fun refreshActiveSpinner(selected: String) {
        val labels = activeLabels()
        activeSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val idx = when {
            selected == FixFuelPrefs.ACTIVE_FROM_PID -> labels.lastIndex
            selected == FixFuelPrefs.ACTIVE_TANK1 -> 0
            else -> {
                val id = selected.toIntOrNull()
                val extras = extraCards.sortedBy { it.id }
                val i = extras.indexOfFirst { it.id == id }
                if (i >= 0) i + 1 else 0
            }
        }
        activeSpinner.setSelection(idx.coerceIn(0, labels.lastIndex))
        activeSpinner.onItemSelectedListener = spinListen
    }

    private fun activeLabels(): Array<String> {
        val names = mutableListOf(S("Tank %1\$d", 1))
        extraCards.sortedBy { it.id }.forEach { names.add(S("Tank %1\$d", it.id)) }
        names.add(S("From PID"))
        return names.toTypedArray()
    }

    private fun activeTankFromSpinner(): String {
        val labels = activeLabels()
        val pos = activeSpinner.selectedItemPosition
        return when {
            pos < 0 || pos >= labels.size -> FixFuelPrefs.ACTIVE_TANK1
            pos == labels.lastIndex -> FixFuelPrefs.ACTIVE_FROM_PID
            pos == 0 -> FixFuelPrefs.ACTIVE_TANK1
            else -> extraCards.sortedBy { it.id }.getOrNull(pos - 1)?.id?.toString() ?: FixFuelPrefs.ACTIVE_TANK1
        }
    }

    private fun extrasFromCards(): List<ExtraTank> {
        val prev = FixFuelPrefs.load(this, editProfileId, migrateLegacy = false).extras.associateBy { it.id }
        return extraCards.sortedBy { it.id }.map { card ->
            val old = prev[card.id]
            ExtraTank(
                id = card.id,
                sourceId = old?.sourceId ?: FixFuelPrefs.DEFAULT_SOURCE_T2,
                customName = card.customName.text.toString().trim(),
                tankGal = card.tank.text.toString().toDoubleOrNull(),
                sourceStatus = old?.sourceStatus ?: "",
            )
        }
    }

    private fun setIfBlankOrInit(e: EditText, v: String) {
        if (!e.isFocused && (e.text.isNullOrBlank() || e.tag == null)) {
            e.setText(v)
            e.tag = "inited"
        }
    }

    private val normalColors = HashMap<EditText, android.content.res.ColorStateList>()

    /** Red text + error hint when invalid; restores the original colour when valid. Returns ok. */
    private fun markField(e: EditText, ok: Boolean, msg: String): Boolean {
        val normal = normalColors.getOrPut(e) { e.textColors }
        if (ok) {
            if (e.error != null) e.error = null
            e.setTextColor(normal)
        } else {
            e.setTextColor(android.graphics.Color.RED)
            e.error = msg
        }
        return ok
    }

    /** Range-checks the numeric settings; invalid fields are shown in red and nothing is saved. */
    private fun formValid(): Boolean {
        var ok = true
        ok = markField(tau, FixFuelPrefs.tauOk(tau.text.toString().trim().toDoubleOrNull()),
            S("0 to %1\$s s", oneDecimal(FixFuelPrefs.MAX_TAU))) && ok
        ok = markField(skew, FixFuelPrefs.skewOk(skew.text.toString().trim().toDoubleOrNull()),
            S("above 0, at most %1\$s %%/s", oneDecimal(FixFuelPrefs.MAX_SKEW))) && ok
        ok = markField(jump, FixFuelPrefs.jumpOk(jump.text.toString().trim().toDoubleOrNull()),
            S("%1\$s to %2\$s %%", oneDecimal(FixFuelPrefs.MIN_JUMP), oneDecimal(FixFuelPrefs.MAX_JUMP))) && ok
        ok = markField(tank, FixFuelPrefs.tankOk(tank.text.toString()), S("blank or a number above 0")) && ok
        for (card in extraCards) {
            ok = markField(card.tank, FixFuelPrefs.tankOk(card.tank.text.toString()), S("blank or a number above 0")) && ok
        }
        return ok
    }

    /** Stored values that load() flagged: show the raw text in red so the user can fix it. */
    private fun showStoredInvalid(c: FixFuelConfig) {
        fun show(key: String, e: EditText) {
            val raw = c.invalid[key] ?: return
            if (!e.isFocused && e.text.toString() != raw) e.setText(raw)
            markField(e, false, S("stored value out of range"))
        }
        show(FixFuelPrefs.KEY_TAU, tau)
        show(FixFuelPrefs.KEY_SKEW, skew)
        show(FixFuelPrefs.KEY_JUMP, jump)
        show(FixFuelPrefs.KEY_TANK, tank)
    }

    private fun persist(): Boolean {
        if (loading || !editProfileExists()) return false
        if (!formValid()) {
            FsLog.i(TAG, "settings not saved: out-of-range field(s) shown in red")
            return false
        }
        FixFuelPrefs.save(
            this,
            customName = customName.text.toString().trim(),
            tauSec = tau.text.toString().trim().toDouble(),
            skewPctPerSec = skew.text.toString().trim().toDouble(),
            jumpPct = jump.text.toString().trim().toDouble(),
            tankGal = tank.text.toString().trim().toDoubleOrNull()?.let { shownUnit.toGal(it) },
            extras = extrasFromCards(),
            activeTank = activeTankFromSpinner(),
            selectPidName = selectPid.text.toString().trim(),
            showFuelNotification = showNotice.isChecked,
            feed012f = FeedMode.entries[feed012f.selectedItemPosition.coerceIn(0, FeedMode.entries.size - 1)],
            profileId = editProfileId,
        )
        val id = editProfileId
        ProfileStore.setProfileField(filesDir, id, "name", profileName.text.toString().trim().ifBlank { S("Vehicle 1") })
        val make = vehicleMake.text.toString().trim()
        ProfileStore.setProfileField(filesDir, id, "make", if (make.isEmpty()) null else make)
        profileNameView.text = ProfileStore.profileField(filesDir, id, "name") ?: S("Vehicle 1")
        return true
    }

    private lateinit var statusLine: TextView
    private lateinit var helpPage: LinearLayout
    private lateinit var aboutPage: LinearLayout

    private fun smallButton(text: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.isAllCaps = false
        b.minWidth = 0; b.minimumWidth = 0
        b.setPadding(24, 0, 24, 0)
        b.setOnClickListener { onClick() }
        return b
    }

    /** Label with a small (i) button that explains the option in a dialog. */
    private fun info(text: String, explanation: String): LinearLayout {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        val l = label(text)
        l.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        row.addView(l)
        val i = TextView(this)
        i.text = "ⓘ"
        i.textSize = 18f
        i.primary()
        i.setPadding(24, 16, 8, 0)
        i.contentDescription = S("About %1\$s", text)
        i.setOnClickListener {
            val body = TextView(this)
            body.text = explanation
            body.textSize = 14f
            body.primary()
            body.setPadding(PAD, PAD / 2, PAD, 0)
            android.app.AlertDialog.Builder(this).setTitle(text).setView(body).setPositiveButton(S("OK"), null).show()
        }
        row.addView(i)
        return row
    }

    /** Help/About sub-page in a scrolling dialog (the page view is reused). */
    private fun showPage(title: String, page: LinearLayout) {
        (page.parent as? android.view.ViewGroup)?.removeView(page)
        val sv = ScrollView(this)
        page.setPadding(PAD, PAD / 2, PAD, 0)
        sv.addView(page)
        android.app.AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton(S("Close"), null)
            .setOnDismissListener { sv.removeView(page) }.show()
    }

    /** One or two lines: profile, source, tank estimate. */
    private fun refreshStatus(c: FixFuelConfig) {
        if (!::statusLine.isInitialized) return
        val id = editProfileId
        val name = ProfileStore.profileField(filesDir, id, "name") ?: S("Vehicle 1")
        val src = S(FuelSources.byId(c.sourceId).label) + if (c.sourceConfirmed) "" else S("(unconfirmed)")
        val tankTxt = c.tankGal?.let { "${VolUnit.show(it, shownUnit)} ${S(shownUnit.label)}" } ?: S("tank ?")
        statusLine.text = "$name · $src · $tankTxt" + if (PluginService.ecuConnected && editingRecordingProfile()) S("· ECU connected") else ""
    }

    private fun label(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 13f
        t.primary()
        t.setPadding(0, 12, 0, 0)
        return t
    }

    private fun textPrimary(): Int {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val c = a.getColor(0, android.graphics.Color.BLACK)
        a.recycle()
        return c
    }

    private fun TextView.primary(): TextView {
        setTextColor(textPrimary())
        return this
    }

    private fun activityLocale(): Locale =
        if (Build.VERSION.SDK_INT >= 24) resources.configuration.locales[0]
        else @Suppress("DEPRECATION") resources.configuration.locale

    private fun oneDecimal(v: Double): String = String.format(activityLocale(), "%.1f", v)

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
        if (Build.VERSION.SDK_INT < 31) return
        val pm = getSystemService(PowerManager::class.java)
        val ignoring = pm != null && pm.isIgnoringBatteryOptimizations(packageName)
        if (ignoring) return
        val prefs = getSharedPreferences("fordfuel_grants", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (t: Throwable) {
            FsLog.w(TAG, "battery list intent ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun torqueInstalled(): Boolean {
        return runCatching { packageManager.getPackageInfo("org.prowl.torque", 0) }.isSuccess
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }

    @Suppress("DEPRECATION")
    private fun versionCode(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else info.versionCode.toString()
    }

    private fun sendFeedback() {
        val body = "App version: ${versionName()}\nAndroid version: ${Build.VERSION.RELEASE}\nDevice model: ${Build.MANUFACTURER} ${Build.MODEL}\n"
        val uri = Uri.parse(
            "mailto:david+fuelsmoothing@lang.hm?subject=" +
                Uri.encode(S("Fuel Smoothing for Torque")) +
                "&body=" +
                Uri.encode(body),
        )
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (t: Throwable) {
            FsLog.w(TAG, "feedback intent ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun connectBilling() {
        val client = BillingClient.newBuilder(this)
            .setListener { result, purchases ->
                when (result.responseCode) {
                    BillingClient.BillingResponseCode.OK -> purchases?.forEach {
                        // Persist first, then acknowledge/consume while still connected.
                        TipStore.note(this, it)
                        finishTip(it)
                    }
                    BillingClient.BillingResponseCode.USER_CANCELED -> TipStore.clear(this)
                    else -> Unit
                }
            }
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .build()
        billing = client
        try {
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                        queryTips(client)
                        queryOwned(client)
                    } else {
                        runOnUiThread { tipButton.text = S("Tips are on the Play Store version") }
                    }
                }

                override fun onBillingServiceDisconnected() {}
            })
        } catch (t: Throwable) {
            FsLog.w(TAG, "billing start ignored: ${t.javaClass.simpleName} ${t.message}")
            tipButton.text = S("Tips are on the Play Store version")
        }
    }

    private fun onTipClicked() {
        val client = billing
        if (client == null) {
            connectBilling() // queries tips and owned purchases once connected
            return
        }
        if (!client.isReady) {
            tipButton.text = S("Tips are on the Play Store version")
            return
        }
        queryTips(client)
    }

    private fun queryTips(client: BillingClient) {
        val products = TIP_IDS.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        client.queryProductDetailsAsync(params) { result, queryResult ->
            val details = queryResult.productDetailsList
            runOnUiThread {
                if (result.responseCode != BillingClient.BillingResponseCode.OK || details.isEmpty()) {
                    tipButton.text = S("Tips are on the Play Store version")
                } else {
                    showTipProducts(details)
                }
            }
        }
    }

    private fun queryOwned(client: BillingClient) {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                purchases.forEach { finishTip(it) }
            }
        }
    }

    private fun showTipProducts(details: List<ProductDetails>) {
        tipChoices.removeAllViews()
        for (detailsItem in details) {
            val b = Button(this)
            b.text = detailsItem.name.ifBlank { detailsItem.productId }
            b.setOnClickListener { launchTip(detailsItem) }
            tipChoices.addView(b)
        }
    }

    private fun launchTip(details: ProductDetails) {
        val client = billing ?: return
        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
            ?: details.oneTimePurchaseOfferDetails?.offerToken
        val productBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
        if (!offer.isNullOrBlank()) productBuilder.setOfferToken(offer)
        val product = productBuilder.build()
        val flow = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(product))
            .build()
        TipStore.put(this, TipRules.Record(TipRules.State.STARTED, System.currentTimeMillis()))
        client.launchBillingFlow(this, flow)
    }

    private fun finishTip(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        val client = billing ?: return
        val token = purchase.purchaseToken
        val consume = {
            val params = ConsumeParams.newBuilder().setPurchaseToken(token).build()
            client.consumeAsync(params) { result, _ ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    TipStore.clear(this)
                    runOnUiThread { thanksView.text = S("Thanks") }
                }
            }
        }
        if (purchase.isAcknowledged) {
            consume()
            return
        }
        val ack = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build()
        client.acknowledgePurchase(ack) { consume() }
    }

    override fun onDestroy() {
        billing?.endConnection()
        billing = null
        super.onDestroy()
    }

    private fun sizeLine(parent: LinearLayout, color: Int): TextView {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = android.view.Gravity.CENTER_VERTICAL
        val sw = View(this)
        val d = resources.displayMetrics.density
        val s = (12 * d).toInt()
        sw.setBackgroundColor(color)
        sw.layoutParams = LinearLayout.LayoutParams(s, s).apply { marginEnd = (8 * d).toInt() }
        val t = TextView(this)
        t.textSize = 14f
        t.primary()
        row.addView(sw)
        row.addView(t)
        parent.addView(row)
        return t
    }

    /** Own chunk size, else the user seed. Band and Kalman show only their own size. Kalman includes sd98 whenever a model is loaded. */
    private fun refreshSizes(c: FixFuelConfig) {
        val dir = ProfileStore.tankDir(filesDir, editProfileId, 1)
        val chunk = ProfileStore.storedMap(dir)
        val chunkGal = chunk?.third?.takeIf { it > 0.0 } ?: c.tankGal?.takeIf { it > 0.0 }
        sizeChunk.text = S("Chunk: %1\$s", sizeText(chunkGal))
        val band = BandFit.load(dir)
        sizeBand.text = S("Band: %1\$s", sizeText(band?.second?.takeIf { it > 0.0 }))
        val kal = Kalman.loadModel(dir)
        sizeKalman.text = S("Kalman: %1\$s", kalmanSizeText(kal))
        fitChart.setCurves(listOf(chunkCurve(chunk), bandCurve(band), kalmanCurve(kal)))
    }

    private fun sizeText(gal: Double?): String {
        if (gal == null || !gal.isFinite() || gal <= 0.0) return S("no size yet")
        return S("%1\$.1f gal", gal)
    }

    private fun kalmanSizeText(kal: Kalman.Model?): String {
        if (kal == null) return S("no size yet")
        val sd = kal.sd98.takeIf { it.isFinite() }
        val gal = kal.g98.takeIf { it.isFinite() && it > 0.0 }
        return when {
            gal != null && sd != null -> S("%1\$.1f ± %2\$.1f gal", gal, sd)
            gal != null -> S("%1\$.1f gal", gal)
            sd != null -> S("no size yet ± %1\$.1f gal", sd)
            else -> S("no size yet")
        }
    }

    private fun chunkCurve(stored: Triple<DoubleArray, DoubleArray, Double>?): (Double) -> Double {
        if (stored == null || !(stored.third > 0.0)) return { 0.0 }
        val (edges, g, cap) = stored
        return { x -> residual(x) { FuelV2.correctedPct(x, { FuelV2.vAt(it, edges, g) }, cap) } }
    }

    private fun bandCurve(band: Pair<DoubleArray, Double>?): (Double) -> Double {
        if (band == null || !(band.second > 0.0)) return { 0.0 }
        val (g, cap) = band
        return { x -> residual(x) { FuelV2.correctedPct(x, { BandFit.vAt(it, g) }, cap) } }
    }

    private fun kalmanCurve(kal: Kalman.Model?): (Double) -> Double {
        if (kal == null || !(kal.g98 > 0.0)) return { 0.0 }
        return { x -> residual(x) { FuelV2.correctedPct(x, { kal.v(it) }, kal.g98) } }
    }

    private fun residual(x: Double, corrected: () -> Double): Double {
        val c = corrected()
        return if (c.isFinite()) c - x else 0.0
    }

    /**
     * Smoothed gauge on X, corrected percent minus smoothed percent on Y.
     * One shared y range includes every line and 0, so a 1:1 fit lies on zero.
     */
    private inner class FitChart(context: Context) : View(context) {
        private val ys = Array(3) { FloatArray(101) }
        private var yLo = -1.0
        private var yHi = 1.0
        private var yStep = 1.0
        private var flat = true
        private val d = resources.displayMetrics.density
        private val line = Array(3) {
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 3f * d
            }
        }
        private val zero = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.GRAY
            strokeWidth = 2f * d
        }
        private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = android.graphics.Color.GRAY
            alpha = 64
            strokeWidth = 1f * d
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d }
        private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 12f * d }

        init {
            line[0].color = 0xFF1565C0.toInt()
            line[1].color = 0xFF2E7D32.toInt()
            line[2].color = 0xFFE65100.toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        fun setCurves(curves: List<(Double) -> Double>) {
            var lo = 0.0
            var hi = 0.0
            for (i in curves.indices) {
                val f = curves[i]
                for (x in 0..100) {
                    val y = f(x.toDouble())
                    val v = if (y.isFinite()) y else 0.0
                    ys[i][x] = v.toFloat()
                    if (v < lo) lo = v
                    if (v > hi) hi = v
                }
            }
            if (hi - lo < 1e-9) {
                flat = true
                yLo = -1.0
                yHi = 1.0
                yStep = 1.0
            } else {
                flat = false
                val pad = (hi - lo) * 0.05
                val lo0 = minOf(0.0, lo - pad)
                val hi0 = maxOf(0.0, hi + pad)
                yStep = chooseStep(lo0, hi0)
                yLo = floor(lo0 / yStep + 1e-9) * yStep
                yHi = ceil(hi0 / yStep - 1e-9) * yStep
            }
            requestLayout()
            invalidate()
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val m = margins()
            val h = m.top + plotHeight() + m.bottom
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ceil(h.toDouble()).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            val m = margins()
            val plotL = m.left
            val plotT = m.top
            val plotR = width - m.right
            val plotB = height - m.bottom
            val span = (yHi - yLo).takeIf { it > 0.0 } ?: 1.0
            fun px(x: Int) = plotL + (plotR - plotL) * x / 100f
            fun py(y: Double) = (plotT + (plotB - plotT) * ((yHi - y) / span)).toFloat()
            val ink = textPrimary()
            labelPaint.color = ink
            titlePaint.color = ink
            for (x in X_TICKS) canvas.drawLine(px(x), plotT, px(x), plotB, grid)
            for (y in yTicks()) canvas.drawLine(plotL, py(y), plotR, py(y), grid)
            canvas.drawLine(plotL, py(0.0), plotR, py(0.0), zero)
            for (i in 0..2) {
                for (x in 0 until 100) canvas.drawLine(px(x), py(ys[i][x].toDouble()), px(x + 1), py(ys[i][x + 1].toDouble()), line[i])
            }
            val half = lineHeight(labelPaint) / 2f
            val gap = 4f * d
            labelPaint.textAlign = Paint.Align.RIGHT
            for (y in yTicks()) {
                val base = py(y) - (labelPaint.ascent() + labelPaint.descent()) / 2f
                canvas.drawText(yLabel(y), plotL - gap, base, labelPaint)
            }
            labelPaint.textAlign = Paint.Align.CENTER
            for (x in X_TICKS) {
                canvas.drawText(xLabel(x), px(x), plotB + half - labelPaint.ascent(), labelPaint)
            }
            titlePaint.textAlign = Paint.Align.CENTER
            val xTitle = S("Smoothed %")
            canvas.drawText(xTitle, (plotL + plotR) / 2f, plotB + half + lineHeight(labelPaint) - titlePaint.ascent(), titlePaint)
            val yTitle = S("Error (%)")
            val tw = titlePaint.measureText(yTitle)
            val cx = lineHeight(titlePaint) / 2f
            val cy = (plotT + plotB) / 2f
            titlePaint.textAlign = Paint.Align.LEFT
            canvas.save()
            canvas.rotate(-90f, cx, cy)
            canvas.drawText(yTitle, cx - tw / 2f, cy - (titlePaint.ascent() + titlePaint.descent()) / 2f, titlePaint)
            canvas.restore()
            val names = arrayOf(
                S("Chunk"),
                S("Band"),
                S("Kalman"),
            )
            val sample = 14f * d
            var lx = plotL
            val legendBase = -labelPaint.ascent()
            val legendMid = lineHeight(labelPaint) / 2f
            labelPaint.textAlign = Paint.Align.LEFT
            for (i in names.indices) {
                canvas.drawLine(lx, legendMid, lx + sample, legendMid, line[i])
                canvas.drawText(names[i], lx + sample + gap, legendBase, labelPaint)
                lx += sample + gap + labelPaint.measureText(names[i]) + 12f * d
            }
        }

        private fun plotHeight(): Float {
            val base = 150f * d
            return max(base, titlePaint.measureText(S("Error (%)")))
        }

        private fun margins(): Box {
            val gap = 4f * d
            var yW = 0f
            for (y in yTicks()) yW = max(yW, labelPaint.measureText(yLabel(y)))
            val half = lineHeight(labelPaint) / 2f
            val left = yW + gap + lineHeight(titlePaint)
            val right = labelPaint.measureText(xLabel(100)) / 2f
            val top = lineHeight(labelPaint) + half
            val bottom = half + lineHeight(labelPaint) + lineHeight(titlePaint)
            return Box(left, top, right, bottom)
        }

        private fun yTicks(): List<Double> {
            if (flat) return listOf(-1.0, 0.0, 1.0)
            val n = ((yHi - yLo) / yStep).roundToInt().coerceAtLeast(1)
            return List(n + 1) { i -> yLo + i * yStep }
        }

        private fun yLabel(v: Double): String {
            val dec = decimals(yStep)
            val eps = 0.5 * 10.0.pow(-dec)
            val shown = if (abs(v) < eps) 0.0 else v
            val body = formatNum(abs(shown), dec)
            return when {
                shown > eps -> "+$body"
                shown < -eps -> "${DecimalFormatSymbols.getInstance(activityLocale()).minusSign}$body"
                else -> body
            }
        }

        private fun xLabel(x: Int): String = formatNum(x.toDouble(), 0)

        private fun formatNum(v: Double, dec: Int): String {
            val fmt = java.text.DecimalFormat()
            fmt.decimalFormatSymbols = DecimalFormatSymbols.getInstance(activityLocale())
            fmt.isGroupingUsed = false
            fmt.minimumFractionDigits = dec
            fmt.maximumFractionDigits = dec
            return fmt.format(v)
        }

        private fun decimals(step: Double): Int {
            if (step >= 1.0 - 1e-9) return 0
            return ceil(-log10(step) - 1e-9).toInt().coerceIn(0, 6)
        }

        private fun chooseStep(lo: Double, hi: Double): Double {
            var step = niceStep((hi - lo).coerceAtLeast(1e-9))
            repeat(8) {
                val a = floor(lo / step + 1e-9) * step
                val b = ceil(hi / step - 1e-9) * step
                val n = ((b - a) / step).roundToInt() + 1
                step = when {
                    n > 6 -> bump(step, true)
                    n < 4 -> bump(step, false)
                    else -> return step
                }
            }
            return step
        }

        private fun niceStep(span: Double): Double {
            val raw = (span / 5.0).coerceAtLeast(1e-9)
            val pow = 10.0.pow(floor(log10(raw)))
            val err = raw / pow
            val mant = when {
                err < 1.5 -> 1.0
                err < 3.5 -> 2.0
                err < 7.5 -> 5.0
                else -> 10.0
            }
            return mant * pow
        }

        private fun bump(step: Double, up: Boolean): Double {
            val pow = 10.0.pow(floor(log10(step) + 1e-12))
            val m = step / pow
            val seq = doubleArrayOf(1.0, 2.0, 5.0, 10.0)
            val i = seq.indices.minByOrNull { abs(seq[it] - m) } ?: 0
            return if (up) {
                if (i < seq.lastIndex) seq[i + 1] * pow else 2.0 * pow * 10.0
            } else {
                if (i > 0) seq[i - 1] * pow else 5.0 * pow / 10.0
            }
        }

        private fun lineHeight(p: Paint): Float = p.fontMetrics.descent - p.fontMetrics.ascent
    }

    private class Box(val left: Float, val top: Float, val right: Float, val bottom: Float)

    private data class ExtraCard(
        val id: Int,
        val root: LinearLayout,
        val source: Spinner,
        val customName: EditText,
        val tank: EditText,
    )

    companion object {
        private const val TAG = "FuelSmooth"
        private const val PAD = 48
        private const val REQ_NOTIF = 3501
        private val TIP_IDS = listOf("tip_small", "tip_medium", "tip_large")
        private val X_TICKS = intArrayOf(0, 25, 50, 75, 100)
    }
}
