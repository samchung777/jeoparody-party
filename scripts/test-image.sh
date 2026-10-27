#!/usr/bin/env bash
# Proves a built app image actually works: starts it next to a throwaway MySQL container on a private
# Docker network and runs scripts/smoke-test.sh against it from inside that network.
#
# Works with a local Docker daemon and with CircleCI's remote Docker (setup_remote_docker), because
# nothing depends on published ports or volume mounts: the smoke test is piped into a curl container.
#
# Usage: scripts/test-image.sh jeoparody-party:latest
# Used by: the build-image job, before the image is saved for publishing.
# Why: tests the artifact itself (the exact image that will ship), not just the code.
set -euo pipefail
cd "$(dirname "$0")/.."

image="${1:?usage: scripts/test-image.sh IMAGE}"
run_id="jp-smoke-$$"
network="$run_id-net"
db="$run_id-db"
app="$run_id-app"

log() { printf '[test-image] %s\n' "$*"; }

cleanup() {
  status=$?
  if (( status != 0 )); then
    log "app logs (last 80 lines):"
    docker logs --tail 80 "$app" 2>&1 || true
  fi
  docker rm -f "$app" "$db" >/dev/null 2>&1 || true
  docker network rm "$network" >/dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

log "network $network"
docker network create "$network" >/dev/null

log "starting MySQL"
docker run -d --name "$db" --network "$network" \
  -e MYSQL_DATABASE=jeopardy -e MYSQL_USER=jeopardy -e MYSQL_PASSWORD=jeopardy -e MYSQL_ROOT_PASSWORD=rootpw \
  mysql:8.4 >/dev/null

log "waiting for MySQL"
for i in $(seq 1 45); do
  if docker exec "$db" mysql -ujeopardy -pjeopardy -h 127.0.0.1 -e 'SELECT 1' jeopardy >/dev/null 2>&1; then
    break
  fi
  if (( i == 45 )); then log "MySQL did not start" >&2; exit 1; fi
  sleep 2
done

log "starting $image"
docker run -d --name "$app" --network "$network" \
  -e DB_URL="jdbc:mysql://$db:3306/jeopardy" -e DB_USER=jeopardy -e DB_PASSWORD=jeopardy \
  -e TRIVIA_IMPORT_ON_STARTUP=false -e TRIVIA_ENRICHMENT_ENABLED=false \
  -e TRIVIA_WIKI_ENABLED=false -e TRIVIA_WIKI_SEED=false \
  "$image" >/dev/null

log "running smoke test inside the network"
docker run --rm -i --network "$network" -e SMOKE_WAIT_SECONDS=120 --entrypoint sh curlimages/curl:8.11.1 \
  -s -- "http://$app:8080" < scripts/smoke-test.sh

log "image $image passed"
