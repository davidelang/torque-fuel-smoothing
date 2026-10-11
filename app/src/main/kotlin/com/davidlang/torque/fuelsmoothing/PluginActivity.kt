package com.davidlang.torque.fuelsmoothing

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
    private lateinit var estimated: TextView
    private lateinit var extrasCol: LinearLayout
    private lateinit var addBtn: Button
    private lateinit var showNotice: CheckBox
    private lateinit var feed012f: Spinner
    private lateinit var primaryAlgo: Spinner
    private lateinit var activeSpinner: Spinner
    private lateinit var selectPid: EditText
    private lateinit var profileNameView: TextView
    private lateinit var profileName: EditText
    private lateinit var vehicleMake: EditText
    private lateinit var detectBtn: Button
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
        val manual = Button(this)
        manual.text = getString(R.string.manual_button)
        manual.setOnClickListener {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.manual_url)))) }
        }
        col.addView(manual)

        if (!torqueInstalled()) {
            val need = TextView(this)
            need.text = "Requires Torque Pro (paid). Torque Lite does not support plugins."
            need.textSize = 16f
            col.addView(need)
            val play = Button(this)
            play.text = "Torque Pro"
            play.setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.prowl.torque")))
            }
            col.addView(play)
        }

        val about = TextView(this)
        about.text = versionName() + "\nNot affiliated with Torque or Ian Hawkins."
        about.textSize = 14f
        col.addView(about)
        val privacy = Button(this)
        privacy.text = "Privacy"
        privacy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.privacy_policy_url))))
        }
        col.addView(privacy)
        val feedback = Button(this)
        feedback.text = "Send feedback"
        feedback.setOnClickListener { sendFeedback() }
        col.addView(feedback)

        tipButton = Button(this)
        tipButton.text = "Tip"
        tipButton.setOnClickListener { onTipClicked() }
        col.addView(tipButton)
        tipChoices = LinearLayout(this)
        tipChoices.orientation = LinearLayout.VERTICAL
        col.addView(tipChoices)
        thanksView = TextView(this)
        thanksView.textSize = 16f
        col.addView(thanksView)
        // Google Play Billing connects only when the user taps Tip, or to finish a recorded tip (no network use otherwise).
        TipReconciler.run(this, "app start")

        profileNameView = TextView(this)
        profileNameView.textSize = 16f
        col.addView(profileNameView)
        col.addView(label("Profile name"))
        profileName = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(profileName)
        col.addView(label("Make"))
        vehicleMake = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(vehicleMake)
        detectBtn = Button(this)
        detectBtn.text = "Detect"
        detectBtn.setOnClickListener {
            val i = Intent(this, PluginService::class.java).setAction(PluginService.ACTION_DETECT)
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } catch (t: Throwable) {
                Log.w(TAG, "detect start ignored: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        col.addView(detectBtn)
        col.addView(label("Odometer miles"))
        odoMiles = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        col.addView(odoMiles)
        val pinBtn = Button(this)
        pinBtn.text = "Save pin"
        pinBtn.setOnClickListener {
            val miles = odoMiles.text.toString().toDoubleOrNull() ?: return@setOnClickListener
            val id = ProfileStore.activeId(filesDir)
            ProfileStore.addPin(filesDir, id, miles, System.currentTimeMillis())
        }
        col.addView(pinBtn)
        gapText = TextView(this)
        gapText.textSize = 14f
        col.addView(gapText)

        showNotice = CheckBox(this)
        showNotice.text = getString(R.string.show_fuel_notification)
        col.addView(showNotice)

        col.addView(label(getString(R.string.write_012f)))
        feed012f = Spinner(this)
        feed012f.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FeedMode.entries.map { it.label })
        col.addView(feed012f)
        col.addView(label("Gauge map for Corrected / Remaining / DTE and the 012F feed"))
        primaryAlgo = Spinner(this)
        primaryAlgo.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, MapAlgo.entries.map { it.label })
        col.addView(primaryAlgo)
        val verbose = android.widget.CheckBox(this)
        verbose.text = "Verbose logging (debug; per-second log lines)"
        verbose.isChecked = FixFuelPrefs.prefs(this).getBoolean(FsLog.KEY_VERBOSE, false)
        verbose.setOnCheckedChangeListener { _, on ->
            FixFuelPrefs.prefs(this).edit().putBoolean(FsLog.KEY_VERBOSE, on).apply()
            FsLog.verbose = on
        }
        col.addView(verbose)

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

        col.addView(label("Tank size, current guess — updated at fills and when Torque disconnects"))
        tank = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        tank.hint = VolUnit.FALLBACK_HINT
        col.addView(tank)
        tankUnit = Spinner(this)
        tankUnit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, VolUnit.entries.map { it.label })
        col.addView(tankUnit)
        tankUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val u = VolUnit.entries[position.coerceIn(0, VolUnit.entries.size - 1)]
                if (loading || u == shownUnit) return
                // Re-express the typed value in the new unit; the stored value stays in gallons.
                val gal = tank.text.toString().trim().toDoubleOrNull()?.let { shownUnit.toGal(it) }
                shownUnit = u
                ProfileStore.setProfileField(filesDir, ProfileStore.activeId(filesDir), "tankUnit", u.name)
                tank.setText(VolUnit.show(gal, u))
                persist()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        estimated = TextView(this)
        estimated.textSize = 14f
        col.addView(estimated)

        live = TextView(this)
        live.textSize = 14f
        col.addView(live)

        val extraHelp = TextView(this)
        extraHelp.text = getString(R.string.tank2_help)
        extraHelp.textSize = 14f
        extraHelp.setPadding(0, 32, 0, 8)
        col.addView(extraHelp)

        extrasCol = LinearLayout(this)
        extrasCol.orientation = LinearLayout.VERTICAL
        col.addView(extrasCol)

        addBtn = Button(this)
        addBtn.text = "Add additional tank"
        addBtn.setOnClickListener { addExtra() }
        col.addView(addBtn)

        col.addView(label("Active tank (gal/hr charged here)"))
        activeSpinner = Spinner(this)
        col.addView(activeSpinner)

        col.addView(label("Select-PID long name (1-based tank id) if active = From PID"))
        selectPid = edit(InputType.TYPE_CLASS_TEXT)
        col.addView(selectPid)

        customName.addTextChangedListener(watch)
        tau.addTextChangedListener(watch)
        skew.addTextChangedListener(watch)
        jump.addTextChangedListener(watch)
        tank.addTextChangedListener(watch)
        selectPid.addTextChangedListener(watch)
        profileName.addTextChangedListener(watch)
        vehicleMake.addTextChangedListener(watch)
        sourceSpinner.onItemSelectedListener = spinListen
        activeSpinner.onItemSelectedListener = spinListen
        showNotice.setOnCheckedChangeListener { _, _ -> if (!loading) persist() }
        feed012f.onItemSelectedListener = spinListen
        primaryAlgo.onItemSelectedListener = spinListen

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

    override fun onResume() {
        super.onResume()
        if (::detectBtn.isInitialized) refreshProfile()
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
        shownUnit = VolUnit.of(ProfileStore.profileField(filesDir, ProfileStore.activeId(filesDir), "tankUnit"))
        tankUnit.setSelection(shownUnit.ordinal)
        val shownGal = tank.text.toString().trim().toDoubleOrNull()?.let { shownUnit.toGal(it) }
        val tankStr = if (shownGal != null && c.tankGal != null && kotlin.math.abs(shownGal - c.tankGal) < 0.05) tank.text.toString() else VolUnit.show(c.tankGal, shownUnit)
        if (tank.text.toString() != tankStr && !tank.isFocused) tank.setText(tankStr)
        showStoredInvalid(c)
        val km = Kalman.loadModel(ProfileStore.tankDir(filesDir, ProfileStore.activeId(filesDir), 1))
        val kmText = km?.let {
            "Kalman tank ${"%.1f".format(it.tank)} gal (${if (it.trusted) "learned" else "provisional"}); gauge covered by >=1/2/3 drives: ${it.cov1}/${it.cov2}/${it.cov3} %"
        }
        estimated.text = listOfNotNull(
            if (c.tankEstimated) "Learned from your drives (Kalman fit, once >= ${Kalman.TRUST_COV2} % of the gauge is covered by 2+ drives)." else null,
            kmText,
        ).joinToString("\n")
        val st = c.sourceStatus
        val lv = FixFuelPrefs.live(this)
        live.text = listOf(st, lv).filter { it.isNotBlank() }.joinToString("\n")
        syncExtraCards(c.extras)
        refreshActiveSpinner(c.activeTank)
        if (selectPid.text.toString() != c.selectPidName) selectPid.setText(c.selectPidName)
        showNotice.isChecked = c.showFuelNotification
        feed012f.setSelection(c.feed012f.ordinal)
        primaryAlgo.setSelection(c.primaryAlgo.ordinal)
        refreshAddBtn()
        refreshProfile()
    }

    private fun refreshProfile() {
        val id = ProfileStore.activeId(filesDir)
        val name = ProfileStore.profileField(filesDir, id, "name") ?: "Vehicle 1"
        profileNameView.text = name
        if (!profileName.isFocused && profileName.text.toString() != name) profileName.setText(name)
        val make = ProfileStore.profileField(filesDir, id, "make") ?: ""
        if (!vehicleMake.isFocused && vehicleMake.text.toString() != make) vehicleMake.setText(make)
        detectBtn.isEnabled = PluginService.ecuConnected
        val notice = File(filesDir, "profile-notice.txt")
        val gap = File(ProfileStore.profileDir(filesDir, id), "gap.txt")
        gapText.text = when {
            notice.isFile && notice.readText().isNotBlank() -> notice.readText()
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
        title.text = "Tank ${t.id}"
        title.textSize = 16f
        title.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val remove = Button(this)
        remove.text = "Remove"
        head.addView(title)
        head.addView(remove)
        root.addView(head)
        root.addView(label("Tank ${t.id} fuel source"))
        val source = Spinner(this)
        source.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, FuelSources.labels())
        root.addView(source)
        root.addView(label("Tank ${t.id} custom long name (e.g. [FuelSmooth] Tank${t.id} Level)"))
        val name = edit(InputType.TYPE_CLASS_TEXT)
        root.addView(name)
        root.addView(label("Tank ${t.id} gallons"))
        val gal = edit(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL)
        root.addView(gal)
        val est = TextView(this)
        est.textSize = 14f
        root.addView(est)
        val card = ExtraCard(t.id, root, source, name, gal, est)
        bindCard(card, t)
        name.addTextChangedListener(watch)
        gal.addTextChangedListener(watch)
        source.onItemSelectedListener = spinListen
        remove.setOnClickListener { removeExtra(card) }
        extrasCol.addView(root)
        return card
    }

    private fun bindCard(card: ExtraCard, t: ExtraTank) {
        card.source.setSelection(FuelSources.indexOf(t.sourceId))
        if (card.customName.text.toString() != t.customName) card.customName.setText(t.customName)
        val g = t.tankGal?.toString() ?: ""
        if (card.tank.text.toString() != g && !card.tank.isFocused) card.tank.setText(g)
        card.estimated.text = if (t.tankEstimated) "Tank ${t.id} shown is estimated usable from mapping." else ""
    }

    private fun addExtra() {
        if (!canAddExtra()) return
        val id = FixFuelPrefs.nextExtraId(extraCards.map { ExtraTank(id = it.id) })
        extraCards.add(buildCard(ExtraTank(id = id)))
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
        val names = mutableListOf("Tank 1")
        extraCards.sortedBy { it.id }.forEach { names.add("Tank ${it.id}") }
        names.add("From PID")
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
        val prev = FixFuelPrefs.load(this).extras.associateBy { it.id }
        return extraCards.sortedBy { it.id }.map { card ->
            val old = prev[card.id]
            ExtraTank(
                id = card.id,
                sourceId = FuelSources.catalog.getOrNull(card.source.selectedItemPosition)?.id
                    ?: FixFuelPrefs.DEFAULT_SOURCE_T2,
                customName = card.customName.text.toString().trim(),
                tankGal = card.tank.text.toString().toDoubleOrNull(),
                learnedUsable = old?.learnedUsable,
                tankEstimated = old?.tankEstimated ?: false,
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
            "0 to ${FixFuelPrefs.MAX_TAU} s") && ok
        ok = markField(skew, FixFuelPrefs.skewOk(skew.text.toString().trim().toDoubleOrNull()),
            "above 0, at most ${FixFuelPrefs.MAX_SKEW} %/s") && ok
        ok = markField(jump, FixFuelPrefs.jumpOk(jump.text.toString().trim().toDoubleOrNull()),
            "${FixFuelPrefs.MIN_JUMP} to ${FixFuelPrefs.MAX_JUMP} %") && ok
        ok = markField(tank, FixFuelPrefs.tankOk(tank.text.toString()), "blank or a number above 0") && ok
        for (card in extraCards) {
            ok = markField(card.tank, FixFuelPrefs.tankOk(card.tank.text.toString()), "blank or a number above 0") && ok
        }
        return ok
    }

    /** Stored values that load() flagged: show the raw text in red so the user can fix it. */
    private fun showStoredInvalid(c: FixFuelConfig) {
        fun show(key: String, e: EditText) {
            val raw = c.invalid[key] ?: return
            if (!e.isFocused && e.text.toString() != raw) e.setText(raw)
            markField(e, false, "stored value out of range")
        }
        show(FixFuelPrefs.KEY_TAU, tau)
        show(FixFuelPrefs.KEY_SKEW, skew)
        show(FixFuelPrefs.KEY_JUMP, jump)
        show(FixFuelPrefs.KEY_TANK, tank)
    }

    private fun persist() {
        if (!formValid()) {
            Log.i(TAG, "settings not saved: out-of-range field(s) shown in red")
            return
        }
        val src = FuelSources.catalog.getOrNull(sourceSpinner.selectedItemPosition)?.id ?: FixFuelPrefs.DEFAULT_SOURCE
        FixFuelPrefs.save(
            this,
            sourceId = src,
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
            primaryAlgo = MapAlgo.entries[primaryAlgo.selectedItemPosition.coerceIn(0, MapAlgo.entries.size - 1)],
        )
        val id = ProfileStore.activeId(filesDir)
        ProfileStore.setProfileField(filesDir, id, "name", profileName.text.toString().trim().ifBlank { "Vehicle 1" })
        val make = vehicleMake.text.toString().trim()
        ProfileStore.setProfileField(filesDir, id, "make", if (make.isEmpty()) null else make)
        profileNameView.text = ProfileStore.profileField(filesDir, id, "name") ?: "Vehicle 1"
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
            Log.w(TAG, "battery list intent ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun torqueInstalled(): Boolean {
        return runCatching { packageManager.getPackageInfo("org.prowl.torque", 0) }.isSuccess
    }

    @Suppress("DEPRECATION")
    private fun versionName(): String {
        return packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }

    private fun sendFeedback() {
        val body = "App version: ${versionName()}\nAndroid version: ${Build.VERSION.RELEASE}\nDevice model: ${Build.MANUFACTURER} ${Build.MODEL}\n"
        val uri = Uri.parse(
            "mailto:david+fuelsmoothing@lang.hm?subject=" +
                Uri.encode("Fuel Smoothing") +
                "&body=" +
                Uri.encode(body),
        )
        try {
            startActivity(Intent(Intent.ACTION_SENDTO, uri))
        } catch (t: Throwable) {
            Log.w(TAG, "feedback intent ignored: ${t.javaClass.simpleName} ${t.message}")
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
                        runOnUiThread { tipButton.text = getString(R.string.tips_play_only) }
                    }
                }

                override fun onBillingServiceDisconnected() {}
            })
        } catch (t: Throwable) {
            Log.w(TAG, "billing start ignored: ${t.javaClass.simpleName} ${t.message}")
            tipButton.text = getString(R.string.tips_play_only)
        }
    }

    private fun onTipClicked() {
        val client = billing
        if (client == null) {
            connectBilling() // queries tips and owned purchases once connected
            return
        }
        if (!client.isReady) {
            tipButton.text = getString(R.string.tips_play_only)
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
                    tipButton.text = getString(R.string.tips_play_only)
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
                    runOnUiThread { thanksView.text = "Thanks" }
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

    private data class ExtraCard(
        val id: Int,
        val root: LinearLayout,
        val source: Spinner,
        val customName: EditText,
        val tank: EditText,
        val estimated: TextView,
    )

    companion object {
        private const val TAG = "FuelSmooth"
        private const val PAD = 48
        private const val REQ_NOTIF = 3501
        private val TIP_IDS = listOf("tip_small", "tip_medium", "tip_large")
    }
}
