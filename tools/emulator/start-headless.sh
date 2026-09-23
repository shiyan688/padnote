#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "${script_dir}/../.." && pwd)
toolchain_root="${PADNOTE_TOOLCHAIN_ROOT:-${project_root}/.toolchain}"
sdk_root="${ANDROID_SDK_ROOT:-${toolchain_root}/android-sdk}"
avd_home="${PADNOTE_AVD_HOME:-${toolchain_root}/avd}"
runtime_dir="${PADNOTE_EMULATOR_RUNTIME:-${toolchain_root}/emulator-runtime}"
avd_name="${PADNOTE_AVD_NAME:-PadNote_API_35_Tablet}"
serial="emulator-5554"
emulator_libs="${toolchain_root}/emulator-libs/usr/lib/x86_64-linux-gnu"
boot_timeout_seconds="${PADNOTE_EMULATOR_BOOT_TIMEOUT_SECONDS:-1200}"

mkdir -p "${runtime_dir}/tmp"
export ANDROID_AVD_HOME="${avd_home}"
export LD_LIBRARY_PATH="${emulator_libs}:${emulator_libs}/pulseaudio${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export ANDROID_TMP="${runtime_dir}/tmp"
export TMPDIR="${runtime_dir}/tmp"
unset HTTP_PROXY HTTPS_PROXY ALL_PROXY http_proxy https_proxy all_proxy

if timeout 6 "${sdk_root}/platform-tools/adb" -s "${serial}" shell getprop sys.boot_completed \
        2>/dev/null | grep -q '^1$' \
        && timeout 6 "${sdk_root}/platform-tools/adb" -s "${serial}" shell service check package \
        2>/dev/null | grep -q 'found$' \
        && timeout 6 "${sdk_root}/platform-tools/adb" -s "${serial}" shell service check activity \
        2>/dev/null | grep -q 'found$'; then
    echo "${serial} is already ready."
    exit 0
fi

cpu_count=$(nproc)
cpu_first=$((cpu_count - 4))
cpu_set="${PADNOTE_EMULATOR_CPUSET:-${cpu_first}-$((cpu_count - 1))}"
acceleration=(-accel off)
if [[ -c /dev/kvm && -r /dev/kvm && -w /dev/kvm ]]; then
    acceleration=(-accel on)
fi

nohup nice -n 10 taskset -c "${cpu_set}" \
    "${sdk_root}/emulator/emulator" \
    -avd "${avd_name}" \
    -port 5554 \
    -no-window \
    -no-audio \
    -no-boot-anim \
    -no-snapshot \
    "${acceleration[@]}" \
    -gpu swiftshader_indirect \
    -cores 4 \
    -memory 4096 \
    -camera-back none \
    -camera-front none \
    >"${runtime_dir}/emulator.log" 2>&1 &
echo $! >"${runtime_dir}/emulator.pid"
emulator_pid=$(<"${runtime_dir}/emulator.pid")
trap '"${script_dir}/stop-headless.sh"; exit 130' INT TERM

boot_deadline=$((SECONDS + boot_timeout_seconds))
while ((SECONDS < boot_deadline)); do
    if timeout 6 "${sdk_root}/platform-tools/adb" -s "${serial}" shell getprop sys.boot_completed \
            2>/dev/null | grep -q '^1$' \
            && timeout 6 "${sdk_root}/platform-tools/adb" -s "${serial}" shell service check package \
            2>/dev/null | grep -q 'found$' \
            && timeout 6 "${sdk_root}/platform-tools/adb" -s "${serial}" shell service check activity \
            2>/dev/null | grep -q 'found$'; then
        trap - INT TERM
        echo "${serial} ready on CPUs ${cpu_set}, guest RAM 4096 MB."
        exit 0
    fi
    if ! kill -0 "${emulator_pid}" 2>/dev/null; then
        echo "Emulator exited during boot; see ${runtime_dir}/emulator.log." >&2
        "${script_dir}/stop-headless.sh"
        exit 1
    fi
    sleep 2
done

echo "Emulator did not finish booting; see ${runtime_dir}/emulator.log." >&2
"${script_dir}/stop-headless.sh"
exit 1
