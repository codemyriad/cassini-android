#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/libs
archive=app/libs/sherpa-onnx-1.13.7.aar
expected=c4ef49e309f24fcee5c106b8a279481aaecaabb078cd37b2cd6e9a62cc8a73c8
if ! printf '%s  %s\n' "$expected" "$archive" | sha256sum --check --status 2>/dev/null; then
    curl --fail --location --retry 3 https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.7/sherpa-onnx-1.13.7.aar -o "$archive.part"
    printf '%s  %s\n' "$expected" "$archive.part" | sha256sum --check
    mv "$archive.part" "$archive"
fi
printf 'Android dependency ready. Build with ./gradlew assembleDebug\n'
