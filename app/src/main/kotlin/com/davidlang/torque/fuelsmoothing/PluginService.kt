package com.davidlang.torque.fuelsmoothing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
        /** Tank 1 only. Null until a fill sets it. Not cleared on session open. */
        var fillAnchorGal: Double? = null
        var sinceFillUsedGal: Double = 0.0
        /** Session fuelGal at the last fill end. Open resets this with usedGal. */
        var fuelAtFillGal: Double = 0.0
        val ledger = TankLedger()
        var steppedChunks: Int = 0
        var mpgRecent: Double = 0.0
        val mpgWindow: ArrayDeque<Pair<Double, Double>> = ArrayDeque()
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
    private var loggedFlowUnit: Boolean = false
    private var lastActive: String = FixFuelPrefs.ACTIVE_TANK1
    private var suppressFillUntilMs: Long = 0L
    private var speedUnit: String = "mph"
    private var mpgPid: String? = null
    private var mpgUnit: String = "mpg"
    private var lastRawPct: Double? = null
    private var lastAuthPct: Double? = null
    private var lastSliderPct: Double? = null
    private var lastEcuConnected: Boolean? = null
    private var sliderWritable: Boolean? = null
    private var nameplateGal: Double? = null
    private var profileId: String = ProfileStore.DEFAULT_ID
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
            ACTION_DETECT -> {
                bindTorque()
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
        stopPolling()
        FixFuelPrefs.prefs(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        closeSession()
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
        prefsDirty = false
        priorCache.clear()
        ensureRuntimes()
        lastActive = resolveStoredActive()
        resolvePids()
        nameplateGal = TankSeed.seed(t)
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
        Log.i(TAG, "ecu 012F slot name=$ecuLevelName pid=${pid ?: "none"} write=${cfg.write012f}")
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
        pumpDetect(t, speedRaw)
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
                Log.w(TAG, "refusing getPIDValues of 012F (not Ford input)")
                null
            } else {
                readDouble(t, pid)
            }
        }
        if (id == 1) {
            if (src.how != cfg.sourceStatus) FixFuelPrefs.setSourceStatus(this, src.how)
        } else if (src.how != (cfg.extra(id)?.sourceStatus ?: "")) {
            FixFuelPrefs.setExtraSourceStatus(this, id, src.how)
        }
        if (id == 1) Log.i(TAG, "source ${cfg.sourceId} ${src.how} active=$lastActive")
        val raw = src.pct
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
                        id, now, raw ?: Double.NaN, held ?: raw ?: Double.NaN, speedRaw,
                        fill = false, ok = false, why = holdWhy(raw, ecu, src.missing, rt.math.smoothed),
                        mode = TrackMode.HOLD, ecu = ecu == true,
                    ),
                )
                stepLive(rt, dF)
            }
            if (held != null) {
                if (id == 1) publishFromSmoothed(t, raw ?: held, held) else publishExtraFrom(t, id, rt, held)
            }
            Log.i(TAG, "tank$id hold raw=$raw why=${holdWhy(raw, ecu, src.missing, rt.math.smoothed)} src=${src.how}")
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
            Log.i(TAG, line)
            if (line != lastLiveLine) {
                lastLiveLine = line
                FixFuelPrefs.setLive(this, line)
            }
        } else {
            publishExtraFrom(t, id, rt, smoothed)
            Log.i(TAG, "tank$id mode=${rt.math.mode} raw=$live smoothed=$smoothed src=${src.how}")
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
            fuelUnit = fuelUnitLabel(),
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
        val out = livePublish(rt)
        lastAuthPct = out.correctedPct
        publish(t, raw, smoothed, out.correctedPct, out.remaining, out.dte)
        writeStockFuelLevel(t, out.correctedPct)
        maybeSyncSlider(t, out.correctedPct)
        updatePresence(statusText())
    }

    private fun publishExtraFrom(t: ITorqueService, id: Int, rt: TankRt, smoothed: Double) {
        val out = livePublish(rt)
        publishExtra(t, id, smoothed, out.correctedPct, out.remaining, out.dte, priorFor(id).cap)
    }

    private fun onEcu(connected: Boolean) {
        ecuConnected = connected
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
        closeSession()
        if (tanks[1] == null) tanks[1] = makeRuntime(1)
    }

    private fun selectProfile() {
        if (tanks.values.any { it.sessions.isOpen }) return
        val t = torque ?: return
        val resp = runCatching { t.sendCommandGetResponse("", "0902") }.getOrNull()?.toList()
        val vin = ProfileStore.parseVin(resp)
        val torqueName = runCatching { t.getVehicleProfileInformation()?.firstOrNull() }.getOrNull()
        val sel = ProfileStore.select(filesDir, vin, torqueName)
        val next = sel.id
        val note = sel.vinNote
        if (sel.created) Log.i(TAG, "profile created $next for Torque profile '$torqueName'")
        if (note.isNotBlank()) Log.w(TAG, "profile $next: $note (stored ${ProfileStore.storedVin(filesDir, next)})")
        val gapFile = File(ProfileStore.profileDir(filesDir, next), "gap.txt")
        val gap = if (gapFile.isFile) gapFile.readText() else ""
        File(filesDir, "profile-notice.txt").writeText(if (note.isBlank()) gap else note)
        if (next == profileId) return
        profileId = next
        ProfileStore.setActiveId(filesDir, next)
        priorCache.clear()
        for (id in tanks.keys.toList()) tanks[id] = makeRuntime(id)
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
        row(Pids.PLUGIN_DTE, "pDTE", "mi", 500f, Pids.MODE_DTE)
        for (extra in cfg.extras) {
            val n = Pids.extraPidNames(extra.id)
            val tmax = remainingScale(priorFor(extra.id).cap)
            row(n.smoothed, n.shortSm, "%", 100f, Pids.extraModePid(extra.id, 1))
            row(n.corrected, n.shortCorr, "%", 100f, Pids.extraModePid(extra.id, 2))
            row(n.remaining, n.shortGal, remainUnit, tmax, Pids.extraModePid(extra.id, 3))
            row(n.dte, n.shortDte, "mi", 500f, Pids.extraModePid(extra.id, 4))
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
        setPid(t, names.dte, names.shortDte, "mi", rmax = 500f, value = dte.toFloat())
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
        setPid(t, Pids.PLUGIN_DTE, "pDTE", "mi", rmax = 500f, value = dte.toFloat())
    }

    private fun writeStockFuelLevel(t: ITorqueService, corrected: Double) {
        if (!cfg.write012f) return
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
            Log.i(TAG, "write 012F name=$ecuLevelName pid=${pid ?: "none"} value=$corrected")
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
        Log.w(TAG, "setPIDInformation $name: ${info.exceptionOrNull()?.message}")
        runCatching { t.setPIDData(name, shortName, unit, rmax, 0f, value) }
            .onFailure { Log.w(TAG, "setPIDData $name: ${it.message}") }
    }

    private fun maybeSyncSlider(t: ITorqueService, auth: Double) {
        if (!FuelMath.crossedOnePercent(lastSliderPct, auth)) return
        lastSliderPct = auth
        runCatching {
            t.storeInProfile("${Pids.PREFIX}authoritativePct", auth.toString(), true)
        }
        val rc = runCatching { t.storeInProfile("tankUsed", auth.toString(), true) }.getOrNull()
        if (sliderWritable == null) {
            sliderWritable = rc == 0
            Log.i(
                TAG,
                "tankUsed storeInProfile rc=$rc writable=$sliderWritable " +
                    "(public API historically cannot write Torque's slider; Overrides PID + setPIDInformation is the DTE path)",
            )
        }
    }

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
        val id = lastActive.toIntOrNull() ?: 1
        for (tid in tanks.keys.toList()) {
            if (tid != id) closeTankSession(tid)
        }
        openTankSession(id)
    }

    private fun openTankSession(id: Int) {
        val rt = tanks[id] ?: return
        if (rt.sessions.isOpen) return
        if (id == 1) lastSliderPct = null
        rt.usedGal = 0.0
        rt.fuelAtFillGal = 0.0
        rt.steppedChunks = 0
        rt.lastRateMs = null
        sessionFlowHrUnit = null
        sessionFlowMinUnit = null
        val sid = rt.sessions.open(profileId = profileId, app = BuildConfig.VERSION_NAME)
        Log.i(TAG, "session open tank=$id $sid files=${rt.sessions.sessionCount()}")
    }

    private fun closeTankSession(id: Int) {
        val rt = tanks[id] ?: return
        if (!rt.sessions.isOpen) return
        if (id == 1) commitTank1SinceFill(rt)
        val closed = rt.sessions.close()
        val dir = ProfileStore.tankDir(filesDir, profileId, id)
        val gap = ProfileStore.rollup(dir, nameplateFor(id))
        priorCache.remove(id)
        Log.i(
            TAG,
            "session close tank=$id samples=${closed.size} files=${rt.sessions.sessionCount()} gap=${gap.replace('\n', ' ')}",
        )
    }

    private fun closeSession() {
        for (id in tanks.keys.toList()) closeTankSession(id)
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
                Log.i(TAG, "tank${rt.id} flow gap ${"%.1f".format(dtSec)}s > ${MAX_FLOW_GAP_SEC}s; no burn added")
            }
            return 0.0
        }
        resolveSessionFlowUnits()
        val hr = readDouble(t, galHrPid ?: Pids.GAL_PER_HOUR)
        val gph = if (hr != null && hr.isFinite() && hr >= 0.0) {
            logFlowUnitOnce("galHr")
            flowToGallonsPerHour(hr, sessionFlowHrUnit ?: "gal", perMinute = false)
        } else {
            val gmin = readDouble(t, galMinPid ?: Pids.GAL_PER_MIN)
            if (gmin != null && gmin.isFinite() && gmin >= 0.0) {
                logFlowUnitOnce("galMin")
                flowToGallonsPerHour(gmin, sessionFlowMinUnit ?: "gal", perMinute = true)
            } else {
                return 0.0
            }
        }
        val dF = gph * (dtSec / 3600.0)
        if (dF.isFinite() && dF > 0.0) rt.usedGal += dF
        return if (dF.isFinite() && dF > 0.0) dF else 0.0
    }

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
        fun pick(label: String, which: String): String {
            if (override != null) return override
            if (label.isNotBlank()) return label
            Log.w(TAG, "flow unit blank for $which; assuming gal (set profile flowUnit to \"l\" to override)")
            return "gal"
        }
        sessionFlowHrUnit = pick(galHrUnit, "galHr")
        sessionFlowMinUnit = pick(galMinUnit, "galMin")
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
        if (rt.id != 1) return
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

    private fun nameplateFor(id: Int): Double? {
        return if (id <= 1) nameplateGal?.takeIf { it > 0.0 } ?: cfg.tankGal else cfg.extra(id)?.tankGal
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

    private fun stepLive(rt: TankRt, dF: Double) {
        val prior = priorFor(rt.id)
        FuelV2.stepSample(rt.ledger, dF)
        val samples = rt.sessions.samples
        if (samples.size < 2) return
        val n = samples.size
        val tSec = DoubleArray(n) { samples[it].t / 1000.0 }
        val raw = DoubleArray(n) { samples[it].rawPct }
        val sm = DoubleArray(n) { samples[it].smoothedPct }
        val used = DoubleArray(n) { samples[it].fuelUsedGal }
        val spd = DoubleArray(n) { RawLog.gateSpeed(samples[it].speed, samples[it].spdUnit) }
        val fill = BooleanArray(n) { samples[it].fill }
        val session = FuelSession("live", tSec, raw, sm, used, spd, fill)
        val chunks = FuelV2.chunksSf(session, prior.gpp, placeholderNMin(), 4.4)
        val fuelNow = samples.last().fuelUsedGal
        while (rt.steppedChunks < chunks.size) {
            val c = chunks[rt.steppedChunks]
            FuelV2.stepChunk(rt.ledger, c.l, fuelNow - c.f, prior.v(c.l), prior.v95)
            rt.steppedChunks++
        }
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
        rt.mpgWindow.addLast(miles to gal)
        val cap = priorFor(rt.id).cap
        val want = 0.15 * cap
        var fuel = 0.0
        var dist = 0.0
        val it = rt.mpgWindow.descendingIterator()
        var keep = 0
        while (it.hasNext()) {
            val p = it.next()
            fuel += p.second
            dist += p.first
            keep++
            if (want > 0.0 && fuel >= want) break
        }
        while (rt.mpgWindow.size > keep) rt.mpgWindow.removeFirst()
        if (fuel > 1e-9) rt.mpgRecent = dist / fuel
    }

    private fun livePublish(rt: TankRt): TankPublish {
        val prior = priorFor(rt.id)
        val mpg = rt.mpgRecent
        val sm = rt.math.smoothed
        if (!rt.ledger.anchored && sm != null && sm.isFinite()) {
            // No fill anchor yet (fresh start): read remaining off the prior curve instead of publishing 0.
            val r = prior.v(sm.coerceIn(0.0, 100.0)).coerceAtLeast(0.0)
            val remaining = r + prior.reserve
            val corrected = if (prior.cap > 0.0) (100.0 * r / prior.cap).coerceIn(0.0, 100.0) else sm
            return TankPublish(r, remaining, corrected, mpg, remaining * mpg, r * mpg)
        }
        return FuelV2.publish(rt.ledger, prior.cap, prior.reserve, mpg)
    }

    private fun loadTank1Ledger(rt: TankRt) {
        rt.fillAnchorGal = FixFuelPrefs.fillAnchorGal(this)
        rt.sinceFillUsedGal = FixFuelPrefs.sinceFillUsedGal(this)
    }

    private fun makeRuntime(id: Int): TankRt {
        val raw = File(ProfileStore.tankDir(filesDir, profileId, id), "raw")
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
        Log.w(TAG, "getPIDValuesAsDouble: ${asDouble.exceptionOrNull()?.message}")
        val asFloat = runCatching { t.getPIDValues(arrayOf(pid)) }
        if (asFloat.isFailure) {
            Log.w(TAG, "getPIDValues: ${asFloat.exceptionOrNull()?.message}")
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
            Log.w(TAG, "notify ignored: ${t.javaClass.simpleName} ${t.message}")
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
        private const val FILL_SWITCH_DEBOUNCE_MS = 5000L
        private const val FULL_FILL_PCT = 90.0
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
