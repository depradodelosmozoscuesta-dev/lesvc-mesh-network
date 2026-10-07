#!/usr/bin/env bash
# Builds the same native code (vendored ggwave + JNI bridge) for the host
# machine so JVM unit tests can run the real encoder/decoder without a phone.
# Usage: tools/build-host-jni.sh <output-dir> [android-sdk-dir] [java-home]
set -euo pipefail
OUT="${1:?output dir}"
SDK="${2:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}}"
JHOME="${3:-${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}}"
SRC="$(cd "$(dirname "$0")/../app/src/main/cpp" && pwd)"
CMAKE="$(command -v cmake || true)"
GEN=()
if [ -n "$SDK" ] && ls -d "$SDK"/cmake/*/bin >/dev/null 2>&1; then
  BIN="$(ls -d "$SDK"/cmake/*/bin | sort -V | tail -1)"
  [ -z "$CMAKE" ] && CMAKE="$BIN/cmake"
  [ -x "$BIN/ninja" ] && GEN=(-G Ninja -DCMAKE_MAKE_PROGRAM="$BIN/ninja")
fi
[ -n "$CMAKE" ] || { echo "cmake not found (install it or the Android SDK 'cmake' package)"; exit 1; }
mkdir -p "$OUT/obj"
"$CMAKE" -S "$SRC" -B "$OUT/obj" "${GEN[@]}" -DCMAKE_BUILD_TYPE=Release -DCMAKE_LIBRARY_OUTPUT_DIRECTORY="$OUT" -DLESVC_JAVA_HOME="$JHOME"
"$CMAKE" --build "$OUT/obj" --target lesvc_ggwave
ls "$OUT"/liblesvc_ggwave.* >/dev/null
echo "host JNI library ready in $OUT"
