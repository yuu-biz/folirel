#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# -gt 1 || ( $# -eq 1 && "$1" != "--device-tests" ) ]]; then
  echo "Usage: tools/verify.sh [--device-tests]" >&2
  exit 2
fi
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
if [[ "${1:-}" == "--device-tests" ]]; then
  ./gradlew :app:connectedDebugAndroidTest
fi
