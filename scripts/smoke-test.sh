#!/bin/sh
# Smoke-tests a running Jeoparody Party instance: health, home page, game creation, trivia API.
# POSIX sh + curl only (jq is optional), so it also runs inside a tiny curl container.
#
# Usage: scripts/smoke-test.sh https://jeoparody-party-xyz.a.run.app
#   SMOKE_WAIT_SECONDS  how long to wait for the app to become healthy (default 120)
set -eu

BASE_URL="${1:-${BASE_URL:-http://localhost:8080}}"
BASE_URL="${BASE_URL%/}"
WAIT="${SMOKE_WAIT_SECONDS:-120}"
FAILURES=0

log()  { printf '[smoke] %s\n' "$*"; }
pass() { printf '[smoke] PASS %s\n' "$*"; }
fail() { printf '[smoke] FAIL %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }

# HTTP helper: sets $STATUS and $BODY in the current shell (no subshell, so the values survive)
request() {
  tmp="${TMPDIR:-/tmp}/smoke.$$"
  # Body-less POSTs still need "Content-Length: 0": Google's front end (Cloud Run) rejects them with 411 otherwise
  len=""
  [ "$1" = "GET" ] || len="Content-Length: 0"
  STATUS=$(curl -sS -o "$tmp" -w '%{http_code}' -X "$1" ${len:+-H "$len"} --max-time 15 "$BASE_URL$2" 2>/dev/null) || STATUS=000
  BODY=$(cat "$tmp" 2>/dev/null || true)
  rm -f "$tmp"
}

log "target: $BASE_URL"

# 1. Health: wait for the app to start (cold starts, Flyway migrations...)
log "waiting up to ${WAIT}s for /actuator/health"
elapsed=0
until request GET /actuator/health && [ "$STATUS" = 200 ] && echo "$BODY" | grep -q '"UP"'; do
  if [ "$elapsed" -ge "$WAIT" ]; then
    fail "health never reported UP (last status $STATUS: $BODY)"
    exit 1
  fi
  sleep 3
  elapsed=$((elapsed + 3))
done
pass "health is UP"

# 2. Landing page is served
request GET /
if [ "$STATUS" = 200 ] && echo "$BODY" | grep -q 'JEOPARODY PARTY'; then pass "landing page"; else fail "landing page (status $STATUS)"; fi

# 3. Player and board pages are served
for page in /play.html /board.html /host.html; do
  request GET "$page"
  if [ "$STATUS" = 200 ]; then pass "$page"; else fail "$page (status $STATUS)"; fi
done

# 4. Creating a game returns a 4-letter room code and a host key
request POST /api/games
if command -v jq >/dev/null 2>&1; then
  code=$(echo "$BODY" | jq -r '.code // empty')
else
  code=$(echo "$BODY" | sed -n 's/.*"code"[[:space:]]*:[[:space:]]*"\([A-Z]\{4\}\)".*/\1/p')
fi
if [ "$STATUS" = 201 ] && [ -n "$code" ] && echo "$BODY" | grep -q '"hostKey"'; then
  pass "created game $code"
  # 5. ...and the room can be looked up
  request GET "/api/games/$code"
  if [ "$STATUS" = 200 ]; then pass "game $code found"; else fail "lookup of game $code (status $STATUS)"; fi
  # 5b. ...and a browser can open its WebSocket. Sends the same Origin a real page would, so it catches
  # proxy/origin mistakes (e.g. HTTPS terminated in front of the app) that leave every game page blank.
  # A successful upgrade (101) keeps the connection open, so curl ends by timeout; we only need the status.
  ws_status=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 5 \
    -H "Origin: $BASE_URL" -H 'Connection: Upgrade' -H 'Upgrade: websocket' \
    -H 'Sec-WebSocket-Version: 13' -H 'Sec-WebSocket-Key: c21va2UtdGVzdC1rZXkxMg==' \
    "$BASE_URL/ws?game=$code&role=board" 2>/dev/null) || true
  if [ "$ws_status" = 101 ]; then pass "WebSocket handshake"; else fail "WebSocket handshake (status ${ws_status:-none})"; fi
else
  fail "create game (status $STATUS: $BODY)"
fi

# 6. Unknown rooms are a clean 404, not a server error
request GET /api/games/ZZZZ
if [ "$STATUS" = 404 ]; then pass "unknown room is 404"; else fail "unknown room (status $STATUS)"; fi

# 7. The trivia API answers with JSON (it may be empty on a fresh database)
request GET /api/keywords
if [ "$STATUS" = 200 ] && echo "$BODY" | grep -q '^\['; then pass "keywords API"; else fail "keywords API (status $STATUS)"; fi

if [ "$FAILURES" -gt 0 ]; then
  log "$FAILURES check(s) failed"
  exit 1
fi
log "all checks passed"
