# project-facts.md — torque-ford-gas-tracking

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `@@SANDBOX_PATH@@/` (absolute: @@ORCH_ROOT@@/@@SANDBOX_PATH@@/)
- Layout: `@@ORCH_ROOT@@/` branch `orchestration`; `@@ORCH_ROOT@@/master/` branch `master`
- git_home: @@GIT_HOME@@
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock` (Grok and cursor). Planner writes `work-history/` (root `TODO.md` and `ENGINEERING_LOG.md` are symlinks).
- Groups: source `./env` (refresh-shell setuid)
- Android app: `app/` module `:app`. Namespace and Kotlin package `com.davidlang.torque.fuelsmoothing`. applicationId is `com.davidlang.torque.fuelsmoothing` when `-Pfuelsmoothing.official=true` or `FUELSMOOTHING_OFFICIAL=1`, or the given id when `-Pfuelsmoothing.dev.package` is set. Gradle fails when neither or both are set. (Torque plugin; Kotlin + AGP, no Compose/Hilt/KSP)
- Compile: `./build_app --standard` only (`assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`)
- Human install: `./deploy` (package name read from the APK `package: name=` line; alias `phone` reads gitignored `deploy_serial=`)
- SDK: gitignored `local.properties` `sdk.dir=` (no machine-path default in scripts)

## Build and commit
- **Standard build:** debug
- **Test build:** none
- Build: `./build_app --standard`. The official choice is `fuelsmoothing_official=1` in `project.config`, which passes `-Pfuelsmoothing.official=true`. Never `--play`.
- Commit: `./build_app --standard "<message>"|@<msg_file> <paths...>` (commits the named paths, then builds, and tags builds)

## Review after implement
- Record HEAD before the executor starts. Each phase: `git diff` that phase's paths against that phase's observable, then commit and build green before the next phase. Before handoff, re-read the plan once.
- A plan line `Review: diff` starts one reviewer. Prompt is the plan path and `<recorded>..HEAD`. Diff that range only. Check the plan's Critical Files and Acceptance lines. Reply in chat. Do not diff `master..<branch>`.
- A deletion, a doc edit, or a pin bump omits `Review: diff`.
- A reviewer `STATUS: FAIL` names a bug: the executor fixes that bug, then the reviewer runs again on the new range.
