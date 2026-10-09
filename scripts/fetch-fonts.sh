#!/usr/bin/env bash
# Download the bundled fonts listed in third_party/fonts/fonts.json, check their SHA-256 and copy them
# into ui/src/main/res/font. Also copy the licence texts into ui/src/main/assets/licenses/fonts.
#
#   scripts/fetch-fonts.sh           download what is missing or different, then verify
#   scripts/fetch-fonts.sh --check   verify only; download nothing and change nothing
#
# If a download fails and CASSINI_FONTS_FALLBACK_DIR is set, the script uses the file with the same
# upstream name from that directory. The SHA-256 must still match.
set -euo pipefail
cd "$(dirname "$0")/.."

manifest=third_party/fonts/fonts.json
font_dir=ui/src/main/res/font
licence_dir=ui/src/main/assets/licenses/fonts
# Licence copies shipped in the APK: "asset name|source under third_party/fonts".
licences=(
    "InstrumentSans-OFL.txt|instrument-sans/OFL.txt"
    "IBMPlexMono-OFL.txt|ibm-plex-mono/LICENSE.txt"
)

check_only=false
case "${1:-}" in
    "") ;;
    --check) check_only=true ;;
    *)
        printf 'Usage: %s [--check]\n' "$0" >&2
        exit 2
        ;;
esac

sha256_of() {
    sha256sum "$1" | cut -d ' ' -f 1
}

# Prints "file sha256 raw-url upstream-name" for each manifest entry.
entries() {
    jq -r '.[] | [
        .file,
        .sha256,
        "https://raw.githubusercontent.com/\(.repo | ltrimstr("https://github.com/"))/\(.ref | @uri)/\(.path)",
        (.path | split("/") | last)
    ] | @tsv' "$manifest"
}

# Downloads one font into $2. Falls back to CASSINI_FONTS_FALLBACK_DIR when the download fails.
download() {
    local url=$1 out=$2 name=$3
    if curl --fail --location --retry 3 --silent --show-error "$url" -o "$out"; then
        return 0
    fi
    rm -f "$out"
    local fallback=${CASSINI_FONTS_FALLBACK_DIR:-}
    if [[ -n $fallback && -f $fallback/$name ]]; then
        printf 'Could not download %s; using %s\n' "$url" "$fallback/$name" >&2
        cp "$fallback/$name" "$out"
        return 0
    fi
    printf 'Could not download %s\n' "$url" >&2
    return 1
}

failures=0
fail() {
    printf '%s\n' "$1" >&2
    failures=$((failures + 1))
}

if ! $check_only; then
    mkdir -p "$font_dir" "$licence_dir"
fi

declare -A listed=()
while IFS=$'\t' read -r file sha url name; do
    listed[$file]=1
    dest=$font_dir/$file
    if [[ -f $dest && $(sha256_of "$dest") == "$sha" ]]; then
        continue
    fi
    if $check_only; then
        if [[ -f $dest ]]; then
            fail "$dest has SHA-256 $(sha256_of "$dest"), expected $sha"
        else
            fail "$dest is missing; run scripts/fetch-fonts.sh"
        fi
        continue
    fi
    if ! download "$url" "$dest.part" "$name"; then
        failures=$((failures + 1))
        continue
    fi
    actual=$(sha256_of "$dest.part")
    if [[ $actual != "$sha" ]]; then
        rm -f "$dest.part"
        fail "$name has SHA-256 $actual, expected $sha"
        continue
    fi
    mv "$dest.part" "$dest"
    printf 'Fetched %s\n' "$dest"
done < <(entries)

# Every font resource must come from the manifest.
shopt -s nullglob
for path in "$font_dir"/*; do
    if [[ -z ${listed[$(basename "$path")]:-} ]]; then
        fail "$path is not listed in $manifest"
    fi
done
shopt -u nullglob

for pair in "${licences[@]}"; do
    asset=$licence_dir/${pair%%|*}
    source=third_party/fonts/${pair#*|}
    if cmp --silent "$source" "$asset"; then
        continue
    fi
    if $check_only; then
        fail "$asset differs from $source"
    else
        cp "$source" "$asset"
        printf 'Copied %s\n' "$asset"
    fi
done

if ((failures > 0)); then
    printf '%d font check(s) failed\n' "$failures" >&2
    exit 1
fi
printf 'Fonts and licence copies match %s\n' "$manifest"
