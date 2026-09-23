#!/usr/bin/env bash
# Real-endpoint smoke test for PadNote's AI routes (direct / split / vault).
#
# Pass the API key explicitly through the environment; it is never printed,
# logged, or committed. Usage:
#
#   tools/e2e/run-e2e.sh [direct|split|vault|all]
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "${script_dir}/../.." && pwd)
toolchain_root="${PADNOTE_TOOLCHAIN_ROOT:-${project_root}/.toolchain}"
jdk_bin="${JAVA_HOME:-${toolchain_root}/jdk}/bin"
sdk_root="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-${toolchain_root}/android-sdk}}"
android_jar="${sdk_root}/platforms/android-35/android.jar"
gradle_home="${PADNOTE_GRADLE_USER_HOME:-${GRADLE_USER_HOME:-${project_root}/.gradle-user-home}}"

key="${E2E_API_KEY:-}"
if [[ -z "${key}" ]]; then
  echo "No API key provided; set E2E_API_KEY explicitly before running this test." >&2
  exit 1
fi

if [[ ! -x "${jdk_bin}/javac" || ! -x "${jdk_bin}/java" || ! -f "${android_jar}" ]]; then
  echo "Set JAVA_HOME to JDK 17 and ANDROID_SDK_ROOT to an SDK containing Platform 35." >&2
  exit 1
fi

json_cache="${gradle_home}/caches/modules-2/files-2.1/org.json"
if [[ ! -d "${json_cache}" ]]; then
  echo "org.json cache not found; run the Gradle unit tests first and set PADNOTE_GRADLE_USER_HOME to their cache." >&2
  exit 1
fi
org_json=$(find "${json_cache}" -name "json-*.jar" -print -quit)
if [[ -z "${org_json}" ]]; then
  echo "org.json jar not found; run one gradle build first." >&2
  exit 1
fi

build_dir=$(mktemp -d)
trap 'rm -rf "${build_dir}"' EXIT

src="${project_root}/android/app/src/main/java"
shims="${script_dir}/java"

"${jdk_bin}/javac" -encoding UTF-8 -nowarn \
  -cp "${android_jar}:${org_json}" \
  -sourcepath "${src}:${shims}" \
  -d "${build_dir}" \
  "${shims}/com/padnote/android/E2eDriver.java" \
  2>&1

E2E_API_KEY="${key}" \
E2E_ENDPOINT="${E2E_ENDPOINT:-https://openrouter.ai/api/v1}" \
E2E_MODEL="${E2E_MODEL:-stealth/ox-alpha}" \
"${jdk_bin}/java" -Djava.awt.headless=true -Dfile.encoding=UTF-8 \
  -cp "${build_dir}:${org_json}:${android_jar}" \
  com.padnote.android.E2eDriver "${1:-all}"
