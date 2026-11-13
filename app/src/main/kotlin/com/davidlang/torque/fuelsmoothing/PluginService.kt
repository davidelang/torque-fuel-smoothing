package com.davidlang.torque.fuelsmoothing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.prowl.torque.remote.ITorqueService
import java.io.File
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.roundToInt

internal fun identityHeader(cmd: String, protocol: String?): String =
    if (cmd == "2216C1" && protocol?.contains("J1850 PWM") == true) "C410F1" else ""

@Suppress("UNUSED_PARAMETER")
internal fun identityQueue(firm: Boolean, vin: String?, probe: String?): List<String> =
    listOf("0902", "0100") // fresh physical identity AND fresh capability discovery, every connection

class PluginService : Service() {
    private class TankRt(
        val id: Int,
        val math: FuelMath = FuelMath(),
        val sessions: SessionStore,
    ) {
        var usedGal: Double = 0.0
        var lastRateMs: Long? = null
        /** Last flow reading as Torque reported it (before unit conversion) and its unit label. */
        var lastFlowRaw: Double? = null
        var lastFlowPid: String? = null
        var lastLvlL: Double? = null
        /** Usable level reads this session (re-detect when an unconfirmed source gave none all session). */
        var validLevels = 0
        var lastFlowUnit: String = ""
        val burnGate = BurnGate()
        var burnWhy: String? = null
        /** Tank 1 only. Null until a fill sets it. Not cleared on session open. */
        var fillAnchorGal: Double? = null
        var sinceFillUsedGal: Double = 0.0
        /** Session fuelGal at the last fill end. Open resets this with usedGal. */
        var fuelAtFillGal: Double = 0.0
        val ledger = TankLedger()
        val stale = StaleLevelGuard()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tanks = mutableMapOf<Int, TankRt>()
    private var torque: ITorqueService? = null
    /** True only after onServiceConnected, not merely bindService returning true. */
    private var bound = false
    private var bindRequested = false
    private var fordPid: String? = null
    private var speedPid: String? = null
    private var fuelUsedPid: String? = null
    private var fuelUsedUnit: String = "gal"
    private var galHrPid: String? = null
    private var galMinPid: String? = null
    private var galHrUnit: String = ""
    private var galMinUnit: String = ""
    private var preferredVolumeUnit: String = ""
    /** Volume unit/max pinned at PID registration so a later preferred-unit change does not mint a twin. */
    private var pidVolumeUnit: String = "gal"
    private var pidVolumeMax: Float = Pids.TANK_SIZE_MAX_GAL
    private var preferredDistanceUnit: String = ""
    private var loggedFlowUnit: Boolean = false
    private var lastActive: String = FixFuelPrefs.ACTIVE_TANK1
    private var suppressFillUntilMs: Long = 0L
    private var mpgPid: String? = null
    /** Blank until resolve returns a unit. The declared "mpg" default is not a unit. */
    private var mpgUnit: String = ""
    private var lastRawPct: Double? = null
    private var lastEcuConnected: Boolean? = null
    private var driveSourcesLogged: Boolean = false
    private var profileId: String = ProfileStore.DEFAULT_ID
    private var selectedTorqueProfile: String? = null
    private var torqueProfileCheckedMs = 0L
    private var profileSelectionReady = false
    /** Wrong/unknown-vehicle hold: sessions go to files/quarantine/<stamp>/ and never touch a profile. */
    private var quarantineDir: File? = null
    internal fun liveQuarantineDir(): File? = quarantineDir
    private var quarantineSuggest: String? = null
    /** Profile the user confirmed for the quarantined data (from the notification); null = park unassigned at session end. */
    private var quarantineConfirm: String? = null
    private var quarantineObs: Fingerprint.Fp? = null
    private var observedIdentity = Fingerprint.Fp()
    private var connectionMode09Known = false
    private var identityReady = false
    private var nextIdentityCheckMs = 0L
    private fun trustedVehicle(): Boolean = identityReady && identity == null && quarantineDir == null

    /** Profile whose settings drive pending DISPLAY only (gauge source, tank size). Never learned into. */
    private var pendingDisplay: String? = null
    /** VIN-less pending that auto-resolves when a fresh live level agrees with [autoMatchLast]. */
    private var autoMatch: String? = null
    private var autoMatchLast: Double? = null
    private var autoMatchUntilMs = 0L
    private var autoMatchPrevUpd: Long? = null
    private var autoMatchAdopt = false
    private val autoMatchReads = ArrayList<Double>()
    /** Pending-only fuel probe ticks for the auto-match candidate (alternates Torque refresh / direct bus read). */
    private var autoMatchProbeTicks = 0
    private var autoMatchFreshByUpd = 0
    private var autoMatchFreshByBus = 0
    /** Confirmation to show; posted at [promptAtMs] (a short grace for a transient VIN loss). */
    private var prompt: IdentityPending.Pending? = null
    private var promptAtMs = 0L
    private var promptPosted = false

    /**
     * Settings editor selection is not a recording identity. Pending vehicles DISPLAY the matched or selected
     * profile's gauge source and tank size when this connection can read that source without a new bus
     * command (Torque PID by name), with fresh SAE 012F support, or with a vendor bus PID on that profile's own
     * protocol. Nothing pending is learned: quarantine raw only, no ledger, maps, fits or detection.
     */
    private fun recordingConfig(): FixFuelConfig {
        val q = quarantineDir
        val base = FixFuelPrefs.load(this, if (q != null) pendingDisplay ?: profileId else profileId, migrateLegacy = false)
        if (q == null) return base
        val mask = observedIdentity.bitmaps["0120"]?.toLongOrNull(16) ?: 0L
        val sae = if ((mask and (1L shl 17)) != 0L) "sae_012f" else FuelDetect.NONE
        val src = FuelSources.byId(base.sourceId)
        val sameProtocol = observedIdentity.protocol != null && pendingDisplay?.let {
            Fingerprint.load(ProfileStore.profileDir(filesDir, it)).protocol == observedIdentity.protocol } == true
        val sourceId = when {
            src.id == "sae_012f" || src.id == FuelDetect.NONE -> sae
            src.longName != null || src.id == "custom_name" -> src.id
            src.modePid != null && sameProtocol -> src.id
            else -> sae
        }
        return base.copy(sourceId = sourceId, sourceConfirmed = false,
            customName = if (sourceId == "custom_name") base.customName else "", sourceStatus = "",
            extras = emptyList(), activeTank = FixFuelPrefs.ACTIVE_TANK1)
    }
    private var pidNames: Map<String, String> = emptyMap()
    private var ecuLevelName: String = ECU_FUEL_NAME
    private var ecuLevelPid: String? = null
    private var ecuFuelResolved: Boolean = false
    private var logged012fWrite: Boolean = false
    private var lastNoticeText: String? = null
    private var lastNoticeChannel: String? = null
    /** Flow units fixed for the open session; null until the first flow read of a session. */
    private var sessionFlowHrUnit: String? = null
    private var sessionFlowMinUnit: String? = null
    private var polling = false
    private var lastLiveLine: String? = null
    private val priorCache = HashMap<Int, Pair<Double?, Prior>>()
    @Volatile private var prefsDirty = true
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != FixFuelPrefs.KEY_LIVE) {
            prefsDirty = true
            priorCache.clear()
        }
    }
    private var detectQueue: ArrayDeque<SourceProbe.Planned>? = null
    private var detectTimeouts = 0
    private var fuelDetect: FuelDetect.Run? = null
    /**
     * Drive-end / disconnect / fill rollups run here, one at a time in submission order (never concurrently,
     * never on the poll thread). Results are applied back on the main thread.
     */
    private val rollupExec = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "FuelSmooth-rollup") }
    private val diagnosticCapture = DiagnosticCapture()
    /** Session ids currently being recorded; rollups never read these files. */
    @Volatile private var openSessions: Set<String> = emptySet()
    @Volatile private var destroyed = false

    private fun refreshOpen() { openSessions = tanks.values.mapNotNull { it.sessions.sessionId }.toSet() }

    private fun refreshRecording() { recording = tanks.values.any { it.sessions.isOpen } }

    private fun submitRollup(what: String, work: () -> Unit, done: () -> Unit) {
        runCatching {
            rollupExec.execute {
                val t0 = System.currentTimeMillis()
                FsLog.i(TAG, "$what start")
                runCatching(work).onFailure { FsLog.w(TAG, "$what failed: ${it.javaClass.simpleName} ${it.message}") }
                FsLog.i(TAG, "$what done in ${System.currentTimeMillis() - t0} ms")
                if (!destroyed) handler.post { if (!destroyed) done() }
            }
        }.onFailure { FsLog.w(TAG, "$what not queued: ${it.message}") }
    }
    /** Auto-detection runs at most once per ECU connection. */
    private var fuelDetectTried = false
    private var lastDetectMs = 0L
    private var cfg: FixFuelConfig = FixFuelConfig(
        sourceId = FixFuelPrefs.DEFAULT_SOURCE,
        customName = "",
        tauSec = FixFuelPrefs.DEFAULT_TAU,
        skewPctPerSec = FixFuelPrefs.DEFAULT_SKEW,
        tankGal = null,
        sourceStatus = "",
    )

    private val poll = object : Runnable {
        override fun run() {
            if (!polling) return
            sampleOnce()
            if (polling) handler.postDelayed(this, POLL_MS)
        }
    }

    /** Poll only while the ECU is connected; OBD_CONNECTED (or isConnectedToECU at bind) starts it. */
    private fun startPolling() {
        if (torque == null || polling) return
        polling = true
        handler.post(poll)
    }

    private fun stopPolling() {
        polling = false
        handler.removeCallbacks(poll)
    }

    /**
     * Primary import: finish queued rollups, swap files/, apply the two prefs files in place.
     * Returns false when that reload cannot be done. The caller then uses the stop-and-kill fallback.
     * Fallback states: service already destroyed, rollup executor shut down or the sentinel cannot be queued,
     * the archive has a root other than files and shared_prefs, or prefs apply / cache reload throws.
     * The sentinel wait runs on the caller, not the main thread, and is not timed out: a slow rollup
     * stays on this path and is not a fallback.
     */
    fun importLive(dataDir: File, tar: File, status: File): Boolean {
        if (destroyed) return false
        if (!runOnMain { stopPolling(); true }) return false
        if (!awaitRollupSentinel()) return false
        if (destroyed) return false
        return runOnMain { importLiveOnMain(dataDir, tar, status) }
    }

    /** Runs [block] on the main looper. The caller waits here, so this must not be used for the rollup sentinel. */
    private fun runOnMain(block: () -> Boolean): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return try {
                block()
            } catch (t: Throwable) {
                FsLog.w(TAG, "import reload failed: ${t.javaClass.simpleName} ${t.message}")
                false
            }
        }
        val done = java.util.concurrent.CountDownLatch(1)
        val ok = booleanArrayOf(false)
        val posted = handler.post {
            try {
                ok[0] = block()
            } catch (t: Throwable) {
                FsLog.w(TAG, "import reload failed: ${t.javaClass.simpleName} ${t.message}")
                ok[0] = false
            } finally {
                done.countDown()
            }
        }
        if (!posted) return false
        done.await()
        return ok[0]
    }

    private fun importLiveOnMain(dataDir: File, tar: File, status: File): Boolean {
        stopPolling()
        var moved = false
        ProfileStore.DataLock.lock()
        try {
            for (rt in tanks.values) {
                if (rt.sessions.isOpen) rt.sessions.close()
            }
            refreshOpen()
            refreshRecording()
            val staged = tar.inputStream().use { DataTransfer.importFilesOnly(dataDir, it) }
            moved = true
            applyStagedPrefs(staged.prefsDir)
            reloadImportedState()
            RollupCache.clear()
            FsLog.init(filesDir)
            DataTransfer.writeStatusFile(status, staged.okJson)
            DataTransfer.clearScratch(dataDir)
        } catch (t: Throwable) {
            FsLog.w(TAG, "import reload failed: ${t.javaClass.simpleName} ${t.message}")
            if (moved) runCatching { DataTransfer.clearScratch(dataDir) }
            return false
        } finally {
            ProfileStore.DataLock.unlock()
        }
        bindTorque()
        val connected = ecuConnected || runCatching { torque?.isConnectedToECU }.getOrNull() == true
        if (connected) startPolling()
        return true
    }

    /** Queued rollups finish before this returns. No timeout and no executor shutdown. Not called on the main thread. */
    private fun awaitRollupSentinel(): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) return false
        if (rollupExec.isShutdown) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        val queued = runCatching { rollupExec.execute { latch.countDown() } }.isSuccess
        if (!queued) return false
        latch.await()
        return true
    }

    private fun applyStagedPrefs(prefsDir: File) {
        applyPrefsFile(FixFuelPrefs.PREFS, File(prefsDir, "${FixFuelPrefs.PREFS}.xml"))
        applyPrefsFile("fordfuel_grants", File(prefsDir, "fordfuel_grants.xml"))
    }

    private fun applyPrefsFile(name: String, file: File) {
        val editor = getSharedPreferences(name, MODE_PRIVATE).edit()
        editor.clear()
        if (file.isFile) readPrefsXml(editor, file)
        if (!editor.commit()) throw java.io.IOException("datatransfer: prefs commit failed for $name")
    }

    private fun readPrefsXml(editor: SharedPreferences.Editor, file: File) {
        val parser = android.util.Xml.newPullParser()
        file.inputStream().use { parser.setInput(it, "UTF-8") }
        var event = parser.eventType
        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG) {
                val tag = parser.name
                val key = parser.getAttributeValue(null, "name").orEmpty()
                when (tag) {
                    "string" -> if (key.isNotEmpty()) editor.putString(key, parser.nextText())
                    "boolean" -> if (key.isNotEmpty()) editor.putBoolean(key, parser.getAttributeValue(null, "value") == "true")
                    "int" -> parser.getAttributeValue(null, "value")?.toIntOrNull()?.let { if (key.isNotEmpty()) editor.putInt(key, it) }
                    "long" -> parser.getAttributeValue(null, "value")?.toLongOrNull()?.let { if (key.isNotEmpty()) editor.putLong(key, it) }
                    "float" -> parser.getAttributeValue(null, "value")?.toFloatOrNull()?.let { if (key.isNotEmpty()) editor.putFloat(key, it) }
                    "set" -> if (key.isNotEmpty()) editor.putStringSet(key, readPrefSet(parser))
                }
            }
            event = parser.next()
        }
    }

    private fun readPrefSet(parser: org.xmlpull.v1.XmlPullParser): Set<String> {
        val values = HashSet<String>()
        while (true) {
            val ev = parser.next()
            if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && parser.name == "set") break
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "string") values.add(parser.nextText())
        }
        return values
    }

    private fun reloadImportedState() {
        // A replaced snapshot does not establish the identity of the currently attached ECU.
        identity = null; identityReady = false; profileSelectionReady = false
        observedIdentity = Fingerprint.Fp(); connectionMode09Known = false
        quarantineDir = null; quarantineObs = null; quarantineSuggest = null; quarantineConfirm = null
        clearPendingIdentity()
        cfg = FixFuelPrefs.load(this)
        FsLog.verbose = getSharedPreferences(FixFuelPrefs.PREFS, MODE_PRIVATE).getBoolean(FsLog.KEY_VERBOSE, false)
        prefsDirty = false
        profileId = ProfileStore.activeId(filesDir)
        priorCache.clear()
        bandCache = null
        kalCache = null
        loadLevelScale()
        tanks.clear()
        tanks[1] = makeRuntime(1)
        ensureRuntimes()
    }

    /** Toyota-type full-scale counts for the active profile (frozen) and the largest count seen. */
    private var lvlFull: Double? = null
    private var lvlMax: Double? = null

    private fun loadLevelScale() {
        if (!trustedVehicle()) { lvlFull = null; lvlMax = null; return }
        lvlFull = ProfileStore.profileField(filesDir, profileId, LevelScale.KEY_FULL)?.toDoubleOrNull()
        lvlMax = ProfileStore.profileField(filesDir, profileId, LevelScale.KEY_MAX)?.toDoubleOrNull()
    }

    private fun levelUnit(id: Int): Double = if (id == 1 && LevelScale.applies(cfg.sourceId)) LevelScale.unit(lvlFull, lvlMax) else 1.0

    private fun seedLevelScale(counts: Double, why: String) {
        if (!trustedVehicle() || lvlFull != null || !LevelScale.applies(cfg.sourceId)) return
        lvlFull = counts
        ProfileStore.setProfileField(filesDir, profileId, LevelScale.KEY_FULL, counts.toString())
        FsLog.i(TAG, "level full scale seeded for $profileId: ${"%.0f".format(counts)} counts ($why); units only, tank size still learned")
    }

    private fun reloadCfgIfDirty() {
        if (!prefsDirty) return
        prefsDirty = false
        cfg = recordingConfig()
        loadLevelScale()
        FsLog.verbose = getSharedPreferences(FixFuelPrefs.PREFS, MODE_PRIVATE).getBoolean(FsLog.KEY_VERBOSE, false)
    }

    /** Reload cfg now, whether or not a preference changed, and tell the page. */
    private fun onSourceNotice() {
        cfg = recordingConfig()
        loadLevelScale()
        prefsDirty = false
        sendBroadcast(Intent(ACTION_SOURCE_CHANGED).setPackage(packageName))
    }

    /** Page-only signal after a detect finish that did not write a source. */
    private fun announceSourceChanged() {
        sendBroadcast(Intent(ACTION_SOURCE_CHANGED).setPackage(packageName))
    }

    /**
     * A real legacy id (catalog id, not none) is stored confirmed on every profile that has no fuelSource.
     * Anything else is stored as none, unconfirmed. The legacy key is then removed.
     */
    private fun migrateLegacySource() {
        val p = FixFuelPrefs.prefs(this)
        if (!p.contains(FixFuelPrefs.KEY_SOURCE)) return
        val raw = p.getString(FixFuelPrefs.KEY_SOURCE, null)?.trim().orEmpty()
        val real = FuelSources.byId(raw).id == raw && raw != FuelDetect.NONE
        val id = if (real) raw else FuelDetect.NONE
        var wrote = false
        for (pid in ProfileStore.profileIds(filesDir)) {
            if (ProfileStore.profileField(filesDir, pid, "fuelSource").isNullOrBlank()) {
                FuelDetect.store(filesDir, pid, id, real)
                wrote = true
            }
        }
        p.edit().remove(FixFuelPrefs.KEY_SOURCE).apply()
        if (wrote) notifySourceChanged(this)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            torque = ITorqueService.Stub.asInterface(service)
            bound = true
            bindRequested = true
            FsLog.i(TAG, "bound Torque API ${runCatching { torque?.version }.getOrNull()}")
            stopPolling()
            handler.post { afterBindWork() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            FsLog.w(TAG, "Torque disconnected; not auto-creating")
            stopPolling()
            onEcuAway()
            // Drop the stale binding so the next bindTorque() does not stack a second connection.
            runCatching { unbindService(this) }
            torque = null
            bound = false
            bindRequested = false
            lastEcuConnected = null
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppStrings.install(this)
        isRunning = true
        instance = this
        FsLog.init(filesDir)
        cfg = FixFuelPrefs.load(this)
        FsLog.verbose = getSharedPreferences(FixFuelPrefs.PREFS, MODE_PRIVATE).getBoolean(FsLog.KEY_VERBOSE, false)
        prefsDirty = false
        FixFuelPrefs.prefs(this).registerOnSharedPreferenceChangeListener(prefsListener)
        ProfileStore.migrate(
            filesDir,
            null,
            ProfileStore.LedgerSeed(
                FixFuelPrefs.fillAnchorGal(this),
                FixFuelPrefs.sinceFillUsedGal(this),
                cfg.tankGal,
            ),
            S("Vehicle 1"),
        )
        profileId = ProfileStore.activeId(filesDir)
        IdentityPending.clear(filesDir) // no live identity prompt before this service reads one
        Unassigned.adoptOrphans(filesDir)
        migrateLegacySource()
        tanks[1] = makeRuntime(1)
        ensureChannel()
        TapStartNotice.cancel(this)
        TipReconciler.run(this, "service start")
        updatePresence(S("Binding to Torque…"))
        bindTorque()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_OBD_CONNECTED -> {
                onEcu(true)
                bindTorque()
            }
            ACTION_PID_QUERY, ACTION_PID_PLUGIN -> bindTorque()
            ACTION_OBD_DISCONNECTED -> onEcu(false)
            ACTION_Q_CONFIRM -> confirmQuarantine(intent.getStringExtra(EXTRA_TARGET))
            ACTION_UNASSIGNED_ASSIGN -> assignUnassigned(intent.getStringExtra(EXTRA_UNASSIGNED_ID), intent.getStringExtra(EXTRA_TARGET))
            ACTION_UNASSIGNED_DISCARD -> discardUnassigned(intent.getStringExtra(EXTRA_UNASSIGNED_ID))
            ACTION_CONFIRM_SOURCE -> {
                val pid = intent.getStringExtra(EXTRA_TARGET)
                val src = intent.getStringExtra(EXTRA_SOURCE)
                val stored = if (pid != null) ProfileStore.profileField(filesDir, pid, "fuelSource") else null
                if (pid != null && src != null && stored == src && src != FuelDetect.NONE) {
                    FuelDetect.store(filesDir, pid, src, true)
                    notifySourceChanged(this)
                    FsLog.i(TAG, "fuel source $src confirmed by user for $pid")
                } else {
                    FsLog.w(TAG, "confirm source mismatch: requested=$src stored=$stored")
                    announceSourceChanged()
                }
                notificationManager()?.cancel(DETECT_NOTIF_ID)
            }
            ACTION_SOURCE_CHANGED -> onSourceNotice()
            ACTION_DETECT -> {
                val requestedProfile = intent.getStringExtra(EXTRA_TARGET)
                if (requestedProfile != null && (requestedProfile != profileId || identity != null)) {
                    announceSourceChanged()
                    return START_STICKY
                }
                bindTorque()
                queueFuelDetect("manual")
                queueDetect()
            }
        }
        updatePresence(statusText())
        if (intent?.action.isNullOrEmpty()) {
            bindTorque()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        diagnosticCapture.shutdown()
        TipReconciler.run(this, "service stop")
        stopPolling()
        FixFuelPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        closeSession()
        destroyed = true
        rollupExec.shutdown()
        // Short wait only; an unfinished rollup is redone at the next close (input signature unchanged).
        runCatching { rollupExec.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS) }
        if (bindRequested) {
            runCatching { unbindService(connection) }
        }
        bindRequested = false
        bound = false
        torque = null
        isRunning = false
        if (instance === this) instance = null
        ecuConnected = false
        super.onDestroy()
    }

    /** Routine PID_QUERY/PID_PLUGIN from the receiver while running: what onStartCommand did, minus the start. */
    fun onRoutineQuery() {
        if (destroyed) return
        bindTorque()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bindTorque() {
        if (bound || bindRequested) return
        val flags = 0
        FsLog.i(TAG, "bindTorque flags=$flags (no BIND_AUTO_CREATE)")
        val ok = runCatching {
            bindService(intentForTorque(), connection, flags)
        }.getOrDefault(false)
        if (ok) {
            bindRequested = true
            FsLog.i(TAG, "bindService ok (waiting onServiceConnected; flags=$flags)")
        } else {
            bindRequested = false
            FsLog.i(TAG, "bindService fail flags=$flags (Torque not running; will not create)")
        }
    }

    private fun intentForTorque(): Intent {
        return Intent().setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService")
    }

    private fun afterBindWork() {
        val t = torque ?: return
        onEcuAway() // every API binding must establish physical identity again before output
        cfg = recordingConfig()
        FsLog.verbose = getSharedPreferences(FixFuelPrefs.PREFS, MODE_PRIVATE).getBoolean(FsLog.KEY_VERBOSE, false)
        prefsDirty = false
        priorCache.clear()
        ensureRuntimes()
        lastActive = resolveStoredActive()
        resolvePids()
        loadLevelScale()
        registerPluginPids(t)
        val connected = ecuConnected || runCatching { t.isConnectedToECU }.getOrNull() == true
        if (connected) {
            startPolling()
        } else {
            stopPolling()
            FsLog.i(TAG, "bound; ECU not connected, waiting for OBD_CONNECTED")
        }
    }

    private fun resolvePids() {
        val t = torque ?: return
        val r = runCatching { Pids.resolve(t) }
            .onFailure { FsLog.w(TAG, "listAllPIDs/getPIDInformation: ${it.message}") }
            .getOrNull() ?: return
        fordPid = r.ford
        speedPid = r.speed
        fuelUsedPid = r.fuelUsed
        if (r.fuelUsedUnit.isNotEmpty()) fuelUsedUnit = r.fuelUsedUnit
        galHrPid = r.galHr ?: Pids.GAL_PER_HOUR
        galMinPid = r.galMin ?: Pids.GAL_PER_MIN
        galHrUnit = r.galHrUnit
        galMinUnit = r.galMinUnit
        mpgPid = r.mpg
        mpgUnit = r.mpgUnit
        pidNames = r.names
        preferredDistanceUnit = runCatching { t.getPreferredUnit("km") }.getOrNull().orEmpty()
        preferredVolumeUnit = runCatching { t.getPreferredUnit("l") }.getOrNull().orEmpty()
        if (preferredVolumeUnit.isBlank()) {
            preferredVolumeUnit = runCatching { t.getPreferredUnit("gal") }.getOrNull().orEmpty()
        }
        FsLog.i(
            TAG,
            "pids ford=${fordPid ?: "UNRESOLVED"} speed=$speedPid fuelUsed=$fuelUsedPid unit=$fuelUsedUnit " +
                "galHr=$galHrPid raw=$galHrUnit galMin=$galMinPid raw=$galMinUnit " +
                "preferredVol=$preferredVolumeUnit mpg=$mpgPid names=${pidNames.size}",
        )
        resolveEcuFuel(t)
    }

    private fun resolveEcuFuel(t: ITorqueService) {
        val all = runCatching { t.listAllPIDs() }.getOrNull() ?: emptyArray()
        val infos = if (all.isEmpty()) {
            emptyArray()
        } else {
            runCatching { t.getPIDInformation(all) }.getOrNull() ?: emptyArray()
        }
        val n = minOf(all.size, infos.size)
        var pid: String? = null
        var name = ECU_FUEL_NAME
        for (i in 0 until n) {
            val id = all[i]
            val nm = Pids.csvName(infos[i])
            if (nm.equals(ECU_FUEL_NAME, ignoreCase = true)) {
                name = nm
                if (pid == null) pid = id
            }
            if (isStock012F(id) && !nm.equals(Pids.FORD_FUEL_NAME, ignoreCase = true)) {
                pid = id
                if (nm.isNotEmpty()) name = nm
            }
        }
        ecuLevelName = name
        ecuLevelPid = pid
        ecuFuelResolved = true
        FsLog.i(TAG, "ecu 012F slot name=$ecuLevelName pid=${pid ?: "none"} write=${cfg.feed012f}")
    }

    private fun sampleOnce() {
        val t = torque ?: return
        val ecu = runCatching { t.isConnectedToECU }.getOrNull()
        val now = System.currentTimeMillis()
        if (ecu != null && ecu != lastEcuConnected) onEcu(ecu)
        if (ecu != true) return // disconnected cached values must never be published as a new car
        if (!syncTorqueProfile(t, now)) return
        if (identity == null && SystemClock.elapsedRealtime() >= nextIdentityCheckMs && fuelDetect == null && detectQueue == null) {
            // A swap can occur without Torque changing profiles or reporting a connection edge.
            // One paced VIN recheck/minute (pending identity retries/30s), not a vendor scan.
            // A same-vehicle monitor keeps the dash live; a detected change invalidates in IdentityRead.
            // Do not blank gauges here: starting a check is not a vehicle change, and a NaN write
            // with the wrong short name was minting empty twin entries.
            try {
                identity = IdentityRead(profileId, selectedTorqueProfile, Fingerprint.loadAll(filesDir)[profileId],
                    checkOnly = identityReady)
            } catch (e: Exception) {
                identityReady = false
                nextIdentityCheckMs = SystemClock.elapsedRealtime() + 30_000L
                FsLog.warn(TAG, "identityStart", "vehicle identity check unavailable: ${e.javaClass.simpleName}")
                return
            }
        }
        if (identity != null) {
            pumpIdentity(t)
            return // destination config/runtime is installed BEFORE the next sample/output
        }
        if (!identityReady) return
        if (quarantineDir != null && tickPendingIdentity()) return
        reloadCfgIfDirty()
        ensureRuntimes()
        if (pidNames.isEmpty()) resolvePids()
        else if (cfg.write012f && !ecuFuelResolved) resolveEcuFuel(t)
        val speedRaw = speedPid?.let { readDouble(t, it) } ?: 0.0
        val speedKmh = speedRaw
        val active = resolveActiveTank(t)
        applyActiveTank(active, now)
        if (ecu == true) ensureActiveSession()
        val skipFill = now < suppressFillUntilMs
        // Never burst bus queries: at most one identity or one detect command per tick.
        if (quarantineDir == null && ecu == true && identity == null && !fuelDetectTried && fuelDetect == null &&
            FuelDetect.shouldAutoDetect(cfg.sourceId, ProfileStore.profileField(filesDir, profileId, FuelDetect.KEY_TRIED) == "true")
        ) {
            fuelDetectTried = true // once per connection; the profile flag is set only when the run completes
            queueFuelDetect("auto")
        }
        when {
            identity != null -> pumpIdentity(t)
            fuelDetect != null -> pumpFuelDetect(t)
            else -> pumpDetect(t, speedRaw)
        }
        sampleTank(1, t, ecu, speedKmh, now, speedRaw, skipFill)
        for (extra in cfg.extras) {
            sampleTank(extra.id, t, ecu, speedKmh, now, speedRaw, skipFill)
        }
        if (ecu == true && identity == null && fuelDetect == null && detectQueue == null) {
            val rt = tanks.values.firstOrNull { it.sessions.isOpen }
            if (rt != null) {
                fun number(x: Double?): Any = x?.takeIf { it.isFinite() } ?: org.json.JSONObject.NULL
                try {
                    diagnosticCapture.tick(t, org.json.JSONObject().put("t", now)
                        .put("speedKmh", number(speedKmh)).put("sessionFuelGal", number(rt.usedGal))
                        .put("torqueFlowRaw", number(rt.lastFlowRaw)).put("torqueFlowUnit", rt.lastFlowUnit)
                        .put("torqueFlowPid", rt.lastFlowPid ?: org.json.JSONObject.NULL)
                        .put("primaryLevelPct", number(lastRawPct)))
                } catch (e: Exception) {
                    diagnosticCapture.disableAfterFailure(e)
                }
            }
        }
    }

    private fun sampleTank(
        id: Int,
        t: ITorqueService,
        ecu: Boolean?,
        speedKmh: Double,
        now: Long,
        speedRaw: Double,
        skipFill: Boolean,
    ) {
        val rt = tanks[id] ?: return
        val srcCfg = if (id == 1) {
            cfg
        } else {
            val extra = cfg.extra(id) ?: return
            cfg.copy(
                sourceId = extra.sourceId,
                customName = extra.customName,
                tankGal = extra.tankGal,
            )
        }
        var lvlUpd: Long? = null // set only when the level is read by Torque PID name (null = bus reply)
        var lvlBusFresh = false // a direct bus reply this tick proves Torque's cached named value is current
        var levelPid: String? = null
        val src = FuelSources.read(t, srcCfg, pidNames) { pid ->
            if (isStock012F(pid)) {
                FsLog.warn(TAG, "refuse012f", "refusing getPIDValues of 012F (not Ford input)")
                null
            } else {
                levelPid = pid
                if (id == 1 && autoMatch != null) {
                    lvlBusFresh = probeAutoMatchLevel(t, srcCfg.sourceId, pid)
                    lvlUpd = updateTime(t, pid) ?: 0L
                }
                readDouble(t, pid)
            }
        }
        tanks[id]?.let { rt -> rt.lastLvlL = src.liters; if (src.pct != null && !src.missing) rt.validLevels++ }
        tanks[id]?.math?.unit = levelUnit(id)
        if (id == 1) {
            if (src.how != cfg.sourceStatus) FixFuelPrefs.setSourceStatus(this, src.how)
        } else if (src.how != (cfg.extra(id)?.sourceStatus ?: "")) {
            FixFuelPrefs.setExtraSourceStatus(this, id, src.how)
        }
        if (id == 1) FsLog.change(TAG, "source", "${cfg.sourceId} ${src.how} $lastActive", "source ${cfg.sourceId} ${src.how} active=$lastActive")
        val raw = src.pct
        val logging0 = rt.sessions.isOpen && lastActive == id.toString()
        if (!src.missing && ecu != false && rt.math.checkReanchor(raw, speedKmh, now)) {
            FsLog.i(TAG, "tank$id level re-anchored to ${rt.math.smoothed} after sustained low readings (raw=$raw)")
            if (logging0) rt.sessions.mark("reanchor", now, "\"sm\":${rt.math.smoothed}")
        }
        var stale = false
        if (raw != null && raw.isFinite() && !src.missing) {
            val tankGal = priorFor(id).cap
            when (rt.stale.observe(raw, rt.usedGal, tankGal)) {
                StaleLevelGuard.Verdict.REFRESH -> {
                    requestLevelRefresh(t, srcCfg.sourceId, levelPid)
                    val burn = rt.usedGal - rt.stale.usedAtChange
                    FsLog.i(TAG, "tank$id stale level refresh: unchanged ${"%.2f".format(raw)} while burn=${"%.3f".format(burn)} gal (threshold=${"%.2f".format(LevelTrust.staleBurnGal(tankGal))})")
                }
                StaleLevelGuard.Verdict.STALE -> {
                    stale = true
                    if (!rt.stale.logged) {
                        rt.stale.logged = true
                        val burn = rt.usedGal - rt.stale.usedAtChange
                        FsLog.w(TAG, "tank$id stale level: unchanged ${"%.2f".format(raw)} while burn=${"%.3f".format(burn)} gal; not used as auto-match baseline")
                    }
                }
                StaleLevelGuard.Verdict.OK -> {}
            }
        }
        val valid = !stale && rt.math.isValidSample(raw, ecu, rt.math.smoothed) && !src.missing
        val logging = rt.sessions.isOpen && lastActive == id.toString()
        if (!valid) {
            rt.math.hold()
            val held = rt.math.smoothed
            val dF = if (logging) integrateGalHr(t, now, rt) else 0.0
            if (logging) {
                rt.sessions.append(
                    sampleRow(
                        id, now, raw ?: Double.NaN, held ?: Double.NaN, speedRaw,
                        fill = false, ok = false, why = if (stale) LevelTrust.WHY_STALE else holdWhy(raw, ecu, src.missing, rt.math.smoothed),
                        mode = TrackMode.HOLD, ecu = ecu == true,
                    ),
                )
                stepLive(rt, dF)
            }
            if (held != null) {
                if (id == 1) publishFromSmoothed(t, raw ?: held, held) else publishExtraFrom(t, id, rt, held)
            }
            (if (stale) LevelTrust.WHY_STALE else holdWhy(raw, ecu, src.missing, rt.math.smoothed)).let { why ->
                FsLog.change(TAG, "state$id", "hold $why", "tank$id hold raw=$raw why=$why src=${src.how}")
            }
            return
        }
        val live = raw!!
        if (id == 1) lastRawPct = live
        if (id == 1 && autoMatch != null) autoMatchStep(live, lvlUpd, lvlBusFresh)
        val before = rt.math.smoothed
        val baseBefore = rt.math.stopBaseline
        val prevMode = rt.math.mode
        val smoothed = rt.math.ingest(
            live,
            speedKmh,
            nowMs = now,
            tauSec = cfg.tauSec,
            skewPctPerSec = cfg.skewPctPerSec,
            // Live fill detect only unlocks the skew limit: fixed 60 s settle and 15 % rise, no settings.
            jumpPct = FuelMath.LIVE_FOLLOW_PCT,
            settleSec = FuelMath.FILL_SETTLE_SEC,
            ignoreFill = skipFill,
        )
        if (id == 1 && trustedVehicle() && LevelScale.applies(cfg.sourceId)) {
            // Highest settled (smoothed, tracking, not filling/holding) count only rises; raw slosh never counts.
            LevelScale.raise(lvlMax, smoothed, rt.math.mode == TrackMode.SKEW)?.let { c ->
                lvlMax = c
                ProfileStore.setProfileField(filesDir, profileId, LevelScale.KEY_MAX, c.toString())
            }
        }
        if (rt.math.mode == TrackMode.FILL && prevMode != TrackMode.FILL) {
            val delta = live - (baseBefore ?: before ?: live)
            FsLog.i(TAG, "tank$id fill start Δ=${"%.1f".format(delta)} vs settled=${baseBefore?.let { "%.1f".format(it) }} raw=$live speedKmh=${"%.1f".format(speedKmh)}")
            if (logging) rt.sessions.mark("fillStart", now)
        }
        val fillEnded = prevMode == TrackMode.FILL && rt.math.mode != TrackMode.FILL
        if (fillEnded) {
            FsLog.i(TAG, "tank$id fill end raw=$live smoothed=$smoothed")
            onFillEnd(rt, smoothed, now, logging)
        }
        val dF = if (logging) integrateGalHr(t, now, rt) else 0.0
        if (logging) {
            rt.sessions.append(
                sampleRow(
                    id, now, live, smoothed, speedRaw,
                    fill = rt.math.mode == TrackMode.FILL,
                    ok = true,
                    why = null,
                    mode = rt.math.mode,
                    ecu = ecu == true,
                ),
            )
            stepLive(rt, dF)
        }
        if (id == 1) {
            publishFromSmoothed(t, live, smoothed)
            val line = "track mode=${rt.math.mode} raw=$live smoothed=$smoothed speedKmh=${"%.1f".format(speedKmh)}"
            FsLog.change(TAG, "state1", "mode ${rt.math.mode}", line)
            if (line != lastLiveLine) {
                lastLiveLine = line
                FixFuelPrefs.setLive(this, line)
            }
        } else {
            publishExtraFrom(t, id, rt, smoothed)
            FsLog.change(TAG, "state$id", "mode ${rt.math.mode}", "tank$id mode=${rt.math.mode} raw=$live smoothed=$smoothed src=${src.how}")
        }
    }

    private fun sampleRow(
        id: Int,
        now: Long,
        raw: Double,
        smoothed: Double,
        speedRaw: Double,
        fill: Boolean,
        ok: Boolean,
        why: String?,
        mode: TrackMode,
        ecu: Boolean,
    ): Sample {
        val rt = tanks[id]
        return Sample(
            t = now,
            rawPct = raw,
            smoothedPct = smoothed,
            fuelUsedGal = rt?.usedGal ?: 0.0,
            speed = speedRaw,
            fill = fill,
            ok = ok,
            why = why,
            mode = mode.name,
            ecu = ecu,
            fuelUnit = rt?.lastFlowUnit?.ifBlank { null } ?: fuelUnitLabel(),
            fuelRaw = rt?.lastFlowRaw,
            lvlL = rt?.lastLvlL,
            lvlSrc = if (id == 1) cfg.sourceId else cfg.extra(id)?.sourceId ?: "",
        )
    }

    private fun fuelUnitLabel(): String {
        return when {
            galHrUnit.isNotBlank() -> galHrUnit
            galMinUnit.isNotBlank() -> galMinUnit
            else -> fuelUsedUnit
        }
    }

    private fun holdWhy(raw: Double?, ecu: Boolean?, missing: Boolean, lastGood: Double?): String {
        if (missing || raw == null || !raw.isFinite()) return "missing"
        if (ecu == false) return "ecu"
        val u = levelUnit(1)
        if (lastGood != null && lastGood > FuelMath.NEAR_EMPTY_PCT * u && raw < FuelMath.GLITCH_RAW_PCT * u) return "glitch<1"
        if (lastGood != null && lastGood > FuelMath.NEAR_EMPTY_PCT * u && lastGood - raw > FuelMath.DROP_HOLD_PT * u) {
            return "held:drop>15"
        }
        return "missing"
    }

    private fun publishHeld(t: ITorqueService, raw: Double?, held: Double) {
        publishFromSmoothed(t, raw ?: held, held)
    }

    private fun publishFromSmoothed(t: ITorqueService, raw: Double, smoothed: Double) {
        val rt = tanks[1] ?: return
        if (!trustedVehicle()) {
            publishPending(t, rt, smoothed)
            return
        }
        val chunkOut = livePublish(rt)
        val bandOut = bandPublish(rt)
        val kalOut = kalmanPublish(rt)
        publishSmoothed(t, smoothed)
        runCatching { publishBand(t, bandOut) }
        runCatching { publishKalman(t, kalOut) }
        runCatching { publishChunk(t, chunkOut) }
        runCatching { publishTankSizes(t) }
        writeStockFuelLevel(t, smoothed, chunkOut.correctedPct, bandOut.correctedPct, kalOut.correctedPct)
        updatePresence(statusText())
    }

    private fun publishExtraFrom(t: ITorqueService, id: Int, rt: TankRt, smoothed: Double) {
        if (!trustedVehicle()) return
        publishExtra(t, id, smoothed, livePublish(rt).correctedPct)
    }

    private fun onEcu(connected: Boolean) {
        ecuConnected = connected
        if (connected != lastEcuConnected) { fuelDetectTried = false; fuelDetect = null }
        if (connected) startPolling() else stopPolling()
        if (lastEcuConnected == connected) return
        lastEcuConnected = connected
        if (connected) {
            profileSelectionReady = false
            torqueProfileCheckedMs = 0L
        } else {
            onEcuAway()
        }
    }

    private fun onEcuAway() {
        closeSession() // close under the previous physical owner before invalidating identity
        torque?.let { invalidateVehicleOutputs(it) }
        profileSelectionReady = false
        torqueProfileCheckedMs = 0L
        identity = null
        identityReady = false
        observedIdentity = Fingerprint.Fp()
        connectionMode09Known = false
        quarantineDir = null; quarantineObs = null; quarantineSuggest = null; quarantineConfirm = null
        clearPendingIdentity()
        Unassigned.adoptOrphans(filesDir)
        refreshUnassignedNotice()
        driveSourcesLogged = false
        resetVehicleRuntime()
    }

    /** The public API exposes only the currently selected Torque profile, not a profile list or setter. */
    private fun syncTorqueProfile(t: ITorqueService, now: Long): Boolean {
        if (now - torqueProfileCheckedMs in 0L until 2_000L) return profileSelectionReady
        torqueProfileCheckedMs = now
        val name = runCatching { t.getVehicleProfileInformation()?.firstOrNull()?.trim() }
            .onFailure { FsLog.warn(TAG, "torqueProfile", "Torque selected profile unavailable: ${it.javaClass.simpleName}") }
            .getOrNull()
        if (name.isNullOrEmpty()) {
            FsLog.warn(TAG, "torqueProfileEmpty", "Torque selected profile is blank; recording waits for a profile")
            if (profileSelectionReady || identityReady) {
                closeSession()
                invalidateVehicleOutputs(t)
            }
            profileSelectionReady = false
            identityReady = false
            identity = null
            observedIdentity = Fingerprint.Fp(); connectionMode09Known = false
            quarantineDir = null; quarantineObs = null; quarantineSuggest = null; quarantineConfirm = null
            clearPendingIdentity()
            return false
        }
        if (profileSelectionReady && name == selectedTorqueProfile) return true
        // Close under the OLD recording identity before selecting or creating any other profile.
        closeSession()
        invalidateVehicleOutputs(t)
        identity = null
        identityReady = false
        observedIdentity = Fingerprint.Fp()
        connectionMode09Known = false
        quarantineDir = null; quarantineObs = null; quarantineSuggest = null; quarantineConfirm = null
        clearPendingIdentity()
        fuelDetect = null
        fuelDetectTried = false
        detectQueue = null
        driveSourcesLogged = false
        selectedTorqueProfile = name
        selectProfile(name)
        profileSelectionReady = true
        return true
    }

    private fun selectProfile(torqueName: String) {
        if (identity != null) return
        if (tanks.values.any { it.sessions.isOpen }) return
        // VIN is not passed here: it is stored only via the fingerprint, after the identity decision,
        // so a wrong vehicle's VIN can never be stamped onto this profile.
        val sel = ProfileStore.select(filesDir, null, torqueName)
        val next = sel.id
        val note = sel.vinNote
        if (sel.created) FsLog.i(TAG, "profile created $next for Torque profile '$torqueName'")
        if (note.isNotBlank()) FsLog.w(TAG, "profile $next: identity mismatch")
        val gapFile = File(ProfileStore.profileDir(filesDir, next), "gap.txt")
        val gap = if (gapFile.isFile) gapFile.readText() else ""
        File(filesDir, "profile-notice.txt").writeText(if (note.isBlank()) gap else note)
        // ECU identity is read one bus command per poll tick (never a burst), then decided in finishIdentity().
        // Sessions wait for the decision (ensureActiveSession), so data never lands in the wrong profile.
        val stored = Fingerprint.loadAll(filesDir)[next]
        identity = IdentityRead(next, torqueName, stored, freshProfile = next.takeIf { sel.created })
        FsLog.i(TAG, "identity read queued for $next (firm=${stored?.firm == true}, steps=${identity!!.queue.size})")
    }

    /**
     * Paced ECU identity read: at most one sendCommandGetResponse per poll tick, total capped at
     * IDENTITY_MAX_MS. Fresh support blocks follow advertised continuation bits; a saved
     * fingerprint never supplies another physical vehicle's diagnostic capabilities.
     */
    private inner class IdentityRead(val next: String, val torqueName: String?, stored: Fingerprint.Fp?,
        var checkOnly: Boolean = false, val freshProfile: String? = null) {
        val startMs = System.currentTimeMillis()
        private val startMono = SystemClock.elapsedRealtime()
        val evidenceId = "$startMs-${java.util.UUID.randomUUID()}"
        val queue = ArrayDeque<String>()
        val bitmaps = LinkedHashMap<String, String>()
        val evidence = org.json.JSONArray()
        val expectedVin = if (quarantineDir == null) stored?.vin?.uppercase() else null
        val previousProbe = stored?.probes?.get("ford2216C1")
        var vin: String? = null
        private var initialVin: String? = null
        private var confirmingVin = false
        private var needsFinalVin = !checkOnly
        var retired = false
        var mode09Known = false
        var probe: String? = null
        var probeRaw: List<String>? = null
        /** Comparison PIDs already re-asked once after an unusable reply (matching needs them, not a timeout). */
        private val retried = HashSet<String>()
        init { queue.addAll(if (checkOnly) listOf("0902") else identityQueue(false, null, null)) }

        fun step(t: ITorqueService): Boolean {
            if (SystemClock.elapsedRealtime() - startMono > IDENTITY_MAX_MS) {
                FsLog.w(TAG, "identity read capped; ${queue.size} steps skipped")
                queue.clear()
                vin = null; bitmaps.clear() // incomplete identity bracket never authorizes a profile
                return true
            }
            val c = queue.pollFirst() ?: return true
            if (runCatching { t.isConnectedToECU }.getOrNull() != true) { retired = true; queue.clear(); return true }
            val header = identityHeader(c, runCatching { t.protocolName }.getOrNull())
            val started = System.currentTimeMillis()
            val r = runCatching { t.sendCommandGetResponse(header, c) }.getOrNull()?.toList()
            // Private files only: never place full VIN/replies in FsLog, logcat or a notification.
            evidence.put(org.json.JSONObject().put("pid", c).put("t", started).put("replyMs", System.currentTimeMillis())
                .put("header", header).put("rawFrames", org.json.JSONArray(r.orEmpty().filter {
                    it.length <= 512 && it.matches(Regex("[0-9A-Fa-f :\\r\\n]+")) }.take(64)))
                .put("replyLimitExceeded", r.orEmpty().size > 64 || r.orEmpty().any { it.length > 512 }))
            val sameConnection = identity === this && torque === t && runCatching {
                t.isConnectedToECU && t.getVehicleProfileInformation()?.firstOrNull()?.trim() == torqueName
            }.getOrDefault(false)
            if (!sameConnection) { retired = true; vin = null; bitmaps.clear(); queue.clear(); return true }
            when (c) {
                "0902" -> {
                    mode09Known = mode09Known || Mode09Replies.messages(r, "02").isNotEmpty()
                    val readVin = ProfileStore.parseVin(r)
                    if (confirmingVin) {
                        if (readVin != initialVin) { vin = null; bitmaps.clear() } else vin = readVin
                    } else {
                        vin = readVin
                        initialVin = readVin
                    }
                    if (checkOnly && (vin != expectedVin || (quarantineDir != null && vin != null))) {
                        // End the old owner's recording before capability discovery or any new sample.
                        closeSession()
                        invalidateVehicleOutputs(t)
                        identityReady = false
                        checkOnly = false
                        needsFinalVin = true
                        queue.add("0100")
                    }
                }
                "2216C1" -> {
                    val reply = r?.joinToString(" ")?.uppercase()?.replace(" ", "")
                    // One unanswered probe is asked again before it can count as "no" (a matching gate).
                    if (reply?.contains("6216C1") != true && retried.add(c) && queue.size < 12) queue.addFirst(c)
                    else { probe = reply; probeRaw = r }
                }
                else -> if (c in Fingerprint.BITMAP_PIDS) {
                    val bm = Fingerprint.parseBitmap(r, c)
                    if (bm != null) {
                        bitmaps[c] = bm
                        val nextPid = Fingerprint.BITMAP_PIDS.getOrNull(Fingerprint.BITMAP_PIDS.indexOf(c) + 1)
                        if ((bm.toLong(16) and 1L) != 0L && nextPid != null && queue.size < 12) queue.addFirst(nextPid)
                    } else if (retried.add(c) && queue.size < 12) {
                        // The fingerprint compares these blocks: ask once more instead of deciding without them.
                        queue.addFirst(c)
                    }
                    // Preserve the existing Ford probe only on its known J1850 protocol, never
                    // blindly send a manufacturer command to a newly encountered CAN vehicle.
                    // A VIN-less vehicle is always probed: the 16C1 result is a matching gate.
                    if (c == "0100" && (previousProbe != "ok" || vin == null) && probe == null && "2216C1" !in queue &&
                        runCatching { t.protocolName }.getOrNull()?.contains("J1850 PWM") == true)
                        queue.add("2216C1")
                }
            }
            // Preserve partial observations too if the process ends before the decision.
            persistIdentityEvidence(Quarantine.dir(filesDir, startMs), this, obs(t))
            if (queue.isEmpty() && needsFinalVin && !confirmingVin) {
                confirmingVin = true
                queue.add("0902") // bracket fresh capabilities with consistent physical identity
            }
            return queue.isEmpty()
        }

        fun obs(t: ITorqueService): Fingerprint.Fp = Fingerprint.Fp(
            vin = vin, protocol = runCatching { t.protocolName }.getOrNull()?.takeIf { it.isNotBlank() },
            bitmaps = bitmaps.toMap(), probes = probe?.let {
                if (it.contains("6216C1")) mapOf("ford2216C1" to "ok")
                else mapOf("ford2216C1" to "no", "ford2216C1Reply" to Fingerprint.replyHex(probeRaw))
            } ?: emptyMap())
    }

    private var identity: IdentityRead? = null

    /** Called once per poll tick while connected; finishes the identity decision when the paced read is done. */
    private fun pumpIdentity(t: ITorqueService) {
        val r = identity ?: return
        if (!r.step(t)) return
        if (r.retired) {
            closeSession()
            persistIdentityEvidence(Quarantine.dir(filesDir, r.startMs), r, Fingerprint.Fp())
            identity = null
            identityReady = false
            observedIdentity = Fingerprint.Fp()
            nextIdentityCheckMs = SystemClock.elapsedRealtime() + 30_000L
            return
        }
        // A successful monitor of the SAME VIN keeps the existing session/smoothing/ledger.
        if (r.checkOnly && r.vin == r.expectedVin) {
            identity = null
            nextIdentityCheckMs = SystemClock.elapsedRealtime() + if (quarantineDir == null) 60_000L else 30_000L
            persistIdentityEvidence(quarantineDir ?: ProfileStore.profileDir(filesDir, profileId), r, r.obs(t))
            return
        }
        try {
            finishIdentity(t, r)
        } catch (e: Exception) {
            identityReady = false // IO/selection failure must not fall through to the old vehicle
            FsLog.warn(TAG, "identityResolve", "vehicle identity unresolved: ${e.javaClass.simpleName}")
        } finally {
            identity = null
            nextIdentityCheckMs = SystemClock.elapsedRealtime() + if (identityReady && quarantineDir == null) 60_000L else 30_000L
        }
    }

    private fun persistIdentityEvidence(dir: File, r: IdentityRead, obs: Fingerprint.Fp) {
        try {
            val data = org.json.JSONObject().put("v", 1).put("t", r.startMs).put("observed", org.json.JSONObject(Fingerprint.toJson(obs)))
                .put("responses", r.evidence).put("retired", r.retired).put("source", "Torque.sendCommandGetResponse")
            val file = File(dir, "identity/${r.evidenceId}.json")
            file.parentFile?.mkdirs()
            AtomicFile.write(file, data.toString())
        } catch (e: Exception) {
            FsLog.warn(TAG, "identityEvidence", "private identity evidence write failed: ${e.javaClass.simpleName}")
        }
    }

    private fun finishIdentity(t: ITorqueService, r: IdentityRead) {
        val obs = r.obs(t)
        val fps = Fingerprint.loadAll(filesDir)
        val hits = fps.filterValues { obs.vin != null && it.vin?.equals(obs.vin, ignoreCase = true) == true }.keys
        val target = if (obs.vin != null && hits.size <= 1)
            ProfileStore.physicalProfile(filesDir, obs.vin, r.torqueName, r.freshProfile, inheritTankFrom = r.next) else r.next
        val pending = obs.vin == null || hits.size > 1
        // VIN-less: saved VIN-less profiles this connection's fingerprint cannot rule out.
        val vinless = if (pending && obs.vin == null) Fingerprint.vinlessCandidates(obs, fps) else emptyList()
        val wasQ = quarantineDir
        val nextQ = if (pending) wasQ ?: Quarantine.dir(filesDir, r.startMs) else null
        val changed = target != profileId || wasQ != nextQ || !identityReady ||
            observedIdentity.bitmaps != obs.bitmaps || observedIdentity.protocol != obs.protocol
        if (changed) {
            closeSession() // previous profile/quarantine still owns every open writer and baseline
            invalidateVehicleOutputs(t)
        }
        quarantineDir = nextQ
        // A VIN-less vehicle is used automatically only after its fresh live level agrees (autoMatch);
        // otherwise the user confirms it for THIS connection. Names/bitmaps alone never decide it.
        quarantineSuggest = when {
            !pending -> null
            hits.isNotEmpty() -> hits.sorted().first()
            vinless.size == 1 -> vinless.single()
            vinless.size > 1 && r.next !in vinless -> vinless.first()
            else -> r.next
        }
        if (changed) quarantineConfirm = null
        quarantineObs = if (pending) obs else null
        observedIdentity = obs // ONLY this connection's capabilities, never another car's saved bitmap
        connectionMode09Known = r.mode09Known
        if (!pending) {
            Fingerprint.save(ProfileStore.profileDir(filesDir, target), Fingerprint.merge(Fingerprint.load(ProfileStore.profileDir(filesDir, target)), obs))
        }
        persistIdentityEvidence(nextQ ?: ProfileStore.profileDir(filesDir, target), r, obs)
        FsLog.i(TAG, "physical identity ${if (pending) "pending" else "resolved"} profile=$target obs=${Fingerprint.redacted(obs)}")
        if (!pending) {
            clearPendingIdentity()
        } else if (wasQ == null || changed) {
            clearPendingIdentity()
            pendingDisplay = if (vinless.size == 1) vinless.single() else r.next
            planPendingIdentity(r, obs, fps, hits.sorted(), vinless)
        }
        if (!pending && target != r.next) {
            notifyIdentity(S("Recording to %1\$s", profileName(target)),
                S("This vehicle is %1\$s (%2\$s), but Torque's profile is '%3\$s'. Data goes to %1\$s; tap to open Torque and switch profiles.",
                    profileName(target), "VIN", r.torqueName.orEmpty()), emptyList())
        }
        profileId = target
        ProfileStore.setActiveId(filesDir, target)
        identityReady = true
        // Config/ledger load only after the final physical owner is installed. No sample is
        // taken until pumpIdentity returns to the next poll tick.
        identity = null
        prefsDirty = true
        reloadCfgIfDirty()
        if (changed) {
            resetVehicleRuntime()
            if (!pending) queueIdentityRollups(target)
        }
    }

    private fun resetVehicleRuntime() {
        priorCache.clear(); bandCache = null; kalCache = null
        pidNames = emptyMap(); ecuFuelResolved = false
        ecuLevelName = ECU_FUEL_NAME; ecuLevelPid = null
        suppressFillUntilMs = 0L
        fordPid = null; speedPid = null; fuelUsedPid = null; galHrPid = null; galMinPid = null; mpgPid = null
        mpgUnit = ""; galHrUnit = ""; galMinUnit = ""; sessionFlowHrUnit = null; sessionFlowMinUnit = null
        lastRawPct = null; lastLiveLine = null; loggedFlowUnit = false; logged012fWrite = false
        driveSourcesLogged = false; fuelDetect = null; fuelDetectTried = false; detectQueue = null
        lastActive = FixFuelPrefs.ACTIVE_TANK1
        tanks.clear()
        loadLevelScale()
        tanks[1] = makeRuntime(1)
        ensureRuntimes()
    }

    /** Tank 1 lives in the profile directory. Later tanks are integer subdirectories of tanks/. */
    private fun kalmanTankDirs(profile: String): List<Pair<Int, File>> {
        val out = ArrayList<Pair<Int, File>>()
        out.add(1 to ProfileStore.tankDir(filesDir, profile, 1))
        val extra = File(ProfileStore.profileDir(filesDir, profile), "tanks").listFiles().orEmpty()
            .mapNotNull { d -> if (!d.isDirectory) null else d.name.toIntOrNull()?.let { n -> n to d } }
            .sortedBy { it.first }
        out.addAll(extra)
        return out
    }

    /** Native library, then a stale state file, then a stale model. A load failure moves neither. */
    private fun asideStaleKalman(profile: String, tank: Int, dir: File): Boolean {
        var stale = false
        try {
            if (Kalman.quarantineState(dir)) stale = true
        } catch (e: Exception) {
            stale = true
            FsLog.w(TAG, "kalman state move failed profile=$profile tank=$tank: ${e.javaClass.simpleName}: ${e.message}")
            return stale
        }
        val model = Kalman.staleModel(dir)
        if (model != null) {
            stale = true
            try {
                val dst = Kalman.moveModel(dir, model.suffix)
                if (dst != null) FsLog.i(TAG, "kalman model $profile tank$tank ${model.detail}; kept as ${dst.name}")
            } catch (e: Exception) {
                FsLog.w(TAG, "kalman model move failed profile=$profile tank=$tank: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return stale
    }

    /**
     * One rollup-executor job per tank directory. The version check runs in the job.
     * A mismatch calls [ProfileStore.rollup] and stops. A match refits only a stale Kalman file.
     * The rebuilding notice is posted at most once, and only from that refit.
     */
    private fun queueIdentityRollups(target: String) {
        val noticed = booleanArrayOf(false)
        for ((tank, dir) in kalmanTankDirs(target)) {
            val nameplate = nameplateFor(tank)
            submitRollup("identity tank$tank", {
                if (ProfileStore.versionChanged(dir)) {
                    ProfileStore.rollup(dir, nameplate, openSessions)
                } else if (asideStaleKalman(target, tank, dir)) {
                    if (!noticed[0]) {
                        noticed[0] = true
                        val who = profileName(target)
                        notifySimple(S("Map refitting"), S("Fuel Smoothing is refitting a map for %1\$s. That fit reads 1:1 until its refit finishes.", who))
                        FsLog.i(TAG, "map refit notice for $target")
                    }
                    ProfileStore.DataLock.lock()
                    try {
                        val sessions = FuelV2.loadSessions(File(dir, "raw"), openSessions)
                        if (sessions.isEmpty()) {
                            FsLog.i(TAG, "kalman refit (stale) tank$tank: no sessions")
                        } else {
                            Kalman.update(dir, sessions, null)
                            if (Kalman.loadModel(dir) == null) FsLog.w(TAG, "kalman refit (stale) tank$tank failed")
                        }
                    } finally {
                        ProfileStore.DataLock.unlock()
                    }
                }
            }) {
                priorCache.remove(tank)
            }
        }
    }

    private fun profileName(id: String): String = ProfileStore.profileField(filesDir, id, "name")
        ?: ProfileStore.profileField(filesDir, id, "torqueProfileName") ?: id

    private fun confirmQuarantine(target: String?, automatic: org.json.JSONObject? = null) {
        if (quarantineDir == null || target == null) {
            FsLog.i(TAG, "quarantine confirm ignored (no quarantine)")
            return
        }
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(now))
        val id = Quarantine.confirmTarget(filesDir, target, now, S("Vehicle %1\$s", day))
        val observation = quarantineObs ?: Fingerprint.Fp()
        val savedVin = Fingerprint.loadAll(filesDir)[id]?.vin
        if (observation.vin != null && savedVin != null && !observation.vin.equals(savedVin, ignoreCase = true)) {
            FsLog.w(TAG, "quarantine confirmation rejected: different physical VIN")
            return
        }
        val evidenceDir = quarantineDir ?: return
        quarantineConfirm = id
        closeSession() // existing explicit Keep action, never an automatic migration of held evidence
        torque?.let { invalidateVehicleOutputs(it) }
        // Preserve why a VIN-less profile was trusted, privately and only for this connection.
        val evidenceFile = File(evidenceDir, if (automatic != null) "match-confirmation.json" else "manual-confirmation.json")
        evidenceFile.parentFile?.mkdirs()
        AtomicFile.write(evidenceFile, (automatic ?: org.json.JSONObject()).put("t", now).put("profileId", id)
            .put("resolution", if (automatic != null) "automatic_fingerprint_match_for_connection"
                else "explicit_user_confirmation_for_connection").toString())
        val torqueName = selectedTorqueProfile
        quarantineDir = null; quarantineObs = null; quarantineSuggest = null; quarantineConfirm = null
        clearPendingIdentity()
        identity = null
        observedIdentity = observation
        profileId = id
        ProfileStore.setActiveId(filesDir, id)
        val dir = ProfileStore.profileDir(filesDir, id)
        if (Fingerprint.load(dir).empty) Fingerprint.save(dir, observation)
        identityReady = true
        nextIdentityCheckMs = SystemClock.elapsedRealtime() + 60_000L
        prefsDirty = true
        reloadCfgIfDirty()
        resetVehicleRuntime()
        if (automatic != null) {
            FsLog.i(TAG, "vehicle matched automatically for this connection -> $id")
            return
        }
        FsLog.i(TAG, "vehicle confirmed by user for this connection -> $id")
        val torqueMatches = torqueName != null &&
            ProfileStore.profileField(filesDir, id, "torqueProfileName")?.equals(torqueName, ignoreCase = true) == true
        if (torqueMatches) {
            notifyIdentity(S("Keeping drive data"), S("This drive will be saved to %1\$s.", profileName(id)), emptyList())
        } else notifyIdentity(
            S("Keeping drive data"),
            S("This drive will be saved to %1\$s. Torque's plugin API can't create or switch Torque profiles: in Torque, create/select a vehicle profile named '%2\$s'.", profileName(id), profileName(id)),
            emptyList(),
        )
    }

    /**
     * Decide how a pending identity is resolved. Auto-use only a VIN-less vehicle with exactly one surviving
     * VIN-less candidate that Torque already selects and whose last recorded level is known; the live level
     * check then runs in [autoMatchStep]. Everything else is a loud confirmation.
     */
    private fun planPendingIdentity(r: IdentityRead, obs: Fingerprint.Fp, fps: Map<String, Fingerprint.Fp>,
        hits: List<String>, vinless: List<String>) {
        val sel = r.next
        val now = SystemClock.elapsedRealtime()
        val held = S("Live smoothed fuel is shown now. Nothing is saved to a profile until you choose. If you do not choose before the drive ends, the data is kept unassigned and is not used for learning until you assign it on the setup page.")
        fun ask(title: String, reason: String, choices: List<String>, delayMs: Long = 0L) {
            // Every matching profile is listed (no cap) and "none, it's new" is always last.
            prompt = IdentityPending.Pending(System.currentTimeMillis(), title, "$reason $held",
                (choices.filter { it != NEW_PROFILE }.distinct() + NEW_PROFILE).map { IdentityPending.Choice(it, choiceLabel(it)) })
            promptAtMs = now + delayMs
            promptPosted = false
        }
        val one = vinless.singleOrNull()
        when {
            hits.size > 1 -> ask(S("Which vehicle is this?"), S("More than one saved profile has this vehicle's VIN."), hits + NEW_PROFILE)
            one != null && one == sel -> {
                val last = lastRecordedLevel(sel)
                if (last == null) {
                    ask(S("Confirm this is %1\$s", profileName(sel)),
                        S("This vehicle matches %1\$s but sent no VIN, and %1\$s has no recorded fuel level to compare.", profileName(sel)),
                        listOf(sel, NEW_PROFILE))
                } else {
                    autoMatch = sel; autoMatchLast = last
                    autoMatchUntilMs = now + AUTO_MATCH_MS
                    autoMatchPrevUpd = null; autoMatchReads.clear(); autoMatchAdopt = false
                    autoMatchProbeTicks = 0; autoMatchFreshByUpd = 0; autoMatchFreshByBus = 0
                    FsLog.i(TAG, "identity auto-match candidate $sel (protocol/bitmaps/Torque profile agree; last level ${"%.1f".format(last)}); probing live level")
                }
            }
            one != null -> ask(S("Is this %1\$s?", profileName(one)),
                S("This vehicle matches %1\$s, but Torque's selected profile is '%2\$s'.", profileName(one), r.torqueName.orEmpty()),
                listOf(one, NEW_PROFILE))
            vinless.size > 1 -> ask(S("Which vehicle is this?"), S("This vehicle sent no VIN and more than one saved profile could match."),
                vinless + NEW_PROFILE)
            fps[sel]?.vin != null -> ask(S("Confirm this is %1\$s", profileName(sel)),
                S("This vehicle sent no VIN, but %1\$s has a saved VIN.", profileName(sel)), listOf(sel, NEW_PROFILE), VIN_LOSS_GRACE_MS)
            else -> ask(S("Confirm this is %1\$s", profileName(sel)),
                S("No saved profile's fingerprint matches this vehicle (it sent no VIN)."), listOf(sel, NEW_PROFILE))
        }
        FsLog.i(TAG, "identity pending plan: auto=${autoMatch ?: "-"} prompt=${prompt?.choices?.map { it.target } ?: "-"} candidates=$vinless hits=${hits.size} protocol=${obs.protocol ?: "-"}")
    }

    /**
     * Fresh live level for an auto-match candidate. A bus-source level is a fresh reply every tick. A Torque-PID
     * level counts only when its update time advances, or when this tick's direct bus reply equals the raw reply
     * behind Torque's cached value ([probeAutoMatchLevel]). A leftover cached value never counts.
     */
    private fun autoMatchStep(raw: Double, upd: Long?, busFresh: Boolean) {
        val cand = autoMatch ?: return
        if (autoMatchAdopt) return
        if (upd != null) {
            val advanced = autoMatchPrevUpd != null && upd > 0L && upd != autoMatchPrevUpd
            autoMatchPrevUpd = upd
            if (!advanced && !busFresh) return
            if (advanced) autoMatchFreshByUpd++ else autoMatchFreshByBus++
        }
        if (!raw.isFinite()) return
        autoMatchReads.add(raw)
        if (autoMatchReads.size < AUTO_MATCH_READS) return
        val m = autoMatchReads.sorted()[autoMatchReads.size / 2]
        val last = autoMatchLast ?: return
        val d = m - last
        val tol = Fingerprint.FUEL_MATCH_PTS * levelUnit(1)
        FsLog.i(TAG, "identity auto-match $cand live=${"%.1f".format(m)} last=${"%.1f".format(last)} Δ=${"%.1f".format(d)} tol=$tol " +
            "fresh(upd=$autoMatchFreshByUpd bus=$autoMatchFreshByBus) probeTicks=$autoMatchProbeTicks")
        if (kotlin.math.abs(d) <= tol) {
            autoMatchAdopt = true // applied at the start of the next tick, outside sampling
            return
        }
        failAutoMatch(cand, if (d > 0) S("Its fuel level (%1\$.0f %%) is higher than %2\$s's last recorded level (%3\$.0f %%). Was it filled?", m, profileName(cand), last)
            else S("Its fuel level (%1\$.0f %%) does not match %2\$s's last recorded level (%3\$.0f %%).", m, profileName(cand), last))
    }

    /**
     * Active fuel probe while a VIN-less auto-match waits for fresh levels (pending only, nothing is learned).
     * Only the Ford 16C1 gauge read by Torque PID name needs this; bus sources already send their command
     * every tick. At most one plugin-initiated bus request per tick, alternating:
     *  - even ticks: Torque's documented refresh request getValueForPid(0x2216C1, triggersDataRefresh = true),
     *    so Torque re-reads [FORD]Fuel Level and its update time advances (the value returned is ignored);
     *  - odd ticks (J1850 PWM only, the header the identity probe already uses): a direct 2216C1 read. It counts
     *    as fresh only if its reply bytes equal the raw reply behind Torque's cached value (getPIDRawResponse),
     *    i.e. the cached decoded level is what this fresh reply decodes to. The plugin never decodes 16C1 itself,
     *    because the [FORD]Fuel Level equation is the user's Torque extra-PID definition.
     * A custom long-name PID has no known command, so it stays on Torque's own polling plus the timeout.
     */
    /**
     * Ask Torque to re-read a named fuel-level PID (triggersDataRefresh). Used when the live level
     * has stayed put while fuel used grew enough to prove the cached value is stale. One request;
     * the new value is picked up on a later tick.
     */
    private fun requestLevelRefresh(t: ITorqueService, sourceId: String, pid: String?) {
        val numeric = pid?.substringBefore(',')?.trim()?.let { token ->
            token.toLongOrNull() ?: token.toLongOrNull(16)
        } ?: if (sourceId == "ford_16c1") Pids.FORD_FUEL_STORED.toLong() else null
        if (numeric == null) return
        runCatching { t.getValueForPid(numeric, true) }
            .onFailure { FsLog.warn(TAG, "staleRefresh", "Torque refresh request failed: ${it.javaClass.simpleName}") }
    }

    private fun probeAutoMatchLevel(t: ITorqueService, sourceId: String, pid: String): Boolean {
        if (sourceId != "ford_16c1" || pidNames[Pids.FORD_FUEL_NAME] != pid) return false
        val tick = autoMatchProbeTicks++
        if (tick % 2 == 0) {
            runCatching { t.getValueForPid(Pids.FORD_FUEL_STORED.toLong(), true) }
                .onFailure { FsLog.warn(TAG, "autoMatchRefresh", "Torque refresh request failed: ${it.javaClass.simpleName}") }
            return false
        }
        val protocol = runCatching { t.protocolName }.getOrNull()
        if (protocol?.contains("J1850 PWM") != true) return false
        val fresh = runCatching { t.sendCommandGetResponse(identityHeader(FORD_16C1_CMD, protocol), FORD_16C1_CMD) }.getOrNull()
        val cached = runCatching { t.getPIDRawResponse(FORD_16C1_CMD) }.getOrNull()
        val a = ford16c1Reply(fresh)
        return a != null && a == ford16c1Reply(cached)
    }

    /** Bytes after the 6216C1 positive-reply marker (header-independent), or null when there is no such reply. */
    private fun ford16c1Reply(lines: Array<String>?): String? = lines.orEmpty().firstNotNullOfOrNull { line ->
        val hex = line.replace(" ", "").uppercase()
        if (!hex.matches(Regex("[0-9A-F]+"))) return@firstNotNullOfOrNull null
        val i = hex.indexOf("6216C1")
        if (i < 0) null else hex.substring(i + 6).takeIf { it.length >= 2 }
    }

    private fun failAutoMatch(cand: String, reason: String) {
        autoMatch = null; autoMatchAdopt = false; autoMatchReads.clear()
        val held = S("Live smoothed fuel is shown now. Nothing is saved to a profile until you choose. If you do not choose before the drive ends, the data is kept unassigned and is not used for learning until you assign it on the setup page.")
        prompt = IdentityPending.Pending(System.currentTimeMillis(), S("Confirm this is %1\$s", profileName(cand)), "$reason $held",
            listOf(cand, NEW_PROFILE).map { IdentityPending.Choice(it, choiceLabel(it)) })
        promptAtMs = 0L
        promptPosted = false
        FsLog.i(TAG, "identity auto-match $cand not confirmed by fuel level; asking")
    }

    /** Pending tick: adopt an agreed auto-match, time it out, or post a due prompt. True = tick consumed. */
    private fun tickPendingIdentity(): Boolean {
        val cand = autoMatch
        if (cand != null && autoMatchAdopt) {
            val reads = autoMatchReads.sorted()
            val ev = org.json.JSONObject().put("lastLevel", autoMatchLast).put("liveMedian", reads[reads.size / 2])
                .put("reads", reads.size).put("protocol", observedIdentity.protocol ?: org.json.JSONObject.NULL)
                .put("torqueProfile", selectedTorqueProfile ?: org.json.JSONObject.NULL)
            confirmQuarantine(cand, automatic = ev)
            return true
        }
        val mono = SystemClock.elapsedRealtime()
        if (cand != null && mono > autoMatchUntilMs) {
            FsLog.i(TAG, "identity auto-match $cand timed out: reads=${autoMatchReads.size} fresh(upd=$autoMatchFreshByUpd bus=$autoMatchFreshByBus) probeTicks=$autoMatchProbeTicks")
            failAutoMatch(cand, S("Its fuel level could not be read to compare with %1\$s.", profileName(cand)))
        }
        val p = prompt
        if (p != null && !promptPosted && mono >= promptAtMs) {
            promptPosted = true
            IdentityPending.write(filesDir, p)
            notifyIdentity(p.title, p.text, p.choices.map { it.target })
            updatePresence(statusText())
        }
        return false
    }

    private fun clearPendingIdentity() {
        if (promptPosted) notificationManager()?.cancel(IDENTITY_NOTIF_ID)
        IdentityPending.clear(filesDir)
        prompt = null; promptPosted = false; promptAtMs = 0L
        autoMatch = null; autoMatchLast = null; autoMatchAdopt = false; autoMatchReads.clear(); autoMatchPrevUpd = null
        autoMatchProbeTicks = 0; autoMatchFreshByUpd = 0; autoMatchFreshByBus = 0
        pendingDisplay = null
    }

    /** Last trusted smoothed tank-1 level for [profile]. Frozen or stale samples are skipped. */
    private fun lastRecordedLevel(profile: String): Double? {
        val src = FuelDetect.load(filesDir, profile).first
        return LevelTrust.lastTrustedLevel(File(ProfileStore.tankDir(filesDir, profile, 1), "raw"), src)
    }

    /** One label for every identity choice surface (popup, banner, notification). */
    private fun choiceLabel(target: String): String =
        if (target == NEW_PROFILE) S("none, it's new") else S("Use %1\$s", profileName(target))

    /**
     * Torque's plugin API (ITorqueService) cannot switch or create vehicle profiles and has no popup call,
     * so the notification opens Torque; the action button only decides where held data goes.
     */
    private fun notifyIdentity(title: String, text: String, choices: List<String>) {
        val nm = notificationManager() ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val torque = packageManager.getLaunchIntentForPackage("org.prowl.torque")
        val ask = choices.isNotEmpty()
        // Android shows about 3 action buttons. A longer list is never cut: the notification opens the setup
        // page, whose popup and banner list every matching profile and "none, it's new".
        val actions = if (choices.size <= MAX_NOTIF_ACTIONS) choices else emptyList()
        val body = if (choices.size > MAX_NOTIF_ACTIONS) "$text ${S("Tap to choose from the full list.")}" else text
        // A decision is loud (heads-up channel, stays until answered) and opens the setup page's prompt.
        val channel = if (ask) CHANNEL_IDENTITY else CHANNEL_ID
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, channel) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(title).setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body)).setAutoCancel(!ask)
        if (ask) {
            b.setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_REMINDER)
            if (Build.VERSION.SDK_INT < 26) {
                @Suppress("DEPRECATION")
                b.setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
            }
            b.setContentIntent(PendingIntent.getActivity(this, 40, Intent(this, PluginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), flags))
        } else if (torque != null) b.setContentIntent(PendingIntent.getActivity(this, 41, torque, flags))
        for ((k, target) in actions.withIndex()) {
            val i = Intent(this, PluginService::class.java).setAction(ACTION_Q_CONFIRM).putExtra(EXTRA_TARGET, target)
            val label = choiceLabel(target)
            @Suppress("DEPRECATION")
            b.addAction(android.R.drawable.ic_menu_save, label, PendingIntent.getService(this, 42 + k, i, flags))
        }
        nm.notify(IDENTITY_NOTIF_ID, b.build())
        FsLog.i(TAG, "identity notice: $title | $text | choices=$choices actions=${actions.size}")
    }

    private fun registerPluginPids(t: ITorqueService) {
        runCatching { sendExtraPidRows(t) }
            .onFailure { FsLog.w(TAG, "sendPIDDataV2: ${it.message}") }
        runCatching { logPluginPidInventory(t) }
            .onFailure { FsLog.w(TAG, "pluginPidInventory: ${it.message}") }
        FsLog.i(TAG, "registered tank1 PIDs extras=${cfg.extras.map { it.id }} volumeUnit=$pidVolumeUnit")
    }

    private fun sendExtraPidRows(t: ITorqueService) {
        // Pin unit and max at registration. sendPIDDataV2 (empty equation, unique ffd0xx ModeAndPID)
        // lists the row in Manage extra PIDs; setPIDInformation then updates that same name-keyed row.
        // A "0" equation used to make Torque import a second 0% entry; the equation stays empty.
        val prefersLiters = Units.prefersLiters(preferredVolumeUnit)
        pidVolumeUnit = Pids.volumeUnit(prefersLiters)
        pidVolumeMax = Pids.volumeMax(prefersLiters)
        val rows = Pids.allOutputs(cfg.extras.map { it.id })
        val names = rows.map { it.name }.toTypedArray()
        val shorts = rows.map { it.shortName }.toTypedArray()
        val modes = rows.map { it.modeAndPid }.toTypedArray()
        val units = rows.map { pluginUnit(it) }.toTypedArray()
        val mins = FloatArray(rows.size) { 0f }
        val maxs = rows.map { pluginMax(it) }.toFloatArray()
        val blank = Array(rows.size) { "" }
        val eq = Array(rows.size) { "" }
        val diag = arrayOfNulls<String>(rows.size)
        val ok = t.sendPIDDataV2(
            BuildConfig.APPLICATION_ID,
            names,
            shorts,
            modes,
            eq,
            mins,
            maxs,
            units,
            blank,
            diag,
            diag,
        )
        FsLog.i(TAG, "sendPIDDataV2 n=${rows.size} ok=$ok remainUnit=$pidVolumeUnit")
    }

    /** Torque's list of [FuelSmooth] rows after bind: id, long name, short, unit. Twins show as repeated names. */
    private fun logPluginPidInventory(t: ITorqueService) {
        val all = t.listAllPIDs() ?: return
        val infos = t.getPIDInformation(all) ?: return
        val n = minOf(all.size, infos.size)
        val rows = ArrayList<String>()
        for (i in 0 until n) {
            val info = infos[i] ?: continue
            val parts = info.split(',')
            val name = parts.getOrNull(0)?.trim().orEmpty()
            if (!name.startsWith("[FuelSmooth]") && !name.startsWith("[FixFuel]") && !name.startsWith("[OldFord]")) continue
            val short = parts.getOrNull(1)?.trim().orEmpty()
            val unit = parts.getOrNull(2)?.trim().orEmpty()
            val eq = parts.getOrNull(3)?.trim().orEmpty()
            rows.add("${all[i]} name=$name short=$short unit=$unit eq=${eq.ifEmpty { "empty" }}")
        }
        FsLog.i(TAG, "plugin PID inventory n=${rows.size}: ${rows.joinToString(" | ")}")
    }

    private fun pluginUnit(pid: Pids.PluginPid): String =
        if (pid.kind == Pids.PluginPidKind.PERCENT) "%" else pidVolumeUnit

    private fun pluginMax(pid: Pids.PluginPid): Float =
        if (pid.kind == Pids.PluginPidKind.PERCENT) pid.percentMax else pidVolumeMax

    private fun setPluginPid(t: ITorqueService, pid: Pids.PluginPid, value: Float) {
        setPid(t, pid.name, pid.shortName, pluginUnit(pid), pluginMax(pid), value)
    }

    private fun setPluginPid(t: ITorqueService, name: String, value: Float) {
        val pid = Pids.output(name, cfg.extras.map { it.id }) ?: return
        setPluginPid(t, pid, value)
    }

    private fun publishExtra(t: ITorqueService, id: Int, smoothed: Double, corrected: Double) {
        val names = Pids.extraPidNames(id)
        for (pid in Pids.extraOutputs(id)) {
            val v = when (pid.name) {
                names.smoothed -> smoothed
                names.corrected -> corrected
                else -> Double.NaN
            }
            if (v.isFinite()) setPluginPid(t, pid, v.toFloat())
        }
    }

    private fun invalidateVehicleOutputs(t: ITorqueService) {
        for (pid in Pids.allOutputs(cfg.extras.map { it.id })) {
            setPluginPid(t, pid, Float.NaN)
        }
        // Withdraw only our own previous stock-slot feedback; this is Torque cache metadata,
        // never an ECU write. No numeric previous-car correction survives identity pending.
        // Retired Remaining/DTE names are never written (a NaN write would re-create them).
        if (logged012fWrite && !isFordName(ecuLevelName)) {
            setPid(t, ecuLevelName, "Fuel", "%", 100f, Float.NaN)
            ecuLevelPid?.takeIf { it != ecuLevelName && !isFordName(it) }?.let { setPid(t, it, "Fuel", "%", 100f, Float.NaN) }
        }
        logged012fWrite = false
        lastRawPct = null
        lastLiveLine = null
        FixFuelPrefs.setLive(this, "")
        updatePresence(S("Waiting for fuel source"))
    }

    /**
     * Identity pending: DISPLAY only, from this connection's live gauge: smoothed, and corrected = smoothed
     * (unity). No Remaining/DTE, no old fit or ledger.
     */
    private fun publishPending(t: ITorqueService, rt: TankRt, smoothed: Double) {
        publishSmoothed(t, smoothed)
        val out = unityPublish(smoothed)
        runCatching { publishFit(t, out, Pids.PLUGIN_KALMAN_CORRECTED) }
        runCatching { publishFit(t, out, Pids.PLUGIN_CHUNK_CORRECTED) }
        runCatching { publishFit(t, out, Pids.PLUGIN_BAND_CORRECTED) }
        if (rt.math.smoothed != null) writeStockFuelLevel(t, smoothed, out.correctedPct, Double.NaN, Double.NaN, pending = true)
        updatePresence(statusText())
    }

    /** Unity map: corrected = smoothed. */
    private fun unityPublish(sm: Double): TankPublish = corrOnly(sm, sized = sm.isFinite())

    /** Only the corrected percent is used; Remaining and DTE are retired and stay NaN. */
    private fun corrOnly(corr: Double, sized: Boolean): TankPublish =
        TankPublish(Double.NaN, Double.NaN, corr, 0.0, Double.NaN, Double.NaN, sized = sized)

    private fun publishSmoothed(t: ITorqueService, smoothed: Double) {
        setPluginPid(t, Pids.PLUGIN_SMOOTHED, LevelScale.display(smoothed, levelUnit(1)).toFloat())
    }

    private var bandCache: Triple<Long, DoubleArray, Double>? = null

    /** Band map (model-band.json) for tank 1, reloaded only when the file changes (drive end / fill). */
    private fun bandModel(): Pair<DoubleArray, Double>? {
        if (!trustedVehicle()) return null
        val f = File(ProfileStore.tankDir(filesDir, profileId, 1), BandFit.FILE)
        val m = if (f.isFile) f.lastModified() else -1L
        bandCache?.let { if (it.first == m) return it.second to it.third }
        val loaded = BandFit.load(f.parentFile!!)
        bandCache = loaded?.let { Triple(m, it.first, it.second) }
        return loaded
    }

    /** No band model: corrected percent is the smoothed percent. */
    private fun bandPublish(rt: TankRt): TankPublish {
        val sm = rt.math.smoothed?.takeIf { it.isFinite() }
        val loaded = bandModel()
        if (loaded == null || sm == null) return corrOnly(sm ?: Double.NaN, sized = false)
        val (g, cap) = loaded
        val v = { l: Double -> BandFit.vAt(l.coerceIn(0.0, 100.0), g) }
        return corrOnly(FuelV2.correctedPct(sm, v, cap).takeIf { it.isFinite() } ?: sm, sized = cap > 0.0)
    }

    private var kalCache: Pair<Long, Kalman.Model?>? = null

    private fun kalmanModel(): Kalman.Model? {
        if (!trustedVehicle()) return null
        val f = File(ProfileStore.tankDir(filesDir, profileId, 1), Kalman.MODEL)
        val m = if (f.isFile) f.lastModified() else -1L
        kalCache?.let { if (it.first == m) return it.second }
        val loaded = Kalman.loadModel(f.parentFile!!)
        kalCache = m to loaded
        return loaded
    }

    /** Kalman starts as a unity map: until its fit has its own size, corrected percent is the smoothed percent. */
    private fun kalmanPublish(rt: TankRt): TankPublish {
        val sm = rt.math.smoothed?.takeIf { it.isFinite() } ?: return corrOnly(Double.NaN, sized = false)
        val km = kalmanModel()
        if (km == null || !(km.g98 > 0.0)) return unityPublish(sm)
        return corrOnly(FuelV2.correctedPct(sm, { km.v(it) }, km.g98).takeIf { it.isFinite() } ?: sm, sized = true)
    }

    private fun publishKalman(t: ITorqueService, b: TankPublish) = publishFit(t, b, Pids.PLUGIN_KALMAN_CORRECTED)

    private fun publishChunk(t: ITorqueService, b: TankPublish) = publishFit(t, b, Pids.PLUGIN_CHUNK_CORRECTED)

    private fun publishBand(t: ITorqueService, b: TankPublish) = publishFit(t, b, Pids.PLUGIN_BAND_CORRECTED)

    /** Corrected percent is written when finite. Remaining and DTE are not written (retired). */
    private fun publishFit(t: ITorqueService, b: TankPublish, correctedName: String) {
        if (b.correctedPct.isFinite()) setPluginPid(t, correctedName, b.correctedPct.toFloat())
    }

    /** Tank size per fit, in the unit pinned at registration. Unwritten PIDs are left alone. */
    private fun publishTankSizes(t: ITorqueService) {
        fun pub(name: String, gal: Double?) {
            if (gal == null || !gal.isFinite() || gal <= 0.0) return
            val vol = if (pidVolumeUnit == "l") (gal * Units.LITERS_PER_GAL).toFloat() else gal.toFloat()
            setPluginPid(t, name, vol)
        }
        pub(Pids.PLUGIN_TANK_CHUNK, priorFor(1).cap)
        pub(Pids.PLUGIN_TANK_BAND, bandModel()?.second)
        pub(Pids.PLUGIN_TANK_KALMAN, kalmanModel()?.g98?.takeIf { it > 0.0 })
    }

    /** [pending]: only this connection's smoothed (unity) value may be fed while identity is unresolved. */
    private fun writeStockFuelLevel(t: ITorqueService, smoothedPct: Double, chunkPct: Double, bandPct: Double?, kalmanPct: Double?,
        pending: Boolean = false) {
        if (!trustedVehicle() && !pending) return
        val corrected = FixFuelPrefs.feedValue(cfg.feed012f, LevelScale.display(smoothedPct, levelUnit(1)), chunkPct, bandPct, kalmanPct) ?: return
        val rt1 = tanks[1]
        if (rt1 != null && (pending || !rt1.ledger.anchored) && (rt1.math.smoothed == null || !corrected.isFinite() || corrected <= 0.0)) {
            // Unanchored with no usable reading: never push a placeholder 0% into Torque's stock slot.
            return
        }
        if (ecuLevelName.equals(Pids.FORD_FUEL_NAME, ignoreCase = true)) {
            FsLog.w(TAG, "skip 012F write: resolved name is Ford extra PID")
            return
        }
        val v = corrected.toFloat()
        setPid(t, ecuLevelName, "Fuel", "%", rmax = 100f, value = v)
        val pid = ecuLevelPid
        if (pid != null && !pid.equals(ecuLevelName, ignoreCase = true) && !isFordName(pid)) {
            setPid(t, pid, "Fuel", "%", rmax = 100f, value = v)
        }
        if (!logged012fWrite) {
            logged012fWrite = true
            FsLog.i(TAG, "write 012F mode=${cfg.feed012f} name=$ecuLevelName pid=${pid ?: "none"} value=$corrected")
        }
    }

    private fun isFordName(s: String): Boolean = s.equals(Pids.FORD_FUEL_NAME, ignoreCase = true)

    private fun isStock012F(pid: String): Boolean {
        val u = pid.replace(",", "").replace(" ", "").uppercase()
        return u == "012F" || u.startsWith("012F")
    }

    private fun setPid(
        t: ITorqueService,
        name: String,
        shortName: String,
        unit: String,
        rmax: Float,
        value: Float,
    ) {
        val info = runCatching { t.setPIDInformation(name, shortName, unit, rmax, 0f, value, null) }
        if (info.isSuccess) return
        FsLog.warn(TAG, "setPIDInformation $name", "setPIDInformation $name: ${info.exceptionOrNull()?.message}")
        runCatching { t.setPIDData(name, shortName, unit, rmax, 0f, value) }
            .onFailure { FsLog.warn(TAG, "setPIDData $name", "setPIDData $name: ${it.message}") }
    }

    /*
     * Tank capacity cannot be written to Torque: storeInProfile() namespaces every key as "plugin_<key>"
     * (the Pixel's .tdv holds both Torque's tankUsed=128.9 and our plugin_tankUsed=85.6, and tankCapacity
     * stayed 132.49), and ITorqueService has no setter for vehicle-profile fields, nor any call to create or
     * switch a vehicle profile. Torque's own remaining/DTE therefore use the tank size typed into Torque times the
     * fuel level we feed onto 012F; the plugin no longer publishes Remaining/DTE PIDs of its own. It writes nothing into the Torque profile
     * (the former plugin_tankUsed / plugin_authoritativePct storeInProfile writes were removed).
     */
    private fun configuredIds(): Set<Int> = setOf(1) + cfg.extras.map { it.id }.toSet()

    private fun isConfigured(id: Int): Boolean = id == 1 || cfg.extras.any { it.id == id }

    private fun resolveStoredActive(): String {
        val id = cfg.activeTank.toIntOrNull()
        return if (id != null && isConfigured(id)) id.toString() else FixFuelPrefs.ACTIVE_TANK1
    }

    private fun resolveActiveTank(t: ITorqueService): String {
        val wanted = if (cfg.activeTank == FixFuelPrefs.ACTIVE_FROM_PID) {
            val want = cfg.selectPidName.trim()
            if (want.isEmpty()) {
                null
            } else {
                val pid = pidNames[want]
                    ?: pidNames.entries.firstOrNull { it.key.equals(want, ignoreCase = true) }?.value
                val v = pid?.let { readDouble(t, it) }
                if (v != null && v.isFinite()) v.roundToInt() else null
            }
        } else {
            cfg.activeTank.toIntOrNull()
        }
        if (wanted != null && isConfigured(wanted)) return wanted.toString()
        val last = lastActive.toIntOrNull()
        if (last != null && isConfigured(last)) return last.toString()
        return FixFuelPrefs.ACTIVE_TANK1
    }

    private fun applyActiveTank(active: String, now: Long) {
        val want = if (isConfigured(active.toIntOrNull() ?: -1)) active else FixFuelPrefs.ACTIVE_TANK1
        if (want == lastActive) return
        FsLog.i(TAG, "active tank $lastActive -> $want (fill-snap ignored ${FILL_SWITCH_DEBOUNCE_MS}ms)")
        closeTankSession(lastActive.toIntOrNull() ?: 1)
        lastActive = want
        suppressFillUntilMs = now + FILL_SWITCH_DEBOUNCE_MS
    }

    private fun ensureActiveSession() {
        if (!profileSelectionReady || identity != null) return
        val id = lastActive.toIntOrNull() ?: 1
        for (tid in tanks.keys.toList()) {
            if (tid != id) closeTankSession(tid)
        }
        openTankSession(id)
    }

    /** Tank 1 from profile.json and each extra tank from extra_tanks, once per drive. */
    private fun logDriveSources() {
        val (src, conf) = FuelDetect.load(filesDir, profileId)
        FsLog.i(TAG, "source=$src confirmed=$conf profile=$profileId")
        val raw = FixFuelPrefs.prefs(this).getString(FixFuelPrefs.KEY_EXTRAS, "[]") ?: "[]"
        for (t in FixFuelPrefs.parseExtras(raw)) FsLog.i(TAG, "tank=${t.id} source=${t.sourceId}")
    }

    private fun openTankSession(id: Int) {
        val rt = tanks[id] ?: return
        if (rt.sessions.isOpen) return
        rt.usedGal = 0.0
        rt.fuelAtFillGal = 0.0
        rt.lastRateMs = null
        rt.burnGate.reset()
        rt.burnWhy = null
        sessionFlowHrUnit = null
        sessionFlowMinUnit = null
        rt.validLevels = 0
        val sid = rt.sessions.open(profileId = profileId, app = BuildConfig.VERSION_NAME)
        try {
            val fp = observedIdentity
            val dir = quarantineDir?.let { Quarantine.rawDir(it, id) } ?: ProfileStore.tankDir(filesDir, profileId, id)
            diagnosticCapture.start(profileId, id, sid, selectedTorqueProfile.orEmpty(), dir,
                fp.protocol, fp.bitmaps, connectionMode09Known, pending = quarantineDir != null)
        } catch (e: Exception) {
            diagnosticCapture.disableAfterFailure(e)
        }
        FsLog.reset()
        refreshOpen()
        if (!driveSourcesLogged) {
            logDriveSources()
            driveSourcesLogged = true
        }
        FsLog.i(TAG, "session open tank=$id $sid files=${rt.sessions.sessionCount()}")
        refreshRecording()
    }

    private fun closeTankSession(id: Int) {
        val rt = tanks[id] ?: return
        if (!rt.sessions.isOpen) return
        diagnosticCapture.end(rt.sessions.sessionId)
        val q = quarantineDir
        if (q != null) {
            // Never write the wrong profile: no ledger, rollup, chunks, model or fills here.
            val closedQ = rt.sessions.close()
            refreshRecording()
            val target = quarantineConfirm
            if (target != null) {
                val moved = Quarantine.resolve(filesDir, q, id, target)
                FsLog.i(TAG, "quarantine close tank=$id samples=${closedQ.size} -> $target files=${moved.map { it.name }}")
                quarantineObs?.let { o ->
                    val pd = ProfileStore.profileDir(filesDir, target)
                    Fingerprint.save(pd, Fingerprint.merge(Fingerprint.load(pd), o))
                }
                val tdir = ProfileStore.tankDir(filesDir, target, id); val np = nameplateFor(id)
                submitRollup("rollup $target tank$id", { ProfileStore.rollup(tdir, np, openSessions) }) { priorCache.clear() }
            } else {
                // Left in the quarantine dir; closeSession parks the tree as unassigned.
                FsLog.i(TAG, "quarantine close tank=$id samples=${closedQ.size} -> unassigned (not learned)")
            }
            return
        }
        rt.math.finishFill()?.let { anchor ->
            FsLog.i(TAG, "tank$id fill still open at session close; ending at plateau $anchor")
            onFillEnd(rt, anchor, System.currentTimeMillis(), true)
        }
        if (id == 1) commitTank1SinceFill(rt)
        val closed = rt.sessions.close()
        if (id == 1 && quarantineDir == null && FuelDetect.noSourceAlert(cfg.sourceId, closed.size, ProfileStore.profileField(filesDir, profileId, FuelDetect.KEY_NONE_ALERTED) == "true")) {
            ProfileStore.setProfileField(filesDir, profileId, FuelDetect.KEY_NONE_ALERTED, "true")
            notifySimple(S("No fuel level source set"), S("Fuel Smoothing has no fuel level source for %1\$s. Open the setup page and tap Detect, or pick a source.", profileName(profileId)))
        }
        val noLevel = if (id == 1 && quarantineDir == null) {
            val was = ProfileStore.profileField(filesDir, profileId, FuelDetect.KEY_NO_LEVEL_ALERTED) == "true"
            val (alert, now) = FuelDetect.noLevelStep(cfg.sourceId, closed.size, rt.validLevels, was)
            if (now != was) ProfileStore.setProfileField(filesDir, profileId, FuelDetect.KEY_NO_LEVEL_ALERTED, now.toString())
            if (rt.validLevels > 0 && cfg.sourceId != FuelDetect.NONE) runCatching {
                if (Fingerprint.markLive(ProfileStore.profileDir(filesDir, profileId), cfg.sourceId)) FsLog.i(TAG, "fingerprint: ${cfg.sourceId} works live for $profileId")
            }
            alert
        } else false
        if (noLevel) {
            // Once, re-armed only after a drive with valid levels; the source is never changed, detection not triggered.
            FsLog.w(TAG, "fuel source ${cfg.sourceId} gave no usable level in ${closed.size} samples")
            notifySimple(
                S("No fuel level this drive"),
                S("%1\$s gave no usable level for %2\$s. Check the adapter connection, or run Detect on the setup page.", S(FuelSources.byId(cfg.sourceId).label), profileName(profileId)),
            )
        }
        closeLiveChunks(rt, closed)
        refreshOpen()
        refreshRecording()
        val dir = ProfileStore.tankDir(filesDir, profileId, id); val np = nameplateFor(id)
        FsLog.i(TAG, "session close tank=$id samples=${closed.size} files=${rt.sessions.sessionCount()}; rollup queued")
        var summary = ""
        submitRollup("rollup tank$id", { summary = ProfileStore.rollup(dir, np, openSessions) }) {
            priorCache.remove(id)
            FsLog.i(TAG, "tank$id $summary")
        }
    }

    private fun closeSession() {
        val q = quarantineDir
        val confirm = quarantineConfirm
        val obs = quarantineObs
        val pendingPrompt = prompt
        val suggest = quarantineSuggest
        for (id in tanks.keys.toList()) closeTankSession(id)
        if (q != null && confirm == null) parkUnassigned(q, obs, pendingPrompt, suggest)
        TipReconciler.run(this, "session close")
    }

    /** Keep unanswered quarantine data as an unassigned drive. Never learned from until the user assigns it. */
    private fun parkUnassigned(
        q: File,
        obs: Fingerprint.Fp?,
        pendingPrompt: IdentityPending.Pending?,
        suggest: String?,
    ) {
        if (!Unassigned.hasSessionJsonl(q)) return
        val title = pendingPrompt?.title ?: S("Unassigned drive")
        val text = pendingPrompt?.text
            ?: S("This drive is kept unassigned and is not used for learning until you assign it to a vehicle.")
        val choices = pendingPrompt?.choices?.map { Unassigned.Choice(it.target, it.label) }
            ?: (listOfNotNull(suggest) + NEW_PROFILE).map { Unassigned.Choice(it, choiceLabel(it)) }
        val started = q.name.toLongOrNull() ?: 0L
        val parked = Unassigned.park(
            filesDir, q, selectedTorqueProfile, title, text, choices, obs, started, System.currentTimeMillis(),
        )
        FsLog.i(TAG, "quarantine parked unassigned id=${parked?.id ?: q.name} files=${if (parked != null) Unassigned.hasSessionJsonl(parked.dir) else false}")
        refreshUnassignedNotice()
    }

    private fun assignUnassigned(id: String?, target: String?) {
        if (id.isNullOrEmpty() || target == null) {
            FsLog.i(TAG, "unassigned assign ignored (id=$id target=$target)")
            return
        }
        val now = System.currentTimeMillis()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date(now))
        val entry = Unassigned.read(Unassigned.dir(filesDir, id))
        val profile = Quarantine.confirmTarget(filesDir, target, now, S("Vehicle %1\$s", day))
        val observation = entry?.fingerprint
        val savedVin = Fingerprint.loadAll(filesDir)[profile]?.vin
        if (observation?.vin != null && savedVin != null && !observation.vin.equals(savedVin, ignoreCase = true)) {
            FsLog.w(TAG, "unassigned assignment rejected: different physical VIN")
            return
        }
        val tanks = entry?.let { Unassigned.tanksWithFiles(it.dir) } ?: emptyList()
        val moved = Unassigned.assign(filesDir, id, profile)
        if (observation != null && !observation.empty) {
            val pd = ProfileStore.profileDir(filesDir, profile)
            Fingerprint.save(pd, Fingerprint.merge(Fingerprint.load(pd), observation))
        }
        for (tank in tanks) {
            val tdir = ProfileStore.tankDir(filesDir, profile, tank)
            val np = nameplateFor(tank)
            submitRollup("rollup $profile tank$tank", { ProfileStore.rollup(tdir, np, openSessions) }) { priorCache.clear() }
        }
        FsLog.i(TAG, "unassigned $id assigned -> $profile files=${moved.map { it.name }}")
        refreshUnassignedNotice()
        notifyIdentity(S("Keeping drive data"), S("This drive will be saved to %1\$s.", profileName(profile)), emptyList())
    }

    private fun discardUnassigned(id: String?) {
        if (id.isNullOrEmpty()) return
        if (Unassigned.discard(filesDir, id)) {
            FsLog.i(TAG, "unassigned $id discarded by user")
        }
        refreshUnassignedNotice()
    }

    /** Heads-up reminder for parked unassigned drives; opens the setup page. No auto-delete. */
    private fun refreshUnassignedNotice() {
        val entries = Unassigned.list(filesDir)
        val nm = notificationManager() ?: return
        if (entries.isEmpty()) {
            if (prompt == null || !promptPosted) nm.cancel(IDENTITY_NOTIF_ID)
            return
        }
        if (promptPosted && prompt != null) return // live question already occupies this notification
        val n = entries.size
        val first = entries.first()
        val title = if (n == 1) first.title.ifBlank { S("Unassigned drive") }
        else S("%1\$d unassigned drive(s) waiting. Open the setup page to assign them to a vehicle.", n)
        val text = first.text.ifBlank {
            S("This drive is kept unassigned and is not used for learning until you assign it to a vehicle.")
        }
        notifyIdentity(title, text, emptyList())
    }

    private fun integrateGalHr(t: ITorqueService, now: Long, rt: TankRt): Double {
        val last = rt.lastRateMs
        rt.lastRateMs = now
        if (last == null) return 0.0
        val dtSec = ((now - last).coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.001)
        if (dtSec > MAX_FLOW_GAP_SEC) {
            // Unknown flow during the gap; adding rate*dt would invent fuel. Burn for the gap is skipped.
            // One gap entry per gap: none again until a sample records burned fuel.
            if (rt.sessions.markGap(now, dtSec)) {
                FsLog.warn(TAG, "flowGap${rt.id}", "tank${rt.id} flow gap ${"%.1f".format(dtSec)}s > ${MAX_FLOW_GAP_SEC}s; no burn added")
            }
            return 0.0
        }
        resolveSessionFlowUnits()
        val pid: String
        val raw: Double?
        val perMinute: Boolean
        val hr = readDouble(t, galHrPid ?: Pids.GAL_PER_HOUR)
        if (hr != null && hr.isFinite() && hr >= 0.0) {
            pid = galHrPid ?: Pids.GAL_PER_HOUR
            raw = hr
            perMinute = false
            rt.lastFlowUnit = galHrUnit.ifBlank { sessionFlowHrUnit ?: "" }
            logFlowUnitOnce("galHr")
        } else {
            val gmin = readDouble(t, galMinPid ?: Pids.GAL_PER_MIN)
            if (gmin == null || !gmin.isFinite() || gmin < 0.0) {
                rt.lastFlowRaw = null
                rt.lastFlowPid = null
                noteBurn(rt, now, BurnGate.Verdict(false, "noFlow"))
                return 0.0
            }
            pid = galMinPid ?: Pids.GAL_PER_MIN
            raw = gmin
            perMinute = true
            rt.lastFlowUnit = galMinUnit.ifBlank { sessionFlowMinUnit ?: "" }
            logFlowUnitOnce("galMin")
        }
        rt.lastFlowRaw = raw
        rt.lastFlowPid = pid
        val rpm = readDouble(t, Pids.RPM)
        val maf = if (rpm == null) readDouble(t, Pids.MAF) else null
        val verdict = rt.burnGate.check(
            BurnGate.Input(
                nowMs = now,
                flow = raw,
                flowUpdMs = updateTime(t, pid),
                rpm = rpm,
                rpmUpdMs = if (rpm != null) updateTime(t, Pids.RPM) else null,
                maf = maf,
                mafUpdMs = if (maf != null) updateTime(t, Pids.MAF) else null,
            ),
        )
        noteBurn(rt, now, verdict)
        if (!verdict.burn) return 0.0
        val unit = if (perMinute) sessionFlowMinUnit ?: "gal" else sessionFlowHrUnit ?: "gal"
        val gph = flowToGallonsPerHour(raw!!, unit, perMinute)
        val dF = gph * (dtSec / 3600.0)
        if (dF.isFinite() && dF > 0.0) rt.usedGal += dF
        return if (dF.isFinite() && dF > 0.0) dF else 0.0
    }

    /** One event per burn on/off transition (not per sample). */
    private fun noteBurn(rt: TankRt, now: Long, v: BurnGate.Verdict) {
        val key = if (v.burn) "on" else v.why
        if (rt.burnWhy == key) return
        rt.burnWhy = key
        rt.sessions.mark(if (v.burn) "burnOn" else "burnOff", now, "\"why\":\"${v.why}\"")
        FsLog.i(TAG, "tank${rt.id} burn ${if (v.burn) "on" else "off"} why=${v.why} flowRaw=${rt.lastFlowRaw} ${rt.lastFlowUnit}")
    }

    private fun updateTime(t: ITorqueService, pid: String): Long? =
        runCatching { t.getPIDUpdateTime(arrayOf(pid))?.firstOrNull() }.getOrNull()?.takeIf { it > 0L }

    /** Unit is fixed for the session by resolveSessionFlowUnits(); no per-sample unit guessing. */
    private fun flowToGallonsPerHour(raw: Double, unit: String, perMinute: Boolean): Double {
        var gph = Units.toGallons(raw, unit)
        if (perMinute) gph *= 60.0
        return gph
    }

    /**
     * Once per session: profile.json "flowUnit" ("gal" or "l") overrides; else Torque's PID label;
     * a blank label is taken as gallons with a warning (never silently liters).
     */
    private fun resolveSessionFlowUnits() {
        if (sessionFlowHrUnit != null && sessionFlowMinUnit != null) return
        val override = ProfileStore.profileField(filesDir, profileId, "flowUnit")
            ?.trim()?.lowercase()?.takeIf { it == "gal" || it == "l" }
        // Standard OBD (SAE J1979 015E is L/h) and Torque's own calculated flow PIDs (ff125d/ff125a) report
        // liters internally whatever Torque displays, so they are always taken as liters. Only a
        // manufacturer-specific flow PID uses the profile "flowUnit" override, then its label, then gal (warned).
        fun pick(pid: String?, label: String, which: String): String {
            // Standard/Torque-calculated flow is metric: L (per h) or, for per-minute PIDs, possibly cc/ml.
            if (pid == null || Pids.isStandardFlowPid(pid)) {
                val l = label.lowercase()
                return if (l.contains("cc") || l.contains("ml")) label else "l"
            }
            if (override != null) return override
            if (label.isNotBlank()) return label
            FsLog.warn(TAG, "flowUnitBlank$which", "flow unit blank for proprietary $which pid=$pid; assuming gal (set profile flowUnit to override)")
            return "gal"
        }
        sessionFlowHrUnit = pick(galHrPid ?: Pids.GAL_PER_HOUR, galHrUnit, "galHr")
        sessionFlowMinUnit = pick(galMinPid ?: Pids.GAL_PER_MIN, galMinUnit, "galMin")
        FsLog.i(TAG, "session flow units galHr=$sessionFlowHrUnit galMin=$sessionFlowMinUnit override=${override ?: "none"}")
    }

    private fun logFlowUnitOnce(which: String) {
        if (loggedFlowUnit) return
        loggedFlowUnit = true
        val out = if (Units.prefersLiters(preferredVolumeUnit)) "l" else "gal"
        FsLog.i(
            TAG,
            "flow unit $which raw galHr=$galHrUnit galMin=$galMinUnit preferred=$preferredVolumeUnit remaining=$out",
        )
    }

    /** Display only: DTE is computed in miles internally and shown in Torque's preferred distance unit. */
    private fun dteKm(): Boolean {
        if (preferredDistanceUnit.isBlank()) {
            preferredDistanceUnit = torque?.let { t -> runCatching { t.getPreferredUnit("km") }.getOrNull() }.orEmpty()
        }
        return preferredDistanceUnit.trim().lowercase().let { it == "km" || it.startsWith("kilomet") }
    }
    private fun dteUnit(): String = if (dteKm()) "km" else "mi"
    private fun dteOut(miles: Double): Float = (if (dteKm()) miles * 1.609344 else miles).toFloat()
    private fun dteMax(): Float = if (dteKm()) 800f else 500f

    private fun remainingOut(gal: Double): Pair<Float, String> {
        return if (Units.prefersLiters(preferredVolumeUnit)) {
            (gal * Units.LITERS_PER_GAL).toFloat() to "l"
        } else {
            gal.toFloat() to "gal"
        }
    }

    private fun remainingScale(gal: Double?): Float {
        val g = gal?.takeIf { it > 0.0 } ?: 50.0
        return if (Units.prefersLiters(preferredVolumeUnit)) (g * Units.LITERS_PER_GAL).toFloat() else g.toFloat()
    }

    /**
     * Live fill end: display only. The skew limit was lifted while FILL lasted so the smoothed level could follow
     * the pump; nothing else changes. No ledger reset, no new chain, no "full" anchor, no mid-drive refit: the maps
     * and burn chains find the add themselves from the stored levels at the end-of-drive rollup.
     */
    private fun onFillEnd(rt: TankRt, smoothed: Double, now: Long, logging: Boolean) {
        if (logging) rt.sessions.mark("fillEnd", now)
        FsLog.i(TAG, "tank${rt.id} live follow end smoothed=$smoothed (display only; analysis runs at rollup)")
    }

    /** Adds burn since the last fill end. Does not zero the session fuel integral. */
    private fun commitTank1SinceFill(rt: TankRt) {
        val anchor = rt.fillAnchorGal ?: return
        val add = rt.usedGal - rt.fuelAtFillGal
        if (!add.isFinite() || add <= 0.0) return
        val next = rt.sinceFillUsedGal + add
        rt.sinceFillUsedGal = next
        ProfileStore.setFillLedger(filesDir, profileId, anchor, next)
        FsLog.i(TAG, "tank1 since-fill used=$next anchor=$anchor added=$add")
    }

    private class Prior(
        val t0: Double,
        val v: (Double) -> Double,
        val v95: Double,
        val hEst: Double,
        val cap: Double?,
        val reserve: Double,
        val gpp: (Double) -> Double,
        val ownModel: Boolean,
    )

    /**
     * No entered seed: a stored profile tankGal is a legacy value (often an old calculated estimate) and is not
     * used. Each fit reports its own learned size; until then it reads 1:1.
     */
    @Suppress("UNUSED_PARAMETER")
    private fun nameplateFor(id: Int): Double? = null

    /** Cached; invalidated at bind, profile switch, session-close rollup and pref changes. */
    private fun priorFor(id: Int): Prior {
        if (!identityReady || quarantineDir != null) return loadPrior(id, null)
        val nameplate = nameplateFor(id)
        priorCache[id]?.let { (np, p) -> if (np == nameplate) return p }
        val p = loadPrior(id, nameplate)
        priorCache[id] = nameplate to p
        return p
    }

    /** A chunk model.json is that fit's own size. Otherwise corrected percent is 1:1 and the cap is the user seed, or none. */
    private fun loadPrior(id: Int, nameplate: Double?): Prior {
        val seed = nameplate?.takeIf { it > 0.0 }
        val stored = if (identityReady && quarantineDir == null) ProfileStore.storedMap(ProfileStore.tankDir(filesDir, profileId, id)) else null
        if (stored != null) {
            val (edges, g, cap) = stored
            val v = { level: Double -> FuelV2.vAt(level, edges, g) }
            val v95 = v(FuelV2.L_HI)
            val hEst = if (cap > v95) cap - v95 else 0.0
            val reserve = FuelV2.reserveGal(0.0)
            return Prior(seed ?: cap, v, v95, hEst, cap, reserve, { level -> FuelV2.gpp(level, edges, g) }, ownModel = true)
        }
        val v = { level: Double -> if (seed != null) (level / 100.0) * seed else 0.0 }
        val reserve = FuelV2.reserveGal(0.0)
        return Prior(seed ?: 0.0, v, if (seed != null) seed * FuelV2.L_HI / 100.0 else 0.0, 0.0, seed, reserve, { if (seed != null) seed / 100.0 else 0.0 }, ownModel = false)
    }

    /** Per tick: O(1). Only this tick's burn moves the ledger; chunk corrections wait for session close. */
    private fun stepLive(rt: TankRt, dF: Double) {
        Live.tick(rt.ledger, dF)
    }

    /** Session close: build the closed session once and apply its chunk corrections to the ledger. */
    private fun closeLiveChunks(rt: TankRt, samples: List<Sample>) {
        if (samples.size < 2 || !rt.ledger.anchored) return
        val prior = priorFor(rt.id)
        val n = samples.size
        val session = FuelSession(
            "closed", DoubleArray(n) { samples[it].t / 1000.0 }, DoubleArray(n) { samples[it].rawPct },
            DoubleArray(n) { samples[it].smoothedPct }, DoubleArray(n) { samples[it].fuelUsedGal },
            DoubleArray(n) { samples[it].speed }, BooleanArray(n) { samples[it].fill },
        )
        val k = Live.closeChunks(rt.ledger, session, prior.gpp, placeholderNMin(), prior.v, prior.v95)
        FsLog.i(TAG, "tank${rt.id} close: applied $k drive chunks to the ledger (rHat=${"%.2f".format(rt.ledger.rHat)})")
    }

    private fun placeholderNMin(): Int {
        val tau = 3.5
        val sig = 1.1
        val scaled = 1.25 * sig / FuelV2.SE_TARGET
        return ceil(2.0 * tau * scaled * scaled).toInt().coerceAtLeast(10)
    }

    /** Chunk fit (or an extra tank's own map): corrected percent only; 1:1 until that fit has its own model. */
    private fun livePublish(rt: TankRt): TankPublish {
        val prior = priorFor(rt.id)
        val sm = rt.math.smoothed?.takeIf { it.isFinite() } ?: return corrOnly(Double.NaN, sized = false)
        val cap = prior.cap
        val corrected = if (prior.ownModel && cap != null && cap > 0.0) {
            FuelV2.correctedPct(sm, prior.v, cap).takeIf { it.isFinite() } ?: sm
        } else {
            sm
        }
        return corrOnly(corrected, sized = cap != null && cap > 0.0)
    }

    private fun loadTank1Ledger(rt: TankRt) {
        // Unowned legacy preferences can belong to a different vehicle. Never use them here.
        val ledger = ProfileStore.fillLedger(filesDir, profileId)
        rt.fillAnchorGal = ledger.fillAnchorGal
        rt.sinceFillUsedGal = ledger.burnSinceFillGal
    }

    private fun makeRuntime(id: Int): TankRt {
        val raw = quarantineDir?.let { Quarantine.rawDir(it, id) } ?: File(ProfileStore.tankDir(filesDir, profileId, id), "raw")
        val rt = if (id <= 1) {
            TankRt(
                id = 1,
                sessions = SessionStore(this, 1, raw),
            )
        } else {
            TankRt(
                id = id,
                sessions = SessionStore(this, id, raw),
            )
        }
        if (id <= 1 && trustedVehicle()) loadTank1Ledger(rt)
        return rt
    }

    private fun ensureRuntimes() {
        if (tanks[1] == null) tanks[1] = makeRuntime(1)
        val keep = configuredIds()
        for (id in keep) {
            if (tanks[id] == null) tanks[id] = makeRuntime(id)
        }
        for (id in tanks.keys.toList()) {
            if (id !in keep) {
                closeTankSession(id)
                tanks.remove(id)
            }
        }
    }


    private fun readDouble(t: ITorqueService, pid: String): Double? {
        val asDouble = runCatching { t.getPIDValuesAsDouble(arrayOf(pid)) }
        if (asDouble.isSuccess) {
            val d = asDouble.getOrNull()?.firstOrNull() ?: return null
            if (d.isNaN() || d.isInfinite()) return null
            return d
        }
        FsLog.warn(TAG, "getPIDValuesAsDouble", "getPIDValuesAsDouble: ${asDouble.exceptionOrNull()?.message}")
        val asFloat = runCatching { t.getPIDValues(arrayOf(pid)) }
        if (asFloat.isFailure) {
            FsLog.warn(TAG, "getPIDValues", "getPIDValues: ${asFloat.exceptionOrNull()?.message}")
            return null
        }
        val f = asFloat.getOrNull()?.firstOrNull() ?: return null
        if (f.isNaN() || f.isInfinite()) return null
        return f.toDouble()
    }

    private fun statusText(): String {
        val p = lastRawPct
        val sid = tanks[1]?.sessions?.sessionId
        return buildString {
            if (promptPosted) append(S("Vehicle decision pending")).append(" · ")
            else if (Unassigned.hasAny(filesDir)) append(S("Vehicle decision pending")).append(" · ")
            if (p == null) append(S("Waiting for fuel source"))
            else append(S("raw %1\$.1f%%", p))
            if (sid != null) append(S("sess %1\$s", sid.takeLast(8)))
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = notificationManager() ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, S("Fuel Smoothing"), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, S("Fuel Smoothing quiet"), NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_IDENTITY, S("Vehicle confirmation"), NotificationManager.IMPORTANCE_HIGH).apply {
                description = S("Asks which vehicle is connected when it cannot be matched automatically.")
                enableVibration(true)
            },
        )
    }

    private fun updatePresence(liveText: String) {
        reloadCfgIfDirty()
        val showPct = cfg.showFuelNotification
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = if (showPct) CHANNEL_ID else CHANNEL_QUIET
            val body = if (showPct) liveText else S("Fuel Smoothing is running")
            if (body == lastNoticeText && channel == lastNoticeChannel) return
            lastNoticeText = body
            lastNoticeChannel = channel
            startAsForeground(body, channel)
            return
        }
        if (!showPct) {
            if (lastNoticeChannel != null) {
                notificationManager()?.cancel(NOTIF_ID)
                lastNoticeText = null
                lastNoticeChannel = null
            }
            return
        }
        if (liveText == lastNoticeText && lastNoticeChannel == CHANNEL_ID) return
        lastNoticeText = liveText
        lastNoticeChannel = CHANNEL_ID
        try {
            notificationManager()?.notify(NOTIF_ID, notification(liveText, CHANNEL_ID))
        } catch (t: Throwable) {
            FsLog.warn(TAG, "notify", "notify ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun startAsForeground(text: String, channelId: String) {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            val n = notification(text, channelId)
            when {
                Build.VERSION.SDK_INT >= 34 ->
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                Build.VERSION.SDK_INT >= 29 ->
                    startForeground(NOTIF_ID, n, 0)
                else -> startForeground(NOTIF_ID, n)
            }
            TapStartNotice.cancel(this)
        } catch (t: Throwable) {
            FsLog.w(TAG, "startForeground ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun notification(text: String, channelId: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle(S("Fuel Smoothing for Torque"))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setOngoing(true)
            .build()
    }

    private fun notificationManager(): NotificationManager? {
        return if (Build.VERSION.SDK_INT >= 23) {
            getSystemService(NotificationManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
        }
    }

    private var fuelDetectManual = false

    /** Notifications from automatic detection are shown once per profile; a manual Detect always reports. */
    private fun detectNotifyAllowed(): Boolean {
        if (fuelDetectManual) return true
        if (ProfileStore.profileField(filesDir, profileId, FuelDetect.KEY_NOTIFIED) == "true") return false
        ProfileStore.setProfileField(filesDir, profileId, FuelDetect.KEY_NOTIFIED, "true")
        return true
    }

    private fun queueFuelDetect(why: String) {
        fuelDetectManual = why == "manual"
        val t = torque ?: return
        if (!ecuConnected) return
        val make = ProfileStore.profileField(filesDir, profileId, "make")
        val vin = ProfileStore.storedVin(filesDir, profileId)
        val names = pidNames.keys.toSet()
        val c = FuelDetect.candidates(vin, make, names)
        fuelDetect = FuelDetect.Run(c)
        FsLog.i(TAG, "fuel source detection ($why) for $profileId: candidates=$c current=${cfg.sourceId} confirmed=${cfg.sourceConfirmed}")
    }

    /** One poll per tick (read-only). See FuelDetect.Run for what counts as an answer. */
    private fun pumpFuelDetect(t: ITorqueService) {
        val run = fuelDetect ?: return
        val id = run.next()
        if (id != null) {
            var upd: Long? = null // set only when the source is read by Torque PID name
            val r = runCatching {
                FuelSources.read(t, cfg.copy(sourceId = id), pidNames) { pid ->
                    if (isStock012F(pid)) null else { upd = updateTime(t, pid) ?: 0L; readDouble(t, pid) }
                }
            }.getOrNull()
            run.report(id, r?.pct, upd, needsAgreement = r?.twoByte == true)
            FsLog.v(TAG, "fuel detect $id -> ${r?.pct} upd=$upd ${r?.how}")
        }
        if (!run.done) return
        fuelDetect = null
        if (!fuelDetectManual) ProfileStore.setProfileField(filesDir, profileId, FuelDetect.KEY_TRIED, "true")
        val res = run.result()
        FsLog.i(TAG, "fuel source detection for $profileId: ${res.id} confirmed=${res.confirmed} level=${res.level} (${res.why}; tried ${run.tried})")
        if (res.id != null) {
            val (storedId, storedConf) = FuelDetect.load(filesDir, profileId)
            val skipConfirm = res.id == storedId && storedConf
            FuelDetect.store(filesDir, profileId, res.id, res.confirmed)
            notifySourceChanged(this)
            if (!detectNotifyAllowed()) Unit
            else if (res.confirmed) notifySimple(
                S("Fuel level source found"),
                S("Using %1\$s for %2\$s (%3\$s).", S(FuelSources.byId(res.id).label), profileName(profileId), res.why),
            )
            else if (!skipConfirm) notifyConfirmSource(res.id, res.level, res.why)
        } else {
            if (detectNotifyAllowed()) notifySimple(S("No fuel level source found"), S("Open Fuel Smoothing setup and pick a source (or add the PID in Torque), then tap Detect."))
            announceSourceChanged()
        }
    }

    /** Asks the user to check the detected source against the dash gauge; Confirm stores it confirmed. */
    private fun notifyConfirmSource(id: String, level: Double?, why: String) {
        val nm = notificationManager() ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val label = S(FuelSources.byId(id).label)
        val who = profileName(profileId)
        val text = if (level != null) {
            S("Detected %1\$s reading %2\$.0f %% for %3\$s. Check it matches the dash fuel gauge, then tap Confirm. Confirm is on the setup page and in the notification (%4\$s).", label, level, who, why)
        } else {
            S("Detected %1\$s for %2\$s. Check it matches the dash fuel gauge, then tap Confirm. Confirm is on the setup page and in the notification (%3\$s).", label, who, why)
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat_notify).setContentTitle(S("Confirm fuel level source")).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
        val i = Intent(this, PluginService::class.java).setAction(ACTION_CONFIRM_SOURCE).putExtra(EXTRA_TARGET, profileId).putExtra(EXTRA_SOURCE, id)
        @Suppress("DEPRECATION")
        b.addAction(android.R.drawable.ic_menu_save, S("Confirm"), PendingIntent.getService(this, 61, i, flags))
        val setup = Intent(this, PluginActivity::class.java)
        b.setContentIntent(PendingIntent.getActivity(this, 62, setup, flags))
        runCatching { nm.notify(DETECT_NOTIF_ID, b.build()) }.onFailure { FsLog.warn(TAG, "notify", "notify ignored: ${it.message}") }
    }

    private fun notifySimple(title: String, text: String) {
        val nm = notificationManager() ?: return
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat_notify).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
        runCatching { nm.notify(DETECT_NOTIF_ID, b.build()) }.onFailure { FsLog.warn(TAG, "notify", "notify ignored: ${it.message}") }
    }

    private fun queueDetect() {
        if (!ecuConnected) return
        if (ProfileStore.distanceManual(filesDir, profileId)) return
        val t = torque ?: return
        val protocol = runCatching { t.getProtocolName() }.getOrNull()
        val make = ProfileStore.profileField(filesDir, profileId, "make")
        val vin = ProfileStore.storedVin(filesDir, profileId)
        val speed = speedPid?.let { readDouble(t, it) } ?: 0.0
        val driving = speed > 0.0
        detectQueue = ArrayDeque(SourceProbe.commandsFor(vin, make, protocol, driving))
        detectTimeouts = 0
    }

    /** One probe per poll. Three empty replies abort and send 0100. NO DATA is not a timeout. */
    private fun pumpDetect(t: ITorqueService, speedRaw: Double) {
        val q = detectQueue ?: return
        if (q.isEmpty()) {
            detectQueue = null
            return
        }
        val now = System.currentTimeMillis()
        val driving = speedRaw > 0.0
        val gap = if (driving) q.first().minGapMs else 0L
        if (gap > 0L && now - lastDetectMs < gap) return
        val cmd = q.removeFirst()
        val resp = runCatching { t.sendCommandGetResponse(cmd.header, cmd.command) }.getOrNull()
        lastDetectMs = now
        val lines = resp?.filterNotNull().orEmpty()
        val text = lines.joinToString(" ")
        if (text.isBlank()) {
            detectTimeouts++
            if (detectTimeouts >= 3) {
                runCatching { t.sendCommandGetResponse("", "0100") }
                detectQueue = null
            }
            return
        }
        if (text.contains("NO DATA", ignoreCase = true)) return
        detectTimeouts = 0
        if (SourceProbe.positive(lines, cmd.command)) {
            FsLog.i(TAG, "probe row ${cmd.row} ${cmd.command} positive")
        }
    }

    companion object {
        private const val TAG = "FuelSmooth"
        private const val CHANNEL_ID = "fuel_tap"
        private const val CHANNEL_QUIET = "fuel_quiet"
        private const val CHANNEL_IDENTITY = "identity_confirm"
        private const val AUTO_MATCH_MS = 90_000L
        private const val AUTO_MATCH_READS = 5
        /** Android notification action buttons that reliably show. */
        private const val MAX_NOTIF_ACTIONS = 3
        private const val FORD_16C1_CMD = "2216C1"
        private const val VIN_LOSS_GRACE_MS = 45_000L
        private const val NOTIF_ID = 350
        private const val POLL_MS = 1000L
        private const val MAX_FLOW_GAP_SEC = 5.0
        private const val IDENTITY_MAX_MS = 20_000L
        private const val FILL_SWITCH_DEBOUNCE_MS = 5000L
        private const val FULL_FILL_PCT = 90.0
        const val ACTION_Q_CONFIRM = "com.davidlang.torque.fuelsmoothing.QUARANTINE_CONFIRM"
        const val ACTION_UNASSIGNED_ASSIGN = "com.davidlang.torque.fuelsmoothing.UNASSIGNED_ASSIGN"
        const val ACTION_UNASSIGNED_DISCARD = "com.davidlang.torque.fuelsmoothing.UNASSIGNED_DISCARD"
        const val EXTRA_TARGET = "target"
        const val EXTRA_UNASSIGNED_ID = "unassignedId"
        const val NEW_PROFILE = Fingerprint.NEW_PROFILE
        const val IDENTITY_NOTIF_ID = 4242
        const val DETECT_NOTIF_ID = 4243
        const val EXTRA_SOURCE = "source"
        val ACTION_CONFIRM_SOURCE: String get() = BuildConfig.APPLICATION_ID + ".CONFIRM_SOURCE"
        val ACTION_SOURCE_CHANGED: String get() = BuildConfig.APPLICATION_ID + ".SOURCE_CHANGED"

        /** Page sends this intent. Already inside the service, run the reload directly. */
        fun notifySourceChanged(ctx: Context) {
            val running = instance
            if (running != null && ctx === running) {
                running.onSourceNotice()
                return
            }
            val i = Intent(ctx, PluginService::class.java).setAction(ACTION_SOURCE_CHANGED)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (t: Throwable) {
                FsLog.w(TAG, "source notice ignored: ${t.javaClass.simpleName} ${t.message}")
            }
        }
        const val ACTION_OBD_CONNECTED = "org.prowl.torque.OBD_CONNECTED"
        const val ACTION_OBD_DISCONNECTED = "org.prowl.torque.OBD_DISCONNECTED"
        const val ACTION_PID_QUERY = "org.prowl.torque.PID_QUERY"
        const val ACTION_PID_PLUGIN = "org.prowl.torque.PID_PLUGIN"
        val ACTION_DETECT: String get() = BuildConfig.APPLICATION_ID + ".DETECT"

        @Volatile
        var ecuConnected: Boolean = false
        private const val ECU_FUEL_NAME = "Fuel Level (From Engine ECU)"

        @Volatile
        var isRunning: Boolean = false

        /** True while any tank session is open. Export and import refuse when this is set. */
        @Volatile
        var recording: Boolean = false

        /** The live service (main thread); null when not created or destroyed, so the receiver falls back to a start. */
        @Volatile
        var instance: PluginService? = null
    }
}
