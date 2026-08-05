# Orchestration drift (app-agnostic)

## Goal

Detect when multi-agent **tooling** and **process policies** diverge across hosts
under the same `git_home` (e.g. `/home/dlang/git`), using VehicleExpenses as the
default reference **for orchestration**, not for Android/app product code.

## What counts as in-scope

| In scope | Out of scope |
|----------|----------------|
| `run-grok*`, `setup_agent.sh`, `update-rules.sh`, `fix-perms`, `remove_worktree.sh` | App/source trees (`android/`, `python/`, feature code) |
| `env` / `ve-env`, `refresh-shell` / `ve-refresh-shell*`, `agent-landlock` | Product Gradle/SDK paths except as optional landlock profiles |
| `filter-*-config`, `project.config.example`, `landlock.config*` | Pin consumer app code under third_party src |
| `.grok/lib/grok-launch-common.sh`, packs, role prompts | Device/emulator rules unique to VE product |
| Library-adapted `AGENT_MANDATES.md` / STANDARD BLOCK **process** law | VE-only Android device mandates |
| eng-log/TODO helpers, merge drivers, `generate_pr.sh` | |

## Naming map (libs vs VE)

| VE | Library / this example |
|----|-------------------------|
| `ve-env` | `env` |
| `ve-refresh-shell` (+ `.c`, install script) | `refresh-shell` (+ `.c`, `install-refresh-shell.sh`) |
| `dev-ai-interaction/` sandbox | `sandbox/` |

Drift tool treats these pairs as **equivalent paths**.

## How to run

```bash
# machine report (no AI)
./tools/report-orchestration-drift.sh
./tools/report-orchestration-drift.sh --ref /home/dlang/git/VehicleExpenses-automated
./tools/report-orchestration-drift.sh --git-home /home/dlang/git --json

# AI-assisted deep review (paste report + this doc)
# tools/prompts/orchestration-drift-agent.md
```

## Severity guide

- **CRITICAL** — missing launcher, setup_agent cannot seed project.config/smudge, no landlock helper while launch-common expects it, no git_home
- **HIGH** — update-rules/fix-perms/remove_worktree thin stubs vs full VE; env/refresh-shell missing
- **MED** — file present but size/hash drift; policy MD process sections diverged
- **LOW** — comments, Android-only optional files absent on libs (expected)

## When fixing

Prefer: copy/adapt from VE → transform names → library hosts + this example.
Do not invent a third orchestration model.
