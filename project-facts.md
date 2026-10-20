# project-facts.md — torque-ford-gas-tracking

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `@@SANDBOX_PATH@@/` (absolute: @@ORCH_ROOT@@/@@SANDBOX_PATH@@/)
- Layout: `@@ORCH_ROOT@@/` branch `orchestration`; `@@ORCH_ROOT@@/master/` branch `master`
- git_home: @@GIT_HOME@@
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock`
- Groups: source `./env` (refresh-shell setuid)

## Build and commit
- **Build type:** android-gradle
- Build: `./build_app --standard --test` (never `--play`: it needs David at a terminal)
- Commit: `./build_app --standard --test "<message>"|@<msg_file> <paths...>` (builds, then commits exactly the named paths and tags builds)
