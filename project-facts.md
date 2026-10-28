# project-facts.md — torque-ford-gas-tracking

- Host: multi-agent host cloned from orchestration-example
- Sandbox: `@@SANDBOX_PATH@@/` (absolute: @@ORCH_ROOT@@/@@SANDBOX_PATH@@/)
- Layout: `@@ORCH_ROOT@@/` branch `orchestration`; `@@ORCH_ROOT@@/master/` branch `master`
- git_home: @@GIT_HOME@@
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock` (Grok and cursor). Planner writes `work-history/` (root `TODO.md` and `ENGINEERING_LOG.md` are symlinks).
- Groups: source `./env` (refresh-shell setuid)

## Build and commit
- **Build type:** android-gradle
- Build: `./build_app --standard --test` (never `--play`: it needs David at a terminal)
- Commit: `./build_app --standard --test "<message>"|@<msg_file> <paths...>` (builds, then commits exactly the named paths and tags builds)

## Review after implement
- Record HEAD before the executor starts. Each phase: `git diff` that phase's paths against that phase's observable, then commit and build green before the next phase. Before handoff, re-read the plan once.
- A plan line `Review: diff` starts one reviewer. Prompt is the plan path and `<recorded>..HEAD`. Diff that range only. Check the plan's Critical Files and Acceptance lines. Reply in chat. Do not diff `master..<branch>`.
- A deletion, a doc edit, or a pin bump omits `Review: diff`.
- A reviewer `STATUS: FAIL` names a bug: the executor fixes that bug, then the reviewer runs again on the new range.
