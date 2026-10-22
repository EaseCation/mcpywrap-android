#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SDK=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Library/Android/sdk"}}
case "$(uname -s)" in Darwin) HOST_TAG=darwin-x86_64 ;; Linux) HOST_TAG=linux-x86_64 ;; *) echo "Unsupported build host" >&2; exit 1 ;; esac
CXX="$SDK/ndk/25.1.8937393/toolchains/llvm/prebuilt/$HOST_TAG/bin/aarch64-linux-android30-clang++"
mkdir -p "$ROOT/game-host/src/main/jniLibs/arm64-v8a"
"$CXX" -std=c++17 -shared -fPIC -O2 -Wl,-z,max-page-size=16384 "$ROOT/game-host/src/main/cpp/bridge.cpp" -o "$ROOT/game-host/src/main/jniLibs/arm64-v8a/libmcpy_bridge.so" -llog -ldl -static-libstdc++
