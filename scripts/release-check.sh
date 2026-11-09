#!/usr/bin/env bash
# Verifies a published GitHub release has every asset the updater / QR / site / setup scripts link to.
# Usage: scripts/release-check.sh [vX.Y.Z]   (default: version in android/gradle.properties)
# Read-only: only does HEAD requests on release asset URLs. Needs curl.
# Contract names: kidguard.apk(+.sha256) is read by Update.kt; kidguard-parent.apk and kidguard-home.apk are linked from
# README / website; versioned copies kidguard[-parent|-home]-<v>.apk are made by scripts/build.sh.
set -euo pipefail
cd "$(dirname "$0")/.."
V=$(sed -n 's/^kg.version=//p' android/gradle.properties)
TAG=${1:-v$V}; N=${TAG#v}
REPO=$(git remote get-url origin 2>/dev/null | sed -E 's#^git@github.com:#https://github.com/#; s#\.git$##')
case "$REPO" in https://github.com/*) ;; *) REPO=https://github.com/HoangTran0410/kidguard ;; esac
miss=0
for f in kidguard.apk kidguard.apk.sha256 kidguard-parent.apk kidguard-parent.apk.sha256 kidguard-home.apk kidguard-home.apk.sha256 \
         "kidguard-$N.apk" "kidguard-parent-$N.apk" "kidguard-home-$N.apk"; do
  code=$(curl -s -o /dev/null -L -I -w '%{http_code}' "$REPO/releases/download/$TAG/$f" || echo 000)
  if [ "$code" = 200 ]; then echo "ok       $f"; else echo "MISSING  $f ($code)"; miss=1; fi
done
# the tag must also be the "latest" release (updater, QR and site use /releases/latest/download/)
LATEST=$(curl -s -o /dev/null -L -w '%{url_effective}' "$REPO/releases/latest" || true)
case "$LATEST" in */tag/"$TAG") echo "ok       latest = $TAG" ;; *) echo "WARN     latest is ${LATEST##*/}, not $TAG"; miss=1 ;; esac
exit $miss
