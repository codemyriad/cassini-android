#!/usr/bin/env bash
# Prepare the native inputs of the build:
#   1. the pinned libopus source in .tools/opus/source (Gradle compiles libcassini_opus.so from it);
#   2. the pinned sherpa-onnx AAR in native/m2, taken from the first source that works:
#      native/m2 itself, the legacy app/libs copy, the GitHub release, a build from source.
#
#   scripts/setup.sh                 prebuilt AAR when possible, else a build from source
#   scripts/setup.sh --prebuilt-only never build from source; fail when no prebuilt AAR is found (CI)
#   scripts/setup.sh --from-source   always build the AAR (needs the NDK; about 10 minutes on a CI runner)
set -euo pipefail
# A new script, pin or patch that this script or one of its scripts uses must also be in the paths of
# .github/workflows/native.yml; scripts/check-native-paths.py checks it.
cd "$(dirname "$0")/.."
from_source=false
prebuilt_only=false
case "${1:-}" in
    --from-source) from_source=true ;;
    --prebuilt-only) prebuilt_only=true ;;
    '') ;;
    *) printf 'Usage: scripts/setup.sh [--from-source | --prebuilt-only]\n' >&2; exit 2 ;;
esac
. scripts/native-common.sh

scripts/fetch-opus-source.sh
# Whistle is bundled offline; prepare only pinned build inputs, never fetch from the installed app.
# The AAR recipe can use a different ANDROID_NDK; Whistle requires the project's pinned SDK NDK.
whistle_ndk=$(ANDROID_NDK= ndk_directory) || { printf 'Install the pinned SDK NDK before preparing Whistle.\n' >&2; exit 1; }
scripts/heavy-lock.sh python3 scripts/package-whistle.py --ndk "$whistle_ndk"

if ! $from_source; then
    if scripts/verify-native-runtime.sh --quiet; then
        printf 'Native runtime ready. Build with ./gradlew :app:assembleDebug\n'
        exit 0
    fi
    legacy=app/libs/sherpa-onnx-1.13.7-nemotron.aar
    if [ -f "$legacy" ] && [ "$(sha256_of "$legacy")" = "$(pin sherpa-onnx-aar sha256)" ]; then
        install_aar "$legacy"
    elif scripts/fetch-native-aar.sh; then
        :
    elif $prebuilt_only; then
        printf 'The pinned prebuilt AAR could not be fetched, and --prebuilt-only forbids a source build.\n' >&2
        exit 1
    else
        printf 'The prebuilt AAR is not available; building it from source.\n' >&2
        from_source=true
    fi
fi

if $from_source; then
    if ! ANDROID_NDK=$(ndk_directory); then
        printf 'Set ANDROID_NDK to an installed NDK %s, then rerun scripts/setup.sh.\n' \
            "$(sed -n 's/^ndk *= *"\(.*\)"/\1/p' gradle/libs.versions.toml)" >&2
        exit 1
    fi
    ANDROID_NDK=$ANDROID_NDK scripts/build-native-aar.sh
fi

if scripts/verify-native-runtime.sh; then
    printf 'Native runtime ready. Build with ./gradlew :app:assembleDebug\n'
elif $from_source; then
    printf 'Warning: the AAR built from source does not match the pinned release build.\n' >&2
else
    exit 1
fi
