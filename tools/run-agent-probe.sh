#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || ( "$1" != "hermes" && "$1" != "openclaw" ) ]]; then
  echo "用法：PADNOTE_AGENT_URL=... PADNOTE_AGENT_TOKEN=... tools/run-agent-probe.sh hermes|openclaw" >&2
  exit 1
fi

script_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "${script_dir}/.." && pwd)
toolchain_root="${PADNOTE_TOOLCHAIN_ROOT:-/public/home/wangyg/padnote-tools}"
java_home="${JAVA_HOME:-${toolchain_root}/jdk}"
gradle_home_dir="${PADNOTE_GRADLE_USER_HOME:-${toolchain_root}/gradle-home}"
gradle_command="${PADNOTE_GRADLE_BIN:-${toolchain_root}/gradle/gradle-8.9/bin/gradle}"

if [[ ! -x "${java_home}/bin/java" ]]; then
  echo "找不到 JDK 17：${java_home}" >&2
  exit 1
fi

export JAVA_HOME="${java_home}"
export PATH="${JAVA_HOME}/bin:${PATH}"
cd "${project_root}"
GRADLE_USER_HOME="${gradle_home_dir}" "${gradle_command}" --no-daemon \
  -p android :agent-probe:run --args="$1"
