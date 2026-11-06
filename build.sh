#!/usr/bin/env bash
# Builds build/kidguard.apk (release, signed with app/signing.jks, made on the first build).
# Needs JDK 17+ and the Android SDK (ANDROID_HOME, default ~/Library/Android/sdk).
# Behind a TLS-inspecting proxy: TRUSTSTORE=/path/to/store.jks ./build.sh (a copy of the JDK cacerts + the proxy CA).
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_HOME:=$HOME/Library/Android/sdk}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
if [ ! -f app/signing.jks ]; then
  keytool -genkeypair -keystore app/signing.jks -storepass kidguard -keypass kidguard -alias kidguard \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=KidGuard" >/dev/null
fi
OPTS=()
if [ -n "${TRUSTSTORE:-}" ]; then
  T="-Djavax.net.ssl.trustStore=$TRUSTSTORE -Djavax.net.ssl.trustStorePassword=${TRUSTSTORE_PASS:-changeit}"
  export JAVA_OPTS="$T"
  OPTS=(-Dorg.gradle.jvmargs="-Xmx2g $T")
fi
./gradlew -q "${OPTS[@]}" testDebugUnitTest assembleRelease
mkdir -p build
cp app/build/outputs/apk/release/app-release.apk build/kidguard.apk
echo "build/kidguard.apk ($(du -h build/kidguard.apk | cut -f1))"
