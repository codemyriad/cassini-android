#!/usr/bin/env bash
# Generates app/src/main/baseline-prof.txt on a device or emulator with direct adb instrumentation, which keeps the
# app data (Gradle's connected tasks can uninstall the app). docs/development.md, "Baseline Profile", explains it.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'USAGE'
Usage: scripts/baseline-profile.sh [--serial SERIAL]

Builds the releaseTest app (release without R8, signed with the local debug key so that it installs over a debug
build and keeps the notes) and the :baselineprofile test APK, installs them, runs BaselineProfileGenerator and copies
the collected profile to app/src/main/baseline-prof.txt. The device must run API 33 or later (or API 28 or later
with root), in English, with at least one transcribed note with speech in the Library.

Options:
  --serial SERIAL   The adb serial of the device (default: $ANDROID_SERIAL, or the only device).
USAGE
}

while [ $# -gt 0 ]; do
    case "$1" in
        --serial) export ANDROID_SERIAL=${2:?--serial needs a serial}; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'Unknown argument: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

if [ -z "${CASSINI_RELEASE_KEYSTORE_FILE:-}" ]; then
    export CASSINI_RELEASE_KEYSTORE_FILE=$HOME/.android/debug.keystore
    export CASSINI_RELEASE_STORE_PASSWORD=${CASSINI_RELEASE_STORE_PASSWORD:-android}
    export CASSINI_RELEASE_KEY_PASSWORD=${CASSINI_RELEASE_KEY_PASSWORD:-android}
    export CASSINI_RELEASE_KEY_ALIAS=${CASSINI_RELEASE_KEY_ALIAS:-androiddebugkey}
fi
./gradlew --no-daemon :app:assembleReleaseTest :baselineprofile:assembleReleaseTest
adb install -r app/build/outputs/apk/releaseTest/app-releaseTest.apk
adb install -r baselineprofile/build/outputs/apk/releaseTest/baselineprofile-releaseTest.apk

runner=org.cassini.baselineprofile/androidx.test.runner.AndroidJUnitRunner
report=$(mktemp)
trap 'rm -f "$report"' EXIT
adb shell am instrument -w -r -e class org.cassini.baselineprofile.BaselineProfileGenerator "$runner" | tee "$report"
# am instrument can return shell status 0 even when the test fails.
if ! grep -q '^OK (1 test)' "$report"; then
    printf 'The generator failed; no profile was copied.\n' >&2
    exit 1
fi

# BaselineProfileRule writes BaselineProfileGenerator_generate-baseline-prof.txt (and a timestamped copy) here.
output=/sdcard/Android/media/org.cassini.baselineprofile
profile=$(adb shell "ls $output/" | tr -d '\r' | grep -E '^BaselineProfileGenerator_generate-.*prof\.txt$' \
    | grep -v -E '[0-9]{4}-[0-9]{2}-[0-9]{2}' | head -1)
profile=${profile:+$output/$profile}
if [ -z "$profile" ]; then
    printf 'No profile in %s on the device.\n' "$output" >&2
    exit 1
fi
adb pull "$profile" app/src/main/baseline-prof.txt
printf 'Wrote app/src/main/baseline-prof.txt (%s rules). Build the release and commit the file.\n' \
    "$(wc -l < app/src/main/baseline-prof.txt)"
