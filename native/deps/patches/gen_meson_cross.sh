#!/bin/bash
# Generates a Meson cross file for dav1d Android builds.
# usage: gen_meson_cross.sh <ndk_path> <api_level> <out_file> [build_root]
#   build_root: superbuild binary dir; when set, -ffile-prefix-map keeps its absolute
#               path out of __FILE__/assert strings in the shipped binary.
set -euo pipefail
NDK="$1"
API="$2"
OUT="$3"
BUILD_ROOT="${4:-}"
TC="$NDK/toolchains/llvm/prebuilt/$(uname -s | tr '[:upper:]' '[:lower:]')-x86_64"
# Meson passes these through its own argv handling, so a quoted single argument
# survives the spaces in this repository's path.
PREFIX_ARG=""
if [ -n "$BUILD_ROOT" ]; then
  PREFIX_ARG=", '-ffile-prefix-map=$BUILD_ROOT=/ultraslim-deps'"
fi
cat > "$OUT" <<EOF
[binaries]
c = '$TC/bin/aarch64-linux-android$API-clang'
cpp = '$TC/bin/aarch64-linux-android$API-clang++'
ar = '$TC/bin/llvm-ar'
strip = '$TC/bin/llvm-strip'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'

[properties]
needs_exe_wrapper = true

[built-in options]
c_args = ['-fPIC', '-ffunction-sections', '-fdata-sections'$PREFIX_ARG]
cpp_args = ['-fPIC', '-ffunction-sections', '-fdata-sections'$PREFIX_ARG]
EOF
