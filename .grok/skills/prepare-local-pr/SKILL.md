---
name: prepare-local-pr
description: >
  Prepare a local multi-agent PR for this project (not GitHub). Pre-submit code
  review vs approved plan, history cleanup, backup tag, generate_pr.sh to
  $sandbox_dir/PRs/. Use when asked to prepare PR, generate PR, cleanup
  history for merge, or /prepare-local-pr. Never use gh pr create, Graphite, or pr-babysit.
  Same skill on VehicleExpenses and first-party library hosts.
when-to-use: "prepare PR, generate PR, cleanup history, local PR, /prepare-local-pr"
---

# prepare-local-pr (Coder)

You prepare a **local** PR document for Master review. This project does **not** use GitHub PR create/babysit workflows.

**Applies to VehicleExpenses and library hosts** (remotetable, extractmail, …) with the same steps.

## Sandbox path

Resolve once at start:

1. `project.config` → `sandbox_dir` (or legacy `sandbox_path`)
2. Else if `./dev-ai-interaction/` exists → that
3. Else `./sandbox/`

Call this **`$SANDBOX`**. Plans live under `$SANDBOX/plans/`. PRs under `$SANDBOX/PRs/`. Historical plans under `$SANDBOX/historical-plans/`.

## Preconditions

- Feature branch (not `master` or `orchestration`).
- Implementation for the approved plan is done.
- Build gate when the host has one: VE → `./build_app` succeeded; library → `./build` or documented tests if present; if no build yet, note that in the PR.
- Confirm `pwd` once; use `./helper` only (no `cd … &&`).

## Steps

1. **Pre-submit code review** (you): diff vs the approved plan path(s). Fail if scope creep / silent improvements. List residual risks.
2. **History cleanup** on this branch: logical commits; create/update `backup-<branch>` if required; never `git commit --amend` of published history without explicit user protocol.
3. Run **`./generate_pr.sh`** with plan paths → `$SANDBOX/PRs/PR-<branch>.md`. Include review summary and any TODO items that merge should **close**.
4. **Third_party (VE consumers only):** if this branch touched `third_party/`, note in PR: mode (ro/rw), lock sha vs HEAD, artifacts/patches, whether a library PR is also required.
5. **Archive completed plans:** move designated CODE LANDED / done plan file(s) from `$SANDBOX/plans/` to `$SANDBOX/historical-plans/`.
6. `./append-to-engineering-log` note: PR prepared, path to PR doc.
7. Stop and tell the user: ready for Master (`./run-grok-master`) independent review + merge. Do not merge yourself.

## Forbidden

- `gh pr create`, Graphite `gt submit`, `/pr-babysit`, force-push without project rules.
- Deploy / `git push` (human only).
