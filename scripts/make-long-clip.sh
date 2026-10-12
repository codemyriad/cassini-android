#!/usr/bin/env bash
# Makes the long clip of the background device tests and of scripts/device-kill-resume.sh: the committed FLEURS
# clip 20 times in a row (about 5 min 17 s), as 16 kHz mono 16-bit WAV. The file is made on the host and is not
# committed; app/build.gradle.kts packages app/src/androidTest/assets into the test APK (fixtures/README.md).
#   scripts/make-long-clip.sh [--print-path]
set -euo pipefail
cd "$(dirname "$0")/.."
source=fixtures/audio/fleurs-it-short.wav
output=app/src/androidTest/assets/generated/fleurs-it-x20.wav
repeats=20
if [ ! -f "$output" ] || [ "$source" -nt "$output" ]; then
    command -v ffmpeg >/dev/null || { printf 'ffmpeg is needed to make %s\n' "$output" >&2; exit 1; }
    mkdir -p "$(dirname "$output")"
    # Audio only: no hardware decode applies. The concat filter joins the copies without a gap.
    inputs=()
    for _ in $(seq "$repeats"); do inputs+=(-i "$source"); done
    filter=$(for index in $(seq 0 $((repeats - 1))); do printf '[%d:a]' "$index"; done)
    ffmpeg -hide_banner -loglevel error -y "${inputs[@]}" \
        -filter_complex "${filter}concat=n=${repeats}:v=0:a=1[out]" -map '[out]' \
        -ac 1 -ar 16000 -c:a pcm_s16le -fflags +bitexact -flags:a +bitexact "$output.tmp.wav"
    mv "$output.tmp.wav" "$output"
fi
if [ "${1:-}" = --print-path ]; then printf '%s\n' "$output"; fi
