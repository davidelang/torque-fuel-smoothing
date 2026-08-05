# project-facts.md — orchestration-example

- Host: pure multi-agent orchestration template (no app)
- Sandbox: `sandbox/`
- git_home: set in project.config to absolute path (e.g. /home/dlang/git)
- Launch: `./run-grok-*` via `.grok/lib/grok-launch-common.sh` + `agent-landlock`
- Groups: source `./env` (refresh-shell setuid)
