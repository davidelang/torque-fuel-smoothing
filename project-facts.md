# project-facts.md — torque-ford-gas-tracking

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `sandbox/` (absolute: /home/dlang/git/torque-ford-gas-tracking/sandbox/)
- Layout: `/home/dlang/git/torque-ford-gas-tracking/` branch `orchestration`; `/home/dlang/git/torque-ford-gas-tracking/master/` branch `master`
- git_home: /home/dlang/git
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock`
- Groups: source `./env` (refresh-shell setuid)
- Android app: `app/` module `:app`, applicationId `org.dlang.torque.fordfuel` (Torque plugin; Kotlin + AGP, no Compose/Hilt/KSP)
- Compile: `./build_app` only (`assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`)
- Human install: `./deploy` (`PACKAGE_NAME=org.dlang.torque.fordfuel`; alias `phone` reads gitignored `deploy_serial=`)
- SDK: gitignored `local.properties` `sdk.dir=` (no machine-path default in scripts)

