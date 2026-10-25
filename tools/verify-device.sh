#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
MCPY_SDK=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
MCPY_ADB=${ADB:-"$MCPY_SDK/platform-tools/adb"}
MCPY_DEVICE=${ANDROID_SERIAL:-emulator-5564}
if [ "${MCPY_SKIP_BUILD:-0}" != 1 ]; then sh "$PROJECT_ROOT/tools/build.sh" :core:workspace:testDebugUnitTest :runtime-core:testDebugUnitTest :app:assembleDebugAndroidTest; fi
# Do not use Gradle's managed test cleanup: it uninstalls the app and its installed runtime.
"$MCPY_ADB" -s "$MCPY_DEVICE" install -r "$PROJECT_ROOT/app/build/outputs/apk/debug/app-debug.apk"
"$MCPY_ADB" -s "$MCPY_DEVICE" install -r "$PROJECT_ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"$MCPY_ADB" -s "$MCPY_DEVICE" shell appops set net.easecation.mcpywrap MANAGE_EXTERNAL_STORAGE allow
"$MCPY_ADB" -s "$MCPY_DEVICE" shell pm grant net.easecation.mcpywrap android.permission.POST_NOTIFICATIONS
mkdir -p "$PROJECT_ROOT/artifacts/product"
"$MCPY_ADB" -s "$MCPY_DEVICE" shell mkdir -p /sdcard/mcpy/logs/product-evidence
# Targeted UI regressions can run without revoking storage access on a prepared phone.
if [ -n "${MCPY_TEST_CLASS:-}" ]; then
  "$MCPY_ADB" -s "$MCPY_DEVICE" shell am instrument -w -e class "$MCPY_TEST_CLASS" net.easecation.mcpywrap.test/androidx.test.runner.AndroidJUnitRunner > "$PROJECT_ROOT/artifacts/product/targeted-test.txt"
  cat "$PROJECT_ROOT/artifacts/product/targeted-test.txt"
  "$MCPY_ADB" -s "$MCPY_DEVICE" pull /sdcard/mcpy/logs/product-evidence/. "$PROJECT_ROOT/artifacts/product/"
  rg -q 'OK \([0-9]+ tests?\)' "$PROJECT_ROOT/artifacts/product/targeted-test.txt"
  exit
fi
"$MCPY_ADB" -s "$MCPY_DEVICE" shell appops set net.easecation.mcpywrap MANAGE_EXTERNAL_STORAGE deny
"$MCPY_ADB" -s "$MCPY_DEVICE" shell am instrument -w -e class net.easecation.mcpywrap.StoragePermissionTest net.easecation.mcpywrap.test/androidx.test.runner.AndroidJUnitRunner > "$PROJECT_ROOT/artifacts/product/permission-test.txt"
"$MCPY_ADB" -s "$MCPY_DEVICE" shell appops set net.easecation.mcpywrap MANAGE_EXTERNAL_STORAGE allow
cat "$PROJECT_ROOT/artifacts/product/permission-test.txt"
rg -q 'OK \([0-9]+ tests?\)' "$PROJECT_ROOT/artifacts/product/permission-test.txt"
"$MCPY_ADB" -s "$MCPY_DEVICE" shell am instrument -w -e class net.easecation.mcpywrap.ProductFlowTest net.easecation.mcpywrap.test/androidx.test.runner.AndroidJUnitRunner > "$PROJECT_ROOT/artifacts/product/instrumentation.txt"
cat "$PROJECT_ROOT/artifacts/product/instrumentation.txt"
rg -q 'OK \([0-9]+ tests?\)' "$PROJECT_ROOT/artifacts/product/instrumentation.txt"
"$MCPY_ADB" -s "$MCPY_DEVICE" pull /sdcard/mcpy/logs/product-evidence/. "$PROJECT_ROOT/artifacts/product/"
