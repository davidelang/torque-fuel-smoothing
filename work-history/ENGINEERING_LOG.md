# ENGINEERING_LOG


## 2026-08-22 - Execute start: e350-fuel-build-and-corpus-20260822-0224-plan.md

- Magic-approved plan: `sandbox/plans/e350-fuel-build-and-corpus-20260822-0224-plan.md`
- Scope: VE-shaped `build_app`/`deploy` + Kotlin/Gradle Torque plugin (session corpus, EMA, partial bins).
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-22 - Phase 4: slider / Overrides PID path

- Public Torque API has no documented write for the profile `tankUsed` slider (research 2026-08-21).
- Plugin attempts `storeInProfile("tankUsed", …)` about every 1% and always `setPIDInformation` onto `[FORD]Fuel Level`.
- Human path remains Overrides PID + Use vehicle fuel level sensor; Plugin DTE is published as `Plugin DTE`.

## 2026-08-22 - Execute start: e350-fuel-fgs-crash-insets-20260822-2039-plan.md

- Magic-approved plan: `sandbox/plans/e350-fuel-fgs-crash-insets-20260822-2039-plan.md`
- Scope: stop FGS crash loop (no startForegroundService from receiver; specialUse FGS from visible activity); WindowInsets on MainActivity; register [E350] PIDs at Torque bind.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-22 - Execute start: old-ford-fuel-usable-from-map-20260822-2055-plan.md

- Magic-approved plan: `sandbox/plans/old-ford-fuel-usable-from-map-20260822-2055-plan.md`
- Scope: rename to Old Ford Fuel / `[OldFord]`; no tank UI; remaining gallons from map (not %/100×35). Exclude FGS/insets already at 79d6573.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-22 - Phase 2: tank nameplate seed (no UI, no hardcoded 35)

- On Torque bind, probe `getSettingString` / `getSettingInt` / `retrieveProfileData` for `tankCapacity` and `profileTankCapacity` (liters if >80, else gal).
- Persist `org.dlang.torque.fordfuel.nameplateGal` if a key works.
- Runtime result is logcat `FordFuel` line `tank seed gal=… from …` or `tank seed none` (not known until human deploy).

## 2026-08-23 - Execute start: old-ford-fuel-smooth-skew-fill-20260823-1509-plan.md

- Magic-approved plan: `sandbox/plans/old-ford-fuel-smooth-skew-fill-20260823-1509-plan.md`
- Scope: display smoother only — hold-on-missing, 0.05 %/s skew cap, fill snap at speed 0. Do not change mapping bins, gal/hr, dual-name publish, or docs.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-23 - Execute start: old-ford-fuel-bind-no-autocreate-20260823-1558-plan.md

- Magic-approved plan: `sandbox/plans/old-ford-fuel-bind-no-autocreate-20260823-1558-plan.md`
- Scope: ANR fix only — never BIND_AUTO_CREATE Torque; no create-retry loop; bound = onServiceConnected; PID resolve off bind callback. Do not change smoother/mapping/dual-name/docs.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-25 - Execute start: old-ford-fuel-autostart-fgs-20260825-1349-plan.md

- Magic-approved plan: `sandbox/plans/old-ford-fuel-autostart-fgs-20260825-1349-plan.md`
- Scope: auto-start FGS after first install (grants, Torque broadcasts, boot/replace). Do not change smoother/mapping/gal/hr/dual-name/docs. Keep no BIND_AUTO_CREATE Torque.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-28 - Execute start: fix-fuel-config-sources-20260828-1301-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-config-sources-20260828-1301-plan.md`
- Scope: rename to Fix Fuel; config page; vendor sources; 012F collect-without-loop; corrected % identity until bins; map from smoothed vs gal/hr. No docs/, no autostart/battery work.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-29 - Execute start: fix-fuel-second-tank-20260828-1409-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-second-tank-20260828-1409-plan.md`
- Scope: optional second independent tank (config, tracker, PIDs, active-tank gal/hr). Tank 1 defaults/autostart/gal-hr unchanged. Copy hardware doc to docs/.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-29 - Resume compact: second-tank Phase 2 gate then Phase 3

- Continue `sandbox/plans/fix-fuel-second-tank-20260828-1409-plan.md` after compact.
- Phase 2 already in worktree (`math2` + four `[FixFuel] Tank2 *` PIDs; no 012F/Ford write). Forensic then `./build_app` before Phase 3 (active tank, bins-t2, fill debounce).

## 2026-08-29 - Phase 3: active tank, bins-t2, fill debounce

- `sandbox/plans/fix-fuel-second-tank-20260828-1409-plan.md`
- Gal/hr integrates only into the active tank session (`tank1` / `tank2` / `from_pid`).
- Tank 2 sessions under `sessions-t2/` with `tank=2`; maps in `bins-t2.json`.
- Fill-snap ignored for 5s after active-tank switch. Tank 2 remaining uses tank-2 gallons (identity until bins).

## 2026-08-31 - Execute start: fix-fuel-n-tanks-pluginactivity-20260831-0959-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-n-tanks-pluginactivity-20260831-0959-plan.md`
- Scope: N extra tanks (Add additional tank, stable ids, cap 8); Torque ACTIVITY_PLUGIN + DEFAULT via PluginActivity; migrate tank2_enabled; From PID = 1-based tank id. Tank 1 defaults/autostart/gal-hr unchanged.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-31 - Phase 2: extra tanks list + Add additional tank UI

- Persist extras as JSON; migrate `tank2_enabled` once → extra id 2. No enable checkbox.
- Add additional tank / Remove (stable ids, cap 8). Active spinner = Tank 1 + extras + From PID.
- `tank2Enabled` derived from extra id 2 so existing dual runtime still sees tank 2 until Phase 3.

## 2026-08-31 - Phase 3: N-tank runtimes + hardware doc copy

- Map of tank runtimes by id; gal/hr only into the active id session; extras register only listed Tank{N} PIDs (id 2 keeps Tank2 names).
- Session/bin paths `sessions-t{N}/` and `bins-t{N}.json`. From PID is 1-based tank id; fill-snap debounce on switch.
- Copied N-tank hardware wording to `docs/fix-fuel-second-tank.md`.

## 2026-08-31 - Execute start: fix-fuel-backcompat-notice-20260831-1240-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-backcompat-notice-20260831-1240-plan.md`
- Scope: minSdk 21; API-branched FGS vs startService; optional fuel-level shade notice (default on); AIDL getPIDValues/setPIDData fallbacks. No mapping/sources/tanks/bind-flag changes.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-31 - Phase 1: minSdk 21, FGS/notice branches, AIDL fallbacks

- `minSdk = 21`; API 21–25 `startService` (optional live notice); API 26–33 FGS without specialUse type; API 34+ `specialUse`.
- Checkbox `show_fuel_notification` default on; off uses `fordfuel_quiet` / static text on 26+, no shade notice on 21–25.
- Battery dialog only API 31+; `getPIDValues`/`setPIDData` fallbacks; Class `getSystemService` and `FLAG_IMMUTABLE` guarded ≥23.

## 2026-09-03 - Execute start: fix-fuel-chunk-usable-units-20260903-2109-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-chunk-usable-units-20260903-2109-plan.md`
- Scope: L/hr→gal in integrateGalHr; usable = robust 100×mappedGal/span; identity holes; rebuild bins.json from smoothed jsonl once. No autostart/N-tanks/notice/012F-help changes.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-09-03 - Phase 1: L/hr units, chunk usable, ECU-down jsonl rebuild

- `integrateGalHr` uses `Units.toGallons` from PID unit (L/hr, cc/min, gal/hr, gal/min); log unit once.
- Usable = 100×mappedGallons/span after 3× density outlier drop; holes identity usable/100.
- Rebuild bins from smoothed jsonl only on ECU-down (`onEcuAway`), then `maybeLearnUsable` so existing >20pt sessions yield a first estimate without another drive.

## 2026-09-07 - Execute start: fix-fuel-torque-volume-units-20260907-0742-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-torque-volume-units-20260907-0742-plan.md`
- Scope: raw AIDL flow unit (never default gal/h; empty → liters); remaining PID gal vs l from getPreferredUnit. Internal math stays gallons. No chunk-formula/autostart/N-tanks changes.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-09-07 - Phase 1: raw flow unit in, preferred remaining unit out

- No `gal/h` default; empty/unknown flow unit treated as SI liters. `gal` label with session mean > 8 gal/h still ×0.264172.
- Remaining (and extra-tank remaining) published as `l` or `gal` from `getPreferredUnit`. Internal jsonl/bins stay gallons.

## 2026-09-07 - Execute start: fix-fuel-register-extra-pids-20260907-1619-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-register-extra-pids-20260907-1619-plan.md`
- Scope: once after bind, `sendPIDDataV2` for [FixFuel]/[OldFord] PIDs (and extras in list) so they appear in Manage extra PIDs. Keep setPIDInformation for values. No 1 Hz V2, no Ford/012F write.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-09-07 - Phase 1: sendPIDDataV2 once at bind

- After bind, register [FixFuel] Smoothed/Corrected/Remaining/DTE and [OldFord] aliases (plus extras in list) with empty mode/header, equation 0, pluginName org.dlang.torque.fordfuel.
- Values still via setPIDInformation each sample. No V2 on 1 Hz path. Remaining unit gal/l from preferred.

## 2026-09-07 - Execute start: fix-fuel-usable-outlier-slosh-20260907-1638-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-usable-outlier-slosh-20260907-1638-plan.md`
- Scope: drop 0.05 gal slosh crumbs before median; keep ~0.35 gal/%; no currentUsable death-spiral cap; bins_v4 rebuild on ECU-down. No sendPIDDataV2 / preferred-unit / autostart changes.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-09-07 - Phase 1: slosh crumbs out of usable median; bins_v4

- Drop bins at/near the 0.05 gal observation floor (gal ≤ 0.10) before the density median; drop <¼ and >3× that center. No currentUsable cap.
- estimateUsable = 100 × mappedGallons / coverageSpan(kept). Rebuild flag bins_v4_slosh (ECU-down).
- Local replay of sandbox/research/tank-8gal-20260907/bins.json → ~28 gal (not ~8).

## 2026-09-07 - Execute start: fix-fuel-extrapid-modepid-20260907-1636-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-extrapid-modepid-20260907-1636-plan.md`
- Scope: sendPIDDataV2 ModeAndPID unique plugin hex (ffd001…) so rows appear in Manage extra PIDs. Header empty, equation 0, no bus poll. Keep setPIDInformation. Help one line. No fuel-math/units/autostart changes.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-09-07 - Phase 1: ModeAndPID ffd001… on sendPIDDataV2

- Unique plugin hex per row (ffd001–004 FixFuel, ffd011–014 OldFord, ffd0n1–4 extra tanks). Header empty, equation 0, no adapter poll.
- Help: Manage extra PIDs lists Corrected after bind; Overrides PID → Fuel Level (From Engine ECU).

## 2026-09-07 - Execute start: fix-fuel-hold-glitch-override-20260907-1746-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-hold-glitch-override-20260907-1746-plan.md`
- Scope: hold raw <1% (and optional >15pt drop) when last good >5%; fill-snap vs held smoothed; sendPIDDataV2 equation not 0. Help: delete 0-equation extra PIDs. No chunk/L/hr/autostart changes.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-09-07 - Phase 1: hold 0.3% junk frames; extra-PID equation empty

- isValidSample: last good >5% and raw <1% (or drop >15 pt) → hold, no ingest, no fill-snap. Fill compares raw to held smoothed.
- sendPIDDataV2 equation empty (not 0) so Manage extra PIDs does not import a 0% duplicate. Help: delete old 0-equation rows; Overrides only on live Corrected.

## 2026-09-07 - Execute start: fix-fuel-override-csv-ref-20260907-1803-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-override-csv-ref-20260907-1803-plan.md`
- Scope: asset CSV [FixFuel] 012F Override equation = plugin Corrected (different long name). Write Downloads/FixFuel-012F.csv; copy extendedpids if writable. Help: Import CSV. Do not sendPIDDataV2 for Overrides.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-09-07 - Phase 1: override CSV to Downloads (equation references Corrected)

- Asset `FixFuel-012F.csv`: Name `[FixFuel] 012F Override`, Short `F012F`, empty ModeAndPID, equation `[FixFuel] Corrected Fuel Level`.
- On activity start and Torque bind: write picker-visible Downloads/FixFuel-012F.csv (MediaStore Q+); copy Torque extendedpids/ only if writable. Log paths.
- Help: ⋮ → Import CSV file; Overrides on 012F Override only; gauges stay plugin Corrected.
- Did not sendPIDDataV2 as the Overrides path; no new storage permission.

## 2026-09-07 - Execute start: fix-fuel-write-012f-20260907-1831-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-write-012f-20260907-1831-plan.md`
- Scope: each sample, setPIDInformation on stock Fuel Level (From Engine ECU) / 012F with plugin Corrected; checkbox default on; never read 012F as Ford input; no extra-PID equations / sendPIDDataV2 as Overrides path.
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-09-07 - Phase 1: write Corrected onto Torque stock 012F

- After publish, setPIDInformation on Fuel Level (From Engine ECU) and listAllPIDs 012F id if present. Checkbox Write corrected % onto Torque Fuel Level (012F) default on; off skips the write.
- Never getPIDValues 012F as Ford input; do not write [FORD]Fuel Level. Help: clear Ford extra-PID override; delete imported 012F Override extra PID; no Import CSV / Overrides PID.
- FuelSources.kt unchanged: default remains ford_16c1; Ford path is [FORD]Fuel Level.

## 2026-10-02 - Execute start: fix-fuel-since-fill-20261002-1759-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-since-fill-20261002-1759-plan.md`
- Scope: tank-1 gallons left are the fill anchor minus fuel burned since that fill, including later sessions. Persist `fill_anchor_gal` and `since_fill_used_gal`. Extra tanks stay on the map integral.
- No `./deploy` by coder. Compile only via `./build_app`.

## 2026-10-09 - Execute start: fix-fuel-v2-20261009-0249-plan.md

- Magic-approved plan: `sandbox/plans/fix-fuel-v2-20261009-0249-plan.md`
- Scope: port Fix Fuel v2 into `app/` (math, raw log, profiles, probes, odometer, UI) per that plan. On-van Detect is not run.
- `./exec-preflight` reported Host `/home/dlang/git/torque-ford-gas-tracking` against this checkout `/home/dlang/git/torque-ford-gas-tracking/master`. Worktree stamp is `master`. Implementing in this checkout.
- No `./deploy` by coder. Compile via `./build_app`. Unit tests via `./gradlew :app:testDebugUnitTest`.

## 2026-10-09 - Fuel v2 phase 4 probes

- On-van Detect was not run.

## 2026-10-09 - Execute start: play-fuel-smoothing-20261009-1717-plan.md

- Magic-approved plan: `sandbox/plans/play-fuel-smoothing-20261009-1717-plan.md`
- Scope: first-time Fuel Smoothing identity, strip old-plugin leftovers, battery list, tip jar, launcher picture. No Play upload, no keystore, no GitHub push.
- `./exec-preflight` reported Host `/home/dlang/git/torque-ford-gas-tracking` against this checkout `/home/dlang/git/torque-ford-gas-tracking/master`. Worktree stamp is `master`. Implementing in this checkout.
- No `./deploy` by coder. Compile via `./build_app`. Unit tests via `./gradlew :app:testDebugUnitTest`.

## 2026-10-16 - Execute start: pieces-reprocess-20261016-2304-plan.md

- Approved plan: `sandbox/plans/pieces-reprocess-20261016-2304-plan.md`
- Scope: neutral piece cutter (`Pieces`) and analysis-version reprocess (`ProfileStore.reprocessAll`). Chunk, band, and Kalman fits stay. No phone install.

## 2026-10-17 - Execute start: chunkfit-on-pieces-20261016-2305-plan.md

- Approved plan: `sandbox/plans/chunkfit-on-pieces-20261016-2305-plan.md`
- Scope: each stored piece is one ChunkFit chunk; refit model.json from pieces.jsonl. Kalman still reads kalmanDrives. No phone install. Do not edit Kalman.kt. Plan 1 already committed at 5c336199.

## 2026-10-17 - Execute start: adb-data-transfer-20261016-2310-plan.md

- Approved plan: `sandbox/plans/adb-data-transfer-20261016-2310-plan.md`
- Scope: adb-only export and exact restore (provider, ustar, manifest field codec, importReplace). No content checks. No phone install. No adb content command.
- David directed this plan before identity-c410f1. That plan is not implemented in this commit.

## 2026-10-17 - Execute start: datatransfer-developer-doc-20261016-2311-plan.md

- Approved plan: `sandbox/plans/datatransfer-developer-doc-20261016-2311-plan.md`
- Scope: one developer page (`docs/DEVELOPER.md`) for adb export/restore, the eval harness, and piece constants. User-facing docs stay silent. No phone install. Adb data-transfer plan already committed.

## 2026-10-17 - Execute start: revert-temp-diag-20261016-2312-plan.md

- Approved plan: `sandbox/plans/revert-temp-diag-20261016-2312-plan.md`
- Scope: `git revert` of TEMP diagnostic `076f4b13`. Keep the summary log, identity header, and `recording`. Fingerprint gate waived in chat 2026-10-17. No phone install. No `--play`. No adb.

## 2026-10-19 - Execute start: kalman-band-independent-20261016-2306-plan.md

- Approved plan: `sandbox/plans/kalman-band-independent-20261016-2306-plan.md`
- Scope: one Kalman apply packet per part from Pieces (block medians, per-row level error); BandFit reads Pieces directly. Neither names ChunkFit. No phone install.

## 2026-10-19 - Endpoint eval harness

- Added EndpointEvalTest and forwarded fuelsmoothing.evalDir into the unit-test JVM.
- Replay passed: 1 test, 0 failures, 58.405s. Report sandbox/research/endpoint-eval-20261019-2332.md.
- ENDPOINT, Kalman.INPUT, FIT_WEAK, and ANALYSIS_VERSION were not changed.

## 2026-10-19 - Execute start: rollup-summary-line-20261016-2308-plan.md

- Approved plan: `sandbox/plans/rollup-summary-line-20261016-2308-plan.md`
- Scope: rollup writes rollup-summary.txt and returns that one line. Session close logs tank id plus the summary. gap.txt is still written. ANALYSIS_VERSION stays 4. No phone install.
- test59 tank dirs are both named tank so the summary profile id matches across the cached and uncached trees.

## 2026-10-19 - Execute start: identity-c410f1-20261016-2309-plan.md

- Approved plan: `sandbox/plans/identity-c410f1-20261016-2309-plan.md`
- Scope: firm profiles send 2216C1 once per connect until the stored probe is ok, using header C410F1 on J1850 PWM. ANALYSIS_VERSION stays. No phone install. No adb.

## 2026-10-20 - Execute start: release-shrink-icon-20261020-0005-plan.md

- Approved plan: `sandbox/plans/release-shrink-icon-20261020-0005-plan.md`
- Scope: release shrinks resources and is not debuggable; debug stays unminified, unshrunk, and debuggable. Session-end snapshot is logged only when BuildConfig.DEBUG. Launcher becomes lossless WebP, same pixels. No phone install. No --play.

## 2026-10-20 - Execute start: no-play-version-tags-20261020-0333-plan.md

- Approved plan: `sandbox/plans/no-play-version-tags-20261020-0333-plan.md`
- Scope: a Play build commits app/versionCode.txt only and creates no git tag. versionName stays git describe on the real version tag, excluding *+* tags. Delete local tags v0.9.1+3, v0.9.2+4, and v0.9.2+5 after each subject is Play build: versionCode N. No phone install. No --play. No push.

## 2026-10-20 - Execute start: about-license-libraries-20261020-1506-plan.md

- Approved plan: `sandbox/plans/about-license-libraries-20261020-1506-plan.md`
- Scope: About dialog lists the installed version, this app's copyright, and the open source libraries shipped in the release bundle. No phone install. No --play. No adb. No new Activity.

## 2026-10-21 - Kalman lvfit6 port, phase 1

- Plan sandbox/plans/kalman-lvfit6-port-20261021-0004-plan.md. Speed becomes km/h. Algorithm tests and the three van resource trees leave the repo. Estimator port is phase 2, after this commit.

## 2026-10-21 - Kalman lvfit6 port, phase 2

- Plan sandbox/plans/kalman-lvfit6-port-20261021-0004-plan.md. Kalman.VERSION 3 replaces the estimator with the level-from-fuel map. Pieces.ANALYSIS_VERSION stays 4.

## 2026-10-21 - Reviewer fix: place() and between-drive previous capture

- Reviewer FAIL on kalman-lvfit6-port-20261021-0004-plan.md. place() attaches a logged fill or parked rise to the part that contains the sample. A between-drive rise uses the immediate previous non-empty capture, shortened to the samples it has.

## 2026-10-28 - Fuel source sticks, drive end never throws

- Plan sandbox/plans/fuel-source-sticks-20261028-1933-plan.md. Commit 1: a non-finite session-end snapshot number is JSON null, and a snapshot write failure is logged; close still clears the session and returns samples. Commit 2: one profile store, the service notice, and the page, including extra tanks.
