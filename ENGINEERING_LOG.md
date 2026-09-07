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
