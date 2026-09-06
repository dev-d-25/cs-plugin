#!/usr/bin/env bash
# Deterministic local build entrypoint (plan Phase 1).
#
# The default `java` on this machine is newer than what AGP 8.7 supports,
# so Rosa must run on Java 17 and the Android SDK must be visible.
# This script pins both when the known local paths exist and otherwise
# falls back to the current environment (CI sets its own JDK/SDK).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [ -d /usr/lib/jvm/java-17-temurin-jdk ]; then
  export JAVA_HOME=/usr/lib/jvm/java-17-temurin-jdk
fi
if [ -z "${ANDROID_HOME:-}" ] && [ -d /home/dev/Android/Sdk ]; then
  export ANDROID_HOME=/home/dev/Android/Sdk
fi
if [ -z "${ANDROID_SDK_ROOT:-}" ] && [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
fi

echo "JAVA_HOME=${JAVA_HOME:-<unset>} (java: $(java -version 2>&1 | head -1))"
echo "ANDROID_HOME=${ANDROID_HOME:-<unset>}"

cd "$ROOT"
exec ./gradlew --offline --no-daemon "$@"
