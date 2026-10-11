#!/usr/bin/env bash
# The device checks of the CI emulator matrix (API 26, 29, 34 and 35; docs/development.md, Continuous integration).
#   TEST_API=<level> scripts/ci-emulator-smoke.sh
# Input, as the emulator job downloads it:
#   .tools/ci-apk          the published APK (minified on tags)
#   .tools/ci-tests        the APK under test (debug, or releaseTest on tags: the release build without R8) and its
#                          instrumentation APK under androidTest/
#   .tools/ci-data-tests   the :data instrumentation APK (API 26 only)
# Output: .tools/emulator-results (instrumentation reports and logcat).
set -euo pipefail
cd "$(dirname "$0")/.."
shopt -s globstar nullglob
published=(.tools/ci-apk/**/*.apk)
apks=(.tools/ci-tests/debug/*.apk .tools/ci-tests/releaseTest/*.apk)
test_apks=(.tools/ci-tests/androidTest/**/*.apk)
test "${#published[@]}" -eq 1
test "${#apks[@]}" -eq 1
test "${#test_apks[@]}" -eq 1
export CASSINI_APK="${apks[0]}" CASSINI_TEST_APK="${test_apks[0]}"
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "${TEST_API:?Set TEST_API to the emulator API level}"

# Rules for each API level of the matrix (google_apis x86_64 images):
# - platform_opus: whether the image has a platform Opus encoder. PortableDocumentTest fails when the device does
#   not agree, so a changed image is seen. The API 29 image has none; recordings there use AAC (capture.m4a).
# - data_tests: also run the :data NoteFtsDeviceTest, the FTS4 tokenizer of the oldest SQLite in the matrix.
# PortableDocumentTest's platform Opus round trip has @SdkSuppress for API 34 only, so the selection has one test
# less on the other levels. The dry run of device-smoke.sh counts that.
data_tests=false
case "$TEST_API" in
    26) platform_opus=false; data_tests=true ;;
    29) platform_opus=false ;;
    34) platform_opus=true ;;
    35) platform_opus=true ;;
    *) printf 'Unsupported CI emulator API: %s\n' "$TEST_API" >&2; exit 1 ;;
esac
mkdir -p .tools/emulator-results
trap 'adb logcat -d > .tools/emulator-results/logcat.txt 2>&1 || true' EXIT

# The tests that need no models and no microphone. The dry run of the same selection gives the expected count.
./scripts/device-smoke.sh --sample fleurs --annotation CiSmoke --arg "expectPlatformOpus=$platform_opus" \
    | tee .tools/emulator-results/instrumentation.txt

if $data_tests; then
    data_apks=(.tools/ci-data-tests/**/*.apk)
    test "${#data_apks[@]}" -eq 1
    # A library test APK instruments itself (org.cassini.data.test) and never touches org.cassini.android.
    adb install -r "${data_apks[0]}"
    adb shell am instrument -w -r -e class org.cassini.data.NoteFtsDeviceTest \
        org.cassini.data.test/androidx.test.runner.AndroidJUnitRunner | tee .tools/emulator-results/data-instrumentation.txt
    python3 -I - .tools/emulator-results/data-instrumentation.txt <<'PY'
import pathlib, re, sys
report = pathlib.Path(sys.argv[1]).read_text()
count = re.search(r'OK \((\d+) tests?\)', report)
if not count or int(count.group(1)) == 0 or any(m in report for m in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=']):
    raise SystemExit('NoteFtsDeviceTest failed; inspect the instrumentation output above.')
if re.search(r'INSTRUMENTATION_STATUS_CODE: -(3|4)\b', report):
    raise SystemExit('A NoteFtsDeviceTest test was skipped; inspect the instrumentation output above.')
print(f'Passed: {count.group(1)} NoteFtsDeviceTest tests')
PY
fi

# The published APK (minified on tags) is not instrumented; install it over the tested build and check
# that it starts and stays up.
./scripts/launch-check.sh "${published[0]}" | tee .tools/emulator-results/launch.txt
