#!/bin/bash
# setup_agent.sh: Automate creation of agent worktrees
# Usage (from orchestration root):
#   ./setup_agent.sh branch-name
#   source ./setup_agent.sh branch-name   # same end state
#
# On success: exec a new interactive shell in the new worktree with project
# environment (umask 002 + full groups via refresh-shell, same as env).
# That replaces this terminal only (other GUI apps stay open).
#
# Always run from the orchestration root.
# Ensures the new worktree is fully permissioned (setgid dirs, log/wrapper,
# run-as-primary, refresh-shell, etc.) so agents can start immediately.

BRANCH_NAME=$1

if [ -z "$BRANCH_NAME" ]; then
    echo "Usage: source ./setup_agent.sh branch-name   # or: ./setup_agent.sh branch-name"
    # When sourced, exit would kill the shell — use return if possible
    return 1 2>/dev/null || exit 1
fi

# So post-checkout does not run fix-perms --all mid-setup or warn about gitignored project.config
export VE_SETUP_AGENT=1
# shellcheck disable=SC2064
trap 'unset VE_SETUP_AGENT' EXIT

# Load primary config for ownership (orchestration root always has project.config)
if [ -f project.config ]; then
  . <(sed 's/=/ /; s/^/export /' project.config | grep -E '^(primary_user|code_group|shared_group|planning_user|coder_user|orchestrator_user)')
fi
PRIMARY_USER=${primary_user:-dlang}
CODE_GROUP=${code_group:-ai-code}
SHARED_GROUP=${shared_group:-ai-shared}
PLANNING_USER=${planning_user:-ai-planner}
CODER_USER=${coder_user:-ai-coder}
ORCHESTRATOR_USER=${orchestrator_user:-ai-orchestrator}

# Enforce correct creation umask for setgid inheritance
umask 007

# 0. Safety Check: Don't name a branch agent-N (reserved for auto-generated dirs)
if [[ "$BRANCH_NAME" =~ ^agent-[0-9]+$ ]]; then
    echo "Error: Branch name cannot be '$BRANCH_NAME' (reserved for directory names)."
    echo "Use a descriptive name like 'feature-x' instead."
    exit 1
fi

is_valid_worktree_dir() {
  local p="$1"
  [ -f "$p/.git" ] && grep -q '^gitdir: ' "$p/.git" 2>/dev/null
}

# 1. Determine next available agent-N ID (reuse slot if prior setup left a non-worktree stub)
N=1
while true; do
  if [ ! -d "agent-$N" ]; then
    AGENT_ID="agent-$N"
    break
  fi
  if is_valid_worktree_dir "agent-$N"; then
    N=$((N + 1))
    continue
  fi
  echo "Removing stale non-worktree directory agent-$N (failed prior setup_agent run)..."
  if [ -f "agent-$N/ENGINEERING_LOG.md" ]; then
    sudo chattr -a "agent-$N/ENGINEERING_LOG.md" 2>/dev/null || true
  fi
  if ! rm -rf "agent-$N" 2>/dev/null; then
    sudo rm -rf "agent-$N" 2>/dev/null || {
      echo "Error: cannot remove stale agent-$N (try: sudo rm -rf agent-$N)"
      exit 1
    }
  fi
  AGENT_ID="agent-$N"
  break
done

# Safety: refuse if branch name would conflict with an existing directory (other than the agent dir we chose)
if [ -d "$BRANCH_NAME" ] && [ "$BRANCH_NAME" != "$AGENT_ID" ]; then
    echo "Error: A directory named '$BRANCH_NAME' already exists. Choose a different branch name to avoid conflict."
    exit 1
fi
if [ -e "${BRANCH_NAME}.wt" ] && [ ! -L "${BRANCH_NAME}.wt" ]; then
    echo "Error: '${BRANCH_NAME}.wt' exists but is not a symlink. Clean it up first or choose different name."
    exit 1
fi

# Ensure main .git/config is not root-owned (breaks git for users without active ai-shared GID)
if [ -f .git/config ] && [ "$(stat -c '%U' .git/config 2>/dev/null || echo '')" = "root" ]; then
  echo "Repairing root-owned .git/config (requires sudo once)..."
  if ! sudo chown "$PRIMARY_USER:$SHARED_GROUP" .git/config 2>/dev/null; then
    echo "Error: .git/config is owned by root. Run:"
    echo "  sudo chown $PRIMARY_USER:$SHARED_GROUP .git/config"
    echo "  sudo ./fix-perms --verbose"
    exit 1
  fi
  chmod 660 .git/config 2>/dev/null || true
fi

# Orchestration root (absolute) — used after cd into agent-N for seeding filters/config.
ORCH_ROOT="$(pwd)"

# Ensure shared git hooks are executable BEFORE worktree add / checkout.
# fix-perms historically chmod 660'd all of .git (strips +x); post-checkout must run.
ensure_common_hooks_executable() {
  local common hooks
  common=$(git rev-parse --git-common-dir 2>/dev/null || echo .git)
  case "$common" in
    /*) hooks="$common/hooks" ;;
    *) hooks="$ORCH_ROOT/$common/hooks" ;;
  esac
  if [ ! -d "$hooks" ]; then
    return 0
  fi
  # Install/refresh post-checkout from tracked template (hooks/post-checkout)
  if [ -f "$ORCH_ROOT/hooks/post-checkout" ]; then
    cp "$ORCH_ROOT/hooks/post-checkout" "$hooks/post-checkout"
  elif [ ! -f "$hooks/post-checkout" ] && [ -f "$ORCH_ROOT/dev-ai-interaction/new-project-template/post-checkout" ]; then
    cp "$ORCH_ROOT/dev-ai-interaction/new-project-template/post-checkout" "$hooks/post-checkout"
  fi
  # Active hooks only (not *.sample)
  find "$hooks" -maxdepth 1 -type f ! -name '*.sample' -exec chmod 775 {} + 2>/dev/null || true
  if [ -f "$hooks/post-checkout" ] && [ ! -x "$hooks/post-checkout" ]; then
    chmod 775 "$hooks/post-checkout" 2>/dev/null || true
  fi
  if [ -f "$hooks/post-checkout" ] && [ ! -x "$hooks/post-checkout" ]; then
    echo "WARNING: $hooks/post-checkout is still not executable (check ownership)."
  fi
}
ensure_common_hooks_executable

# 2. Create Worktree & Branch
echo "Creating worktree for $AGENT_ID on branch $BRANCH_NAME..."

WORKTREE_ERR=0
if git show-ref --verify --quiet "refs/heads/$BRANCH_NAME"; then
    git worktree add --no-checkout "$AGENT_ID" "$BRANCH_NAME" || WORKTREE_ERR=$?
else
    # Create from current master
    git worktree add --no-checkout "$AGENT_ID" -b "$BRANCH_NAME" master || WORKTREE_ERR=$?
    if [ "$WORKTREE_ERR" -eq 0 ]; then
      # Create (or force-update) a lightweight tag for git describe to anchor on.
      echo "Creating/updating lightweight tag ${BRANCH_NAME}-start for versioning..."
      git tag -f "${BRANCH_NAME}-start" "$BRANCH_NAME" || WORKTREE_ERR=$?
    fi
fi

if [ "$WORKTREE_ERR" -ne 0 ]; then
    echo "Error: Failed to create worktree."
    exit 1
fi

# 3. Create convenience symlink for the branch
if [ -e "${BRANCH_NAME}.wt" ]; then
    if [ -L "${BRANCH_NAME}.wt" ] && [ "$(readlink "${BRANCH_NAME}.wt")" = "$AGENT_ID" ]; then
        echo "Symlink '${BRANCH_NAME}.wt' already points correctly. Skipping."
    else
        echo "Warning: '${BRANCH_NAME}.wt' exists but is not the correct symlink. Removing and recreating."
        rm -f "${BRANCH_NAME}.wt"
        ln -s "$AGENT_ID" "${BRANCH_NAME}.wt"
        echo "Created symlink: ${BRANCH_NAME}.wt -> $AGENT_ID"
    fi
else
    ln -s "$AGENT_ID" "${BRANCH_NAME}.wt"
    echo "Created symlink: ${BRANCH_NAME}.wt -> $AGENT_ID"
fi

# Files that use filter=manage-configs (see .gitattributes). Smudge substitutes @@ tokens.
STAMPED_FILES=(
  run-grok run-grok-master run-grok-planner run-grok-coder run-grok-orchestrator
  env
  setup-project set-worktree-perms set-sandbox-perms
)

seed_smudge_inputs() {
  local orch_root="$1"
  if [ -f "$orch_root/project.config" ]; then
    cp "$orch_root/project.config" ./project.config
  fi
  for f in filter-apply-config filter-clean-config; do
    if [ -f "$orch_root/$f" ]; then
      cp "$orch_root/$f" "./$f"
    fi
  done
}

re_smudge_stamped_files() {
  # Apply orchestration's filter script directly (do not use git checkout smudge:
  # the branch may ship an older broken filter-apply-config that git would invoke).
  local f mode tmp
  if [ ! -f ./filter-apply-config ]; then
    echo "Error: filter-apply-config missing after seeding from $ORCH_ROOT"
    return 1
  fi
  if grep -q '\${!CONF_\${' ./filter-apply-config 2>/dev/null; then
    echo "Error: filter-apply-config at $ORCH_ROOT still has broken indirect expansion."
    echo "  Commit the fix on orchestration, then retry."
    return 1
  fi
  for f in "${STAMPED_FILES[@]}"; do
    if ! git cat-file -e "HEAD:$f" 2>/dev/null; then
      continue
    fi
    tmp=".$f.smudged"
    if ! git show "HEAD:$f" | bash ./filter-apply-config > "$tmp"; then
      echo "Error: manual smudge failed for $f"
      rm -f "$tmp"
      return 1
    fi
    mv "$tmp" "$f"
    mode=$(git ls-tree HEAD "$f" | awk '{print $1}')
    case "$mode" in
      100755|100775) chmod +x "$f" ;;
    esac
  done
}

verify_smudge() {
  local failed=0
  if [ ! -f project.config ]; then
    echo "Error: project.config missing (smudge filters cannot run without it)."
    return 1
  fi
  for f in "${STAMPED_FILES[@]}"; do
    [ -f "$f" ] || continue
    # Match real tokens only (@@SANDBOX_PATH@@). Do NOT match:
    # - comments mentioning "@@ tokens"
    # - intentional runtime checks like: [[ "$1" == @@* ]]
    if grep -qE '@@[A-Z0-9_]+@@' "$f" 2>/dev/null; then
      echo "Error: Unsubstituted @@ tokens remain in $f (smudge filter did not run correctly)."
      failed=1
    fi
  done
  return "$failed"
}

# 4. Populate worktree from branch tip, then manually smudge stamped files.
cd "$AGENT_ID"

# project.config is gitignored — seed BEFORE checkout so post-checkout (if it runs)
# and any early tooling see it. Re-seed after checkout in case filters were overwritten.
if [ ! -f "$ORCH_ROOT/project.config" ]; then
  echo "Error: $ORCH_ROOT/project.config missing."
  echo "  Copy project.config.example → project.config and fill local values, then retry."
  cd ..
  git worktree remove --force "$AGENT_ID" 2>/dev/null || rm -rf "$AGENT_ID"
  return 1 2>/dev/null || exit 1
fi
seed_smudge_inputs "$ORCH_ROOT"

# After --no-checkout the index is empty; 'git checkout .' matches nothing.
# Bypass git smudge on bulk checkout — feature branches may carry broken filter scripts.
# VE_SETUP_AGENT=1 makes post-checkout a no-op (avoids mid-setup sudo fix-perms --all).
if ! git -c filter.manage-configs.smudge=cat -c filter.manage-configs.clean=cat checkout HEAD -- .; then
  echo "Error: Failed to populate worktree (git checkout HEAD -- .)."
  cd ..
  git worktree remove --force "$AGENT_ID" 2>/dev/null || rm -rf "$AGENT_ID"
  return 1 2>/dev/null || exit 1
fi

# Re-seed filters from orchestration (authoritative) then smudge stamped paths.
seed_smudge_inputs "$ORCH_ROOT"
if ! re_smudge_stamped_files; then
  echo "Error: Failed to smudge stamped files using orchestration filter scripts."
  cd ..
  git worktree remove --force "$AGENT_ID" 2>/dev/null || rm -rf "$AGENT_ID"
  return 1 2>/dev/null || exit 1
fi

if ! verify_smudge; then
  echo "Hint: ensure orchestration root has project.config and working filter-apply-config."
  cd ..
  git worktree remove --force "$AGENT_ID" 2>/dev/null || rm -rf "$AGENT_ID"
  return 1 2>/dev/null || exit 1
fi

if [ "$(id -un)" != "$PRIMARY_USER" ] && [ "$EUID" -ne 0 ]; then
  sudo -u "$PRIMARY_USER" git config core.sharedRepository group
else
  git config core.sharedRepository group
fi

# 5. Setup Agent Workspace Folders
mkdir -p .gemini/policies
mkdir -p .gemini/plans
touch .gemini/plans/.gitkeep

# 6. Setup Sandbox (Symlink)
ln -sf ../dev-ai-interaction dev-ai-interaction

# 6b. Copy local.properties for Android builds
if [ -f "../master/local.properties" ]; then
    cp ../master/local.properties local.properties
elif [ -f "../local.properties" ]; then
    cp ../local.properties local.properties
fi

# 7. Initialize AGENT_CONTEXT.md
if [ -f "../AGENT_CONTEXT.md.template" ]; then
    cp "../AGENT_CONTEXT.md.template" AGENT_CONTEXT.md
    sed -i "s/agent-X/$AGENT_ID/" AGENT_CONTEXT.md
    sed -i "s/UNASSIGNED/$BRANCH_NAME/" AGENT_CONTEXT.md
else
    cat > AGENT_CONTEXT.md <<EOF
# Agent Context: $AGENT_ID

- **Current Branch:** $BRANCH_NAME
- **Status:** INITIALIZED
EOF
fi

# 8. Make this a *fully working* tree with all correct permissions.
#    Since we are always run from the orchestration root (which has the
#    authoritative latest copies and fixers), we:
#    - copy latest critical infra files
#    - build the setuid helper
#    - run the standard permission fixers (via sudo for root:ai-shared parts
#      like ENGINEERING_LOG.md ownership + chattr +a, and wrapper)
#    This ensures the new agent can immediately use append-to-engineering-log,
#    run-as-primary re-exec, correct setgid, etc. without extra steps.
AGENT_ABS=$(pwd)
PARENT_ROOT=".."

# Copy latest authoritative copies of key permission/infra files from orchestration root
# (ensures even if the branch tip was slightly behind, the tree is current)
for f in append-to-engineering-log run-as-primary.c env refresh-shell.c; do
  if [ -f "$PARENT_ROOT/$f" ]; then
    cp -p "$PARENT_ROOT/$f" "$AGENT_ABS/$f" 2>/dev/null || true
  fi
done
# Launchers often live only on orchestration until update-rules; seed common ones
for f in run-grok-coder run-grok-orchestrator run-grok-master run-grok-planner; do
  if [ -f "$PARENT_ROOT/$f" ] && [ ! -f "$AGENT_ABS/$f" ]; then
    cp -p "$PARENT_ROOT/$f" "$AGENT_ABS/$f" 2>/dev/null || true
    chmod +x "$AGENT_ABS/$f" 2>/dev/null || true
  fi
done

# Build / ensure the setuid helper (run-as-primary) is present and correct
if [ -f "$AGENT_ABS/run-as-primary.c" ]; then
  (cd "$AGENT_ABS" && gcc -O2 -Wall -o run-as-primary run-as-primary.c && chmod 4755 run-as-primary && chown "$PRIMARY_USER:$CODE_GROUP" run-as-primary) 2>/dev/null || echo "    Warning: run-as-primary build/chmod may need gcc or manual fix"
fi

# Create log file with minimal header if missing (fixer will harden it)
if [ ! -f "$AGENT_ABS/ENGINEERING_LOG.md" ]; then
  echo "## $(date +%Y-%m-%d) - Initial log for $AGENT_ID" > "$AGENT_ABS/ENGINEERING_LOG.md"
fi 2>/dev/null || true

cd "$ORCH_ROOT" || cd "$PARENT_ROOT" || true

# Use unified fix-perms for the new tree (pass --skip-sudoers so sudoers rules
# are only (re)installed at true initial setup-project time).
# Silent on success (no output if it works).
# Note: fix-perms blanket-chmods .git then restores hook +x (ensure_git_hooks_executable).
sudo ./fix-perms --skip-sudoers "$AGENT_ABS" 2>/dev/null || true

# Re-lock setuid binaries silently (in case not covered).
if [ -f "$AGENT_ABS/run-as-primary" ]; then
  chown "$PRIMARY_USER:$CODE_GROUP" "$AGENT_ABS/run-as-primary" 2>/dev/null || true
  chmod 4755 "$AGENT_ABS/run-as-primary" 2>/dev/null || true
fi

# refresh-shell: build + setuid-root in orch AND this agent worktree (not in git).
# One sudo password may be requested. Never leave setuid on non-root owner.
if [ -x "$ORCH_ROOT/install-refresh-shell.sh" ]; then
  echo "Installing refresh-shell (setuid root) in orch + worktree..."
  "$ORCH_ROOT/install-refresh-shell.sh" "$ORCH_ROOT" 2>/dev/null || \
    sudo "$ORCH_ROOT/install-refresh-shell.sh" "$ORCH_ROOT" 2>/dev/null || true
  "$ORCH_ROOT/install-refresh-shell.sh" "$AGENT_ABS" 2>/dev/null || \
    sudo "$ORCH_ROOT/install-refresh-shell.sh" "$AGENT_ABS" 2>/dev/null || true
else
  # Fallback inline install
  for _dest in "$ORCH_ROOT" "$AGENT_ABS"; do
    [ -f "$_dest/refresh-shell.c" ] || continue
    (cd "$_dest" && gcc -O2 -Wall -o refresh-shell refresh-shell.c) 2>/dev/null || true
    if [ -f "$_dest/refresh-shell" ]; then
      if sudo chown root:root "$_dest/refresh-shell" 2>/dev/null; then
        sudo chmod 4755 "$_dest/refresh-shell" 2>/dev/null || true
      else
        chmod 755 "$_dest/refresh-shell" 2>/dev/null || true
      fi
    fi
  done
fi

# After fix-perms, re-assert hooks (defense in depth; post-checkout must stay runnable)
ensure_common_hooks_executable

# Merge drivers are repo-global (.git config); ensure installed for all worktrees
if [ -x "$ORCH_ROOT/install-merge-drivers.sh" ]; then
  (cd "$ORCH_ROOT" && ./install-merge-drivers.sh >/dev/null) || true
fi
chmod +x "$AGENT_ABS/git-merge-drivers/"* "$AGENT_ABS/install-merge-drivers.sh" "$AGENT_ABS/merge-branch-into-master.sh" 2>/dev/null || true

# CRITICAL: seed/smudge/copy of tracked files must not leave the agent worktree dirty,
# or ./build_app will refuse (uncommitted tracked files gate). Commit if needed.
(
  cd "$AGENT_ABS" || exit 0
  if ! git diff --quiet 2>/dev/null || ! git diff --cached --quiet 2>/dev/null; then
    echo "Committing setup_agent tracked-file updates so build_app can run..."
    git add -u 2>/dev/null || true
    # Newly copied tracked scripts may be untracked if not on branch tip yet
    git add -A -- \
      env deploy build_app setup_agent.sh fix-perms update-rules.sh \
      append-to-engineering-log get-builds-tag.sh install-*.sh \
      git-merge-drivers hooks AGENT_MANDATES.md AGENTS.md GROK.md \
      standard-plan-compliance-block.md project-facts.md 2>/dev/null || true
    if ! git diff --cached --quiet 2>/dev/null; then
      git commit -m "chore: setup_agent seed/sync tracked infra (keep build_app clean)" || true
    fi
  fi
)

# --- Enter new worktree shell (env semantics) ---
# Replace this process with an interactive shell in AGENT_ABS, umask 002, full
# project groups when refresh-shell is installed setuid (same as source ./env).
unset VE_SETUP_AGENT
trap - EXIT

echo "Worktree ready: $AGENT_ABS"
echo "Branch: $BRANCH_NAME  Agent ID: $AGENT_ID"
echo "Next: run a launcher from here, e.g.  ../run-grok-coder   or   ../run-grok-planner"
echo "Entering worktree shell (umask 002 + project groups via env helper)..."

export ENV_CWD="$AGENT_ABS"
umask 002

# Prefer setuid-root helper only (setuid+non-root owner is unsafe — see env)
REFRESH=""
for _vrs in "$ORCH_ROOT/refresh-shell" "$AGENT_ABS/refresh-shell"; do
  if [ -x "$_vrs" ] && [ -u "$_vrs" ] && [ "$(stat -c '%U' "$_vrs" 2>/dev/null)" = "root" ]; then
    REFRESH="$_vrs"
    break
  fi
done

if [ -n "$REFRESH" ]; then
  exec "$REFRESH"
fi

# Fallback: interactive shell in worktree with umask 002 (groups may still be stale)
echo "NOTE: refresh-shell not setuid yet — shell may lack project groups."
echo "  One-time: gcc -O2 -Wall -o refresh-shell refresh-shell.c && sudo chown root:root refresh-shell && sudo chmod 4755 refresh-shell"
echo "  Then: source ./env"
cd "$AGENT_ABS" || {
  echo "ERROR: cannot cd to $AGENT_ABS"
  return 1 2>/dev/null || exit 1
}
SHELL_BIN="${SHELL:-/bin/bash}"
exec "$SHELL_BIN"
