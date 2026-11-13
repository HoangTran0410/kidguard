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
node scripts/check-search.mjs   # every page section must be findable in the feature search
scripts/check-js.sh              # page-script runtime bugs node --check misses (const reassign, ...)
(cd android && ./gradlew -q ${OPTS[@]+"${OPTS[@]}"} testKidDebugUnitTest assembleKidRelease assembleHomeRelease :parent:testDebugUnitTest :parent:assembleRelease)
mkdir -p build
cp android/app/build/outputs/apk/kid/release/app-kid-release.apk build/kidguard.apk
cp android/app/build/outputs/apk/home/release/app-home-release.apk build/kidguard-home.apk
cp android/parent/build/outputs/apk/release/parent-release.apk build/kidguard-parent.apk
# release assets: the child self-update (Update.kt) needs kidguard.apk.sha256 next to the APK
(cd build && for f in kidguard.apk kidguard-parent.apk kidguard-home.apk; do shasum -a 256 "$f" > "$f.sha256"; done)
# versioned copies for people who want a named file; site + README link releases/latest/download/<fixed name>, so a
# release needs no link edits. Only the version chip (<span class="ver">) is stamped.
V=$(sed -n 's/^kg.version=//p' android/gradle.properties)
cp build/kidguard.apk "build/kidguard-$V.apk"; cp build/kidguard-parent.apk "build/kidguard-parent-$V.apk"; cp build/kidguard-home.apk "build/kidguard-home-$V.apk"
perl -pi -e 's#<span class="ver">v[0-9.]*</span>#<span class="ver">v'"$V"'</span>#g' \
  website/index.html website/en/index.html website/guide.html website/en/guide.html
scripts/stamp-site.sh

# QR provisioning: SHA-256 of the signing certificate, URL-safe base64 without padding -> ui/provision.json + ui/qr.html defaults
APKSIGNER=$(ls "$ANDROID_HOME"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)
if [ -n "$APKSIGNER" ]; then
  HEX=$("$APKSIGNER" verify --print-certs build/kidguard.apk | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -1)
else
  HEX=$(keytool -list -v -keystore android/app/signing.jks -storepass kidguard -alias kidguard | sed -n 's/^ *SHA256: //p' | tr -d ':' | tr 'A-F' 'a-f' | head -1)
fi
if [ -n "$HEX" ]; then
  SUM=$(printf '%s' "$HEX" | xxd -r -p | base64 | tr '+/' '-_' | tr -d '=\n')
  # fallback = this checkout's own GitHub repo (remote origin), so a fork's QR points at the fork's release
  REPO_URL=$(git remote get-url origin 2>/dev/null | sed -E 's#^git@github.com:#https://github.com/#; s#\.git$##' || true)
  case "$REPO_URL" in https://github.com/*) ;; *) REPO_URL=https://github.com/HoangTran0410/kidguard ;; esac
  [ -f ui/provision.json ] && URL=$(sed -n 's/.*"apkUrl": *"\([^"]*\)".*/\1/p' ui/provision.json) || URL=
  : "${URL:=$REPO_URL/releases/latest/download/kidguard.apk}"
  J="{\"component\":\"dev.kidguard/dev.kidguard.Admin\",\"checksum\":\"$SUM\",\"apkUrl\":\"$URL\"}"
  echo "$J" > ui/provision.json
  # the worker bundles only .html, so the same values are inlined into the page between /*KG*/ markers
  sed -E "s|/\*KG\*/.*/\*KG\*/|/*KG*/$J/*KG*/|" ui/qr.html > ui/qr.html.tmp && mv ui/qr.html.tmp ui/qr.html
  echo "QR checksum: $SUM"
else
  echo "warning: could not compute the signing checksum (ui/provision.json not updated)" >&2
fi
echo "build/kidguard.apk ($(du -h build/kidguard.apk | cut -f1))"
