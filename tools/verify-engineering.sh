#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MCPY_SDK=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
MCPY_ADB="$MCPY_SDK/platform-tools/adb"
MCPY_DEVICE=${ANDROID_SERIAL:-emulator-5564}
if [ "${MCPY_SKIP_BUILD:-0}" != 1 ]; then sh "$ROOT/tools/build.sh" :app:assembleDebugAndroidTest; fi
"$MCPY_ADB" -s "$MCPY_DEVICE" install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk"
"$MCPY_ADB" -s "$MCPY_DEVICE" install -r "$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"$MCPY_ADB" -s "$MCPY_DEVICE" shell appops set net.easecation.mcpywrap MANAGE_EXTERNAL_STORAGE allow
mkdir -p "$ROOT/artifacts/engineering"
"$MCPY_ADB" -s "$MCPY_DEVICE" shell am instrument -w -e class net.easecation.mcpywrap.EngineeringCoreTest net.easecation.mcpywrap.test/androidx.test.runner.AndroidJUnitRunner > "$ROOT/artifacts/engineering/instrumentation.txt"
cat "$ROOT/artifacts/engineering/instrumentation.txt"
rg -q 'OK \([0-9]+ tests?\)' "$ROOT/artifacts/engineering/instrumentation.txt"
"$MCPY_ADB" -s "$MCPY_DEVICE" pull /sdcard/mcpy/logs/product-evidence/core-integration.json "$ROOT/artifacts/engineering/"
"$MCPY_ADB" -s "$MCPY_DEVICE" pull /sdcard/mcpy/logs/product-evidence/core-update.json "$ROOT/artifacts/engineering/"
