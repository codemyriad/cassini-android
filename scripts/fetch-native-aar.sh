#!/usr/bin/env bash
# Download the prebuilt sherpa-onnx AAR from its GitHub release, check it against the pin in
# scripts/native-dependencies.json and install it into native/m2.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/native-common.sh
url=$(pin sherpa-onnx-aar url)
sha=$(pin sherpa-onnx-aar sha256)
work=.tools/native-aar
download=$work/sherpa-onnx-nemotron.aar
mkdir -p "$work"
if ! curl --fail --location --retry 3 --silent --show-error "$url" -o "$download.part"; then
    rm -f "$download.part"
    printf 'Could not download %s\n' "$url" >&2
    exit 1
fi
if ! printf '%s  %s\n' "$sha" "$download.part" | sha256sum --check --status; then
    printf 'Downloaded AAR has SHA-256 %s, expected %s\n' "$(sha256_of "$download.part")" "$sha" >&2
    rm -f "$download.part"
    exit 1
fi
install_aar "$download.part"
rm -f "$download.part"
