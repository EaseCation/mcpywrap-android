#!/bin/sh
set -eu
PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
if [ -n "${MCPY_JAVA_HOME:-}" ]; then
  MCPY_JDK=$MCPY_JAVA_HOME
elif [ "$(uname -s)" = Darwin ]; then
  MCPY_JDK=$(/usr/libexec/java_home -v 17)
else
  MCPY_JDK=${JAVA_HOME:?Set JAVA_HOME to JDK 17}
fi
cd "$PROJECT_ROOT"
exec env JAVA_HOME="$MCPY_JDK" ./gradlew :app:assembleDebug :core:model:test "$@"
