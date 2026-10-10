#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
shopt -s globstar nullglob
# .tools/ci-apk holds the published APK. .tools/ci-tests holds the APK under test (debug, or
# releaseTest on tags: the release build without R8) and its instrumentation APK under androidTest/.
published=(.tools/ci-apk/**/*.apk)
apks=(.tools/ci-tests/debug/*.apk .tools/ci-tests/releaseTest/*.apk)
test_apks=(.tools/ci-tests/androidTest/**/*.apk)
test "${#published[@]}" -eq 1
test "${#apks[@]}" -eq 1
test "${#test_apks[@]}" -eq 1
export CASSINI_APK="${apks[0]}" CASSINI_TEST_APK="${test_apks[0]}"
# The 0.1.0 checks that need no models, by name. The count of the dry run must also match.
classes=(
    --class org.cassini.android.InterfaceTest
    --class org.cassini.android.SettingsTest
    --class org.cassini.android.PortableDocumentTest#opusEncoderCapabilityHasClearOutcome
    --class org.cassini.android.LibraryTest#microphonePauseResumeSavesPlayableAudioAndReopensFromLibrary
    --class org.cassini.android.LibraryTest#leavingRecordingScreenKeepsCapturingInTheService
    --class org.cassini.android.SpeakerIdentificationTest#aTranscriptLeavesNoSeparateTranscribeOrSpeakerStep
    --class org.cassini.android.SpeakerIdentificationTest#downloadingModelsRequiresConsent
)
export CASSINI_EXPECTED_TESTS=11
test "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" = "${TEST_API:?Set TEST_API to the emulator API level}"
case "$TEST_API" in
    26|29) ;;
    34)
        classes+=(--class org.cassini.android.PortableDocumentTest#platformOpusEncodingKeepsDurationAndSpeechClock)
        export CASSINI_EXPECTED_TESTS=12
        ;;
    *) printf 'Unsupported CI emulator API: %s\n' "$TEST_API" >&2; exit 1 ;;
esac
mkdir -p .tools/emulator-results
trap 'adb logcat -d > .tools/emulator-results/logcat.txt 2>&1 || true' EXIT
./scripts/device-smoke.sh --sample fleurs "${classes[@]}" | tee .tools/emulator-results/instrumentation.txt
# The published APK (minified on tags) is not instrumented; install it over the tested build and check
# that it starts and stays up.
./scripts/launch-check.sh "${published[0]}" | tee .tools/emulator-results/launch.txt
