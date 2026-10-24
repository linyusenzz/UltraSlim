#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--device" ) ]]; then
  echo "usage: $0 [--device]" >&2
  exit 2
fi
export ULTRASLIM_BUILD_ROOT="${ULTRASLIM_BUILD_ROOT:-$HOME/.cache/ultraslim-build}"
python3 scripts/check_repository.py
python3 scripts/test_release_identity.py
host_build="$ULTRASLIM_BUILD_ROOT/host-test"
cmake -G Ninja -S native -B "$host_build" -DULTRASLIM_HOST_TEST=ON -DCMAKE_BUILD_TYPE=Release
cmake --build "$host_build" --parallel 4
ctest --test-dir "$host_build" --output-on-failure
gradle_args=(-Pkotlin.incremental=false)
if [[ "${1:-}" == "--device" ]]; then
  gradle_args+=(-PultraslimDebugSuffix=.verifyqa)
fi
./gradlew "${gradle_args[@]}" :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:lintRelease :app:validateDebugScreenshotTest
release_apk="$ULTRASLIM_BUILD_ROOT/app/outputs/apk/release/app-release-unsigned.apk"
if [[ -n "${ULTRASLIM_KEYSTORE:-}" ]]; then
  release_apk="$ULTRASLIM_BUILD_ROOT/app/outputs/apk/release/app-release.apk"
fi
python3 scripts/check_apk_alignment.py "$ULTRASLIM_BUILD_ROOT/app/outputs/apk/debug/app-debug.apk" "$release_apk"
if [[ "${1:-}" == "--device" ]]; then
  ./gradlew "${gradle_args[@]}" :app:connectedDebugAndroidTest
fi
