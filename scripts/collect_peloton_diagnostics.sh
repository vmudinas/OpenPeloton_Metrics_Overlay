#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/android_env.sh"

OUTPUT_DIR="$ROOT_DIR/diagnostics"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
OUTPUT_FILE="$OUTPUT_DIR/peloton_metrics_$TIMESTAMP.txt"
mkdir -p "$OUTPUT_DIR"

if ! adb get-state >/dev/null 2>&1; then
  echo "No authorized Android device is connected."
  adb devices -l
  exit 1
fi

{
  echo "=== DEVICE ==="
  adb shell getprop ro.product.manufacturer
  adb shell getprop ro.product.model
  adb shell getprop ro.build.version.release
  adb shell getprop ro.build.version.sdk

  echo
  echo "=== APP PACKAGE ==="
  adb shell dumpsys package org.openpelo.beltstatusdump

  echo
  echo "=== APP OPS ==="
  adb shell appops get org.openpelo.beltstatusdump

  echo
  echo "=== PELOTON SERVICES ==="
  adb shell dumpsys activity services |
    grep -E -i -C 4 "affernet|ITreadInterface|sensorstate|tread|belt" || true

  echo
  echo "=== RECENT PELOTON BROADCASTS ==="
  adb shell dumpsys activity broadcasts |
    grep -E -i -C 4 "BELT_STATUS|IN_CLASS_STATUS|beltstatusdump" || true

  echo
  echo "=== APP AND AFFERNET LOGCAT ==="
  adb logcat -d -v threadtime \
    -s "TreadMetrics:I" "AffernetService:D" "TreadServiceHelper:D" "*:S"
} >"$OUTPUT_FILE" 2>&1

echo "Diagnostics saved to: $OUTPUT_FILE"
