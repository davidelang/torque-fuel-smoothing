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
import android.util.Log
import org.prowl.torque.remote.ITorqueService
import java.io.File
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.roundToInt

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
        var mpgRecent: Double = 0.0
        val mpgWindow = Live.MpgWindow()
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
    private var preferredDistanceUnit: String = ""
    private var loggedFlowUnit: Boolean = false
    private var lastActive: String = FixFuelPrefs.ACTIVE_TANK1
    private var suppressFillUntilMs: Long = 0L
    private var speedUnit: String = "mph"
    private var mpgPid: String? = null
    private var mpgUnit: String = "mpg"
    private var lastRawPct: Double? = null
    private var lastAuthPct: Double? = null
    private var lastEcuConnected: Boolean? = null
    private var nameplateGal: Double? = null
    private var profileId: String = ProfileStore.DEFAULT_ID
    /** Wrong/unknown-vehicle hold: sessions go to files/quarantine/<stamp>/ and never touch a profile. */
    private var quarantineDir: File? = null
    private var quarantineSuggest: String? = null
    /** Profile the user confirmed for the quarantined data (from the notification); null = discard at session end. */
    private var quarantineConfirm: String? = null
    private var quarantineObs: Fingerprint.Fp? = null
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
    /** Session ids currently being recorded; rollups never read these files. */
    @Volatile private var openSessions: Set<String> = emptySet()
    @Volatile private var destroyed = false

    private fun refreshOpen() { openSessions = tanks.values.mapNotNull { it.sessions.sessionId }.toSet() }

    private fun submitRollup(what: String, work: () -> Unit, done: () -> Unit) {
        runCatching {
            rollupExec.execute {
                val t0 = System.currentTimeMillis()
                runCatching(work).onFailure { Log.w(TAG, "$what failed: ${it.javaClass.simpleName} ${it.message}") }
                Log.i(TAG, "$what done in ${System.currentTimeMillis() - t0} ms")
                if (!destroyed) handler.post { if (!destroyed) done() }
            }
        }.onFailure { Log.w(TAG, "$what not queued: ${it.message}") }
    }
    /** Auto-detection runs at most once per ECU connection. */
    private var fuelDetectTried = false
    private var lastDetectMs = 0L
    private var cfg: FixFuelConfig = FixFuelConfig(
        sourceId = FixFuelPrefs.DEFAULT_SOURCE,
        customName = "",
        tauSec = FixFuelPrefs.DEFAULT_TAU,
        skewPctPerSec = FixFuelPrefs.DEFAULT_SKEW,
        jumpPct = FixFuelPrefs.DEFAULT_JUMP,
        tankGal = null,
        learnedUsable = null,
        tankEstimated = false,
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

    private fun reloadCfgIfDirty() {
        if (!prefsDirty) return
        prefsDirty = false
        cfg = FixFuelPrefs.load(this)
        FsLog.verbose = getSharedPreferences(FixFuelPrefs.PREFS, MODE_PRIVATE).getBoolean(FsLog.KEY_VERBOSE, false)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            torque = ITorqueService.Stub.asInterface(service)
            bound = true
            bindRequested = true
            Log.i(TAG, "bound Torque API ${runCatching { torque?.version }.getOrNull()}")
            stopPolling()
            handler.post { afterBindWork() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Torque disconnected; not auto-creating")
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
        isRunning = true
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
                cfg.learnedUsable,
                cfg.tankGal,
            ),
        )
        profileId = ProfileStore.activeId(filesDir)
        tanks[1] = makeRuntime(1)
        ensureChannel()
        TapStartNotice.cancel(this)
        TipReconciler.run(this, "service start")
        updatePresence("Binding to Torque…")
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
            ACTION_CONFIRM_SOURCE -> {
                val pid = intent.getStringExtra(EXTRA_TARGET); val src = intent.getStringExtra(EXTRA_SOURCE)
                if (pid != null && src != null && ProfileStore.profileField(filesDir, pid, "fuelSource") == src) {
                    FuelDetect.store(filesDir, pid, src, true)
                    prefsDirty = true
                    Log.i(TAG, "fuel source $src confirmed by user for $pid")
                }
                notificationManager()?.cancel(DETECT_NOTIF_ID)
            }
            ACTION_DETECT -> {
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
        ecuConnected = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun bindTorque() {
        if (bound || bindRequested) return
        val flags = 0
        Log.i(TAG, "bindTorque flags=$flags (no BIND_AUTO_CREATE)")
        val ok = runCatching {
            bindService(intentForTorque(), connection, flags)
        }.getOrDefault(false)
        if (ok) {
            bindRequested = true
            Log.i(TAG, "bindService ok (waiting onServiceConnected; flags=$flags)")
        } else {
            bindRequested = false
            Log.i(TAG, "bindService fail flags=$flags (Torque not running; will not create)")
        }
    }

    private fun intentForTorque(): Intent {
        return Intent().setClassName("org.prowl.torque", "org.prowl.torque.remote.TorqueService")
    }

    private fun afterBindWork() {
        val t = torque ?: return
        cfg = FixFuelPrefs.load(this)
        FsLog.verbose = getSharedPreferences(FixFuelPrefs.PREFS, MODE_PRIVATE).getBoolean(FsLog.KEY_VERBOSE, false)
        prefsDirty = false
        priorCache.clear()
        ensureRuntimes()
        lastActive = resolveStoredActive()
        resolvePids()
        nameplateGal = TankSeed.seed(t)
        // Torque's profile capacity is the initial guess shown in settings, until a learned model exists.
        val seeded = nameplateGal
        // Seed the per-profile tank size from Torque's capacity only while it is blank; afterwards the plugin maintains it.
        if (seeded != null && seeded > 0.0 && cfg.tankGal == null && quarantineDir == null &&
            ProfileStore.setTankGuess(filesDir, profileId, seeded, false)
        ) {
            prefsDirty = true
            Log.i(TAG, "tank size for $profileId seeded from Torque capacity ${"%.1f".format(seeded)} gal")
        }
        registerPluginPids(t)
        val connected = ecuConnected || runCatching { t.isConnectedToECU }.getOrNull() == true
        if (connected) {
            startPolling()
        } else {
            stopPolling()
            Log.i(TAG, "bound; ECU not connected, waiting for OBD_CONNECTED")
        }
    }

    private fun resolvePids() {
        val t = torque ?: return
        val r = runCatching { Pids.resolve(t) }
            .onFailure { Log.w(TAG, "listAllPIDs/getPIDInformation: ${it.message}") }
            .getOrNull() ?: return
        fordPid = r.ford
        speedPid = r.speed
        if (r.speedUnit.isNotEmpty()) speedUnit = r.speedUnit
        fuelUsedPid = r.fuelUsed
        if (r.fuelUsedUnit.isNotEmpty()) fuelUsedUnit = r.fuelUsedUnit
        galHrPid = r.galHr ?: Pids.GAL_PER_HOUR
        galMinPid = r.galMin ?: Pids.GAL_PER_MIN
        galHrUnit = r.galHrUnit
        galMinUnit = r.galMinUnit
        mpgPid = r.mpg
        if (r.mpgUnit.isNotEmpty()) mpgUnit = r.mpgUnit
        pidNames = r.names
        preferredDistanceUnit = runCatching { t.getPreferredUnit("km") }.getOrNull().orEmpty()
        preferredVolumeUnit = runCatching { t.getPreferredUnit("l") }.getOrNull().orEmpty()
        if (preferredVolumeUnit.isBlank()) {
            preferredVolumeUnit = runCatching { t.getPreferredUnit("gal") }.getOrNull().orEmpty()
        }
        Log.i(
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
        Log.i(TAG, "ecu 012F slot name=$ecuLevelName pid=${pid ?: "none"} write=${cfg.feed012f}")
    }

    private fun sampleOnce() {
        reloadCfgIfDirty()
        if (nameplateGal == null || nameplateGal!! <= 0.0) nameplateGal = cfg.tankGal
        ensureRuntimes()
        val t = torque ?: return
        val ecu = runCatching { t.isConnectedToECU }.getOrNull()
        if (pidNames.isEmpty()) resolvePids()
        else if (cfg.write012f && !ecuFuelResolved) resolveEcuFuel(t)
        val now = System.currentTimeMillis()
        val speedRaw = speedPid?.let { readDouble(t, it) } ?: 0.0
        val speedMph = FuelMath.toMph(speedRaw, speedUnit)
        val active = resolveActiveTank(t)
        if (ecu != null && ecu != lastEcuConnected) {
            onEcu(ecu)
        }
        applyActiveTank(active, now)
        if (ecu == true) ensureActiveSession()
        val skipFill = now < suppressFillUntilMs
        // Never burst bus queries: at most one identity or one detect command per tick.
        if (ecu == true && identity == null && !fuelDetectTried && fuelDetect == null &&
            FuelDetect.shouldAutoDetect(cfg.sourceId, ProfileStore.profileField(filesDir, profileId, FuelDetect.KEY_TRIED) == "true")
        ) {
            fuelDetectTried = true
            ProfileStore.setProfileField(filesDir, profileId, FuelDetect.KEY_TRIED, "true")
            queueFuelDetect("auto")
        }
        when {
            identity != null -> pumpIdentity(t)
            fuelDetect != null -> pumpFuelDetect(t)
            else -> pumpDetect(t, speedRaw)
        }
        sampleTank(1, t, ecu, speedMph, now, speedRaw, skipFill)
        for (extra in cfg.extras) {
            sampleTank(extra.id, t, ecu, speedMph, now, speedRaw, skipFill)
        }
    }

    private fun sampleTank(
        id: Int,
        t: ITorqueService,
        ecu: Boolean?,
        speedMph: Double,
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
                learnedUsable = extra.learnedUsable,
            )
        }
        val src = FuelSources.read(t, srcCfg, pidNames) { pid ->
            if (isStock012F(pid)) {
                FsLog.warn(TAG, "refuse012f", "refusing getPIDValues of 012F (not Ford input)")
                null
            } else {
                readDouble(t, pid)
            }
        }
        tanks[id]?.let { rt -> rt.lastLvlL = src.liters; if (src.pct != null && !src.missing) rt.validLevels++ }
        if (id == 1) {
            if (src.how != cfg.sourceStatus) FixFuelPrefs.setSourceStatus(this, src.how)
        } else if (src.how != (cfg.extra(id)?.sourceStatus ?: "")) {
            FixFuelPrefs.setExtraSourceStatus(this, id, src.how)
        }
        if (id == 1) FsLog.change(TAG, "source", "${cfg.sourceId} ${src.how} $lastActive", "source ${cfg.sourceId} ${src.how} active=$lastActive")
        val raw = src.pct
        val logging0 = rt.sessions.isOpen && lastActive == id.toString()
        if (!src.missing && ecu != false && rt.math.checkReanchor(raw, speedMph, now)) {
            Log.i(TAG, "tank$id level re-anchored to ${rt.math.smoothed} after sustained low readings (raw=$raw)")
            if (logging0) rt.sessions.mark("reanchor", now, "\"sm\":${rt.math.smoothed}")
        }
        val valid = rt.math.isValidSample(raw, ecu, rt.math.smoothed) && !src.missing
        val logging = rt.sessions.isOpen && lastActive == id.toString()
        if (!valid) {
            rt.math.hold()
            val held = rt.math.smoothed
            val dF = if (logging) integrateGalHr(t, now, rt) else 0.0
            if (logging) {
                noteDistance(rt, speedMph, dF)
                rt.sessions.append(
                    sampleRow(
                        id, now, raw ?: Double.NaN, held ?: Double.NaN, speedRaw,
                        fill = false, ok = false, why = holdWhy(raw, ecu, src.missing, rt.math.smoothed),
                        mode = TrackMode.HOLD, ecu = ecu == true,
                    ),
                )
                stepLive(rt, dF)
            }
            if (held != null) {
                if (id == 1) publishFromSmoothed(t, raw ?: held, held) else publishExtraFrom(t, id, rt, held)
            }
            holdWhy(raw, ecu, src.missing, rt.math.smoothed).let { why ->
                FsLog.change(TAG, "state$id", "hold $why", "tank$id hold raw=$raw why=$why src=${src.how}")
            }
            return
        }
        val live = raw!!
        if (id == 1) lastRawPct = live
        val before = rt.math.smoothed
        val prevMode = rt.math.mode
        val smoothed = rt.math.ingest(
            live,
            speedMph,
            nowMs = now,
            tauSec = cfg.tauSec,
            skewPctPerSec = cfg.skewPctPerSec,
            jumpPct = cfg.jumpPct,
            ignoreFill = skipFill,
        )
        if (rt.math.mode == TrackMode.FILL && prevMode != TrackMode.FILL) {
            val delta = live - (before ?: live)
            Log.i(TAG, "tank$id fill start Δ=${"%.1f".format(delta)} raw=$live speedMph=${"%.1f".format(speedMph)}")
            if (logging) rt.sessions.mark("fillStart", now)
        }
        val fillEnded = prevMode == TrackMode.FILL && rt.math.mode != TrackMode.FILL
        if (fillEnded) {
            Log.i(TAG, "tank$id fill end raw=$live smoothed=$smoothed")
            onFillEnd(rt, smoothed, now, logging)
        }
        val dF = if (logging) integrateGalHr(t, now, rt) else 0.0
        if (logging) {
            noteDistance(rt, speedMph, dF)
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
            val line = "track mode=${rt.math.mode} raw=$live smoothed=$smoothed speedMph=${"%.1f".format(speedMph)}"
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
            spdUnit = speedUnit,
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
        if (lastGood != null && lastGood > FuelMath.NEAR_EMPTY_PCT && raw < FuelMath.GLITCH_RAW_PCT) return "glitch<1"
        if (lastGood != null && lastGood > FuelMath.NEAR_EMPTY_PCT && lastGood - raw > FuelMath.DROP_HOLD_PT) {
            return "held:drop>15"
        }
        return "missing"
    }

    private fun publishHeld(t: ITorqueService, raw: Double?, held: Double) {
        publishFromSmoothed(t, raw ?: held, held)
    }

    private fun publishFromSmoothed(t: ITorqueService, raw: Double, smoothed: Double) {
        val rt = tanks[1] ?: return
        val chunkOut = livePublish(rt)
        val bandOut = bandPublish(rt)
        val kalOut = kalmanPublish(rt)
        val out = FixFuelPrefs.primary(cfg.primaryAlgo, chunkOut, bandOut, kalOut)
        lastAuthPct = out.correctedPct
        publish(t, raw, smoothed, out.correctedPct, out.remaining, out.dte)
        if (bandOut != null) runCatching { publishBand(t, bandOut) }
        if (kalOut != null) runCatching { publishKalman(t, kalOut) }
        writeStockFuelLevel(t, smoothed, out.correctedPct)
        updatePresence(statusText())
    }

    private fun publishExtraFrom(t: ITorqueService, id: Int, rt: TankRt, smoothed: Double) {
        val out = livePublish(rt)
        publishExtra(t, id, smoothed, out.correctedPct, out.remaining, out.dte, priorFor(id).cap)
    }

    private fun onEcu(connected: Boolean) {
        ecuConnected = connected
        if (connected != lastEcuConnected) { fuelDetectTried = false; fuelDetect = null }
        if (connected) startPolling() else stopPolling()
        if (lastEcuConnected == connected) return
        lastEcuConnected = connected
        if (connected) {
            selectProfile()
            ensureActiveSession()
        } else {
            onEcuAway()
        }
    }

    private fun onEcuAway() {
        identity = null
        closeSession()
        if (tanks[1] == null) tanks[1] = makeRuntime(1)
    }

    private fun selectProfile() {
        if (identity != null) return
        if (tanks.values.any { it.sessions.isOpen }) return
        val t = torque ?: return
        val torqueName = runCatching { t.getVehicleProfileInformation()?.firstOrNull() }.getOrNull()
        // VIN is not passed here: it is stored only via the fingerprint, after the identity decision,
        // so a wrong vehicle's VIN can never be stamped onto this profile.
        val sel = ProfileStore.select(filesDir, null, torqueName)
        val next = sel.id
        val note = sel.vinNote
        if (sel.created) Log.i(TAG, "profile created $next for Torque profile '$torqueName'")
        if (note.isNotBlank()) Log.w(TAG, "profile $next: $note (stored ${ProfileStore.storedVin(filesDir, next)})")
        val gapFile = File(ProfileStore.profileDir(filesDir, next), "gap.txt")
        val gap = if (gapFile.isFile) gapFile.readText() else ""
        File(filesDir, "profile-notice.txt").writeText(if (note.isBlank()) gap else note)
        // ECU identity is read one bus command per poll tick (never a burst), then decided in finishIdentity().
        // Sessions wait for the decision (ensureActiveSession), so data never lands in the wrong profile.
        val stored = Fingerprint.loadAll(filesDir)[next]
        identity = IdentityRead(next, torqueName, stored)
        Log.i(TAG, "identity read queued for $next (firm=${stored?.firm == true}, steps=${identity!!.queue.size})")
    }

    /**
     * Paced ECU identity read: at most one sendCommandGetResponse per poll tick, total capped at
     * IDENTITY_MAX_MS. A firm profile only re-checks its strongest key (VIN if stored, else 0100).
     */
    private inner class IdentityRead(val next: String, val torqueName: String?, stored: Fingerprint.Fp?) {
        val startMs = System.currentTimeMillis()
        val queue = ArrayDeque<String>()
        val bitmaps = LinkedHashMap<String, String>()
        var vin: String? = null; var calId: String? = null; var cvn: String? = null; var ecuName: String? = null
        var probe: String? = null
        var bitmapsDone = false

        init {
            when {
                stored?.firm == true && stored.vin != null -> queue.add("0902")
                stored?.firm == true -> queue.add("0100")
                else -> queue.addAll(listOf("0100", "0902", "0904", "0906", "090A", "2216C1"))
            }
        }

        fun step(t: ITorqueService): Boolean {
            if (System.currentTimeMillis() - startMs > IDENTITY_MAX_MS) {
                Log.w(TAG, "identity read capped after ${IDENTITY_MAX_MS}ms; ${queue.size} steps skipped")
                queue.clear()
            }
            val c = queue.pollFirst() ?: return true
            if (runCatching { t.isConnectedToECU }.getOrNull() != true) {
                queue.clear()
                return true
            }
            val r = runCatching { t.sendCommandGetResponse("", c) }.getOrNull()?.toList()
            when (c) {
                "0902" -> vin = ProfileStore.parseVin(r)
                "0904" -> calId = Fingerprint.parseAscii(r, "04")
                "0906" -> cvn = Fingerprint.parseCvn(r)
                "090A" -> ecuName = Fingerprint.parseAscii(r, "0A")
                "2216C1" -> probe = r?.joinToString(" ")?.uppercase()?.replace(" ", "")
                else -> if (c in Fingerprint.BITMAP_PIDS) {
                    val bm = Fingerprint.parseBitmap(r, c)
                    if (bm != null) {
                        bitmaps[c] = bm
                        // Next bitmap PID only if this one says it is supported; queued right behind.
                        val nextPid = Fingerprint.BITMAP_PIDS.getOrNull(Fingerprint.BITMAP_PIDS.indexOf(c) + 1)
                        if ((bm.toLong(16) and 1L) != 0L && nextPid != null && queue.size < 12) queue.addFirst(nextPid)
                    }
                }
            }
            return queue.isEmpty()
        }

        fun obs(t: ITorqueService): Fingerprint.Fp = Fingerprint.Fp(
            vin = vin, calId = calId, cvn = cvn, ecuName = ecuName,
            protocol = runCatching { t.protocolName }.getOrNull()?.takeIf { it.isNotBlank() },
            bitmaps = bitmaps,
            probes = probe?.let { mapOf("ford2216C1" to if (it.contains("6216C1")) "ok" else "no") } ?: emptyMap(),
        )
    }

    private var identity: IdentityRead? = null

    /** Called once per poll tick while connected; finishes the identity decision when the paced read is done. */
    private fun pumpIdentity(t: ITorqueService) {
        val r = identity ?: return
        if (!r.step(t)) return
        identity = null
        finishIdentity(t, r.next, r.torqueName, r.obs(t))
    }

    private fun finishIdentity(t: ITorqueService, next: String, torqueName: String?, obs: Fingerprint.Fp) {
        val fps = Fingerprint.loadAll(filesDir)
        val d = Fingerprint.decide(next, obs, fps)
        Log.i(TAG, "identity decision ${d::class.simpleName} torque='$torqueName' profile=$next: ${d.why} obs=${Fingerprint.redacted(obs)}")
        // Verbose adds the non-identifying parts only (bitmaps, probes, protocol); VIN/calId/CVN never reach logcat.
        FsLog.v(TAG, "identity obs detail=${Fingerprint.toJson(obs.copy(vin = null, calId = null, cvn = null))}")
        val wasQ = quarantineDir
        quarantineDir = null
        quarantineSuggest = null
        quarantineConfirm = null
        quarantineObs = null
        var target = next
        when (d) {
            is Fingerprint.Decision.Normal -> if (!obs.empty) {
                Fingerprint.save(ProfileStore.profileDir(filesDir, next), Fingerprint.merge(fps[next] ?: Fingerprint.Fp(), obs))
            }
            is Fingerprint.Decision.Route -> {
                target = d.target
                Fingerprint.save(ProfileStore.profileDir(filesDir, target), Fingerprint.merge(fps[target] ?: Fingerprint.Fp(), obs))
                notifyIdentity(
                    "Recording to ${profileName(target)}",
                    "This vehicle is ${profileName(target)} (${d.why}), but Torque's profile is '$torqueName'. Data goes to ${profileName(target)}; tap to open Torque and switch profiles.",
                    emptyList(),
                )
            }
            is Fingerprint.Decision.Quarantine -> {
                quarantineDir = Quarantine.dir(filesDir, System.currentTimeMillis())
                quarantineSuggest = d.suggest
                quarantineObs = obs
                val sug = d.suggest?.let { "Torque profile '${profileName(it)}'" } ?: "a new vehicle profile"
                notifyIdentity(
                    "Wrong vehicle profile?",
                    "Torque profile '$torqueName' doesn't match this vehicle (${d.why}). Suggested: $sug. Data is held and discarded at drive end unless you keep it.",
                    Fingerprint.actions(d.suggest),
                )
            }
        }
        Log.i(TAG, "identity route: write=${quarantineDir?.path ?: target} suggest=${quarantineSuggest}")
        ProfileStore.setActiveId(filesDir, next)
        prefsDirty = true // tank size etc. are per profile
        if (target == profileId && wasQ == quarantineDir) return
        profileId = target
        priorCache.clear()
        for (id in tanks.keys.toList()) tanks[id] = makeRuntime(id)
    }

    private fun profileName(id: String): String = ProfileStore.profileField(filesDir, id, "torqueProfileName")
        ?: ProfileStore.profileField(filesDir, id, "name") ?: id

    private fun confirmQuarantine(target: String?) {
        if (quarantineDir == null || target == null) {
            Log.i(TAG, "quarantine confirm ignored (no quarantine)")
            return
        }
        val id = Quarantine.confirmTarget(filesDir, target, System.currentTimeMillis())
        quarantineConfirm = id
        Log.i(TAG, "quarantine confirmed by user -> $id")
        notifyIdentity(
            "Keeping drive data",
            "This drive will be saved to ${profileName(id)}. Torque's plugin API can't create or switch Torque profiles: " +
                "in Torque, create/select a vehicle profile named '${profileName(id)}'.",
            emptyList(),
        )
    }

    /**
     * Torque's plugin API (ITorqueService) cannot switch or create vehicle profiles and has no popup call,
     * so the notification opens Torque; the action button only decides where held data goes.
     */
    private fun notifyIdentity(title: String, text: String, choices: List<String>) {
        val nm = notificationManager() ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val torque = packageManager.getLaunchIntentForPackage("org.prowl.torque")
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(android.R.drawable.stat_sys_warning).setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
        if (torque != null) b.setContentIntent(PendingIntent.getActivity(this, 41, torque, flags))
        for ((k, target) in choices.withIndex()) {
            val i = Intent(this, PluginService::class.java).setAction(ACTION_Q_CONFIRM).putExtra(EXTRA_TARGET, target)
            val label = if (target == NEW_PROFILE) "Create new profile" else "Use ${profileName(target)}"
            @Suppress("DEPRECATION")
            b.addAction(android.R.drawable.ic_menu_save, label, PendingIntent.getService(this, 42 + k, i, flags))
        }
        nm.notify(IDENTITY_NOTIF_ID, b.build())
        Log.i(TAG, "identity notice: $title | $text | choices=$choices")
    }

    private fun registerPluginPids(t: ITorqueService) {
        runCatching { sendExtraPidRows(t) }
            .onFailure { Log.w(TAG, "sendPIDDataV2: ${it.message}") }
        runCatching {
            publishFour(t, 0.0, 0.0, 0.0, 0.0)
            for (extra in cfg.extras) {
                publishExtra(t, extra.id, 0.0, 0.0, 0.0, 0.0, priorFor(extra.id).cap)
            }
        }.onFailure { Log.w(TAG, "registerPluginPids: ${it.message}") }
        Log.i(TAG, "registered tank1 PIDs extras=${cfg.extras.map { it.id }}")
    }

    private fun sendExtraPidRows(t: ITorqueService) {
        val remainUnit = remainingOut(0.0).second
        val remainMax = remainingScale(priorFor(1).cap)
        val names = mutableListOf<String>()
        val shorts = mutableListOf<String>()
        val modes = mutableListOf<String>()
        val units = mutableListOf<String>()
        val mins = mutableListOf<Float>()
        val maxs = mutableListOf<Float>()
        fun row(name: String, shortName: String, unit: String, max: Float, modePid: String) {
            names.add(name)
            shorts.add(shortName)
            modes.add(modePid)
            units.add(unit)
            mins.add(0f)
            maxs.add(max)
        }
        row(Pids.PLUGIN_SMOOTHED, "SmFuel", "%", 100f, Pids.MODE_SMOOTHED)
        row(Pids.PLUGIN_CORRECTED, "CorrFuel", "%", 100f, Pids.MODE_CORRECTED)
        row(Pids.PLUGIN_REMAINING, "GalLeft", remainUnit, remainMax, Pids.MODE_REMAINING)
        row(Pids.PLUGIN_DTE, "pDTE", dteUnit(), dteMax(), Pids.MODE_DTE)
        row(Pids.PLUGIN_BAND_CORRECTED, "CorrBand", "%", 100f, Pids.MODE_BAND_CORRECTED)
        row(Pids.PLUGIN_BAND_REMAINING, "GalBand", remainUnit, remainMax, Pids.MODE_BAND_REMAINING)
        row(Pids.PLUGIN_BAND_DTE, "DTEBand", dteUnit(), dteMax(), Pids.MODE_BAND_DTE)
        row(Pids.PLUGIN_KALMAN_CORRECTED, "CorrKal", "%", 100f, Pids.MODE_KALMAN_CORRECTED)
        row(Pids.PLUGIN_KALMAN_REMAINING, "GalKal", remainUnit, remainMax, Pids.MODE_KALMAN_REMAINING)
        row(Pids.PLUGIN_KALMAN_DTE, "DTEKal", dteUnit(), dteMax(), Pids.MODE_KALMAN_DTE)
        for (extra in cfg.extras) {
            val n = Pids.extraPidNames(extra.id)
            val tmax = remainingScale(priorFor(extra.id).cap)
            row(n.smoothed, n.shortSm, "%", 100f, Pids.extraModePid(extra.id, 1))
            row(n.corrected, n.shortCorr, "%", 100f, Pids.extraModePid(extra.id, 2))
            row(n.remaining, n.shortGal, remainUnit, tmax, Pids.extraModePid(extra.id, 3))
            row(n.dte, n.shortDte, dteUnit(), dteMax(), Pids.extraModePid(extra.id, 4))
        }
        val count = names.size
        val blank = Array(count) { "" }
        val eq = Array(count) { "" }
        val diag = arrayOfNulls<String>(count)
        val ok = t.sendPIDDataV2(
            BuildConfig.APPLICATION_ID,
            names.toTypedArray(),
            shorts.toTypedArray(),
            modes.toTypedArray(),
            eq,
            mins.toFloatArray(),
            maxs.toFloatArray(),
            units.toTypedArray(),
            blank,
            diag,
            diag,
        )
        Log.i(TAG, "sendPIDDataV2 n=$count ok=$ok remainUnit=$remainUnit")
    }

    private fun publishExtra(
        t: ITorqueService,
        id: Int,
        smoothed: Double,
        corrected: Double,
        remain: Double,
        dte: Double,
        usable: Double?,
    ) {
        val names = Pids.extraPidNames(id)
        val (vol, vu) = remainingOut(remain)
        val rmax = remainingScale(usable)
        setPid(t, names.smoothed, names.shortSm, "%", rmax = 100f, value = smoothed.toFloat())
        setPid(t, names.corrected, names.shortCorr, "%", rmax = 100f, value = corrected.toFloat())
        setPid(t, names.remaining, names.shortGal, vu, rmax = rmax, value = vol)
        setPid(t, names.dte, names.shortDte, dteUnit(), rmax = dteMax(), value = dteOut(dte))
    }

    private fun publish(
        t: ITorqueService,
        raw: Double,
        smoothed: Double,
        corrected: Double,
        remain: Double,
        dte: Double,
    ) {
        runCatching { publishFour(t, smoothed, corrected, remain, dte) }
    }

    private var bandCache: Triple<Long, DoubleArray, Double>? = null

    /** Band map (model-band.json) for tank 1, reloaded only when the file changes (drive end / fill). */
    private fun bandModel(): Pair<DoubleArray, Double>? {
        val f = File(ProfileStore.tankDir(filesDir, profileId, 1), BandFit.FILE)
        val m = if (f.isFile) f.lastModified() else -1L
        bandCache?.let { if (it.first == m) return it.second to it.third }
        val loaded = BandFit.load(f.parentFile!!)
        bandCache = loaded?.let { Triple(m, it.first, it.second) }
        return loaded
    }

    /** Band outputs: corrected = band mapGal(gauge) / band capToZero x 100; remaining = band map gal + reserve. */
    private fun bandPublish(rt: TankRt): TankPublish? {
        val (g, cap) = bandModel() ?: return null
        val sm = rt.math.smoothed?.takeIf { it.isFinite() } ?: return null
        val v = { l: Double -> BandFit.vAt(l.coerceIn(0.0, 100.0), g) }
        val corr = FuelV2.correctedPct(sm, v, cap)
        if (!corr.isFinite()) return null
        val r = v(sm).coerceAtLeast(0.0)
        val reserve = priorFor(1).reserve
        val mpg = rt.mpgRecent
        return TankPublish(r, r + reserve, corr, mpg, (r + reserve) * mpg, r * mpg)
    }

    private var kalCache: Pair<Long, Kalman.Model?>? = null

    private fun kalmanModel(): Kalman.Model? {
        val f = File(ProfileStore.tankDir(filesDir, profileId, 1), Kalman.MODEL)
        val m = if (f.isFile) f.lastModified() else -1L
        kalCache?.let { if (it.first == m) return it.second }
        val loaded = Kalman.loadModel(f.parentFile!!)
        kalCache = m to loaded
        return loaded
    }

    /** Kalman outputs: corrected = G(gauge) / G(100) x 100; remaining = G(gauge) + reserve. */
    private fun kalmanPublish(rt: TankRt): TankPublish? {
        val km = kalmanModel() ?: return null
        val sm = rt.math.smoothed?.takeIf { it.isFinite() } ?: return null
        val corr = FuelV2.correctedPct(sm, { km.v(it) }, km.tank)
        if (!corr.isFinite()) return null
        val r = km.v(sm).coerceAtLeast(0.0)
        val reserve = priorFor(1).reserve
        val mpg = rt.mpgRecent
        return TankPublish(r, r + reserve, corr, mpg, (r + reserve) * mpg, r * mpg)
    }

    private fun publishKalman(t: ITorqueService, b: TankPublish) {
        val (vol, vu) = remainingOut(b.remaining)
        val rmax = remainingScale(priorFor(1).cap)
        setPid(t, Pids.PLUGIN_KALMAN_CORRECTED, "CorrKal", "%", rmax = 100f, value = b.correctedPct.toFloat())
        setPid(t, Pids.PLUGIN_KALMAN_REMAINING, "GalKal", vu, rmax = rmax, value = vol)
        setPid(t, Pids.PLUGIN_KALMAN_DTE, "DTEKal", dteUnit(), rmax = dteMax(), value = dteOut(b.dte))
    }

    private fun publishBand(t: ITorqueService, b: TankPublish) {
        val (vol, vu) = remainingOut(b.remaining)
        val rmax = remainingScale(priorFor(1).cap)
        setPid(t, Pids.PLUGIN_BAND_CORRECTED, "CorrBand", "%", rmax = 100f, value = b.correctedPct.toFloat())
        setPid(t, Pids.PLUGIN_BAND_REMAINING, "GalBand", vu, rmax = rmax, value = vol)
        setPid(t, Pids.PLUGIN_BAND_DTE, "DTEBand", dteUnit(), rmax = dteMax(), value = dteOut(b.dte))
    }

    private fun publishFour(
        t: ITorqueService,
        smoothed: Double,
        corrected: Double,
        remain: Double,
        dte: Double,
    ) {
        val (vol, vu) = remainingOut(remain)
        val rmax = remainingScale(priorFor(1).cap)
        setPid(t, Pids.PLUGIN_SMOOTHED, "SmFuel", "%", rmax = 100f, value = smoothed.toFloat())
        setPid(t, Pids.PLUGIN_CORRECTED, "CorrFuel", "%", rmax = 100f, value = corrected.toFloat())
        setPid(t, Pids.PLUGIN_REMAINING, "GalLeft", vu, rmax = rmax, value = vol)
        setPid(t, Pids.PLUGIN_DTE, "pDTE", dteUnit(), rmax = dteMax(), value = dteOut(dte))
    }

    private fun writeStockFuelLevel(t: ITorqueService, smoothedPct: Double, correctedPct: Double) {
        val corrected = FixFuelPrefs.feedValue(cfg.feed012f, smoothedPct, correctedPct) ?: return
        val rt1 = tanks[1]
        if (rt1 != null && !rt1.ledger.anchored && (rt1.math.smoothed == null || !corrected.isFinite() || corrected <= 0.0)) {
            // Unanchored with no usable reading: never push a placeholder 0% into Torque's stock slot.
            return
        }
        if (ecuLevelName.equals(Pids.FORD_FUEL_NAME, ignoreCase = true)) {
            Log.w(TAG, "skip 012F write: resolved name is Ford extra PID")
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
            Log.i(TAG, "write 012F mode=${cfg.feed012f} name=$ecuLevelName pid=${pid ?: "none"} value=$corrected")
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
     * switch a vehicle profile. So the learned tank size cannot drive Torque's own remaining/DTE, and our
     * Remaining/DTE PIDs stay required. The plugin therefore writes nothing into the Torque profile
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
        Log.i(TAG, "active tank $lastActive -> $want (fill-snap ignored ${FILL_SWITCH_DEBOUNCE_MS}ms)")
        closeTankSession(lastActive.toIntOrNull() ?: 1)
        lastActive = want
        suppressFillUntilMs = now + FILL_SWITCH_DEBOUNCE_MS
    }

    private fun ensureActiveSession() {
        if (identity != null) return
        val id = lastActive.toIntOrNull() ?: 1
        for (tid in tanks.keys.toList()) {
            if (tid != id) closeTankSession(tid)
        }
        openTankSession(id)
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
        FsLog.reset()
        refreshOpen()
        Log.i(TAG, "session open tank=$id $sid files=${rt.sessions.sessionCount()}")
    }

    private fun closeTankSession(id: Int) {
        val rt = tanks[id] ?: return
        if (!rt.sessions.isOpen) return
        val q = quarantineDir
        if (q != null) {
            // Never write the wrong profile: no ledger, rollup, chunks, model or fills here.
            val closedQ = rt.sessions.close()
            val target = quarantineConfirm
            val moved = Quarantine.resolve(filesDir, q, id, target)
            Log.i(TAG, "quarantine close tank=$id samples=${closedQ.size} -> ${target ?: "discarded (not learned)"} files=${moved.map { it.name }}")
            if (target != null) {
                quarantineObs?.let { o ->
                    val pd = ProfileStore.profileDir(filesDir, target)
                    Fingerprint.save(pd, Fingerprint.merge(Fingerprint.load(pd), o))
                }
                val tdir = ProfileStore.tankDir(filesDir, target, id); val np = nameplateFor(id)
                submitRollup("rollup $target tank$id", { ProfileStore.rollup(tdir, np, openSessions) }) { priorCache.clear() }
            }
            return
        }
        rt.math.finishFill()?.let { anchor ->
            Log.i(TAG, "tank$id fill still open at session close; ending at plateau $anchor")
            onFillEnd(rt, anchor, System.currentTimeMillis(), true)
        }
        if (id == 1) commitTank1SinceFill(rt)
        val closed = rt.sessions.close()
        if (id == 1 && quarantineDir == null && FuelDetect.noLevelAlert(cfg.sourceId, closed.size, rt.validLevels)) {
            // Once per session; the source definition is never changed and detection is not triggered.
            Log.w(TAG, "fuel source ${cfg.sourceId} gave no usable level in ${closed.size} samples")
            notifySimple(
                "No fuel level this drive",
                "${FuelSources.byId(cfg.sourceId).label} gave no usable level for ${profileName(profileId)}. Check the adapter connection, or run Detect on the setup page.",
            )
        }
        closeLiveChunks(rt, closed)
        refreshOpen()
        val dir = ProfileStore.tankDir(filesDir, profileId, id); val np = nameplateFor(id)
        Log.i(TAG, "session close tank=$id samples=${closed.size} files=${rt.sessions.sessionCount()}; rollup queued")
        var gap = ""
        submitRollup("rollup tank$id", { gap = ProfileStore.rollup(dir, np, openSessions) }) {
            priorCache.remove(id)
            publishTankGuess(id)
            Log.i(TAG, "tank$id gap=${gap.replace('\n', ' ')}")
        }
    }

    private fun closeSession() {
        for (id in tanks.keys.toList()) closeTankSession(id)
        TipReconciler.run(this, "session close")
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
        Log.i(TAG, "tank${rt.id} burn ${if (v.burn) "on" else "off"} why=${v.why} flowRaw=${rt.lastFlowRaw} ${rt.lastFlowUnit}")
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
        Log.i(TAG, "session flow units galHr=$sessionFlowHrUnit galMin=$sessionFlowMinUnit override=${override ?: "none"}")
    }

    private fun logFlowUnitOnce(which: String) {
        if (loggedFlowUnit) return
        loggedFlowUnit = true
        val out = if (Units.prefersLiters(preferredVolumeUnit)) "l" else "gal"
        Log.i(
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

    private fun onFillEnd(rt: TankRt, smoothed: Double, now: Long, logging: Boolean) {
        rt.fuelAtFillGal = rt.usedGal
        rt.sinceFillUsedGal = 0.0
        val prior = priorFor(rt.id)
        FuelV2.stepFill(rt.ledger, smoothed, smoothed, prior.v, prior.v95, prior.hEst)
        if (logging) rt.sessions.mark("fillEnd", now)
        if (logging && rt.sessions.isOpen && quarantineDir == null) {
            // Fill detection: the pre-fill part of this drive is complete; segment it, refit, reload the map.
            rt.sessions.flush()
            val dir = ProfileStore.tankDir(filesDir, profileId, rt.id); val np = nameplateFor(rt.id)
            val open = rt.sessions.sessionId; val tid = rt.id
            var ok = false
            val bytes = open?.let { File(File(dir, "raw"), "$it.jsonl").length() }
            submitRollup("fill update tank$tid", { ok = ProfileStore.fillUpdate(dir, np, open, bytes) }) {
                if (ok) { priorCache.remove(tid); publishTankGuess(tid) }
            }
        }
        if (rt.id != 1 || quarantineDir != null) return
        val anchor = rt.ledger.rHat.takeIf { rt.ledger.anchored && it.isFinite() && it > 0.0 } ?: return
        rt.fillAnchorGal = anchor
        FixFuelPrefs.setFillLedger(this, anchor, 0.0)
        Log.i(TAG, "tank1 fill anchor=$anchor sinceFill=0 smoothed=$smoothed fuelGal=${rt.usedGal}")
    }

    /** Adds burn since the last fill end. Does not zero the session fuel integral. */
    private fun commitTank1SinceFill(rt: TankRt) {
        val anchor = rt.fillAnchorGal ?: return
        val add = rt.usedGal - rt.fuelAtFillGal
        if (!add.isFinite() || add <= 0.0) return
        val next = rt.sinceFillUsedGal + add
        rt.sinceFillUsedGal = next
        FixFuelPrefs.setFillLedger(this, anchor, next)
        Log.i(TAG, "tank1 since-fill used=$next anchor=$anchor added=$add")
    }

    private class Prior(
        val t0: Double,
        val v: (Double) -> Double,
        val v95: Double,
        val hEst: Double,
        val cap: Double,
        val reserve: Double,
        val gpp: (Double) -> Double,
    )

    private fun hasModel(id: Int): Boolean = ProfileStore.storedMap(ProfileStore.tankDir(filesDir, profileId, id)) != null

    /** After an analysis cycle (drive end or fill): publish the learned capacity as the current tank guess. */
    private fun publishTankGuess(id: Int) {
        if (id != 1 || quarantineDir != null) return
        // Trust trigger: the Kalman G(100) replaces the entered/seeded tank size only once >= TRUST_COV2 % of the
        // gauge is covered by 2+ distinct drives; before that the initial estimate stays (Kalman shown as provisional).
        val km = Kalman.loadModel(ProfileStore.tankDir(filesDir, profileId, id))
        val cap = Kalman.trustedTank(km) ?: return
        if (ProfileStore.setTankGuess(filesDir, profileId, cap, true)) {
            prefsDirty = true
            // Only the plugin's per-profile setting changes: Torque's tankCapacity is not writable from the
            // plugin API (storeInProfile keys land under "plugin_"), so Torque's own figure stays as entered.
            Log.i(TAG, "tank guess for $profileId updated to learned capacity ${"%.1f".format(cap)} gal (Torque tankCapacity not writable; unchanged)")
        }
    }

    private fun nameplateFor(id: Int): Double? {
        // The profile's tank size (seeded, learned or user-edited) is the starting guess; Torque's value only if blank.
        return if (id <= 1) cfg.tankGal?.takeIf { it > 0.0 } ?: nameplateGal?.takeIf { it > 0.0 } else cfg.extra(id)?.tankGal
    }

    /** Cached; invalidated at bind, profile switch, session-close rollup and pref changes. */
    private fun priorFor(id: Int): Prior {
        val nameplate = nameplateFor(id)
        priorCache[id]?.let { (np, p) -> if (np == nameplate) return p }
        val p = loadPrior(id, nameplate)
        priorCache[id] = nameplate to p
        return p
    }

    /** A learned model.json always wins; the user's tank guess only seeds t0 when there is none. */
    private fun loadPrior(id: Int, nameplate: Double?): Prior {
        val t0 = if (nameplate != null && nameplate > 0.0) nameplate else 30.0
        val stored = ProfileStore.storedMap(ProfileStore.tankDir(filesDir, profileId, id))
        if (stored != null) {
            val (edges, g, cap) = stored
            val v = { level: Double -> FuelV2.vAt(level, edges, g) }
            val v95 = v(FuelV2.L_HI)
            val hEst = if (cap > v95) cap - v95 else 0.07 * v95
            val reserve = FuelV2.reserveGal(0.0)
            return Prior(t0, v, v95, hEst, cap, reserve, { level -> FuelV2.gpp(level, edges, g) })
        }
        val v = { level: Double -> (level / 100.0) * t0 }
        val v95 = v(FuelV2.L_HI)
        val hEst = 0.07 * v95
        val cap = v95 + hEst
        val reserve = FuelV2.reserveGal(0.0)
        return Prior(t0, v, v95, hEst, cap, reserve) { t0 / 100.0 }
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
            DoubleArray(n) { RawLog.gateSpeed(samples[it].speed, samples[it].spdUnit) }, BooleanArray(n) { samples[it].fill },
        )
        val k = Live.closeChunks(rt.ledger, session, prior.gpp, placeholderNMin(), prior.v, prior.v95)
        Log.i(TAG, "tank${rt.id} close: applied $k drive chunks to the ledger (rHat=${"%.2f".format(rt.ledger.rHat)})")
    }

    private fun placeholderNMin(): Int {
        val tau = 3.5
        val sig = 1.1
        val scaled = 1.25 * sig / FuelV2.SE_TARGET
        return ceil(2.0 * tau * scaled * scaled).toInt().coerceAtLeast(10)
    }

    private fun noteDistance(rt: TankRt, speedMph: Double, dF: Double) {
        val miles = if (speedMph.isFinite() && speedMph > 0.0) speedMph / 3600.0 else 0.0
        val gal = if (dF.isFinite() && dF > 0.0) dF else 0.0
        if (miles == 0.0 && gal == 0.0) return
        rt.mpgWindow.add(miles, gal, 0.15 * priorFor(rt.id).cap)
        rt.mpgRecent = rt.mpgWindow.mpg
    }

    private fun livePublish(rt: TankRt): TankPublish {
        val prior = priorFor(rt.id)
        val mpg = rt.mpgRecent
        val sm = rt.math.smoothed
        if (!rt.ledger.anchored && sm != null && sm.isFinite()) {
            // No fill anchor yet (fresh start): read remaining off the prior curve instead of publishing 0.
            val r = prior.v(sm.coerceIn(0.0, 100.0)).coerceAtLeast(0.0)
            val remaining = r + prior.reserve
            val corrected = FuelV2.correctedPct(sm, prior.v, prior.cap).takeIf { it.isFinite() } ?: sm
            return TankPublish(r, remaining, corrected, mpg, remaining * mpg, r * mpg)
        }
        val pub = FuelV2.publish(rt.ledger, prior.cap, prior.reserve, mpg)
        // Corrected % always comes from the map at the smoothed gauge over the map's own capacity (scale-free);
        // the ledger only drives Remaining/DTE.
        val corr = if (sm != null) FuelV2.correctedPct(sm, prior.v, prior.cap) else Double.NaN
        return if (corr.isFinite()) pub.copy(correctedPct = corr) else pub
    }

    private fun loadTank1Ledger(rt: TankRt) {
        rt.fillAnchorGal = FixFuelPrefs.fillAnchorGal(this)
        rt.sinceFillUsedGal = FixFuelPrefs.sinceFillUsedGal(this)
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
        if (id <= 1) loadTank1Ledger(rt)
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
            if (p == null) append("Waiting for fuel source")
            else append("raw ${"%.1f".format(p)}% corr ${"%.1f".format(lastAuthPct ?: p)}%")
            if (sid != null) append(" sess ${sid.takeLast(8)}")
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = notificationManager() ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Fuel Smoothing", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "Fuel Smoothing quiet", NotificationManager.IMPORTANCE_MIN),
        )
    }

    private fun updatePresence(liveText: String) {
        reloadCfgIfDirty()
        val showPct = cfg.showFuelNotification
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = if (showPct) CHANNEL_ID else CHANNEL_QUIET
            val body = if (showPct) liveText else getString(R.string.running_quiet)
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
            Log.w(TAG, "startForeground ignored: ${t.javaClass.simpleName} ${t.message}")
        }
    }

    private fun notification(text: String, channelId: String): Notification {
        val b = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return b.setContentTitle(getString(R.string.app_name))
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
        Log.i(TAG, "fuel source detection ($why) for $profileId: candidates=$c current=${cfg.sourceId} confirmed=${cfg.sourceConfirmed}")
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
        val res = run.result()
        Log.i(TAG, "fuel source detection for $profileId: ${res.id} confirmed=${res.confirmed} level=${res.level} (${res.why}; tried ${run.tried})")
        if (res.id != null) {
            FuelDetect.store(filesDir, profileId, res.id, res.confirmed)
            prefsDirty = true
            if (!detectNotifyAllowed()) Unit
            else if (res.confirmed) notifySimple("Fuel level source found", "Using ${FuelSources.byId(res.id).label} for ${profileName(profileId)} (${res.why}).")
            else notifyConfirmSource(res.id, res.level, res.why)
        } else if (detectNotifyAllowed()) {
            notifySimple("No fuel level source found", "Open Fuel Smoothing setup and pick a source (or add the PID in Torque), then tap Detect.")
        }
    }

    /** Asks the user to check the detected source against the dash gauge; Confirm stores it confirmed. */
    private fun notifyConfirmSource(id: String, level: Double?, why: String) {
        val nm = notificationManager() ?: return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        val text = "Detected ${FuelSources.byId(id).label}" + (level?.let { " reading ${"%.0f".format(it)} %" } ?: "") +
            " for ${profileName(profileId)}. Check it matches the dash fuel gauge, then tap Confirm ($why)."
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_stat_notify).setContentTitle("Confirm fuel level source").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setAutoCancel(true)
        val i = Intent(this, PluginService::class.java).setAction(ACTION_CONFIRM_SOURCE).putExtra(EXTRA_TARGET, profileId).putExtra(EXTRA_SOURCE, id)
        @Suppress("DEPRECATION")
        b.addAction(android.R.drawable.ic_menu_save, "Confirm", PendingIntent.getService(this, 61, i, flags))
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
        val driving = RawLog.gateSpeed(speed, speedUnit) > 0.0
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
        val driving = RawLog.gateSpeed(speedRaw, speedUnit) > 0.0
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
            Log.i(TAG, "probe row ${cmd.row} ${cmd.command} positive")
        }
    }

    companion object {
        private const val TAG = "FuelSmooth"
        private const val CHANNEL_ID = "fuel_tap"
        private const val CHANNEL_QUIET = "fuel_quiet"
        private const val NOTIF_ID = 350
        private const val POLL_MS = 1000L
        private const val MAX_FLOW_GAP_SEC = 5.0
        private const val IDENTITY_MAX_MS = 20_000L
        private const val FILL_SWITCH_DEBOUNCE_MS = 5000L
        private const val FULL_FILL_PCT = 90.0
        const val ACTION_Q_CONFIRM = "com.davidlang.torque.fuelsmoothing.QUARANTINE_CONFIRM"
        const val EXTRA_TARGET = "target"
        const val NEW_PROFILE = Fingerprint.NEW_PROFILE
        const val IDENTITY_NOTIF_ID = 4242
        const val DETECT_NOTIF_ID = 4243
        const val EXTRA_SOURCE = "source"
        val ACTION_CONFIRM_SOURCE: String get() = BuildConfig.APPLICATION_ID + ".CONFIRM_SOURCE"
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
    }
}
