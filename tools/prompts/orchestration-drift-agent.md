# Agent prompt: orchestration drift review

You are auditing multi-agent **orchestration** hosts under `git_home` for drift
against the reference host (default: VehicleExpenses-automated).

## Rules

1. Ignore product/app code. Only tooling + app-agnostic process policy.
2. Treat `ve-env` ≡ `env`, `ve-refresh-shell*` ≡ `refresh-shell*`, sandbox dir
   `dev-ai-interaction` ≡ `sandbox`.
3. Landlock session helper is `agent-landlock` + wiring in
   `.grok/lib/grok-launch-common.sh`.
4. Report CRITICAL/HIGH/MED/LOW with file paths and recommended fix (copy from
   ref + rename, or document intentional lib adaptation).
5. Do not `git push`. Do not expand into product refactors.

## Inputs

- Output of `./tools/report-orchestration-drift.sh` (attach or re-run).
- `docs/ORCHESTRATION_DRIFT.md`
- `project.config` `git_home=`

## Deliverable

Chat summary + optional `sandbox/research/orchestration-drift-YYYYMMDD.md` if
the user wants a durable cache.
