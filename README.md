# torque-ford-gas-tracking

Cloned from orchestration-example. Multi-agent orchestration host: launchers,
policies, Landlock helper, group-refresh shell, setup/update/fix tooling.

## Layout

- `/home/dlang/git/torque-ford-gas-tracking/` — branch `orchestration` (managing tree)
- `/home/dlang/git/torque-ford-gas-tracking/master/` — worktree on branch `master`
- `/home/dlang/git/torque-ford-gas-tracking/sandbox/` — plans, research, PRs, failure logs

## Quick start

```bash
source ./env
./setup_agent.sh my-feature
./run-grok-planner
```

Landlock (mutation-only) wraps agents via `agent-landlock` from `run-grok*`.
