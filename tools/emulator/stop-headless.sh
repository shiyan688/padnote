#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "${script_dir}/../.." && pwd)
toolchain_root="${PADNOTE_TOOLCHAIN_ROOT:-${project_root}/.toolchain}"
sdk_root="${ANDROID_SDK_ROOT:-${toolchain_root}/android-sdk}"
runtime_dir="${PADNOTE_EMULATOR_RUNTIME:-${toolchain_root}/emulator-runtime}"
serial="emulator-5554"

if "${sdk_root}/platform-tools/adb" -s "${serial}" get-state >/dev/null 2>&1; then
    "${sdk_root}/platform-tools/adb" -s "${serial}" emu kill >/dev/null
fi

if [[ -f "${runtime_dir}/emulator.pid" ]]; then
    emulator_pid=$(<"${runtime_dir}/emulator.pid")
    for _ in $(seq 1 30); do
        if ! kill -0 "${emulator_pid}" 2>/dev/null; then
            rm -f "${runtime_dir}/emulator.pid"
            echo "${serial} stopped."
            exit 0
        fi
        sleep 1
    done
    kill "${emulator_pid}"
    rm -f "${runtime_dir}/emulator.pid"
fi

echo "${serial} stopped."
