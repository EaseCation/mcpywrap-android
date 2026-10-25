#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MCPY_SDK=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
MCPY_DEVICE=${ANDROID_SERIAL:-emulator-5564}
"$MCPY_SDK/platform-tools/adb" -s "$MCPY_DEVICE" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
"$MCPY_SDK/platform-tools/adb" -s "$MCPY_DEVICE" shell am start -n net.easecation.mcpywrap/.MainActivity
