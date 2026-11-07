#!/usr/bin/env bash
# Builds build/kidguard.apk + build/kidguard-parent.apk (release, signed with android/app/signing.jks, made on the first build).
# Needs JDK 17+ and the Android SDK (ANDROID_HOME, default ~/Library/Android/sdk).
# Behind a TLS-inspecting proxy: TRUSTSTORE=/path/to/store.jks scripts/build.sh (a copy of the JDK cacerts + the proxy CA).
set -euo pipefail
cd "$(dirname "$0")/.."   # repo root; the Gradle project is android/
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
[ -f android/local.properties ] || echo "sdk.dir=$ANDROID_HOME" > android/local.properties
if [ ! -f android/app/signing.jks ]; then
  # a new key = phones with the old build can't update and the QR checksum changes: never make one silently on CI
  if [ -n "${CI:-}" ]; then echo "android/app/signing.jks missing (CI won't generate a new release key)" >&2; exit 1; fi
  keytool -genkeypair -keystore android/app/signing.jks -storepass kidguard -keypass kidguard -alias kidguard \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=KidGuard" >/dev/null
fi
OPTS=()
if [ -n "${TRUSTSTORE:-}" ]; then
  T="-Djavax.net.ssl.trustStore=$TRUSTSTORE -Djavax.net.ssl.trustStorePassword=${TRUSTSTORE_PASS:-changeit}"
  export JAVA_OPTS="$T"
  OPTS=(-Dorg.gradle.jvmargs="-Xmx2g $T")
fi
(cd android && ./gradlew -q ${OPTS[@]+"${OPTS[@]}"} testDebugUnitTest assembleRelease :parent:testDebugUnitTest :parent:assembleRelease)
mkdir -p build
cp android/app/build/outputs/apk/release/app-release.apk build/kidguard.apk
cp android/parent/build/outputs/apk/release/parent-release.apk build/kidguard-parent.apk
# release assets: the child self-update (Update.kt) needs kidguard.apk.sha256 next to the APK
(cd build && for f in kidguard.apk kidguard-parent.apk; do shasum -a 256 "$f" > "$f.sha256"; done)
# versioned copies for people downloading by hand (Chrome otherwise saves "kidguard (1).apk"); the fixed names above
# stay for QR provisioning, setup scripts and the updater. Site + README link the versioned ones and show the version.
V=$(sed -n 's/^kg.version=//p' android/gradle.properties)
cp build/kidguard.apk "build/kidguard-$V.apk"; cp build/kidguard-parent.apk "build/kidguard-parent-$V.apk"
perl -pi -e 's#releases/(?:latest/download|download/v[0-9.]+)/kidguard(-parent)?(?:-[0-9.]+)?\.apk#releases/download/v'"$V"'/kidguard$1-'"$V"'.apk#g; s#<span class="ver">v[0-9.]*</span>#<span class="ver">v'"$V"'</span>#g' \
  site/index.html site/en/index.html README.md
scripts/stamp-site.sh

# QR provisioning: SHA-256 of the signing certificate, URL-safe base64 without padding -> web/provision.json + web/qr.html defaults
APKSIGNER=$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ]; then
  HEX=$("$APKSIGNER" verify --print-certs build/kidguard.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)
else
  HEX=$(keytool -list -v -keystore android/app/signing.jks -storepass kidguard -alias kidguard | sed -n 's/^ *SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f' | head -1)
fi
if [ -n "$HEX" ]; then
  SUM=$(printf '%s' "$HEX" | xxd -r -p | base64 | tr '+/' '-_' | tr -d '=\n')
  [ -f web/provision.json ] && URL=$(sed -n 's/.*"apkUrl": *"\([^"]*\)".*/\1/p' web/provision.json) || URL=
  : "${URL:=https://github.com/HoangTran0410/kidguard/releases/latest/download/kidguard.apk}"
  J="{\"component\":\"dev.kidguard/dev.kidguard.Admin\",\"checksum\":\"$SUM\",\"apkUrl\":\"$URL\"}"
  echo "$J" > web/provision.json
  # the worker bundles only .html, so the same values are inlined into the page between /*KG*/ markers
  sed -E "s|/\*KG\*/.*/\*KG\*/|/*KG*/$J/*KG*/|" web/qr.html > web/qr.html.tmp && mv web/qr.html.tmp web/qr.html
  echo "QR checksum: $SUM"
else
  echo "warning: could not compute the signing checksum (web/provision.json not updated)" >&2
fi
echo "build/kidguard.apk ($(du -h build/kidguard.apk | cut -f1))"
