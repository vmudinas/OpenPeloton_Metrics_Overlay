#!/usr/bin/env bash

if [[ -z "${JAVA_HOME:-}" ]]; then
  if command -v brew >/dev/null 2>&1; then
    JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home"
  elif [[ -x /usr/libexec/java_home ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17)"
  fi
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
  if [[ -d "$HOME/Library/Android/sdk" ]]; then
    ANDROID_HOME="$HOME/Library/Android/sdk"
  elif command -v brew >/dev/null 2>&1; then
    ANDROID_HOME="$(brew --prefix)/share/android-commandlinetools"
  fi
fi

if [[ -z "${JAVA_HOME:-}" || ! -d "$JAVA_HOME" ]]; then
  echo "Java 17 was not found. Run ./scripts/setup_android_cli_macos.sh." >&2
  return 1 2>/dev/null || exit 1
fi

if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then
  echo "Android SDK was not found. Run ./scripts/setup_android_cli_macos.sh." >&2
  return 1 2>/dev/null || exit 1
fi

export JAVA_HOME
export ANDROID_HOME
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
