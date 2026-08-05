---
name: master-merge
description: >
  Master PR review and special-file merge per MASTER_AGENT_MANDATE.md. Independent
  review of local PR markdown, audit_merge, eng-log/TODO/project-facts handling,
  build gate (build_app or host equivalent). Same process on VehicleExpenses and
  library hosts. Use when reviewing or merging a feature branch / PR in master worktree.
when-to-use: "review PR, merge branch, master merge, integrate branch, /master-merge"
---

# master-merge (Master)

Follow **`MASTER_AGENT_MANDATE.md`** in full (read that file first). This skill is a summary only.

**Applies to VehicleExpenses and library hosts** (remotetable, extractmail, …).

## Sandbox path

Same resolution as prepare-local-pr: `project.config` `sandbox_dir` / `sandbox_path`, else `dev-ai-interaction/` or `sandbox/`. Call it **`$SANDBOX`**.

## Typical triggers

- Review PR / review branch (forensic only unless user says merge)
- Merge / integrate branch (full special-file protocol + build gate)

## Steps (summary — mandate is authoritative)

1. Read `$SANDBOX/PRs/PR-<branch>.md`.
2. `git log master..<branch>` and `git diff master..<branch>` vs plans.
3. Reject unauthorized / plan-violating changes.
4. On merge:
   - `python3 $SANDBOX/audit_merge.py <branch>` if present; else note missing and continue with manual divergence check
   - **`./install-merge-drivers.sh`** when present
   - **`./merge-branch-into-master.sh <branch>`** (or host equivalent)
   - **ENGINEERING_LOG:** ve-englog + `append-to-engineering-log` → third version. No `chattr -a`.
   - **TODO / project-facts:** ve-special-ours + restore specials → master base; then `todo-close` / `todo-append` and facts prune vs branch delta
   - **POST-MERGE GATE (before build commit):**
     ```bash
     git diff --cached --name-only
     # Feature merge must list feature paths, not ONLY ENGINEERING_LOG.md
     ```
     If only eng-log is staged while the branch changed product files → **FAILED**. Do not build/commit.
   - **Build gate:**
     - VE: `./build_app` (commits merge when that is project convention)
     - Library: `./build` or test command from `project.config` `build_command` if set; if none exists yet, complete special-file merge carefully and document “no compile gate”
5. After successful merge, move finished plans to `$SANDBOX/historical-plans/` if still in `plans/`. Do **not** auto-run `./update-rules.sh` unless human requests a brain sweep.
6. Never set `works` tag. Inform user to run `./remove_worktree.sh` when done.

## Failed merge recovery

See `docs/reference/ORCHESTRATION_MERGE_INFRA_SYNC.md` (VE) and `MASTER_AGENT_MANDATE.md`. Do not promote one-off reset scripts as normal flow. Orchestration (or lib orch) is source of truth for merge script fixes, then `./update-rules.sh`.

## Expectation

Independent review should ideally find **nothing** if coder used prepare-local-pr well.
