#!/usr/bin/env bash
# Build Cassini's sherpa-onnx AAR: release 1.13.7 plus Sortformer speaker diarization
# (Nemotron-3-Diarization), for arm64-v8a and x86_64. Follows upstream's android.yaml.
#
#   ANDROID_NDK=~/Android/Sdk/ndk/28.2.13676358 scripts/build-native-aar.sh
#
# SHERPA_ONNX_SRC selects an existing checkout instead of the pinned source archive.
set -euo pipefail
cd "$(dirname "$0")/.."
root=$PWD
: "${ANDROID_NDK:?Set ANDROID_NDK to an Android NDK directory}"
work=$root/.tools/native-build
mkdir -p "$work"

if [ -z "${SHERPA_ONNX_SRC:-}" ]; then
    read -r url sha < <(python3 -c 'import json; e = next(e for e in json.load(open("scripts/native-dependencies.json")) if e["name"] == "sherpa-onnx"); print(e["url"], e["sha256"])')
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
output=app/libs/sherpa-onnx-1.13.7-nemotron.aar
cp -v "$src/android/SherpaOnnxAar/sherpa_onnx/build/outputs/aar/sherpa_onnx-release.aar" "$output"
sha256sum "$output"
