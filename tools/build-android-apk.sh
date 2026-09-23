#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "${script_dir}/.." && pwd)

# A maintainer may keep an offline toolchain together, but external users must
# opt into that layout explicitly. Normal users provide JAVA_HOME and an SDK.
toolchain_root="${PADNOTE_TOOLCHAIN_ROOT:-}"

if [[ -z "${JAVA_HOME:-}" && -n "${toolchain_root}" ]]; then
  JAVA_HOME="${toolchain_root}/jdk"
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME}/bin/java" ]]; then
  echo "JAVA_HOME must point to an executable JDK (set JAVA_HOME or PADNOTE_TOOLCHAIN_ROOT)." >&2
  exit 1
fi
# Both must reach the gradle child process; defaults above are plain shell vars.
export JAVA_HOME
export PATH="${JAVA_HOME}/bin:${PATH}"

if [[ -z "${ANDROID_SDK_ROOT:-}" ]]; then
  ANDROID_SDK_ROOT="${ANDROID_HOME:-}"
fi
if [[ -z "${ANDROID_SDK_ROOT}" && -n "${toolchain_root}" ]]; then
  ANDROID_SDK_ROOT="${toolchain_root}/android-sdk"
fi
if [[ ! -d "${ANDROID_SDK_ROOT}/platforms/android-35" ]]; then
  echo "ANDROID_SDK_ROOT (or ANDROID_HOME) must contain Android SDK Platform 35." >&2
  exit 1
fi
export ANDROID_SDK_ROOT

gradle_home_dir="${PADNOTE_GRADLE_USER_HOME:-${toolchain_root:+${toolchain_root}/gradle-home}}"
gradle_home_dir="${gradle_home_dir:-${project_root}/.gradle-user-home}"
gradle_command="${PADNOTE_GRADLE_BIN:-${project_root}/android/gradlew}"
if [[ ! -x "${gradle_command}" ]]; then
  echo "Gradle wrapper not found or not executable: ${gradle_command}" >&2
  echo "Run from a checkout containing android/gradlew, or set PADNOTE_GRADLE_BIN for a controlled wrapper." >&2
  exit 1
fi
apk_source="${project_root}/android/app/build/outputs/apk/debug/app-debug.apk"

# Read the version from build.gradle so the artifact name cannot drift out of
# sync with versionName and silently overwrite a previous release.
version_name=$(sed -n "s/^[[:space:]]*versionName[[:space:]]*'\([^']*\)'.*/\1/p" \
  "${project_root}/android/app/build.gradle" | head -1)
if [[ -z "${version_name}" ]]; then
  echo "Could not read versionName from android/app/build.gradle." >&2
  exit 1
fi
apk_target="${project_root}/dist/PadNote-MatePadAir-${version_name}-debug.apk"

mkdir -p "${gradle_home_dir}" "${project_root}/dist"

(
  cd "${project_root}/android"
  GRADLE_USER_HOME="${gradle_home_dir}" "${gradle_command}" --no-daemon \
    :app:assembleDebug :app:lintDebug
)

cp "${apk_source}" "${apk_target}"
apksigner="${ANDROID_SDK_ROOT}/build-tools/35.0.0/apksigner"
if [[ ! -x "${apksigner}" ]]; then
  echo "Android build-tools 35.0.0 apksigner not found: ${apksigner}" >&2
  exit 1
fi
"${apksigner}" verify --verbose "${apk_target}"

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum "${apk_target}"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 "${apk_target}"
elif command -v openssl >/dev/null 2>&1; then
  openssl dgst -sha256 "${apk_target}"
else
  echo "No SHA-256 utility found (tried sha256sum, shasum, openssl)." >&2
  exit 1
fi
