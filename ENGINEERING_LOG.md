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
