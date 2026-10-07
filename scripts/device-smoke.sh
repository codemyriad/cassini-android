#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [ -z "${CASSINI_TEST_APK:-}" ] && [ ! -f app/src/androidTest/assets/italian-smoke.wav ]; then
    printf 'Run python3 scripts/fetch-smoke-audio.py first (requires ffmpeg).\n' >&2
    exit 1
fi
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
if adb shell dumpsys window policy | python3 -c 'import sys; sys.exit(0 if "showing=true" in sys.stdin.read() else 1)'; then
    printf 'Unlock the device before running UI and microphone checks.\n' >&2
    exit 1
fi
if [ -n "${CASSINI_APK:-}" ]; then
    test -f "$CASSINI_APK"
    if [ -z "${CASSINI_TEST_BUILD_TYPE:-}" ]; then
        sdk_path=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
        if [ -z "$sdk_path" ]; then
            sdk_path=$(python3 -c 'from pathlib import Path; print(next(line.removeprefix("sdk.dir=") for line in Path("local.properties").read_text().splitlines() if line.startswith("sdk.dir=")))')
        fi
        manifest=$("$sdk_path/build-tools/34.0.0/aapt" dump xmltree "$CASSINI_APK" AndroidManifest.xml)
        if [[ "$manifest" == *'android:debuggable'*'0xffffffff'* ]]; then
            CASSINI_TEST_BUILD_TYPE=debug
        else
            CASSINI_TEST_BUILD_TYPE=release
        fi
    fi
else
    CASSINI_TEST_BUILD_TYPE=${CASSINI_TEST_BUILD_TYPE:-debug}
fi
case "$CASSINI_TEST_BUILD_TYPE" in
    debug|release) ;;
    *) printf 'CASSINI_TEST_BUILD_TYPE must be debug or release.\n' >&2; exit 1 ;;
esac
if [ "$CASSINI_TEST_BUILD_TYPE" = release ]; then
    export CASSINI_BETA_KEYSTORE_FILE=${CASSINI_BETA_KEYSTORE_FILE:-$HOME/.android/debug.keystore}
fi
build_type=${CASSINI_TEST_BUILD_TYPE^}
if [ -n "${CASSINI_TEST_APK:-}" ]; then
    test -f "$CASSINI_TEST_APK"
else
    tasks=(":app:assemble${build_type}AndroidTest")
    if [ -z "${CASSINI_APK:-}" ]; then tasks+=(":app:assemble${build_type}"); fi
    ./gradlew "-PdeviceTestBuildType=$CASSINI_TEST_BUILD_TYPE" "${tasks[@]}"
fi
adb install -r "${CASSINI_APK:-app/build/outputs/apk/$CASSINI_TEST_BUILD_TYPE/app-$CASSINI_TEST_BUILD_TYPE.apk}"
adb install -r "${CASSINI_TEST_APK:-app/build/outputs/apk/androidTest/$CASSINI_TEST_BUILD_TYPE/app-$CASSINI_TEST_BUILD_TYPE-androidTest.apk}"
# Direct instrumentation retains app data/model downloads; Gradle UTP may uninstall the app.
report=$(mktemp)
trap 'rm -f "$report"' EXIT
selection=()
if [ -n "${2:-}" ]; then selection=(-e class "$2"); fi
adb shell am instrument -w -r "${selection[@]}" -e downloadModels true -e audioSample "${1:-fleurs}" \
    org.cassini.android.test/androidx.test.runner.AndroidJUnitRunner | tee "$report"
# am instrument can return shell status 0 even when tests fail or the process crashes.
python3 - "$report" <<'PY'
import os,pathlib,re,sys
report=pathlib.Path(sys.argv[1]).read_text()
if 'OK (' not in report or any(marker in report for marker in ['FAILURES!!!','INSTRUMENTATION_FAILED','shortMsg=']):
    raise SystemExit('Device smoke test failed; inspect instrumentation output above.')
expected=os.environ.get('CASSINI_EXPECTED_TESTS')
if expected is not None:
    count=re.search(r'OK \((\d+) tests?\)', report)
    if not count or int(count.group(1)) != int(expected) or re.search(r'INSTRUMENTATION_STATUS_CODE: -(3|4)\b', report):
        raise SystemExit('Required device checks did not all run; inspect instrumentation output above.')
PY
