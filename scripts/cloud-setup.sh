#!/usr/bin/env bash
set -euo pipefail
# Prepared cloud snapshot retains /workspace/tooling. Standard local builds only need JDK 21 + SDK 35.
export JAVA_HOME=/workspace/tooling/jdk-21.0.9+10
export ANDROID_HOME=/workspace/tooling/android-sdk
export GRADLE_USER_HOME=/workspace/tooling/gradle-home
export JAVA_OPTS="${JAVA_OPTS:-} -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
if [[ ! -x "$JAVA_HOME/bin/javac" || ! -f "$ANDROID_HOME/platforms/android-35/android.jar" ]]; then
  echo 'Prepared JDK/Android SDK missing. Restore the published environment snapshot or install JDK 21 and SDK 35 using README instructions.' >&2
  exit 1
fi
cd /workspace/Between-Us
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > local.properties
./gradlew -Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts \
  -Dhttps.proxyHost=proxy -Dhttps.proxyPort=8080 \
  -Dhttp.proxyHost=proxy -Dhttp.proxyPort=8080 \
  :core:test testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 scripts/check_apk.py
