#!/usr/bin/env bash
set -euo pipefail

repo=$(cd "$(dirname "$0")/../.." && pwd)
cd "$repo"
mkdir -p dist/ci
python3 ios/scripts/generate-project.py
xcodebuild -version
simulator=${PADNOTE_SIMULATOR_ID:-}
if [[ -z $simulator ]]; then
  simulator=$(xcrun simctl list devices available --json | python3 -c '
import json, sys
devices = json.load(sys.stdin)["devices"]
for runtime, values in sorted(devices.items(), reverse=True):
    if "iOS" not in runtime:
        continue
    for device in values:
        if device.get("isAvailable") and "iPad" in device["name"]:
            print(device["udid"])
            sys.exit(0)
sys.exit("No available iPad simulator; install an iOS runtime before testing.")
')
fi

xcodebuild test -project ios/PadNote.xcodeproj -scheme PadNote \
  -destination "platform=iOS Simulator,id=$simulator" \
  -derivedDataPath ios/build/CI-DerivedData \
  -resultBundlePath dist/ci/ipad.xcresult \
  -parallel-testing-enabled NO CODE_SIGNING_ALLOWED=NO \
  2>&1 | tee dist/ci/ipad.log
