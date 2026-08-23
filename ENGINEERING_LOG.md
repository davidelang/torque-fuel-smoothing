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
