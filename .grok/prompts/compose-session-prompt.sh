#!/bin/bash
# compose-session-prompt.sh — concatenate ordered repo-relative files into a Grok session prompt.
# Usage (from worktree root, or pass SCRIPT_DIR via first existing path resolution):
#   ./.grok/prompts/compose-session-prompt.sh new_agent_prompt .grok/prompts/role-coder.md
# Env: GROK_PROMPT_ROOT — optional absolute worktree root (default: discover from this script).
set -euo pipefail

ROOT="${GROK_PROMPT_ROOT:-}"
if [ -z "$ROOT" ]; then
  HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  # .grok/prompts -> worktree root
  ROOT="$(cd "$HERE/../.." && pwd)"
fi

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <rel-path> [rel-path...]" >&2
  exit 2
fi

out=""
for rel in "$@"; do
  f="$ROOT/$rel"
  if [ ! -f "$f" ]; then
    echo "ERROR: missing prompt pack file: $rel (resolved $f)" >&2
    exit 1
  fi
  {
    echo "===== BEGIN $rel ====="
    cat "$f"
    echo
    echo "===== END $rel ====="
    echo
  }
done
