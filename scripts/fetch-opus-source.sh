#!/usr/bin/env bash
# Download the pinned libopus source and unpack it into .tools/opus/source, where the Gradle
# externalNativeBuild compiles it into libcassini_opus.so.
set -euo pipefail
cd "$(dirname "$0")/.."
. scripts/native-common.sh
url=$(pin opus url)
sha=$(pin opus sha256)
work=.tools/opus
archive=$work/source.tar.gz
mkdir -p "$work"
if ! printf '%s  %s\n' "$sha" "$archive" | sha256sum --check --status >/dev/null 2>&1; then
    curl --fail --location --retry 3 --silent --show-error "$url" -o "$archive.part"
    printf '%s  %s\n' "$sha" "$archive.part" | sha256sum --check --quiet
    mv "$archive.part" "$archive"
fi
# The tree is unpacked beside its final place and renamed, so an existing source directory is complete.
# Its stamp records the archive it came from, so a changed opus pin unpacks the new source.
stamp=$work/source/.archive-sha256
if [ ! -f "$work/source/CMakeLists.txt" ] || [ "$(cat "$stamp" 2>/dev/null)" != "$sha" ]; then
    rm -rf "$work/source" "$work/source.partial"
    mkdir -p "$work/source.partial"
    tar xzf "$archive" -C "$work/source.partial" --strip-components=1
    printf '%s\n' "$sha" > "$work/source.partial/.archive-sha256"
    mv "$work/source.partial" "$work/source"
fi
printf 'libopus source ready in %s\n' "$work/source"
