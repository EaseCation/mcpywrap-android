#!/bin/sh
set -eu
MCPY_SDK=${ANDROID_HOME:-"$HOME/Library/Android/sdk"}
exec "${MCPY_EMULATOR_BIN:-$MCPY_SDK/emulator/emulator}" \
  -avd "${MCPY_AVD:-Mcpy_Product_34_ARM64}" \
  -port "${MCPY_EMULATOR_PORT:-5564}" -skin 720x1600 -gpu "${MCPY_EMULATOR_GPU:-swiftshader_indirect}" -no-audio -no-snapshot
