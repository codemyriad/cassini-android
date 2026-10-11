#!/usr/bin/env bash
# Runs a selection of the instrumented tests on a device with direct adb instrumentation, which keeps the app data
# and the downloaded models (Gradle's connected tasks can uninstall the app and erase them).
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
Usage: scripts/device-smoke.sh [options]

Selects tests by annotation, by class, or both (both: the tests that match both), installs the app and its test
APK, and runs the selection with adb instrumentation. It first makes a dry run (-e log true) of the same selection
to get the expected count. It fails when a test fails, when a test is skipped or ignored, or when the count of the
real run differs from the dry run (or from CASSINI_EXPECTED_TESTS, when that is set).

Options:
  --annotation NAME   A test annotation in org.cassini.test: CiSmoke, NeedsModels, NeedsMicrophone or Manual.
                      A Manual test skips without its inputs, and a skip fails the run. Thus do not select
                      Manual. Run each Manual test as follows:
                      - AcceptanceWalkTest: use scripts/acceptance-walk.sh.
                      - NoteOpeningTimingTest: use scripts/note-opening-timing.sh.
                      - ModelDownloadFlowTest: use --class with --arg emptyModelRoot=true.
                      - The DeviceSmokeTest tests that read the YouTube clip: first get the clip with
                        tools/fetch-youtube-audio.sh (the test APK includes it), then use --class.
  --class CLASS       A test class or method, for example org.cassini.android.background.RecordingServiceTest or
                      org.cassini.android.DeviceSmokeTest#importsRealItalianAudioAndPreservesItsDuration.
                      Repeat it to select more than one.
  --bench             Run the :bench experiments (org.cassini.bench) instead of the app's device tests. Select them
                      with --class, for example org.cassini.bench.DiarizationPocTest. Debug builds only. The bench
                      uses the models the app installed and never downloads one.
  --serial SERIAL     The adb serial of the device (default: $ANDROID_SERIAL, or the only device).
  --sample NAME       The audioSample argument of the 0.1.0 tests (default: fleurs).
                      The script also gives downloadModels=true. Thus, if Parakeet and the speech detector are
                      missing, DeviceSmokeTest downloads them (several hundred MB).
  --arg KEY=VALUE     One more instrumentation argument (-e KEY VALUE). Repeat it for more.
  --help              Shows this text.

Environment:
  CASSINI_APK         The APK under test, instead of a build (debug or releaseTest; an R8 build is refused).
  CASSINI_TEST_APK    The instrumentation APK, instead of a build.
  CASSINI_TEST_BUILD_TYPE  debug or releaseTest (found from CASSINI_APK when that is set; default debug).
  CASSINI_EXPECTED_TESTS   Also require this count.
EOF
}

annotation=
bench=false
classes=()
sample=fleurs
extra=()
while [ $# -gt 0 ]; do
    case "$1" in
        --annotation) annotation=${2:?--annotation needs a name}; shift 2 ;;
        --class) classes+=("${2:?--class needs a class name}"); shift 2 ;;
        --bench) bench=true; shift ;;
        --serial) export ANDROID_SERIAL=${2:?--serial needs a serial}; shift 2 ;;
        --sample) sample=${2:?--sample needs a name}; shift 2 ;;
        --arg)
            value=${2:?--arg needs KEY=VALUE}
            if [[ "$value" != *=* ]]; then printf -- '--arg needs KEY=VALUE, not %s\n' "$value" >&2; exit 2; fi
            extra+=(-e "${value%%=*}" "${value#*=}")
            shift 2
            ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'Unknown argument: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done
case "$annotation" in
    ''|CiSmoke|NeedsModels|NeedsMicrophone|Manual) ;;
    *) printf 'Unknown annotation: %s (use CiSmoke, NeedsModels, NeedsMicrophone or Manual)\n' "$annotation" >&2; exit 2 ;;
esac
if $bench && { [ -n "$annotation" ] || [ "${#classes[@]}" -eq 0 ]; }; then
    printf 'Select the bench experiments with --class, not --annotation.\n' >&2
    exit 2
fi
if [ -z "$annotation" ] && [ "${#classes[@]}" -eq 0 ]; then
    printf 'Select tests with --annotation or --class.\n\n' >&2
    usage >&2
    exit 2
fi
selection=()
if [ -n "$annotation" ]; then selection+=(-e annotation "org.cassini.test.$annotation"); fi
if [ "${#classes[@]}" -gt 0 ]; then
    joined=$(IFS=,; printf '%s' "${classes[*]}")
    selection+=(-e class "$joined")
fi

if [ -z "${CASSINI_TEST_APK:-}" ] && [ ! -f fixtures/audio/fleurs-it-short.wav ]; then
    printf 'Missing fixtures/audio/fleurs-it-short.wav; it is committed, so check out the full tree.\n' >&2
    exit 1
fi
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard
if adb shell dumpsys window policy | python3 -I -c 'import sys; sys.exit(0 if "showing=true" in sys.stdin.read() else 1)'; then
    printf 'Unlock the device before running UI and microphone checks.\n' >&2
    exit 1
fi
if [ -n "${CASSINI_APK:-}" ]; then
    test -f "$CASSINI_APK"
    # R8 marks the dex it writes with "~~R8{"; D8 (debug and releaseTest) writes "~~D8{".
    if python3 -I - "$CASSINI_APK" <<'PY'
import re, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as apk:
    dex = [name for name in apk.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
    sys.exit(0 if any(b"~~R8{" in apk.read(name) for name in dex) else 1)
PY
    then
        printf '%s is minified by R8 and cannot be instrumented: R8 removed members the tests call.\n' "$CASSINI_APK" >&2
        printf 'Instrument the releaseTest build instead; scripts/launch-check.sh checks that a release APK launches.\n' >&2
        exit 1
    fi
    if [ -z "${CASSINI_TEST_BUILD_TYPE:-}" ]; then
        sdk_path=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
        if [ -z "$sdk_path" ]; then
            sdk_path=$(python3 -I -c 'from pathlib import Path; print(next(line.removeprefix("sdk.dir=") for line in Path("local.properties").read_text().splitlines() if line.startswith("sdk.dir=")))')
        fi
        manifest=$("$sdk_path/build-tools/36.0.0/aapt" dump xmltree "$CASSINI_APK" AndroidManifest.xml)
        # A non-debuggable APK that R8 did not minify is releaseTest.
        if [[ "$manifest" == *'android:debuggable'*'0xffffffff'* ]]; then
            CASSINI_TEST_BUILD_TYPE=debug
        else
            CASSINI_TEST_BUILD_TYPE=releaseTest
        fi
    fi
else
    CASSINI_TEST_BUILD_TYPE=${CASSINI_TEST_BUILD_TYPE:-debug}
fi
case "$CASSINI_TEST_BUILD_TYPE" in
    debug|releaseTest) ;;
    *) printf 'CASSINI_TEST_BUILD_TYPE must be debug or releaseTest.\n' >&2; exit 1 ;;
esac
if $bench && [ "$CASSINI_TEST_BUILD_TYPE" != debug ]; then
    printf 'The bench instruments the debug build only.\n' >&2
    exit 1
fi
if [ "$CASSINI_TEST_BUILD_TYPE" = releaseTest ] && [ -z "${CASSINI_BETA_KEYSTORE_FILE:-}" ]; then
    # Without a supplied key, sign with the local debug key, so the APK installs over a debug build.
    export CASSINI_BETA_KEYSTORE_FILE=$HOME/.android/debug.keystore
    export CASSINI_RELEASE_STORE_PASSWORD=${CASSINI_RELEASE_STORE_PASSWORD:-android}
    export CASSINI_RELEASE_KEY_PASSWORD=${CASSINI_RELEASE_KEY_PASSWORD:-android}
    export CASSINI_RELEASE_KEY_ALIAS=${CASSINI_RELEASE_KEY_ALIAS:-androiddebugkey}
fi
build_type=${CASSINI_TEST_BUILD_TYPE^}
if [ -n "${CASSINI_TEST_APK:-}" ]; then
    test -f "$CASSINI_TEST_APK"
else
    # The long clip of the background tests is made on the host and is not committed (fixtures/README.md).
    if ! scripts/make-long-clip.sh; then
        printf 'Warning: no long clip; the background tests that use it will fail.\n' >&2
    fi
    tasks=(":app:assemble${build_type}AndroidTest")
    if $bench; then tasks=(":bench:assembleDebug"); fi
    if [ -z "${CASSINI_APK:-}" ]; then tasks+=(":app:assemble${build_type}"); fi
    ./gradlew --console=plain "-PdeviceTestBuildType=$CASSINI_TEST_BUILD_TYPE" "${tasks[@]}"
fi
adb install -r "${CASSINI_APK:-app/build/outputs/apk/$CASSINI_TEST_BUILD_TYPE/app-$CASSINI_TEST_BUILD_TYPE.apk}"
if $bench; then
    adb install -r "${CASSINI_TEST_APK:-bench/build/outputs/apk/debug/bench-debug.apk}"
    # The bench has no scratch root: it reads the models and files of the installed app.
    runner=org.cassini.bench/androidx.test.runner.AndroidJUnitRunner
else
    adb install -r "${CASSINI_TEST_APK:-app/build/outputs/apk/androidTest/$CASSINI_TEST_BUILD_TYPE/app-$CASSINI_TEST_BUILD_TYPE-androidTest.apk}"
    runner=org.cassini.android.test/org.cassini.android.CassiniTestRunner
fi
arguments=("${selection[@]}" -e downloadModels true -e audioSample "$sample")
if [ "${#extra[@]}" -gt 0 ]; then arguments+=("${extra[@]}"); fi
dry=$(mktemp)
report=$(mktemp)
trap 'rm -f "$dry" "$report"' EXIT
# The dry run lists the selected tests without running them; its count is the expected count.
adb shell am instrument -w -r -e log true "${arguments[@]}" "$runner" > "$dry"
expected=$(python3 -I - "$dry" <<'PY'
import pathlib, re, sys
report = pathlib.Path(sys.argv[1]).read_text()
count = re.search(r'OK \((\d+) tests?\)', report)
if not count or any(marker in report for marker in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=']):
    sys.stderr.write(report)
    raise SystemExit('The dry run of the selection failed.')
if int(count.group(1)) == 0:
    raise SystemExit('The selection has no tests.')
print(count.group(1))
PY
)
# The emulator job of android.yml looks for this line: after it, a failure is a test failure and gets no retry.
printf 'Selected tests (dry run): %s\n' "$expected"
if [ -n "${CASSINI_EXPECTED_TESTS:-}" ] && [ "$expected" != "$CASSINI_EXPECTED_TESTS" ]; then
    printf 'The selection has %s tests, but CASSINI_EXPECTED_TESTS is %s.\n' "$expected" "$CASSINI_EXPECTED_TESTS" >&2
    exit 1
fi
adb shell am instrument -w -r "${arguments[@]}" "$runner" | tee "$report"
# am instrument can return shell status 0 even when tests fail or the process crashes.
python3 -I - "$report" "$expected" <<'PY'
import pathlib, re, sys
report = pathlib.Path(sys.argv[1]).read_text()
if 'OK (' not in report or any(marker in report for marker in ['FAILURES!!!', 'INSTRUMENTATION_FAILED', 'shortMsg=']):
    raise SystemExit('Device tests failed; inspect the instrumentation output above.')
count = re.search(r'OK \((\d+) tests?\)', report)
if not count or int(count.group(1)) != int(sys.argv[2]):
    raise SystemExit(f'Ran {count.group(1) if count else "no"} tests, but the dry run selected {sys.argv[2]}.')
# -3: ignored; -4: an assumption failed (a skip).
if re.search(r'INSTRUMENTATION_STATUS_CODE: -(3|4)\b', report):
    raise SystemExit('A selected test was skipped; inspect the instrumentation output above.')
time = re.search(r'^Time: ([\d.,]+)', report, re.M)
print(f'Passed: {count.group(1)} of {sys.argv[2]} tests' + (f' in {time.group(1)} s' if time else ''))
PY
