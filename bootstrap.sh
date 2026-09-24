#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.jar"
if [[ ! -f "$WRAPPER" ]]; then
  mkdir -p "$(dirname "$WRAPPER")"
  echo "Downloading Gradle wrapper jar..."
  curl -L --fail -o "$WRAPPER" https://raw.githubusercontent.com/BSGHumanz/forge-1.20.1-47.4.10-mdk/master/gradle/wrapper/gradle-wrapper.jar
fi
if [[ ! -x "$ROOT/gradlew" ]]; then
  echo "gradlew is missing; run: gradle wrapper --gradle-version 8.8"
  exit 1
fi
exec "$ROOT/gradlew" "$@"
