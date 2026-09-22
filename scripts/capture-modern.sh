#!/usr/bin/env bash
# Capture the modern sample (Spring Boot / HikariCP) under eclipse-temurin:25 into ./capture/modern.
# Usage: scripts/capture-modern.sh [scenario] [agent_opts]
set -euo pipefail
HERE="$(dirname "${BASH_SOURCE[0]}")"
source "$HERE/lib.sh"
"$HERE/run-sample.sh" modern "${1:-normal}" "$ROOT_DIR/capture/modern" "${2:-}"
log "modern capture complete:"; ls -la "$ROOT_DIR/capture/modern" >&2
