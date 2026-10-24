#!/bin/bash
# Builds the native dependency stack (libjpeg-turbo, x265, aom, libde265, libheif)
# into native/prefix/<flavor>.
#
# Usage:
#   build_deps.sh host
#   build_deps.sh android <abi> <ndk_path> [api_level]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NATIVE_DIR="$(dirname "$SCRIPT_DIR")"
MODE="${1:?usage: build_deps.sh host|android <abi> <ndk_path> [api]}"

# Bound this superbuild's outer projects and their direct build-tool jobs.
TOTAL_JOBS="${ULTRASLIM_DEPS_TOTAL_JOBS-4}"
PROJECT_JOBS="${ULTRASLIM_DEPS_PROJECT_JOBS-2}"
validate_positive_integer() {
  case "$2" in
    ''|*[!0-9]*|0*)
      echo "$1 must be a positive integer (got: $2)" >&2
      exit 2
      ;;
  esac
}
validate_positive_integer ULTRASLIM_DEPS_TOTAL_JOBS "$TOTAL_JOBS"
validate_positive_integer ULTRASLIM_DEPS_PROJECT_JOBS "$PROJECT_JOBS"
if [ "$PROJECT_JOBS" -gt "$TOTAL_JOBS" ]; then
  PROJECT_JOBS="$TOTAL_JOBS"
fi
INNER_JOBS=$((TOTAL_JOBS / PROJECT_JOBS))

if [ "$MODE" = "host" ]; then
  FLAVOR="host"
  EXTRA_ARGS=()
elif [ "$MODE" = "android" ]; then
  ABI="${2:?missing abi}"
  NDK="${3:?missing ndk path}"
  API="${4:-26}"
  FLAVOR="android-${ABI}"
  # The NDK toolchain restricts find_* to the sysroot; the codecs we pre-install
  # live outside it, so wrap the toolchain and relax the lookup modes.
  WRAPPER_TOOLCHAIN="$SCRIPT_DIR/build/android-findboth.toolchain.cmake"
  mkdir -p "$SCRIPT_DIR/build"
  cat > "$WRAPPER_TOOLCHAIN" <<EOF
include("$NDK/build/cmake/android.toolchain.cmake")
set(CMAKE_FIND_ROOT_PATH_MODE_PROGRAM NEVER)
set(CMAKE_FIND_ROOT_PATH_MODE_LIBRARY BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_INCLUDE BOTH)
set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH)
EOF
  EXTRA_ARGS=(
    -DCMAKE_TOOLCHAIN_FILE="$WRAPPER_TOOLCHAIN"
    -DANDROID_ABI="$ABI"
    -DANDROID_PLATFORM="android-$API"
  )
else
  echo "unknown mode: $MODE" >&2
  exit 1
fi

BUILD_DIR="$SCRIPT_DIR/build/$FLAVOR"
PREFIX="$NATIVE_DIR/prefix/$FLAVOR"

if [ "$MODE" = "android" ]; then
  # some dep builds invoke llvm-ar & friends by bare name
  export PATH="$NDK/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64/bin:$PATH"
fi

mkdir -p "$BUILD_DIR" "$PREFIX"

if [ "$MODE" = "android" ]; then
  # dav1d uses Meson; pre-generate its cross file for this ABI/API.
  # Meson doesn't read the CMake path-map injection, so pass the build root here.
  bash "$SCRIPT_DIR/patches/gen_meson_cross.sh" "$NDK" "$API" "$BUILD_DIR/android-meson-cross.ini" "$BUILD_DIR"
fi

cmake -G Ninja -S "$SCRIPT_DIR" -B "$BUILD_DIR" \
  -DCMAKE_BUILD_TYPE=Release \
  -DDEPS_PREFIX="$PREFIX" \
  -DDEPS_INNER_JOBS="$INNER_JOBS" \
  ${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}

cmake --build "$BUILD_DIR" --parallel "$PROJECT_JOBS"

echo "==> deps installed to $PREFIX"
ls "$PREFIX/lib"
