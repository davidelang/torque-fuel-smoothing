# torque-ford-gas-tracking

Cloned from orchestration-example. Multi-agent orchestration host: launchers,
policies, Landlock helper, group-refresh shell, setup/update/fix tooling.

## Layout

- `@@ORCH_ROOT@@/` — branch `orchestration` (managing tree)
- `@@ORCH_ROOT@@/master/` — worktree on branch `master`
- `@@SANDBOX_PATH@@/` — plans, research, PRs, failure logs

## Quick start

```bash
source ./env
./setup_agent.sh my-feature
./run-grok-planner
```

Landlock (mutation-only) wraps agents via `agent-landlock` from `run-grok*`.
