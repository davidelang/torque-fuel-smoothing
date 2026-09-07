package org.dlang.torque.fordfuel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import org.prowl.torque.remote.ITorqueService
import kotlin.math.roundToInt

class PluginService : Service() {
    private class TankRt(
        val id: Int,
        val math: FuelMath = FuelMath(),
        val sessions: SessionStore,
        val bins: BinStore,
    ) {
        var usedGal: Double = 0.0
        var lastRateMs: Long? = null
        var flowStartMs: Long? = null
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
    private var pidNames: Map<String, String> = emptyMap()
    private var lastNoticeText: String? = null
    private var lastNoticeChannel: String? = null
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
            sampleOnce()
            handler.postDelayed(this, POLL_MS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            torque = ITorqueService.Stub.asInterface(service)
            bound = true
            bindRequested = true
            Log.i(TAG, "bound Torque API ${runCatching { torque?.version }.getOrNull()}")
            handler.removeCallbacks(poll)
            handler.post { afterBindWork() }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "Torque disconnected; not auto-creating")
            onEcuAway()
            torque = null
            bound = false
            bindRequested = false
            lastEcuConnected = null
            handler.removeCallbacks(poll)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        cfg = FixFuelPrefs.load(this)
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
        }
        updatePresence(statusText())
        if (intent?.action.isNullOrEmpty()) {
            bindTorque()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        closeSession()
        if (bindRequested) {
            runCatching { unbindService(connection) }
        }
        bindRequested = false
        bound = false
        torque = null
        isRunning = false
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
        ensureRuntimes()
        lastActive = resolveStoredActive()
        resolvePids()
        runCatching { tanks[1]?.bins?.loadFromProfile(t) }
        nameplateGal = TankSeed.seed(t)
        registerPluginPids(t)
        handler.removeCallbacks(poll)
        handler.post(poll)
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
    }

    private fun sampleOnce() {
        cfg = FixFuelPrefs.load(this)
        nameplateGal = cfg.learnedUsable ?: cfg.tankGal
        ensureRuntimes()
        val t = torque ?: return
        val ecu = runCatching { t.isConnectedToECU }.getOrNull()
        if (pidNames.isEmpty()) resolvePids()
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
        val src = FuelSources.read(t, srcCfg, pidNames) { pid -> readDouble(t, pid) }
        if (id == 1) {
            if (src.how != cfg.sourceStatus) FixFuelPrefs.setSourceStatus(this, src.how)
        } else if (src.how != (cfg.extra(id)?.sourceStatus ?: "")) {
            FixFuelPrefs.setExtraSourceStatus(this, id, src.how)
        }
        if (id == 1) Log.i(TAG, "source ${cfg.sourceId} ${src.how} active=$lastActive")
        val raw = src.pct
        val valid = rt.math.isValidSample(raw, ecu) && !src.missing
        if (!valid) {
            rt.math.hold()
            val held = rt.math.smoothed
            if (held != null) {
                if (id == 1) publishHeld(t, raw, held) else publishExtraFrom(t, id, rt, held)
            }
            Log.i(TAG, "tank$id hold raw=$raw src=${src.how}")
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
        }
        if (prevMode == TrackMode.FILL && rt.math.mode != TrackMode.FILL) {
            Log.i(TAG, "tank$id fill end raw=$live smoothed=$smoothed")
        }
        if (rt.sessions.isOpen && lastActive == id.toString()) {
            integrateGalHr(t, now, rt)
            rt.sessions.append(
                Sample(
                    t = now,
                    rawPct = live,
                    smoothedPct = smoothed,
                    fuelUsedGal = rt.usedGal,
                    speed = speedRaw,
                    fill = rt.math.mode == TrackMode.FILL,
                ),
            )
        }
        if (id == 1) {
            publishFromSmoothed(t, live, smoothed)
            val line = "track mode=${rt.math.mode} raw=$live smoothed=$smoothed speedMph=${"%.1f".format(speedMph)}"
            Log.i(TAG, line)
            FixFuelPrefs.setLive(this, line)
        } else {
            publishExtraFrom(t, id, rt, smoothed)
            Log.i(TAG, "tank$id mode=${rt.math.mode} raw=$live smoothed=$smoothed src=${src.how}")
        }
    }

    private fun publishHeld(t: ITorqueService, raw: Double?, held: Double) {
        publishFromSmoothed(t, raw ?: held, held)
    }

    private fun publishFromSmoothed(t: ITorqueService, raw: Double, smoothed: Double) {
        val rt = tanks[1] ?: return
        val bins = rt.bins.bins.toList()
        val usable = cfg.learnedUsable ?: cfg.tankGal
        val remain = rt.math.remainingGal(smoothed, bins, usable, rt.bins.reserveGal)
        val corrected = rt.math.correctedPct(smoothed, remain, usable, bins.isEmpty())
        lastAuthPct = corrected
        val mpgRaw = mpgPid?.let { readDouble(t, it) }
        val mpg = mpgRaw?.let { FuelMath.mpgFrom(it, mpgUnit) } ?: 0.0
        val dte = rt.math.dteMiles(remain, mpg)
        publish(t, raw, smoothed, corrected, remain, dte)
        maybeSyncSlider(t, corrected)
        updatePresence(statusText())
    }

    private fun publishExtraFrom(t: ITorqueService, id: Int, rt: TankRt, smoothed: Double) {
        val extra = cfg.extra(id)
        val bins = rt.bins.bins.toList()
        val usable = extra?.learnedUsable ?: extra?.tankGal
        val remain = rt.math.remainingGal(smoothed, bins, usable, rt.bins.reserveGal)
        val corr = rt.math.correctedPct(smoothed, remain, usable, bins.isEmpty())
        val mpgRaw = mpgPid?.let { readDouble(t, it) }
        val mpg = mpgRaw?.let { FuelMath.mpgFrom(it, mpgUnit) } ?: 0.0
        publishExtra(t, id, smoothed, corr, remain, rt.math.dteMiles(remain, mpg), usable)
    }

    private fun onEcu(connected: Boolean) {
        if (lastEcuConnected == connected) return
        lastEcuConnected = connected
        if (connected) {
            ensureActiveSession()
        } else {
            onEcuAway()
        }
    }

    private fun onEcuAway() {
        closeSession()
        if (tanks[1] == null) tanks[1] = makeRuntime(1)
        for (id in tanks.keys.toList()) {
            val rt = tanks[id] ?: continue
            rt.bins.rebuildFromJsonlOnce()
            maybeLearnUsable(id)
        }
    }

    private fun registerPluginPids(t: ITorqueService) {
        runCatching { sendExtraPidRows(t) }
            .onFailure { Log.w(TAG, "sendPIDDataV2: ${it.message}") }
        runCatching {
            publishFour(t, 0.0, 0.0, 0.0, 0.0)
            for (extra in cfg.extras) {
                publishExtra(t, extra.id, 0.0, 0.0, 0.0, 0.0, extra.learnedUsable ?: extra.tankGal)
            }
        }.onFailure { Log.w(TAG, "registerPluginPids: ${it.message}") }
        Log.i(TAG, "registered tank1 PIDs extras=${cfg.extras.map { it.id }}")
    }

    private fun sendExtraPidRows(t: ITorqueService) {
        val remainUnit = remainingOut(0.0).second
        val remainMax = remainingScale(nameplateGal)
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
        row(Pids.ALIAS_SMOOTHED, "SmFuel", "%", 100f, Pids.MODE_ALIAS_SMOOTHED)
        row(Pids.ALIAS_FORD, "PFord", "%", 100f, Pids.MODE_ALIAS_FORD)
        row(Pids.ALIAS_REMAINING, "GalLeft", remainUnit, remainMax, Pids.MODE_ALIAS_REMAINING)
        row(Pids.ALIAS_DTE, "pDTE", "mi", 500f, Pids.MODE_ALIAS_DTE)
        for (extra in cfg.extras) {
            val n = Pids.extraPidNames(extra.id)
            val tmax = remainingScale(extra.learnedUsable ?: extra.tankGal)
            row(n.smoothed, n.shortSm, "%", 100f, Pids.extraModePid(extra.id, 1))
            row(n.corrected, n.shortCorr, "%", 100f, Pids.extraModePid(extra.id, 2))
            row(n.remaining, n.shortGal, remainUnit, tmax, Pids.extraModePid(extra.id, 3))
            row(n.dte, n.shortDte, "mi", 500f, Pids.extraModePid(extra.id, 4))
        }
        val count = names.size
        val blank = Array(count) { "" }
        val eq = Array(count) { "0" }
        val diag = arrayOfNulls<String>(count)
        val ok = t.sendPIDDataV2(
            "org.dlang.torque.fordfuel",
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
        val rmax = remainingScale(nameplateGal)
        setPid(t, Pids.PLUGIN_SMOOTHED, "SmFuel", "%", rmax = 100f, value = smoothed.toFloat())
        setPid(t, Pids.PLUGIN_CORRECTED, "CorrFuel", "%", rmax = 100f, value = corrected.toFloat())
        setPid(t, Pids.PLUGIN_REMAINING, "GalLeft", vu, rmax = rmax, value = vol)
        setPid(t, Pids.PLUGIN_DTE, "pDTE", "mi", rmax = 500f, value = dte.toFloat())
        setPid(t, Pids.ALIAS_SMOOTHED, "SmFuel", "%", rmax = 100f, value = smoothed.toFloat())
        setPid(t, Pids.ALIAS_FORD, "PFord", "%", rmax = 100f, value = corrected.toFloat())
        setPid(t, Pids.ALIAS_REMAINING, "GalLeft", vu, rmax = rmax, value = vol)
        setPid(t, Pids.ALIAS_DTE, "pDTE", "mi", rmax = 500f, value = dte.toFloat())
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
        rt.lastRateMs = null
        rt.flowStartMs = null
        val sid = rt.sessions.open()
        Log.i(TAG, "session open tank=$id $sid files=${rt.sessions.sessionCount()}")
    }

    private fun closeTankSession(id: Int) {
        val rt = tanks[id] ?: return
        if (!rt.sessions.isOpen) return
        val closed = rt.sessions.close()
        val obs = FuelMath.observationsFrom(closed)
        rt.bins.add(obs)
        rt.bins.persist(torque)
        maybeLearnUsable(id)
        Log.i(
            TAG,
            "session close tank=$id samples=${closed.size} obs=${obs.bins.size} reserve+=${obs.reserveGal} " +
                "bins=${rt.bins.bins.size} reserve=${rt.bins.reserveGal} files=${rt.sessions.sessionCount()}",
        )
    }

    private fun closeSession() {
        for (id in tanks.keys.toList()) closeTankSession(id)
    }

    private fun integrateGalHr(t: ITorqueService, now: Long, rt: TankRt) {
        val last = rt.lastRateMs
        rt.lastRateMs = now
        if (last == null) return
        val dtSec = ((now - last).coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.001)
        val hr = readDouble(t, galHrPid ?: Pids.GAL_PER_HOUR)
        val gph = if (hr != null && hr.isFinite() && hr >= 0.0) {
            logFlowUnitOnce("galHr")
            flowToGallonsPerHour(hr, galHrUnit, perMinute = false, rt, now, last)
        } else {
            val gmin = readDouble(t, galMinPid ?: Pids.GAL_PER_MIN)
            if (gmin != null && gmin.isFinite() && gmin >= 0.0) {
                logFlowUnitOnce("galMin")
                flowToGallonsPerHour(gmin, galMinUnit, perMinute = true, rt, now, last)
            } else {
                return
            }
        }
        rt.usedGal += gph * (dtSec / 3600.0)
    }

    private fun flowToGallonsPerHour(
        raw: Double,
        unit: String,
        perMinute: Boolean,
        rt: TankRt,
        now: Long,
        last: Long,
    ): Double {
        var gph = Units.toGallons(raw, unit)
        if (perMinute) gph *= 60.0
        if (Units.looksLikeGallons(unit)) {
            val origin = rt.flowStartMs ?: last
            rt.flowStartMs = origin
            val dtSec = ((now - last).coerceAtLeast(0L) / 1000.0).coerceAtLeast(0.001)
            val hours = ((now - origin) / 3_600_000.0).coerceAtLeast(dtSec / 3600.0)
            val mean = (rt.usedGal + gph * (dtSec / 3600.0)) / hours
            if (mean > Units.IMPLAUSIBLE_GPH) gph *= Units.GAL_PER_LITER
        }
        return gph
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

    private fun maybeLearnUsable(id: Int) {
        val rt = tanks[id] ?: return
        val bins = rt.bins.bins.toList()
        val usable = FuelMath.estimateUsable(bins) ?: return
        if (usable <= 0.0) return
        val kept = FuelMath.filterOutliers(bins)
        val span = FuelMath.coverageSpan(kept)
        val mapped = FuelMath.mappedGallons(bins)
        if (id == 1) {
            FixFuelPrefs.setLearnedUsable(this, usable)
            nameplateGal = usable
        } else {
            FixFuelPrefs.setLearnedUsableExtra(this, id, usable)
        }
        cfg = FixFuelPrefs.load(this)
        Log.i(TAG, "learned usable tank=$id gal=$usable span=$span mappedGal=$mapped bins=${bins.size}")
    }

    private fun makeRuntime(id: Int): TankRt {
        return if (id <= 1) {
            TankRt(
                id = 1,
                sessions = SessionStore(this, 1),
                bins = BinStore(this),
            )
        } else {
            TankRt(
                id = id,
                sessions = SessionStore(this, id),
                bins = BinStore(
                    this,
                    fileName = "bins-t$id.json",
                    profileKey = "${Pids.PREFIX}bins_t$id",
                    clearLegacy = false,
                ),
            )
        }
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

    private fun remainingMax(): Float = remainingScale(nameplateGal)

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
            NotificationChannel(CHANNEL_ID, "Fix Fuel", NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_QUIET, "Fix Fuel quiet", NotificationManager.IMPORTANCE_MIN),
        )
    }

    private fun updatePresence(liveText: String) {
        cfg = FixFuelPrefs.load(this)
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
            .setSmallIcon(R.drawable.ic_launcher)
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

    companion object {
        private const val TAG = "FordFuel"
        private const val CHANNEL_ID = "fordfuel"
        private const val CHANNEL_QUIET = "fordfuel_quiet"
        private const val NOTIF_ID = 350
        private const val POLL_MS = 1000L
        private const val FILL_SWITCH_DEBOUNCE_MS = 5000L
        const val ACTION_OBD_CONNECTED = "org.prowl.torque.OBD_CONNECTED"
        const val ACTION_OBD_DISCONNECTED = "org.prowl.torque.OBD_DISCONNECTED"
        const val ACTION_PID_QUERY = "org.prowl.torque.PID_QUERY"
        const val ACTION_PID_PLUGIN = "org.prowl.torque.PID_PLUGIN"

        @Volatile
        var isRunning: Boolean = false
    }
}
