#!/bin/sh
set -eu

with_android=false
case "${1-}" in
  "") ;;
  --android) with_android=true ;;
  *)
    printf '%s\n' '用法：sh scripts/check.sh [--android]' >&2
    exit 2
    ;;
esac
if [ "$#" -gt 1 ]; then
  printf '%s\n' '用法：sh scripts/check.sh [--android]' >&2
  exit 2
fi

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$project_root"

python3 scripts/check-source.py
npm run format:check
node --check app/src/main/resources/io/github/fengying/alipaydisplay/total-assets.js
node --check app/src/main/resources/io/github/fengying/alipaydisplay/fund-summary.js
node --test app/src/test/js/*.test.js

if [ "$with_android" = true ]; then
  ./gradlew --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
fi
