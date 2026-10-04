#!/usr/bin/env bash
# Native measurements with a public four-speaker clip. No app data is cleared.
set -euo pipefail
cd "$(dirname "$0")/.."
python3 scripts/fetch-diarization-models.py
python3 - <<'PY'
from pathlib import Path
import urllib.request
clip = Path('.tools/diar/four-speakers.wav')
if not clip.is_file():
    with urllib.request.urlopen('https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/0-four-speakers-zh.wav', timeout=30) as source:
        clip.write_bytes(source.read())
PY
remote=/data/local/tmp/cassini-diar
adb shell mkdir -p "$remote"
for file in segmentation.int8.onnx embedding.onnx four-speakers.wav; do
    adb push ".tools/diar/$file" "$remote/$file"
done
# An explicitly supplied private conversation is never fetched or included in test assets.
if [ -f .tools/diar/conversation.wav ]; then adb push .tools/diar/conversation.wav "$remote/conversation.wav"; fi
adb shell chmod -R a+rX "$remote"
export CASSINI_EXPECTED_TESTS=1
scripts/device-smoke.sh int8 fleurs "${1:-org.cassini.android.DiarizationPocTest#fourSpeakerClipTimeAndMemory}"
