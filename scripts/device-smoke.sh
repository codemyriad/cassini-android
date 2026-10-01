#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ ! -f app/src/androidTest/assets/italian-smoke.wav ]; then
    printf 'Run python3 scripts/fetch-smoke-audio.py first (requires ffmpeg).\n' >&2
    exit 1
fi
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# Direct instrumentation retains app data/model downloads; Gradle UTP may uninstall the app.
report=$(mktemp)
trap 'rm -f "$report"' EXIT
adb shell am instrument -w -r -e precision "${1:-int8}" -e downloadModels true -e audioSample "${2:-fleurs}" \
    org.cassini.android.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
# am instrument can return shell status 0 even when tests fail or the process crashes.
python3 - "$report" <<'PY'
import pathlib,sys
report=pathlib.Path(sys.argv[1]).read_text()
if 'OK (' not in report or any(marker in report for marker in ['FAILURES!!!','INSTRUMENTATION_FAILED','shortMsg=']):
    raise SystemExit('Device smoke test failed; inspect instrumentation output above.')
PY
