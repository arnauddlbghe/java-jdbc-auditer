#!/usr/bin/env bash
# Measure the agent's runtime overhead: run a sample WITH and WITHOUT the agent,
# several times, and print the wall-clock delta. Timing is taken INSIDE the
# container (around the java process) to exclude container start-up noise.
#
# Usage: scripts/measure-overhead.sh [jvm_version] [app_jar_rel] [scenario] [runs]
#   defaults: 25  sample-modern/target/sample-modern.jar  normal  5
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

JVM="${1:-25}"
APP_REL="${2:-$MODERN_JAR_REL}"
SCENARIO="${3:-normal}"
RUNS="${4:-5}"

AGENT_ABS="$ROOT_DIR/$AGENT_JAR_REL"
APP_ABS="$ROOT_DIR/$APP_REL"
[ -f "$AGENT_ABS" ] || die "agent jar missing: $AGENT_ABS (make build)"
[ -f "$APP_ABS" ]   || die "app jar missing: $APP_ABS (make build)"

pg_up

# one_timing <with_agent:0|1>  -> prints elapsed milliseconds for one run
one_timing() {
  local with_agent="$1"
  local agent_flag=""
  [ "$with_agent" = "1" ] && agent_flag="-javaagent:$AGENT_JAR_REL=out=/w/out"
  pg_reset >/dev/null
  tar -C "$ROOT_DIR" -cf - "$AGENT_JAR_REL" "$APP_REL" 2>/dev/null | \
  docker run -i --rm --network "$NETWORK" \
      -e JDBC_URL="jdbc:postgresql://postgres:5432/capture" \
      -e JDBC_USER=capture -e JDBC_PASSWORD=capture \
      -w /w "eclipse-temurin:$JVM" sh -c '
        tar -xf - >/dev/null 2>&1
        mkdir -p /w/out
        s=$(date +%s%N)
        java '"$agent_flag"' -jar '"$APP_REL"' '"$SCENARIO"' >/dev/null 2>&1
        e=$(date +%s%N)
        echo "ELAPSED_MS=$(( (e - s) / 1000000 ))"
      ' | sed -n 's/^ELAPSED_MS=//p'
}

avg() { # reads numbers on stdin, prints integer average
  awk '{s+=$1; n++} END { if (n>0) printf "%.0f", s/n; else print "0" }'
}

log "warming up..."
one_timing 0 >/dev/null || true

declare -a without with
log "measuring WITHOUT agent ($RUNS runs)..."
for _ in $(seq 1 "$RUNS"); do without+=("$(one_timing 0)"); done
log "measuring WITH agent ($RUNS runs)..."
for _ in $(seq 1 "$RUNS"); do with+=("$(one_timing 1)"); done

w_avg="$(printf '%s\n' "${without[@]}" | avg)"
a_avg="$(printf '%s\n' "${with[@]}"    | avg)"
overhead_ms=$(( a_avg - w_avg ))
overhead_pct="n/a"
[ "$w_avg" -gt 0 ] && overhead_pct="$(awk -v a="$a_avg" -v w="$w_avg" 'BEGIN{printf "%.1f", (a-w)*100.0/w}')"

echo
echo "=================== AGENT OVERHEAD (jvm=$JVM, app=$APP_REL, scenario=$SCENARIO, runs=$RUNS) ==================="
echo "  without agent (ms): ${without[*]}   avg=${w_avg}"
echo "  with    agent (ms): ${with[*]}   avg=${a_avg}"
echo "  overhead: ${overhead_ms} ms  (${overhead_pct} %)"
echo "==========================================================================================================="
