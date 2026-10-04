#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
shopt -s globstar nullglob
apks=(.tools/ci-apk/**/*.apk)
test_apks=(.tools/ci-tests/**/*.apk)
test "${#apks[@]}" -eq 1
test "${#test_apks[@]}" -eq 1
export CASSINI_APK="${apks[0]}" CASSINI_TEST_APK="${test_apks[0]}"
classes=org.cassini.android.InterfaceTest,org.cassini.android.SettingsTest
export CASSINI_EXPECTED_TESTS=4
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "${TEST_API:?Set TEST_API to the emulator API level}"
case "$TEST_API" in
    26) ;;
    29)
        classes+=,org.cassini.android.PortableDocumentTest#platformOpusEncodingKeepsDurationAndSpeechClock
        export CASSINI_EXPECTED_TESTS=5
        ;;
    *) printf 'Unsupported CI emulator API: %s\n' "$TEST_API" >&2; exit 1 ;;
esac
mkdir -p .tools/emulator-results
trap 'adb logcat -d > .tools/emulator-results/logcat.txt 2>&1 || true' EXIT
./scripts/device-smoke.sh int8 fleurs "$classes" | tee .tools/emulator-results/instrumentation.txt
