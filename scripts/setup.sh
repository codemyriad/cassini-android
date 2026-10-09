#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs
if [ ! -f .tools/opus/jni.sha256 ] || ! sha256sum --check --status .tools/opus/jni.sha256; then
    scripts/build-opus-jni.sh
fi
# Incremental diarization requires the JNI extension checked into this repository.
if [ -f app/libs/streaming-diarization.sha256 ] && sha256sum --check --status app/libs/streaming-diarization.sha256; then
    printf 'Streaming Android dependency ready. Build with ./gradlew assembleDebug\n'
    exit 0
fi
if [ -z "${ANDROID_NDK:-}" ]; then
    printf 'Set ANDROID_NDK to an installed NDK, then rerun scripts/setup.sh.\n' >&2
    exit 1
fi
scripts/build-native-aar.sh
