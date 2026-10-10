# project-facts.md — torque-ford-gas-tracking

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `@@SANDBOX_PATH@@/` (absolute: @@ORCH_ROOT@@/@@SANDBOX_PATH@@/)
- Layout: `@@ORCH_ROOT@@/` branch `orchestration`; `@@ORCH_ROOT@@/master/` branch `master`
- git_home: @@GIT_HOME@@
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock`
- Groups: source `./env` (refresh-shell setuid)
- Android app: `app/` module `:app`. Namespace and Kotlin package `com.davidlang.torque.fuelsmoothing`. Default applicationId is `com.<user>.torque.fuelsmoothing`, where `<user>` is the OS user lowercased, characters outside `a-z0-9` replaced by `_`, then trimmed, and blank becomes `local`. Play applicationId `com.davidlang.torque.fuelsmoothing` only when Gradle property `fuelsmoothing.official` is `true` or env `FUELSMOOTHING_OFFICIAL` is `1` (Torque plugin; Kotlin + AGP, no Compose/Hilt/KSP)
- Compile: `./build_app` only (`assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`)
- Human install: `./deploy` (package name read from the APK `package: name=` line; alias `phone` reads gitignored `deploy_serial=`)
- SDK: gitignored `local.properties` `sdk.dir=` (no machine-path default in scripts)
