#!/usr/bin/env bash
# Capture the legacy sample (raw JDBC) under eclipse-temurin:8 into ./capture/legacy.
# Usage: scripts/capture-legacy.sh [scenario] [agent_opts]
set -euo pipefail
HERE="$(dirname "${BASH_SOURCE[0]}")"
source "$HERE/lib.sh"
"$HERE/run-sample.sh" legacy "${1:-normal}" "$ROOT_DIR/capture/legacy" "${2:-}"
log "legacy capture complete:"; ls -la "$ROOT_DIR/capture/legacy" >&2
