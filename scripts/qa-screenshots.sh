#!/usr/bin/env bash
# Captures the QA screenshot matrix (D-1155) on a running phone or foldable emulator without requiring downloaded ASR
# models. Runs QaScreenshotsTest in light and dark mode (and optional largest-text mode on phone) and pulls the PNGs
# into build/qa-screenshots/<run>/.
set -euo pipefail
# shellcheck source=scripts/heavy-lock.sh
. "$(dirname "$0")/heavy-lock.sh"
heavy_lock "$0" "$@"
cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
Usage: scripts/qa-screenshots.sh --size SIZE [options]

Options:
  --serial SERIAL    The adb serial of the device (default: emulator-5554).
  --size SIZE        phone, fold-folded, fold-unfolded-portrait, or fold-unfolded-landscape.
  --largest-text     Also capture phone largest-text screenshots (font_scale=2.0, light & dark).
  --out DIR          Output directory (default: build/qa-screenshots/<timestamp>).
  --help             Shows this text.

Environment:
  CASSINI_APK, CASSINI_TEST_APK   Use prebuilt debug APKs instead of invoking Gradle.
EOF
}

serial=emulator-5554
size=
largest_text=false
out=
while [ $# -gt 0 ]; do
    case "$1" in
        --serial) serial=${2:?--serial needs a serial}; shift 2 ;;
        --size) size=${2:?--size needs a size}; shift 2 ;;
        --largest-text) largest_text=true; shift ;;
        --out) out=${2:?--out needs a directory}; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'Unknown argument: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done

case "$size" in
    phone|fold-folded|fold-unfolded-portrait|fold-unfolded-landscape) ;;
    *) printf 'Unsupported or missing --size: %s\n\n' "${size:-<empty>}" >&2; usage >&2; exit 2 ;;
esac

export ANDROID_SERIAL=$serial
package=io.codemyriad.cassini.talk.debug
test_class=io.codemyriad.cassini.acceptance.QaScreenshotsTest
out=${out:-build/qa-screenshots/$(date +%Y%m%d-%H%M%S)}
mkdir -p "$out"

night=$(adb shell cmd uimode night | tr -d '\r' | sed -n 's/^Night mode: //p')
font_scale=$(adb shell settings get system font_scale | tr -d '\r')
restore() {
    adb shell cmd uimode night "${night:-no}" >/dev/null || true
    if [ -n "$font_scale" ] && [ "$font_scale" != "null" ]; then
        adb shell settings put system font_scale "$font_scale" >/dev/null || true
    else
        adb shell settings put system font_scale 1.0 >/dev/null || true
    fi
    adb shell run-as "$package" rm -rf files/qa-shots || true
}
trap restore EXIT

if [ -z "${CASSINI_APK:-}" ] || [ -z "${CASSINI_TEST_APK:-}" ]; then
    nice -n 10 ./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
    export CASSINI_APK=app/build/outputs/apk/debug/app-debug.apk
    export CASSINI_TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi
export CASSINI_TEST_BUILD_TYPE=debug

run_pass() {
    local theme=$1
    local largest=$2
    local night_val=no
    [ "$theme" = dark ] && night_val=yes
    adb shell cmd uimode night "$night_val" >/dev/null
    if [ "$largest" = true ]; then
        adb shell settings put system font_scale 2.0 >/dev/null
    else
        adb shell settings put system font_scale 1.0 >/dev/null
    fi

    local args=(--arg "qaSize=$size" --arg "qaTheme=$theme")
    if [ "$largest" = true ]; then
        args+=(--arg "qaLargestText=true")
    fi

    printf '\n== %s · %s (largest=%s) ==\n' "$size" "$theme" "$largest"
    scripts/device-smoke.sh --class "$test_class#captureFirstRun" "${args[@]}"
    scripts/device-smoke.sh --class "$test_class#captureAllScreens" "${args[@]}"

    if adb shell run-as "$package" test -d files/qa-shots; then
        adb exec-out run-as "$package" tar c -C files/qa-shots . | tar x -C "$out"
        adb shell run-as "$package" rm -rf files/qa-shots
    fi
}

run_pass light false
run_pass dark false
if $largest_text; then
    run_pass light true
    run_pass dark true
fi

printf 'QA screenshots saved in %s\n' "$out"
