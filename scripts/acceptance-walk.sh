#!/usr/bin/env bash
# Runs the acceptance walk (AcceptanceWalkTest) on a device in light, dark and Italian (or French and Brazilian
# Portuguese on request), and collects a screenshot of every board into build/acceptance/<run>/<variant>/. It keeps the app data and the models: it uses direct adb
# instrumentation (scripts/device-smoke.sh), never a Gradle connected task, and it makes a backup of the files/
# metadata (not the models) first. It restores the night mode and the app language at the end.
set -euo pipefail
cd "$(dirname "$0")/.."

usage() {
    cat <<'EOF'
Usage: scripts/acceptance-walk.sh [options]

Options:
  --serial SERIAL    The adb serial of the device (default: emulator-5554).
  --variant NAME     light, dark, it, fr or pt-BR (fr and pt-BR set the app language as Android's per-app setting
                     does). Repeat it to run more than one (default: light, dark and it).
  --part PART        models (the real-model walk), download (the download screens) or both (default: both).
  --backup FILE      Where to write the backup of files/ (default: build/acceptance/<run>/files-backup.tar).
  --help             Shows this text.

Environment:
  CASSINI_APK, CASSINI_TEST_APK   Use these debug APKs instead of a build.
EOF
}

serial=emulator-5554
variants=()
part=both
backup=
while [ $# -gt 0 ]; do
    case "$1" in
        --serial) serial=${2:?--serial needs a serial}; shift 2 ;;
        --variant) variants+=("${2:?--variant needs a name}"); shift 2 ;;
        --part) part=${2:?--part needs models, download or both}; shift 2 ;;
        --backup) backup=${2:?--backup needs a file}; shift 2 ;;
        --help|-h) usage; exit 0 ;;
        *) printf 'Unknown argument: %s\n\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
done
if [ "${#variants[@]}" -eq 0 ]; then variants=(light dark it); fi
for variant in "${variants[@]}"; do
    case "$variant" in light|dark|it|fr|pt-BR) ;; *) printf 'Unknown variant: %s\n' "$variant" >&2; exit 2 ;; esac
done
case "$part" in models|download|both) ;; *) printf 'Unknown part: %s\n' "$part" >&2; exit 2 ;; esac

export ANDROID_SERIAL=$serial
package=org.cassini.android
test_class=org.cassini.android.acceptance.AcceptanceWalkTest
run=$(date +%Y%m%d-%H%M%S)
out=build/acceptance/$run
mkdir -p "$out"
backup=${backup:-$out/files-backup.tar}

model_dirs="files/parakeet-ultra files/speaker-models files/vad"

# A recursive listing (with hidden files such as .part) and the checksums of the "verified" markers. It uses adb shell,
# which gives the exit status of the command (adb exec-out does not), so a failed listing stops the script. The
# "total" lines (allocated blocks) are left out: the filesystem can change them for a file whose bytes stay the same
# (seen on a CI emulator for the 104 MB speaker model).
models_listing() {
    local listing
    if ! listing=$(adb shell "run-as $package sh -c 'ls -lAR $model_dirs && md5sum files/*/verified*'"); then
        printf 'Cannot list the model files of %s.\n' "$package" >&2
        return 1
    fi
    printf '%s\n' "$listing" | tr -d '\r' | grep -v '^total ' || true
}

# 0. The app and its models must be on the device: the walk uses them and never downloads.
for dir in $model_dirs; do
    if ! adb shell run-as "$package" test -f "$dir/verified" >/dev/null 2>&1; then
        printf 'No verified models in %s of %s on %s.\n' "$dir" "$package" "$serial" >&2
        printf 'Install the app, open it and download the models first. Then run this script again.\n' >&2
        exit 1
    fi
done

# 1. The test clips, the backup and the state to restore.
scripts/acceptance-audio.sh
adb exec-out run-as "$package" tar c \
    --exclude=files/parakeet-ultra --exclude=files/vad --exclude=files/speaker-models \
    files > "$backup"
if ! tar tf "$backup" >/dev/null 2>&1; then
    printf 'The backup of files/ is not a tar archive: %s\n' "$backup" >&2
    exit 1
fi
printf 'Backup of files/ (no models): %s (%s bytes)\n' "$backup" "$(wc -c < "$backup")"
models_listing > "$out/models-before.txt"
night=$(adb shell cmd uimode night | tr -d '\r' | sed -n 's/^Night mode: //p')
locales=$(adb shell cmd locale get-app-locales "$package" | tr -d '\r' | sed -n 's/.* are \[\(.*\)\]$/\1/p')
restore() {
    adb shell cmd uimode night "${night:-no}" >/dev/null || true
    if [ -n "$locales" ]; then
        adb shell cmd locale set-app-locales "$package" --locales "$locales" >/dev/null || true
    else
        adb shell cmd locale set-app-locales "$package" >/dev/null || true
    fi
    adb shell run-as "$package" rm -rf files/acceptance-out || true
}
trap restore EXIT

# 2. One build of the app and of its tests.
if [ -z "${CASSINI_APK:-}" ] || [ -z "${CASSINI_TEST_APK:-}" ]; then
    nice -n 10 ./gradlew --console=plain :app:assembleDebug :app:assembleDebugAndroidTest
    export CASSINI_APK=app/build/outputs/apk/debug/app-debug.apk
    export CASSINI_TEST_APK=app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
fi
export CASSINI_TEST_BUILD_TYPE=debug

expected_models=(00-library 01-recording 02-transcribing 03-read-and-listen 04-name-a-speaker
    06-note-actions-and-export 07-saved-voices 08-settings 09-trash 10-states-not-verified 10-states-paused
    10-states-storage-low 10-states-unreadable)
expected_download=(05-model-download 10-states-checking 10-states-downloading 10-states-file-check
    10-states-no-connection 10-states-no-space)

# 3. The walk per variant; the screenshots are collected also when a part fails.
status=0
for variant in "${variants[@]}"; do
    printf '\n== Variant %s ==\n' "$variant"
    if [ "$variant" = dark ]; then adb shell cmd uimode night yes >/dev/null; else adb shell cmd uimode night no >/dev/null; fi
    args=(--arg "acceptanceLabel=$variant")
    case "$variant" in it|fr|pt-BR) args+=(--arg "acceptanceLanguage=$variant") ;; esac
    expected=()
    if [ "$part" != download ]; then
        scripts/device-smoke.sh --class "$test_class#realModels" "${args[@]}" 2>&1 | tee "$out/$variant-models.log" ||
            status=1
        expected+=("${expected_models[@]}")
    fi
    if [ "$part" != models ]; then
        scripts/device-smoke.sh --class "$test_class#downloadScreens" --arg emptyModelRoot=true "${args[@]}" 2>&1 |
            tee "$out/$variant-download.log" || status=1
        expected+=("${expected_download[@]}")
    fi
    mkdir -p "$out/$variant"
    # adb shell gives the exit status of the command; adb exec-out does not.
    if adb shell run-as "$package" test -d "files/acceptance-out/$variant"; then
        adb exec-out run-as "$package" tar c "files/acceptance-out/$variant" |
            tar x -C "$out/$variant" --strip-components=3 || status=1
        adb shell run-as "$package" rm -rf "files/acceptance-out/$variant"
    fi
    for name in "${expected[@]}"; do
        if [ ! -s "$out/$variant/$name.png" ]; then
            printf 'Missing screenshot: %s/%s.png\n' "$variant" "$name" >&2
            status=1
        fi
    done
    printf 'Screenshots of %s: %s\n' "$variant" "$(find "$out/$variant" -name '*.png' | wc -l)"
done

# 4. The models must be as they were.
models_listing > "$out/models-after.txt" || status=1
if ! cmp -s "$out/models-before.txt" "$out/models-after.txt"; then
    printf 'The model files changed:\n' >&2
    diff "$out/models-before.txt" "$out/models-after.txt" >&2 || true
    status=1
fi
restore
trap - EXIT
printf 'Night mode %s and app language [%s] restored. Output: %s\n' "${night:-no}" "$locales" "$out"
if [ "$status" -ne 0 ]; then printf 'The acceptance walk failed; see the logs in %s.\n' "$out" >&2; fi
exit "$status"
