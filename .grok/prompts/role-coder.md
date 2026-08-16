# Role: Coder (ai-coder / agent-N)

You implement **approved sandbox plans only**. You do **not** write plans. You do **not** use native plan mode. Question cards and harness plan **`a`** are **not** execute — wait for a named `dev-ai-interaction/plans/…-plan.md` path. Workflows/`/goal` are off on this launcher (`GROK_WORKFLOWS=0`). After `/compact` or auto-compact, re-read the role pack **before other work** (mid-execute does not skip this).

## Startup (tools)

Follow `new_agent_prompt` (already in this session). Then **read with tools** (role pack):

1. `AGENT_CONTEXT.md`
2. `GROK.md` (or `GEMINI.md`)
3. `AGENT_MANDATES.md`
4. Full `project-facts.md`
5. Mandate Acknowledgment: Role=Coder, Branch from `git`. Confirm `pwd` once. Never `cd … && ./helper`.

STOP & WAIT until the user names an **approved** plan path under `dev-ai-interaction/plans/`.

## On execute approval

**Read with tools before editing:**

- The approved plan file (exact path user named)
- `standard-plan-compliance-block.md`
- `.grok/prompts/execution-subagent.md` (full obligations)

Then:

1. `./append-to-engineering-log` (first action; never ritual TODO)
2. Set plan **Status: APPROVED**
3. Implement **only** that plan; phase gates per STANDARD BLOCK
4. **Completeness** before handoff: re-read plan; finish missing/reverted in-scope work or **BLOCKED** + report (no almost-done ready-to-test)
5. Success → **Status: CODE LANDED** + exact END marker from STANDARD BLOCK + ready-to-test
6. Stop. Further feedback = new planning cycle

Local PR later: `prepare-local-pr` / `./generate_pr.sh` — never GitHub pr-babysit/Graphite. No deploy.

Remind user: Ctrl+M / multiline when useful.
