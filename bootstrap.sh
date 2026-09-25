#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
WRAPPER="$ROOT/gradle/wrapper/gradle-wrapper.jar"
if [[ ! -f "$WRAPPER" ]]; then
  echo "gradle/wrapper/gradle-wrapper.jar is missing." >&2
  echo "Regenerate it with a trusted local Gradle install: gradle wrapper --gradle-version 8.8" >&2
  exit 1
fi
if [[ ! -x "$ROOT/gradlew" ]]; then
  chmod +x "$ROOT/gradlew"
fi
if [[ -z "${JAVA_HOME:-}" ]] && command -v brew >/dev/null 2>&1; then
  BREW_JAVA_HOME="$(brew --prefix openjdk@17 2>/dev/null || true)/libexec/openjdk.jdk/Contents/Home"
  if [[ -x "$BREW_JAVA_HOME/bin/java" ]]; then
    export JAVA_HOME="$BREW_JAVA_HOME"
  fi
fi
exec "$ROOT/gradlew" "$@"
