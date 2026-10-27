#!/usr/bin/env bash
# Waits until MySQL accepts connections AND answers a query.
# Used in CI before the integration tests (the MySQL sidecar container needs a few seconds to start).
#
# Usage: scripts/wait-for-db.sh            (settings from env vars below)
#   DB_HOST     default 127.0.0.1
#   DB_PORT     default 3306
#   DB_USER     default root
#   DB_PASSWORD default rootpw
#   DB_WAIT_SECONDS  default 90
# Used by: the test job, before `mvn verify`; the MySQL sidecar needs a few seconds after the job starts.
# Why: without it, integration tests could fail randomly depending on how fast MySQL starts.
set -euo pipefail

host="${DB_HOST:-127.0.0.1}"
port="${DB_PORT:-3306}"
user="${DB_USER:-root}"
password="${DB_PASSWORD:-rootpw}"
timeout="${DB_WAIT_SECONDS:-90}"

log() { printf '[wait-for-db] %s\n' "$*"; }

# Prefer the mysql client (it proves auth works); fall back to a plain TCP check.
db_ready() {
  if command -v mysql >/dev/null 2>&1; then
    MYSQL_PWD="$password" mysql --protocol=TCP -h "$host" -P "$port" -u "$user" \
      --connect-timeout=2 -e 'SELECT 1' >/dev/null 2>&1
  else
    (exec 3<>"/dev/tcp/$host/$port") >/dev/null 2>&1
  fi
}

log "waiting up to ${timeout}s for MySQL at ${host}:${port}"
start=$(date +%s)
until db_ready; do
  elapsed=$(( $(date +%s) - start ))
  if (( elapsed >= timeout )); then
    log "MySQL did not become ready after ${elapsed}s" >&2
    exit 1
  fi
  sleep 2
done
log "MySQL is ready after $(( $(date +%s) - start ))s"
