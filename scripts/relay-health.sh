#!/usr/bin/env bash
# Is the relay worker alive? Exits 1 with the reason when it is not. Run by deploy.sh after a deploy and by the
# GitHub Actions cron (.github/workflows/health.yml: a failed scheduled run emails the repo owner).
# INCIDENT 2026-11-17: Cloudflare killed the worker (automated "network abuse" report 1 s after a deploy); every request
# got "error code: 1101" for 3 h while we guessed at phone bugs. Usage: scripts/relay-health.sh [relay-url]
set -uo pipefail
URL="${1:-https://kg-relay.99-hoangtran.workers.dev}"
bad=0
check() {   # path, expected status
  local out code body
  out=$(curl -s -m 20 -w '\n%{http_code}' -H 'X-KG-Token: health-check' "$URL$1") || { echo "FAIL $1: no answer (DNS / network / timeout)"; bad=1; return; }
  code=${out##*$'\n'}; body=${out%$'\n'*}
  if [ "$code" = "$2" ]; then echo "ok   $1 $code"; return; fi
  bad=1
  if [[ "$body" =~ [Ee]rror\ code:\ ([0-9]{3,4}) ]]; then
    echo "FAIL $1: Cloudflare error ${BASH_REMATCH[1]} (HTTP $code): the worker itself is down."
  else
    echo "FAIL $1: HTTP $code (expected $2): $(printf '%s' "$body" | head -c 120)"
  fi
}
check / 404                               # JSON relay (plan B): no page, a plain 404 = the script runs; no Durable Object involved
check /c/0000000000000000/state 401       # Durable Object path, unknown phone
if [ "$bad" = 1 ]; then
  cat <<'EOF'
Next: 1101 on every path with any deployed version = the script was killed (abuse report / limits), not our code.
  - Cloudflare API: GET /accounts/<id>/abuse-reports  (or the "Network abuse received" email)
  - dashboard: Workers > kidguard > Metrics / Logs; a worker reached through a service binding says "This script has been killed"
  - docs/decisions/relay.md (INCIDENT 2026-11-17)
EOF
  exit 1
fi
echo "Relay ok: $URL"
