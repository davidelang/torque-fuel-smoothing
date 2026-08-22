# ENGINEERING_LOG


## 2026-08-22 - Execute start: e350-fuel-build-and-corpus-20260822-0224-plan.md

- Magic-approved plan: `sandbox/plans/e350-fuel-build-and-corpus-20260822-0224-plan.md`
- Scope: VE-shaped `build_app`/`deploy` + Kotlin/Gradle Torque plugin (session corpus, EMA, partial bins).
- No `./deploy` / `adb install` by coder; compile only via `./build_app`.

## 2026-08-22 - Phase 4: slider / Overrides PID path

- Public Torque API has no documented write for the profile `tankUsed` slider (research 2026-08-21).
- Plugin attempts `storeInProfile("tankUsed", …)` about every 1% and always `setPIDInformation` onto `[FORD]Fuel Level`.
- Human path remains Overrides PID + Use vehicle fuel level sensor; Plugin DTE is published as `Plugin DTE`.
