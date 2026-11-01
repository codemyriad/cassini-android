#!/usr/bin/env bash
# Copies the models that scripts/fetch-ci-models.py prepared into the files/ directory of the installed debug app,
# with run-as. Then the device tests with @NeedsModels find them, as after a download in the app.
#   scripts/push-models.sh [DIR]    (default DIR: .tools/ci-models)
# A file that is already on the device with the same SHA-256 is not copied again. The script copies the marker files
# last, so an interrupted copy leaves the model not ready instead of ready with a bad file. The app must be a debug
# build (run-as needs a debuggable app). Set ANDROID_SERIAL to select the device.
set -euo pipefail
cd "$(dirname "$0")/.."
source_dir=${1:-.tools/ci-models}
package=org.cassini.android
staging=/data/local/tmp/cassini-model.part
test -d "$source_dir" || { printf 'No models in %s: run scripts/fetch-ci-models.py first.\n' "$source_dir" >&2; exit 1; }
adb shell run-as "$package" true || { printf 'run-as %s failed: install a debug build first.\n' "$package" >&2; exit 1; }

# Each model directory holds its files and one marker file ("verified").
markers=()
copied=0
# The list comes on descriptor 3, because adb shell reads its standard input.
while IFS= read -r -d '' file <&3; do
    relative=${file#"$source_dir"/}
    case "$(basename "$relative")" in
        verified*) markers+=("$relative"); continue ;;
    esac
    local_hash=$(sha256sum "$file" | cut -d' ' -f1)
    device_hash=$(adb shell run-as "$package" sha256sum "files/$relative" < /dev/null 2>/dev/null | tr -d '\r' | cut -d' ' -f1 || true)
    if [ "$local_hash" = "$device_hash" ]; then
        printf 'Already on the device: %s\n' "$relative"
        continue
    fi
    printf 'Copying %s\n' "$relative"
    adb push "$file" "$staging" >/dev/null
    # The shell user reads the staged file and run-as writes it, so no SELinux rule must allow the app to read
    # /data/local/tmp. The marker of the model goes first, so the model is not ready during the copy.
    directory=$(dirname "$relative")
    adb shell "run-as $package sh -c 'umask 077 && mkdir -p files/$directory && rm -f files/$directory/verified* && cat > files/$relative.part && mv files/$relative.part files/$relative' < $staging"
    adb shell rm -f "$staging"
    device_hash=$(adb shell run-as "$package" sha256sum "files/$relative" < /dev/null | tr -d '\r' | cut -d' ' -f1)
    test "$local_hash" = "$device_hash" || { printf 'The copy of %s on the device is different.\n' "$relative" >&2; exit 1; }
    copied=$((copied + 1))
done 3< <(find "$source_dir" -type f ! -path '*/.downloads/*' ! -name '*.part' -print0 | sort -z)

# A marker removed above for one copied file must come back for every model of that directory.
for relative in "${markers[@]}"; do
    value=$(cat "$source_dir/$relative")
    current=$(adb shell run-as "$package" cat "files/$relative" < /dev/null 2>/dev/null | tr -d '\r' || true)
    if [ "$current" != "$value" ]; then
        printf '%s' "$value" | adb shell "run-as $package sh -c 'umask 077 && cat > files/$relative'"
    fi
done
printf 'Copied %d file(s); %d marker(s) in place.\n' "$copied" "${#markers[@]}"
