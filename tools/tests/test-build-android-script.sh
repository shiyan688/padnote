#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
project_root=$(cd "${script_dir}/../.." && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/padnote-build-script.XXXXXX")
trap 'rm -rf "${tmp}"' EXIT

fixture="${tmp}/repo"
build_script="${fixture}/tools/build-android-apk.sh"
mkdir -p "${fixture}/tools" "${fixture}/android/app" "${fixture}/android/gradle/wrapper"
cp "${project_root}/tools/build-android-apk.sh" "${build_script}"
cat >"${fixture}/android/app/build.gradle" <<'EOF'
android {
    defaultConfig {
        versionName '0.0.0-test'
    }
}
EOF

fail() { echo "test-build-android-script: $*" >&2; exit 1; }
assert_contains() {
  local needle=$1 file=$2
  grep -F -- "$needle" "$file" >/dev/null || {
    printf 'test-build-android-script: expected [%s] in %s\n' "$needle" "$file" >&2
    exit 1
  }
}

fake_java="${tmp}/jdk/bin/java"
mkdir -p "$(dirname "${fake_java}")"
cat >"${fake_java}" <<'EOF'
#!/usr/bin/env bash
exit 0
EOF
chmod +x "${fake_java}"

sdk="${tmp}/sdk"
mkdir -p "${sdk}/platforms/android-35" "${sdk}/build-tools/35.0.0"
cat >"${sdk}/build-tools/35.0.0/apksigner" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "$*" >"${APKSIGNER_LOG}"
exit 0
EOF
chmod +x "${sdk}/build-tools/35.0.0/apksigner"

wrapper="${fixture}/android/gradlew"
cat >"${wrapper}" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >"${GRADLE_ARGS_LOG}"
mkdir -p app/build/outputs/apk/debug
printf 'fake apk\n' >app/build/outputs/apk/debug/app-debug.apk
EOF
chmod +x "${wrapper}"

args_log="${tmp}/gradle-args"
sign_log="${tmp}/apksigner-args"
export GRADLE_ARGS_LOG="${args_log}" APKSIGNER_LOG="${sign_log}"

env -u PADNOTE_TOOLCHAIN_ROOT \
  JAVA_HOME="${tmp}/jdk" ANDROID_SDK_ROOT="${sdk}" \
  PADNOTE_GRADLE_USER_HOME="${tmp}/gradle-home" \
  "${build_script}" >"${tmp}/success.log"
assert_contains ':app:assembleDebug :app:lintDebug' "${args_log}"
assert_contains 'verify --verbose' "${sign_log}"
find "${fixture}/dist" -name 'PadNote-MatePadAir-*-debug.apk' -type f -print -quit | grep . >/dev/null \
  || fail 'debug APK was not copied to dist'

if env -u PADNOTE_TOOLCHAIN_ROOT JAVA_HOME="${tmp}/missing-jdk" ANDROID_SDK_ROOT="${sdk}" \
  "${build_script}" >"${tmp}/missing-java.out" 2>&1; then
  fail 'missing JAVA_HOME unexpectedly succeeded'
fi
assert_contains 'JAVA_HOME must point to an executable JDK' "${tmp}/missing-java.out"

if env -u PADNOTE_TOOLCHAIN_ROOT JAVA_HOME="${tmp}/jdk" ANDROID_SDK_ROOT="${tmp}/missing-sdk" \
  "${build_script}" >"${tmp}/missing-sdk.out" 2>&1; then
  fail 'missing SDK unexpectedly succeeded'
fi
assert_contains 'must contain Android SDK Platform 35' "${tmp}/missing-sdk.out"

echo 'test-build-android-script: ok'
