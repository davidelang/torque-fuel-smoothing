#!/usr/bin/env bash
# fix-ve-git-shared.sh — repair VE monorepo .git to multi-user model (dlang + sudo).
# Wrapper around fix-multiuser-git-hosts.sh for this host only.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export HOSTS="VehicleExpenses-automated"
export GIT_HOME="$(dirname "$ROOT")"
# Prefer explicit if monorepo name differs
if [[ "$(basename "$ROOT")" == "VehicleExpenses-automated" ]]; then
  export GIT_HOME="$(dirname "$ROOT")"
  export HOSTS="VehicleExpenses-automated"
fi
exec bash "$ROOT/fix-multiuser-git-hosts.sh" "$@"
