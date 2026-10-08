#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs
# sherpa-onnx 1.13.7 with Sortformer diarization; rebuild with scripts/build-native-aar.sh.
archive=app/libs/sherpa-onnx-1.13.7-nemotron.aar
expected=d03a06f8cad8f9d761c97974ee8042d713e166aa1c14df09101303fda7a65b08
if ! printf '%s  %s\n' "$expected" "$archive" | sha256sum --check --status 2>/dev/null; then
    curl --fail --location --retry 3 https://github.com/codemyriad/cassini-android/releases/download/native-sherpa-onnx-1.13.7-nemotron.1/sherpa-onnx-1.13.7-nemotron.aar -o "$archive.part"
    printf '%s  %s\n' "$expected" "$archive.part" | sha256sum --check
    mv "$archive.part" "$archive"
fi
printf 'Android dependency ready. Build with ./gradlew assembleDebug\n'
