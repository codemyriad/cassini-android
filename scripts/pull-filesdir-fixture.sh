#!/usr/bin/env bash
# Copy an app-private directory tree from a device into a local fixture directory.
#
#   scripts/pull-filesdir-fixture.sh [--sanitise] [--source DIR] [--package NAME] OUTPUT
#
# By default it copies the app's whole files/ directory. --source names another directory below
# the app's data directory, for example cache/fixture-capture/v0.1.0-basic (FixtureCaptureTest
# writes the trees there). The copy uses `run-as <package> tar`, so the app must be debuggable.
#
# OUTPUT/.mtimes records each file's modification time in milliseconds, because git does not keep
# them and a checkpoint's `signature` (length:mtime) depends on them. A source tree that already has
# a .mtimes manifest (FixtureCaptureTest writes one) keeps it unchanged. Format: one line per file,
# "<relative path><TAB><mtime ms>", sorted by path.
#
# --sanitise replaces model blobs (everything in parakeet-v3/, parakeet-v3-fp32/, vad/ and
# speaker-models/ except the verified* markers) by zero-length stand-ins without copying them, and
# drops voices.json and voiceprints/, which hold real voiceprints. Never commit a tree pulled from
# a phone with real recordings: documents/ and processing-checkpoints/ hold the audio. An OUTPUT
# inside fixtures/filesdir needs --sanitise unless the source is below cache/fixture-capture/, and
# .gitignore keeps a tree there ignored until it is listed by name.
#
# Set ANDROID_SERIAL when several devices are connected.
set -euo pipefail
package=org.cassini.android
source_dir=files
sanitise=false
while [ $# -gt 0 ]; do
    case "$1" in
        --sanitise) sanitise=true; shift ;;
        --source) source_dir=${2:?--source needs a directory}; shift 2 ;;
        --package) package=${2:?--package needs a name}; shift 2 ;;
        -h|--help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        -*) printf 'Unknown option: %s\n' "$1" >&2; exit 2 ;;
        *) break ;;
    esac
done
if [ $# -ne 1 ]; then
    printf 'Usage: %s [--sanitise] [--source DIR] [--package NAME] OUTPUT\n' "$0" >&2
    exit 2
fi
output=$1
case "$source_dir" in
    /*|*..*|*\'*) printf 'The source must be a plain path below the app data directory.\n' >&2; exit 2 ;;
esac
fixtures=$(cd "$(dirname "$0")/.." && pwd)/fixtures/filesdir
target=$(realpath -m -- "$output")
if [[ "$target/" == "$fixtures/"* ]] && ! $sanitise && [[ "$source_dir" != cache/fixture-capture/* ]]; then
    printf 'Refusing to copy %s unsanitised into %s: pass --sanitise.\n' "$source_dir" "$output" >&2
    exit 2
fi
if [ -e "$output" ] && [ -n "$(ls -A "$output" 2>/dev/null)" ]; then
    printf '%s is not empty; choose a new directory.\n' "$output" >&2
    exit 1
fi
mkdir -p "$output"

device() { adb exec-out "run-as $package sh -c '$1'"; }

# The device shell receives these expressions inside single quotes: escape globs and parentheses.
models='-path ./parakeet-v3/\* -o -path ./parakeet-v3-fp32/\* -o -path ./vad/\* -o -path ./speaker-models/\*'
blob="\\( $models \\) ! -name verified\\*"
private='-path ./voices.json -o -path ./voiceprints/\*'
if $sanitise; then
    selection="find . -type f ! \\( $blob \\) ! \\( $private \\)"
else
    selection="find . -type f"
fi
# Each line: size, mtime with nanoseconds, path. Paths come from find, so they start with ./
listing=$(device "cd $source_dir && find . -type f -exec stat -c %s\\|%y\\|%n {} +")
if [ -z "$listing" ]; then
    printf 'Nothing to copy: %s is empty or unreadable for %s.\n' "$source_dir" "$package" >&2
    exit 1
fi
device "cd $source_dir && $selection | tar -cf - -T -" | tar -xf - -C "$output"

LISTING=$listing python3 -I - "$output" "$sanitise" <<'PY'
import datetime
import os
import pathlib
import sys

output = pathlib.Path(sys.argv[1])
sanitise = sys.argv[2] == "true"
models = ("parakeet-v3/", "parakeet-v3-fp32/", "vad/", "speaker-models/")
existing = output / ".mtimes"
times = {}
for line in os.environ["LISTING"].splitlines():
    if not line.strip():
        continue
    size, stamp, name = line.split("|", 2)
    path = name.removeprefix("./")
    if path == ".mtimes":
        continue
    # toybox prints "2026-10-09 12:41:59.217000000 +0200"; keep millisecond precision like File.lastModified().
    day, clock, zone = stamp.split()
    whole, _, fraction = clock.partition(".")
    moment = datetime.datetime.strptime(f"{day} {whole} {zone}", "%Y-%m-%d %H:%M:%S %z")
    millis = int(moment.timestamp()) * 1000 + int((fraction + "000")[:3])
    if sanitise and (path == "voices.json" or path.startswith("voiceprints/")):
        continue
    if sanitise and path.startswith(models) and not pathlib.PurePath(path).name.startswith("verified"):
        stand_in = output / path
        stand_in.parent.mkdir(parents=True, exist_ok=True)
        stand_in.write_bytes(b"")
    times[path] = millis
if existing.exists():
    print(f"Kept the tree's own {existing}")
else:
    existing.write_text("".join(f"{path}\t{times[path]}\n" for path in sorted(times)))
missing = [path for path in times if not (output / path).is_file()]
if missing:
    raise SystemExit(f"Not copied: {missing}")
print(f"Copied {len(times)} files into {output}")
PY
