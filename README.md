# orchestration-example

**App-free** multi-agent orchestration host: launchers, policies, Landlock helper,
group-refresh shell, setup/update/fix tooling — **no product application**.

## Purpose

1. Golden layout for standing up a new multi-agent repo next to VehicleExpenses.
2. Drift detection: compare this tree / VE against other clones under `git_home`
   for **orchestration tooling** and **app-agnostic policies**.

## Quick start

```bash
cp project.config.example project.config
# set git_home=/absolute/path/to/git  (required)
source ./env                 # umask 002 + group refresh via ./refresh-shell
./setup_agent.sh my-feature  # creates agent-N worktree
./run-grok-planner
```

Landlock (mutation-only) wraps agents via `agent-landlock` from `run-grok*`.

## Drift report

```bash
./tools/report-orchestration-drift.sh
# or with AI assistance:
#   see docs/ORCHESTRATION_DRIFT.md and tools/prompts/orchestration-drift-agent.md
```

## Relation to VehicleExpenses

VehicleExpenses is the living SoT for orchestration **while** product work continues
there. This repo should track **app-agnostic** copies. Use the drift tool before
porting changes into library hosts (extractmail, remotetable, …).

Library hosts use unprefixed names: `env`, `refresh-shell` (VE still has `ve-*`).
