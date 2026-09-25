#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
sdk=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}
test -n "$sdk"
mkdir -p "$repo/dist/ci"
adb="$sdk/platform-tools/adb"
emulator="$sdk/emulator/emulator"
serial=emulator-5554

# This entry expects a disposable CI runner, never an attached personal device.
if [[ ${CI:-} != true ]]; then
  echo 'Run native tests locally with an explicitly selected emulator; this entry requires CI=true.' >&2
  exit 2
fi

"$emulator" -avd padnote-ci -port 5554 -no-window -no-audio -no-boot-anim \
  -no-snapshot -no-metrics -gpu swiftshader_indirect -accel on \
  -memory 2048 -cores 2 -camera-back none -camera-front none \
  >"$repo/dist/ci/android-emulator.log" 2>&1 &
emulator_pid=$!
trap 'kill "$emulator_pid" 2>/dev/null || true' EXIT

deadline=$((SECONDS + 300))
booted=false
while (( SECONDS < deadline )); do
  if ! kill -0 "$emulator_pid" 2>/dev/null; then
    echo 'Emulator exited before boot; see android-emulator.log.' >&2
    exit 1
  fi
  if [[ $(timeout 10 "$adb" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
    booted=true
    break
  fi
  sleep 2
done
if [[ $booted != true ]]; then
  echo 'Emulator did not boot within 300 seconds.' >&2
  exit 1
fi
"$adb" -s "$serial" shell settings put global window_animation_scale 0
"$adb" -s "$serial" shell settings put global transition_animation_scale 0
"$adb" -s "$serial" shell settings put global animator_duration_scale 0
"$adb" -s "$serial" shell input keyevent 82
cd "$repo/android"
# Gradle evaluates test reports and fails on test failures; adb instrument alone
# can return exit 0 even when assertions failed.
ANDROID_SERIAL="$serial" bash gradlew --no-daemon :app:connectedDebugAndroidTest \
  2>&1 | tee "$repo/dist/ci/android-native.log"
