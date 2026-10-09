#!/usr/bin/env bash
# Build Cassini's sherpa-onnx AAR: release 1.13.7 plus Sortformer speaker diarization
# (Nemotron-3-Diarization), for arm64-v8a and x86_64. Follows upstream's android.yaml, then
# installs the AAR into the repo-local Maven repository native/m2. scripts/setup.sh --from-source
# runs it; scripts/verify-native-runtime.sh compares the result with the pinned release build.
#
#   ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 scripts/build-native-aar.sh
#
# SHERPA_ONNX_SRC selects an existing checkout instead of the pinned source archive.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
. scripts/native-common.sh
: "${ANDROID_NDK:?Set ANDROID_NDK to an Android NDK directory}"
work=$root/.tools/native-build
mkdir -p "$work"

if [ -z "${SHERPA_ONNX_SRC:-}" ]; then
    url=$(pin sherpa-onnx url)
    sha=$(pin sherpa-onnx sha256)
    archive=$work/sherpa-onnx.tar.gz
    if ! printf '%s  %s\n' "$sha" "$archive" | sha256sum --check --status 2>/dev/null; then
        curl --fail --location --retry 3 "$url" -o "$archive.part"
        printf '%s  %s\n' "$sha" "$archive.part" | sha256sum --check
        mv "$archive.part" "$archive"
    fi
    rm -rf "$work/sherpa-onnx"
    mkdir -p "$work/sherpa-onnx"
    tar xzf "$archive" -C "$work/sherpa-onnx" --strip-components=1
    SHERPA_ONNX_SRC=$work/sherpa-onnx
fi
src=$(cd "$SHERPA_ONNX_SRC" && pwd)

# Keep the incremental JNI API reproducible against the pinned source archive. The source sits
# inside this repository's .tools/, where git apply would silently skip every path; stop discovery.
GIT_CEILING_DIRECTORIES=$(dirname "$src")
export GIT_CEILING_DIRECTORIES
if (cd "$src" && git apply --check "$root/scripts/streaming-diarization.patch"); then
    (cd "$src" && git apply "$root/scripts/streaming-diarization.patch")
elif ! (cd "$src" && git apply --reverse --check "$root/scripts/streaming-diarization.patch"); then
    printf 'Source does not match the pinned streaming diarization patch.\n' >&2
    exit 1
fi

export ANDROID_NDK SHERPA_ONNX_ENABLE_C_API=ON SHERPA_ONNX_ONNXRUNTIME_VERSION=1.27.1
jni=$src/android/SherpaOnnxAar/sherpa_onnx/src/main/jniLibs
rm -rf "$jni"
for abi in arm64-v8a x86_64; do
    script=build-android-${abi/_/-}.sh
    (cd "$src" && "./$script")
    mkdir -p "$jni/$abi"
    cp -v "$src/build-android-${abi/_/-}/install/lib/"*.so "$jni/$abi/"
done

(cd "$src/android/SherpaOnnxAar" && ./gradlew --no-daemon :sherpa_onnx:assembleRelease)
install_aar "$src/android/SherpaOnnxAar/sherpa_onnx/build/outputs/aar/sherpa_onnx-release.aar"
