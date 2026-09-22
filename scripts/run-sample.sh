#!/usr/bin/env bash
# Generic sample runner used by capture-*.sh and by the integration tests.
# Usage: scripts/run-sample.sh <legacy|modern> <scenario> <out_dir> [agent_opts]
#   legacy -> eclipse-temurin:8  + sample-legacy.jar
#   modern -> eclipse-temurin:25 + sample-modern.jar
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

WHICH="${1:?usage: run-sample.sh <legacy|modern> <scenario> <out_dir> [agent_opts]}"
SCENARIO="${2:-normal}"
OUT_DIR="${3:?out_dir required}"
AGENT_OPTS="${4:-}"

case "$WHICH" in
  legacy) JVM=8;  APP_REL="$LEGACY_JAR_REL" ;;
  modern) JVM=25; APP_REL="$MODERN_JAR_REL" ;;
  *) die "unknown sample '$WHICH' (expected legacy|modern)" ;;
esac

pg_up
pg_reset
rm -rf "$OUT_DIR"
run_app_in_jvm "$JVM" "$APP_REL" "$SCENARIO" "$OUT_DIR" "$AGENT_OPTS"
