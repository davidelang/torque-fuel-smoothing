# project-facts.md — torque-ford-gas-tracking

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `@@SANDBOX_PATH@@/` (absolute: @@ORCH_ROOT@@/@@SANDBOX_PATH@@/)
- Layout: `@@ORCH_ROOT@@/` branch `orchestration`; `@@ORCH_ROOT@@/master/` branch `master`
- git_home: @@GIT_HOME@@
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock`
- Groups: source `./env` (refresh-shell setuid)
- Android app: `app/` module `:app`, applicationId `org.@@PRIMARY_USER@@.torque.fordfuel` (Torque plugin; Kotlin + AGP, no Compose/Hilt/KSP)
- Compile: `./build_app` only (`assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`)
- Human install: `./deploy` (`PACKAGE_NAME=org.@@PRIMARY_USER@@.torque.fordfuel`; alias `phone` reads gitignored `deploy_serial=`)
- SDK: gitignored `local.properties` `sdk.dir=` (no machine-path default in scripts)
