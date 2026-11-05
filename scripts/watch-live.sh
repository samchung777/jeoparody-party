#!/usr/bin/env bash
# Release validation signal for CircleCI Smart Deployments (the watch-live job).
# Re-runs the smoke test against the live site once a minute for WATCH_MINUTES. Two failures in a row send a
# "triggered" signal to CircleCI's release validation webhook; the release-jeoparody job then fails the release
# and CircleCI starts the rollback pipeline (.circleci/rollback.yml).
#
# Usage: scripts/watch-live.sh https://jeoparody-party-....run.app
# Env:
#   CIRCLECI_DEPLOY_WEBHOOK_SECRET  required: Org Settings > Deploys > Webhook secrets, saved as a project env var
#   CIRCLECI_ORG_ID                 default: this project's CircleCI organization id
#   WATCH_MINUTES                   default 10 (must be shorter than evaluation_time in continue_config.yml)
#   CIRCLE_SHA1                     set by CircleCI: the release version (the image tag)
# Why: this project has no monitoring tool, so the pipeline watches the live site itself.
set -euo pipefail
cd "$(dirname "$0")/.."

url="${1:?usage: scripts/watch-live.sh LIVE_URL}"
secret="${CIRCLECI_DEPLOY_WEBHOOK_SECRET:?set CIRCLECI_DEPLOY_WEBHOOK_SECRET in Project Settings > Environment Variables}"
org_id="${CIRCLECI_ORG_ID:-37afcccc-e625-4cde-b508-2f9d9097152c}"
minutes="${WATCH_MINUTES:-10}"
version="${CIRCLE_SHA1:-unknown}"

log() { printf '[watch] %s\n' "$*"; }

# Sends one validation signal ("triggered" fails the release). Field names match the release job's data_points.
signal() {
  local body
  body=$(printf '{"provider":"custom","component_name":"jeoparody-party","env":"production","version":"%s","signal_name":"live_smoke","alert_transition":"%s"}' \
    "$version" "$1")
  curl -sS --fail-with-body -X POST "https://circleci.com/api/v3/deploy/hooks/${org_id}/validate" \
    -H "Authorization: Bearer ${secret}" -H 'Content-Type: application/json' --data "$body"
  echo
}

failures=0
for ((i = 1; i <= minutes; i++)); do
  if SMOKE_WAIT_SECONDS=30 sh scripts/smoke-test.sh "$url" > /tmp/smoke.log 2>&1; then
    log "check $i/$minutes passed"
    failures=0
  else
    failures=$((failures + 1))
    log "check $i/$minutes FAILED ($failures in a row):"
    sed 's/^/[watch]   /' /tmp/smoke.log
    if (( failures >= 2 )); then
      log "two failures in a row: telling CircleCI the release is unhealthy"
      signal triggered
      exit 1
    fi
  fi
  if (( i < minutes )); then sleep 60; fi
done
log "the live site stayed healthy for $minutes minutes"
