#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/android_env.sh"

cd "$ROOT_DIR"
./gradlew --no-daemon clean lintDebug testDebugUnitTest assembleDebug

APK_PATH="$PWD/app/build/outputs/apk/debug/app-debug.apk"
DIST_DIR="$ROOT_DIR/dist"
DIST_APK="$DIST_DIR/VitasTreadMetrics-debug.apk"
mkdir -p "$DIST_DIR"
cp "$APK_PATH" "$DIST_APK"

echo "APK built at: $APK_PATH"
echo "Packaged copy: $DIST_APK"
shasum -a 256 "$DIST_APK"
