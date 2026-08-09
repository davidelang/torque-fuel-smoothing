#!/usr/bin/env bash
# stop-completeness-gate.sh — thin Stop hook (Grok 1.0).
# Blocks turn-end only when the last assistant message claims ready-to-test /
# CODE LANDED / goal complete WITHOUT evidence markers.
# Opt-in strictness: set VE_STOP_COMPLETENESS=1 (default off to avoid false blocks
# on normal chat). When off, always allow stop (observe-only residual path).
#
# Does NOT force re-entry to native plan mode (project stance: avoid plan mode).
set -euo pipefail

input=$(cat || true)
reason=$(echo "$input" | jq -r '.reason // empty' 2>/dev/null || true)
# Only gate genuine end_turn
if [[ "$reason" != "end_turn" && -n "$reason" ]]; then
  exit 0
fi

if [[ "${VE_STOP_COMPLETENESS:-0}" != "1" && "${VE_STOP_COMPLETENESS:-0}" != "true" ]]; then
  exit 0
fi

msg=$(echo "$input" | jq -r '.lastAssistantMessage // empty' 2>/dev/null || true)
[[ -z "$msg" ]] && exit 0

# Claims of completion
if ! echo "$msg" | grep -qiE 'ready to test|CODE LANDED|goal (is )?complete|results ready to test'; then
  exit 0
fi

# Evidence that completeness was considered
if echo "$msg" | grep -qiE 'END OF EXECUTION TURN|evidence:|Status: CODE LANDED|Status: BLOCKED|landlock-matrix|grok1-infra-evidence|matrix_exit=0'; then
  exit 0
fi

# Block and ask for evidence
printf '%s\n' '{"decision":"block","reason":"Completeness gate (VE_STOP_COMPLETENESS=1): message claims ready-to-test/CODE LANDED/goal complete without END marker or evidence path. Add evidence (or Status BLOCKED residuals) before finishing."}'
exit 0
