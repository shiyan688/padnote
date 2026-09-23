#!/bin/sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
PROJECT="$PROJECT_ROOT/ios/PadNote.xcodeproj"
SCHEME="PadNote"
SIMULATOR_ID=${PADNOTE_SIMULATOR_ID:-172ECBE4-DB05-400A-8051-7D9B3DB0C775}
BUILD_ROOT="$PROJECT_ROOT/ios/build"
DERIVED_DATA="$BUILD_ROOT/DerivedData"
REPORTS="$BUILD_ROOT/reports"

mkdir -p "$DERIVED_DATA" "$REPORTS"

DESTINATION="platform=iOS Simulator,id=$SIMULATOR_ID"
BUILD_LOG="$REPORTS/build.log"
TEST_LOG="$REPORTS/test.log"

echo "Building $SCHEME for $DESTINATION"
xcodebuild build \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -destination "$DESTINATION" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGNING_ALLOWED=NO \
  >"$BUILD_LOG" 2>&1

echo "Testing $SCHEME for $DESTINATION"
xcodebuild test \
  -project "$PROJECT" \
  -scheme "$SCHEME" \
  -destination "$DESTINATION" \
  -derivedDataPath "$DERIVED_DATA" \
  CODE_SIGNING_ALLOWED=NO \
  -parallel-testing-enabled NO \
  >"$TEST_LOG" 2>&1

echo "Build and test commands completed. Logs: $REPORTS"
