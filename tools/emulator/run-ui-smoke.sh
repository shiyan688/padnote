#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "${script_dir}/../.." && pwd)
toolchain_root="${PADNOTE_TOOLCHAIN_ROOT:-${project_root}/.toolchain}"
sdk_root="${ANDROID_SDK_ROOT:-${toolchain_root}/android-sdk}"
runtime_dir="${PADNOTE_EMULATOR_RUNTIME:-${toolchain_root}/emulator-runtime}"
gradle_home="${PADNOTE_GRADLE_USER_HOME:-${toolchain_root}/gradle-home}"
gradle_bin="${PADNOTE_GRADLE_BIN:-${toolchain_root}/gradle/gradle-8.9/bin/gradle}"
serial="emulator-5554"
app_id="${PADNOTE_APP_ID:-com.padnote.android.beta}"

"${script_dir}/start-headless.sh"
trap '"${script_dir}/stop-headless.sh"' EXIT

export JAVA_HOME="${JAVA_HOME:-${toolchain_root}/jdk}"
export ANDROID_SDK_ROOT="${sdk_root}"
(
    cd "${project_root}/android"
    GRADLE_USER_HOME="${gradle_home}" "${gradle_bin}" --no-daemon \
        :app:assembleDebug :app:assembleDebugAndroidTest
)

adb="${sdk_root}/platform-tools/adb"
"${adb}" -s "${serial}" install -r \
    "${project_root}/android/app/build/outputs/apk/debug/app-debug.apk"
"${adb}" -s "${serial}" install -r \
    "${project_root}/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
"${adb}" -s "${serial}" shell pm clear "${app_id}"

mkdir -p "${runtime_dir}"
result_file="${runtime_dir}/instrumentation.txt"
"${adb}" -s "${serial}" shell am instrument -w \
    "${app_id}.test/androidx.test.runner.AndroidJUnitRunner" \
    | tee "${result_file}"
grep -Eq '^OK \([0-9]+ tests?\)$' "${result_file}"
