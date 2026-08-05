# grok-launch-common.sh — shared machinery for run-grok* (no project law).
# Install at: <repo>/.grok/lib/grok-launch-common.sh
# Thin wrappers set GROK_LAUNCHER_DIR + ROLE_KEY, then source this and call grok_launch_main "$@".
#
# Rules: only in files listed by .grok/prompts/packs/<ROLE_KEY>.pack
# Wiring: project.config (users, sandbox_dir, optional *_model)
#
# shellcheck shell=bash

set -euo pipefail

if [[ -z "${GROK_LAUNCHER_DIR:-}" ]]; then
  echo "ERROR: GROK_LAUNCHER_DIR must be set to the repository worktree root" >&2
  exit 2
fi
SCRIPT_DIR="$(cd "$GROK_LAUNCHER_DIR" && pwd)"

if [[ -f "$SCRIPT_DIR/project.config" ]]; then
  # KEY=VALUE lines only (skip comments/blank). Keep equals — do not split on '='.
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" ]] && continue
    [[ "$line" =~ ^[a-zA-Z_][a-zA-Z0-9_]*= ]] || continue
    # shellcheck disable=SC2163
    export "$line"
  done <"$SCRIPT_DIR/project.config" || true
fi

# Prefer explicit GROK_BIN, then project.config keys (VE uses grok_bin_default).
# project.config may use $HOME/… as literal text — expand after load.
# git_home from project.config is ABSOLUTE (e.g. /home/you/git), not ~/git.
_expand_path() {
  local p="$1"
  p="${p/#\~/$HOME}"
  if [[ "$p" == *'$HOME'* ]]; then
    p="${p//\$HOME/$HOME}"
  fi
  printf '%s' "$p"
}
_raw_bin="${GROK_BIN:-${grok_bin:-${grok_bin_default:-}}}"
if [[ -z "$_raw_bin" || "$_raw_bin" == @@* ]]; then
  _raw_bin="${HOME}/git/grok/bin/grok"
fi
GROK_BIN="$(_expand_path "$_raw_bin")"

# Shared host-clone root for third_party / landlock (absolute path in project.config)
GIT_HOME_CONFIG="${git_home:-}"
if [[ -n "$GIT_HOME_CONFIG" && "$GIT_HOME_CONFIG" != @@* ]]; then
  export GIT_HOME="${GIT_HOME:-$GIT_HOME_CONFIG}"
fi

# Prefer sandbox_dir / sandbox_path from project.config; stamped default:
# sandbox_path=@@SANDBOX_PATH@@  (smudge from project.config; clean restores token)
_sandbox_stamp="@@SANDBOX_PATH@@"
SANDBOX_REL="${sandbox_dir:-${sandbox_path:-}}"
if [[ -z "$SANDBOX_REL" || "$SANDBOX_REL" == @@* ]]; then
  if [[ "$_sandbox_stamp" != @@* && -n "$_sandbox_stamp" ]]; then
    SANDBOX_REL="$_sandbox_stamp"
  fi
fi
if [[ -z "$SANDBOX_REL" || "$SANDBOX_REL" == @@* ]]; then
  # Layout discovery only (no machine-absolute paths)
  if [[ -d "$SCRIPT_DIR/dev-ai-interaction" || -L "$SCRIPT_DIR/dev-ai-interaction" ]]; then
    SANDBOX_REL="dev-ai-interaction"
  elif [[ -d "$SCRIPT_DIR/sandbox" || -L "$SCRIPT_DIR/sandbox" ]]; then
    SANDBOX_REL="sandbox"
  elif [[ -d "$SCRIPT_DIR/../dev-ai-interaction" || -L "$SCRIPT_DIR/../dev-ai-interaction" ]]; then
    SANDBOX_REL="../dev-ai-interaction"
  elif [[ -d "$SCRIPT_DIR/../sandbox" || -L "$SCRIPT_DIR/../sandbox" ]]; then
    SANDBOX_REL="../sandbox"
  else
    SANDBOX_REL="sandbox"
  fi
fi
if [[ "$SANDBOX_REL" = /* ]]; then
  SANDBOX_DIR="$SANDBOX_REL"
else
  SANDBOX_DIR="$SCRIPT_DIR/$SANDBOX_REL"
fi
# Normalize .. in path
SANDBOX_DIR="$(cd "$SANDBOX_DIR" 2>/dev/null && pwd || echo "$SANDBOX_DIR")"

COMPOSE="${SCRIPT_DIR}/.grok/prompts/compose-session-prompt.sh"
if [[ ! -f "$COMPOSE" ]]; then
  echo "ERROR: missing $COMPOSE" >&2
  exit 1
fi
[[ -x "$COMPOSE" ]] || chmod +x "$COMPOSE" 2>/dev/null || true

ANDROID_SHARED=""
if [[ -d "$SCRIPT_DIR/.android-shared" ]]; then
  ANDROID_SHARED="$SCRIPT_DIR/.android-shared"
elif [[ -d "$SCRIPT_DIR/../.android-shared" ]]; then
  ANDROID_SHARED="$(cd "$SCRIPT_DIR/.." && pwd)/.android-shared"
fi

umask "${umask_launch:-002}"

PACK_PATHS=()
EXTRA_ARGS=()
MODEL_ARGS=()
TODO_GATE_FLAGS=()
PROMPT_FILE=""
PROMPT=""

resolve_pack_file() {
  if [[ -n "${PACK_FILE:-}" ]]; then
    if [[ "$PACK_FILE" = /* ]]; then echo "$PACK_FILE"; else echo "$SCRIPT_DIR/$PACK_FILE"; fi
    return
  fi
  if [[ -z "${ROLE_KEY:-}" ]]; then
    echo "ERROR: ROLE_KEY or PACK_FILE required" >&2
    exit 2
  fi
  local f="$SCRIPT_DIR/.grok/prompts/packs/${ROLE_KEY}.pack"
  if [[ ! -f "$f" ]]; then
    echo "ERROR: missing pack list: $f" >&2
    echo "Add one repo-relative prompt path per line (project rules live in those files)." >&2
    exit 1
  fi
  echo "$f"
}

read_pack_paths() {
  local packf="$1" line
  PACK_PATHS=()
  while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="${line#"${line%%[![:space:]]*}"}"
    line="${line%"${line##*[![:space:]]}"}"
    [[ -z "$line" ]] && continue
    PACK_PATHS+=("$line")
  done <"$packf"
  if [[ ${#PACK_PATHS[@]} -eq 0 ]]; then
    echo "ERROR: pack file empty: $packf" >&2
    exit 1
  fi
}

compose_prompt() {
  export GROK_PROMPT_ROOT="$SCRIPT_DIR"
  "$COMPOSE" "${PACK_PATHS[@]}"
}

resolve_run_user() {
  case "${ROLE_KEY:-}" in
    planner) echo "${planning_user:-ai-planner}" ;;
    coder) echo "${coder_user:-ai-coder}" ;;
    master) echo "${master_user:-${coder_user:-ai-coder}}" ;;
    orchestrator) echo "${orchestrator_user:-ai-orchestrator}" ;;
    primary) echo "${primary_user:-${SUDO_USER:-${USER:-dlang}}}" ;;
    *) echo "${primary_user:-${USER:-dlang}}" ;;
  esac
}

build_model_args() {
  MODEL_ARGS=()
  if [[ -n "${FORCE_MODEL:-}" ]]; then
    MODEL_ARGS=(--model "$FORCE_MODEL")
    return 0
  fi
  if [[ -n "${GROK_FORCE_MODEL:-}" ]]; then
    MODEL_ARGS=(--model "$GROK_FORCE_MODEL")
    return 0
  fi
  local v=""
  case "${ROLE_KEY:-}" in
    planner) v="${planner_model:-${GROK_PLANNER_MODEL:-}}" ;;
    coder) v="${coder_model:-}" ;;
    master) v="${master_model:-}" ;;
    orchestrator) v="${orchestrator_model:-}" ;;
    primary) v="${primary_model:-}" ;;
  esac
  # IMPORTANT: with set -e, a failing `[[ ]] && cmd` as the last statement of a
  # function returns non-zero and aborts the launcher silently. Always return 0.
  if [[ -n "$v" ]]; then
    MODEL_ARGS=(--model "$v")
  fi
  return 0
}

build_todo_gate_flags() {
  TODO_GATE_FLAGS=()
  if [[ "${GROK_TODO_GATE:-0}" = "1" || "${GROK_TODO_GATE:-0}" = "true" ]]; then
    TODO_GATE_FLAGS=(--todo-gate)
  fi
  return 0
}

collect_extra_args() {
  EXTRA_ARGS=()
  local found=0 a
  for a in "$@"; do
    if [[ "$found" -eq 1 ]]; then
      EXTRA_ARGS+=("$a")
    elif [[ "$a" = "--" ]]; then
      found=1
    fi
  done
}

# Build PROMPT from pack files and/or optional prompt file argument.
prepare_prompt() {
  PROMPT_FILE=""
  PACK_PATHS=()

  # Explicit prompt file as first argument (e.g. master-written planner prompt)
  if [[ -n "${1:-}" && -f "${1:-}" ]]; then
    PROMPT_FILE="$1"
    shift
    PROMPT="$(cat "$PROMPT_FILE")"
    collect_extra_args "$@"
    return
  fi
  collect_extra_args "$@"

  local packf default_prompt
  packf="$(resolve_pack_file)"
  read_pack_paths "$packf"

  default_prompt="${SANDBOX_DIR}/.planning-agent-prompt.txt"
  if [[ "${ROLE_KEY:-}" = "planner" \
     && -f "$default_prompt" \
     && "${GROK_IGNORE_PLANNING_PROMPT_FILE:-0}" != "1" ]]; then
    PROMPT_FILE="$default_prompt"
    PROMPT="$(cat "$PROMPT_FILE")"
    return
  fi

  PROMPT="$(compose_prompt)"

  if [[ "${ROLE_KEY:-}" = "planner" && "${GROK_WRITE_PLANNING_PROMPT_FILE:-1}" = "1" ]]; then
    mkdir -p "$SANDBOX_DIR"
    printf '%s\n' "$PROMPT" >"$default_prompt"
    PROMPT_FILE="$default_prompt"
  fi
}

launch_grok_with_prompt() {
  local prompt="$1"
  local run_user
  run_user="$(resolve_run_user)"
  build_model_args
  build_todo_gate_flags

  if [[ ! -x "$GROK_BIN" && ! -f "$GROK_BIN" ]]; then
    echo "ERROR: grok binary not found or not executable: $GROK_BIN" >&2
    echo "Set GROK_BIN or grok_bin / grok_bin_default in project.config" >&2
    exit 1
  fi

  echo "Launching Grok role=${ROLE_KEY:-?} root=$SCRIPT_DIR"
  echo "Grok binary: $GROK_BIN"
  echo "Sandbox: $SANDBOX_DIR"
  echo "Running as: $run_user"
  if [[ -n "${PROMPT_FILE:-}" ]]; then
    echo "Prompt file: $PROMPT_FILE"
  fi
  if [[ ${#PACK_PATHS[@]} -gt 0 ]]; then
    echo "Prompt pack: ${PACK_PATHS[*]}"
  fi
  if [[ -n "$ANDROID_SHARED" ]]; then
    echo "ANDROID_USER_HOME: $ANDROID_SHARED"
  fi
  echo "Tip: Ctrl+M or /multiline for multi-line input."

  # Mutation-only Landlock (agent-landlock) as the role user, immediately before grok.
  # AGENT_LANDLOCK_DISABLE=1 skips; missing ABI warns and continues (helper soft-fail).
  local landlock_helper="${SCRIPT_DIR}/agent-landlock"
  local landlock_args=()
  if [[ -x "$landlock_helper" || -f "$landlock_helper" ]]; then
    [[ -x "$landlock_helper" ]] || chmod +x "$landlock_helper" 2>/dev/null || true
    landlock_args=(
      "$landlock_helper"
      --role "${ROLE_KEY:-primary}"
      --worktree "$SCRIPT_DIR"
      --
    )
    echo "Landlock: $landlock_helper role=${ROLE_KEY:-primary}"
  else
    echo "Landlock: helper missing at $landlock_helper — launching without session Landlock" >&2
  fi

  # shellcheck disable=SC2086
  exec sudo -u "$run_user" -- env \
    ${ANDROID_SHARED:+ANDROID_USER_HOME="$ANDROID_SHARED"} \
    GROK_PROMPT_ROOT="$SCRIPT_DIR" \
    GIT_HOME="${GIT_HOME:-}" \
    bash -c 'umask '"${umask_launch:-002}"'; exec "$@"' bash \
      ${landlock_args[@]+"${landlock_args[@]}"} \
      "$GROK_BIN" \
      "$prompt" \
      ${MODEL_ARGS[@]+"${MODEL_ARGS[@]}"} \
      ${TODO_GATE_FLAGS[@]+"${TODO_GATE_FLAGS[@]}"} \
      --no-alt-screen \
      ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}
}

grok_launch_main() {
  prepare_prompt "$@"
  launch_grok_with_prompt "$PROMPT"
}
