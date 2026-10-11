#!/usr/bin/env bash
# Makes the clips of the acceptance walk (AcceptanceWalkTest): the committed FLEURS clip repeated 2, 3, 8 and 23
# times (about 32 s, 48 s, 2 min 7 s and 6 min 4 s), as 16 kHz mono 16-bit WAV. Each note of the walk gets its own
# clip, so that no import is a duplicate of another. The files are made on the host and are not committed;
# app/build.gradle.kts packages app/src/androidTest/assets into the test APK (fixtures/README.md).
#   scripts/acceptance-audio.sh [--print-paths]
set -euo pipefail
cd "$(dirname "$0")/.."
source=fixtures/audio/fleurs-it-short.wav
directory=app/src/androidTest/assets/generated
made=()
for repeats in 2 3 8 23; do
    output=$directory/acceptance-x$repeats.wav
    if [ ! -f "$output" ] || [ "$source" -nt "$output" ]; then
        command -v ffmpeg >/dev/null || { printf 'ffmpeg is needed to make %s\n' "$output" >&2; exit 1; }
        mkdir -p "$directory"
        # Audio only: no hardware decode applies. -stream_loop plays the input again without a gap.
        ffmpeg -hide_banner -loglevel error -y -stream_loop $((repeats - 1)) -i "$source" \
            -ac 1 -ar 16000 -c:a pcm_s16le -fflags +bitexact -flags:a +bitexact "$output.tmp.wav"
        mv "$output.tmp.wav" "$output"
    fi
    made+=("$output")
done
if [ "${1:-}" = --print-paths ]; then printf '%s\n' "${made[@]}"; fi
