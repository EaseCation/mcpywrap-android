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
"$MCPY_ADB" -s "$MCPY_DEVICE" shell pm grant net.easecation.mcpywrap android.permission.POST_NOTIFICATIONS
mkdir -p "$ROOT/artifacts/runtime"
"$MCPY_ADB" -s "$MCPY_DEVICE" shell am instrument -w -e class net.easecation.mcpywrap.RuntimeIntegrationTest net.easecation.mcpywrap.test/androidx.test.runner.AndroidJUnitRunner > "$ROOT/artifacts/runtime/instrumentation.txt"
cat "$ROOT/artifacts/runtime/instrumentation.txt"
"$MCPY_ADB" -s "$MCPY_DEVICE" pull /sdcard/mcpy/logs/product-evidence/. "$ROOT/artifacts/runtime/"
rg -q 'OK \([0-9]+ tests?\)' "$ROOT/artifacts/runtime/instrumentation.txt"
