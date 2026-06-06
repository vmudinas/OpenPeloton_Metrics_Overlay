#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/android_env.sh"

APK_PATH="$ROOT_DIR/dist/VitasTreadMetrics-debug.apk"

if [[ ! -f "$APK_PATH" ]]; then
  "$ROOT_DIR/scripts/build_overlay_apk.sh"
fi

adb devices
adb install -r "$APK_PATH"
adb shell appops set org.openpelo.beltstatusdump SYSTEM_ALERT_WINDOW allow || true
DEVICE_SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
if [[ "$DEVICE_SDK" =~ ^[0-9]+$ ]] && (( DEVICE_SDK >= 33 )); then
  adb shell pm grant \
    org.openpelo.beltstatusdump \
    android.permission.POST_NOTIFICATIONS || true
fi
adb shell monkey \
  -p org.openpelo.beltstatusdump \
  -c android.intent.category.LAUNCHER \
  1
