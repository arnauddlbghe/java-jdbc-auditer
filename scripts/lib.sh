#!/usr/bin/env bash
# Shared helpers for the capture scripts.
#
# Key constraint on this machine (Rancher Desktop): host bind-mounts of the repo
# fail with "operation not permitted". So we NEVER mount the repo into the sample
# containers. Instead we:
#   1. tar the agent jar + app jar into the container over stdin ("tar-pipe"),
#   2. run the app there with the agent,
#   3. tar the produced ./out directory back over stdout,
#   4. extract it on the host.
# The exact same agent jar is fed to both the JVM 8 and JVM 25 containers.

set -euo pipefail

# Rancher Desktop ships the docker CLI here.
export PATH="$PATH:$HOME/.rd/bin"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/docker/docker-compose.yml"
NETWORK="capnet"

AGENT_JAR_REL="jdbc-capture-agent/target/jdbc-capture-agent.jar"
LEGACY_JAR_REL="sample-legacy/target/sample-legacy.jar"
MODERN_JAR_REL="sample-modern/target/sample-modern.jar"
SPIKE_JAR_REL="spike-app/target/spike-app.jar"

log()  { printf '\033[1;34m[capture]\033[0m %s\n' "$*" >&2; }
warn() { printf '\033[1;33m[capture]\033[0m %s\n' "$*" >&2; }
die()  { printf '\033[1;31m[capture]\033[0m %s\n' "$*" >&2; exit 1; }

have_docker() { command -v docker >/dev/null 2>&1; }

pg_up() {
  have_docker || die "docker not found (Rancher Desktop running? PATH includes ~/.rd/bin?)"
  log "starting PostgreSQL (docker compose)..."
  docker compose -f "$COMPOSE_FILE" up -d
  log "waiting for PostgreSQL to become healthy..."
  local id
  id="$(docker compose -f "$COMPOSE_FILE" ps -q postgres)"
  for _ in $(seq 1 60); do
    local st
    st="$(docker inspect -f '{{.State.Health.Status}}' "$id" 2>/dev/null || echo starting)"
    [ "$st" = "healthy" ] && { log "PostgreSQL healthy"; return 0; }
    sleep 1
  done
  die "PostgreSQL did not become healthy in time"
}

pg_down() {
  log "stopping PostgreSQL..."
  docker compose -f "$COMPOSE_FILE" down -v || true
}

# Reset the schema between runs so scenarios are reproducible.
pg_reset() {
  local id
  id="$(docker compose -f "$COMPOSE_FILE" ps -q postgres)"
  docker exec -i "$id" psql -U capture -d capture -v ON_ERROR_STOP=1 \
    -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;" >/dev/null
  log "schema reset"
}

# run_app_in_jvm <jvm_version> <app_jar_rel> <scenario> <out_host_dir> [agent_opts]
#
# Runs <app_jar_rel> under eclipse-temurin:<jvm_version> with the capture agent,
# on the capnet network (so JDBC_URL host `postgres` resolves), and extracts the
# produced trace directory into <out_host_dir>. Prints the app's own stdout.
run_app_in_jvm() {
  local jvm="$1" app_rel="$2" scenario="$3" out_dir="$4" agent_opts="${5:-}"
  local agent_abs="$ROOT_DIR/$AGENT_JAR_REL"
  local app_abs="$ROOT_DIR/$app_rel"

  [ -f "$agent_abs" ] || die "agent jar missing: $agent_abs (build it first: make build)"
  [ -f "$app_abs" ]   || die "app jar missing: $app_abs (build it first: make build)"

  mkdir -p "$out_dir"
  local opts_suffix=""
  [ -n "$agent_opts" ] && opts_suffix=",$agent_opts"

  local result_tar
  result_tar="$(mktemp -t captar.XXXXXX)"

  log "running $app_rel in eclipse-temurin:$jvm (scenario=$scenario, opts=out=/w/out${opts_suffix})"
  # tar the two jars in (paths kept relative to repo root), run, tar ./out back out.
  tar -C "$ROOT_DIR" -cf - "$AGENT_JAR_REL" "$app_rel" 2>/dev/null | \
  docker run -i --rm --network "$NETWORK" \
      -e JDBC_URL="${JDBC_URL:-jdbc:postgresql://postgres:5432/capture}" \
      -e JDBC_USER="${JDBC_USER:-capture}" \
      -e JDBC_PASSWORD="${JDBC_PASSWORD:-capture}" \
      -w /w "eclipse-temurin:$jvm" sh -c '
        tar -xf - >/dev/null 2>&1
        mkdir -p /w/out
        java -javaagent:'"$AGENT_JAR_REL"'=out=/w/out'"$opts_suffix"' \
             -jar '"$app_rel"' '"$scenario"' >/w/out/app.stdout 2>/w/out/app.stderr
        echo $? >/w/out/app.exit
        tar -C /w -cf - out
      ' > "$result_tar"

  tar -C "$out_dir" --strip-components=1 -xf "$result_tar"
  rm -f "$result_tar"

  local exit_code
  exit_code="$(cat "$out_dir/app.exit" 2>/dev/null || echo '?')"
  log "app exit code = $exit_code ; trace -> $out_dir/trace.jsonl"
  cat "$out_dir/app.stdout" 2>/dev/null || true
  return 0
}
