#!/usr/bin/env bash
# Dev fast path: build + sign the UI pack from the working tree and install it on ONE phone, no deploy, no APK (< 10 s).
# The phone verifies the signature (only a pack signed with our key installs; older seq / wrong minKid is refused), so the
# endpoint needs no token. Reload the page on the phone afterwards. A later `scripts/deploy.sh` pack (newer seq) replaces it.
#   scripts/ui-push.sh --host 192.168.1.50          kid phone on the LAN (port 8770)
#   scripts/ui-push.sh --adb [SERIAL]               over adb forward (SERIAL needed when more than one device is attached)
#   scripts/ui-push.sh --host IP --no-pack          resend the last pack in /tmp/kg-ui-push
#   scripts/ui-push.sh --parent [SERIAL]            the parent app on that phone (app must be open: its proxy listens only then)
# Without --parent: the KID phone's page (LAN / on-device parent page).
set -euo pipefail
cd "$(dirname "$0")/.."
HOST=""; ADB=0; SERIAL=""; PORT=8770; BUILD=1; PARENT=0
while [ $# -gt 0 ]; do case "$1" in
  --host) HOST="$2"; shift 2;;
  --adb) ADB=1; if [ $# -gt 1 ] && [[ "$2" != --* ]]; then SERIAL="$2"; shift; fi; shift;;
  --parent) PARENT=1; ADB=1; if [ $# -gt 1 ] && [[ "$2" != --* ]]; then SERIAL="$2"; shift; fi; shift;;
  --no-pack) BUILD=0; shift;;
  *) echo "usage: $0 --host IP[:port] | --adb [SERIAL] | --parent [SERIAL] [--no-pack]" >&2; exit 1;; esac; done
[ -n "$HOST" ] || [ "$ADB" = 1 ] || { echo "usage: $0 --host IP[:port] | --adb [SERIAL]" >&2; exit 1; }

scripts/check-js.sh >/dev/null   # catches what node --check misses (const reassign)
for f in ui/assets/*.js; do node --check "$f"; done   # never push a page that does not even parse
OUT=/tmp/kg-ui-push
[ "$BUILD" = 0 ] || { rm -rf "$OUT"; node scripts/ui-pack.mjs --out "$OUT"; }
ZIP="$OUT/pack.zip"; [ -f "$ZIP" ] || { echo "no $ZIP" >&2; exit 1; }

if [ "$ADB" = 1 ]; then
  A=(adb); [ -z "$SERIAL" ] || A=(adb -s "$SERIAL")
  if [ -z "$SERIAL" ] && [ "$(adb devices | grep -c 'device$')" -gt 1 ]; then echo "several devices attached: pass the serial (--adb SERIAL)" >&2; exit 1; fi
  LP=$((18700 + RANDOM % 200))
  if [ "$PARENT" = 1 ]; then
    # the parent app's proxy listens on 127.0.0.1:<random port> while the app is open; its Host check wants that same port
    UID_P=$("${A[@]}" shell dumpsys package dev.kidguard.parent | sed -n 's/.*appId=\([0-9]*\).*/\1/p' | head -1)
    [ -n "$UID_P" ] || { echo "parent app not installed" >&2; exit 1; }
    HEXP=$("${A[@]}" shell cat /proc/net/tcp /proc/net/tcp6 | awk -v u="$UID_P" '$4=="0A" && $8==u {split($2,a,":"); print a[2]; exit}')
    [ -n "$HEXP" ] || { echo "parent app proxy not running: open the KidGuard parent app on the phone first" >&2; exit 1; }
    PORT=$((16#$HEXP)); LP=$PORT
  fi
  "${A[@]}" forward tcp:$LP tcp:$PORT >/dev/null
  trap '"${A[@]}" forward --remove tcp:$LP >/dev/null 2>&1 || true' EXIT
  BASE="http://127.0.0.1:$LP"
else
  case "$HOST" in *:*) BASE="http://$HOST";; *) BASE="http://$HOST:$PORT";; esac
fi
echo "push -> $BASE"
curl -sS -m 20 -X POST -H 'Content-Type: application/zip' --data-binary @"$ZIP" "$BASE/ui/push"; echo
curl -sS -m 5 "$BASE/ui/pack.json"; echo
echo "Reload the page on the phone (the page also shows a Reload bar by itself)."
