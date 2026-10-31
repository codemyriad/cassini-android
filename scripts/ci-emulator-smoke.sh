#!/usr/bin/env bash
# The device checks of the CI emulator matrix (API 26, 29, 34 and 35; docs/development.md, Continuous integration).
# Any device on API 35 or above also works, with the rules of API 35.
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
#   Empty: no expectation. No API 35 run has confirmed a value yet, so API 35 accepts either result.
#   A level above 35 (for example a Pixel 8 on API 37) uses the rules of API 35: no expectation differs there.
# - data_tests: also run the :data NoteFtsDeviceTest, the FTS4 tokenizer of the oldest SQLite in the matrix.
# PortableDocumentTest's platform Opus round trip has @SdkSuppress for API 34 only, so the selection has one test
# less on the other levels. The dry run of device-smoke.sh counts that.
data_tests=false
case "$TEST_API" in
    26) platform_opus=false; data_tests=true ;;
    29) platform_opus=false ;;
    34) platform_opus=true ;;
    *)
        if [[ "$TEST_API" =~ ^[0-9]+$ ]] && [ "$TEST_API" -ge 35 ]; then
            platform_opus=
        else
            printf 'Unsupported CI emulator API: %s (use 26, 29, 34, or 35 and above)\n' "$TEST_API" >&2
            exit 1
        fi
        ;;
esac
mkdir -p .tools/emulator-results

# A system error dialog stays over every test, so no test activity gets window focus and no soft keyboard opens.
# The emulator runner injects a key right after boot; when the launcher has no window yet, the key times out and some
# seconds later the launcher gets an "Application Not Responding" dialog (seen on API 34 and 35). dumpsys does not
# always report such a dialog as the focused window (API 34), so look for it in the window list.
# With hide_error_dialogs Android shows no new error dialog (it stops the app instead); a dialog that is already
# open closes when its process stops. The setting gets its old value at the end. The workflow runs everything again
# when a dialog stays, because that happens before the tests.
hide_before=$(adb shell settings get global hide_error_dialogs | tr -d '\r')
finish() {
    if [ "$hide_before" = null ] || [ -z "$hide_before" ]; then
        adb shell settings delete global hide_error_dialogs >/dev/null || true
    else
        adb shell settings put global hide_error_dialogs "$hide_before" || true
    fi
    adb logcat -d > .tools/emulator-results/logcat.txt 2>&1 || true
}
trap finish EXIT
# Waits up to $1 s until the command after the message succeeds; otherwise prints the message and fails.
wait_for() {
    local seconds=$1 message=$2
    shift 2
    local end=$((SECONDS + seconds))
    until "$@"; do
        if [ "$SECONDS" -ge "$end" ]; then
            printf '%s (waited %s s)\n' "$message" "$seconds" >&2
            return 1
        fi
        sleep 0.5
    done
}
error_dialog() {
    adb shell dumpsys window windows | tr -d '\r' | grep -m 1 -oE 'Application (Not Responding|Error): [^ }:]+' || true
}
dialog_closed() { [ "$(error_dialog)" != "$1" ]; }
# Android reports no state for the setting (no API level dumps mShowDialogs), so there is nothing to wait for: the
# system server applies it when the settings provider notifies it.
adb shell settings put global hide_error_dialogs 1
for _ in 1 2 3 4 5; do
    dialog=$(error_dialog)
    [ -n "$dialog" ] || break
    printf 'Closing a system error dialog before the tests: %s\n' "$dialog"
    adb shell am force-stop "${dialog##* }"
    wait_for 10 "The system error dialog did not close: $dialog" dialog_closed "$dialog"
done
if [ -n "$(error_dialog)" ]; then
    printf 'A system error dialog is still open: %s\n' "$(error_dialog)" >&2
    exit 1
fi

# The tests that need no models and no microphone. The dry run of the same selection gives the expected count.
expectation=()
if [ -n "$platform_opus" ]; then expectation=(--arg "expectPlatformOpus=$platform_opus"); fi
# The workflow does not retry the job after this script prints the dry-run count (.github/workflows/android.yml).
./scripts/device-smoke.sh --sample fleurs --annotation CiSmoke "${expectation[@]}" \
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
