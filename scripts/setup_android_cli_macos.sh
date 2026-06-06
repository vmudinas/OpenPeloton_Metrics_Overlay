#!/usr/bin/env bash
set -euo pipefail

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "This setup script is for macOS."
  exit 1
fi

if ! command -v brew >/dev/null 2>&1; then
  echo "Homebrew is required: https://brew.sh"
  exit 1
fi

brew install openjdk@17
brew install --cask android-commandlinetools android-platform-tools

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/android_env.sh"

yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses >/dev/null
sdkmanager \
  --sdk_root="$ANDROID_HOME" \
  "platforms;android-34" \
  "build-tools;34.0.0"

java -version
adb version
sdkmanager --version
echo "Android command-line toolchain is ready."
