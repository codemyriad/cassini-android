#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools app/src/androidTest/assets
sample_seconds="${1:-30}"
if [[ "$sample_seconds" != 30 && "$sample_seconds" != 180 ]]; then
    printf 'Usage: %s [30|180]\n' "$0" >&2
    exit 1
fi
uvx yt-dlp --no-playlist -f bestaudio --write-info-json \
    -o '.tools/youtube-source.%(ext)s' 'https://www.youtube.com/watch?v=UmZwQf5TV3c'
python3 - "$sample_seconds" <<'PY'
import json,pathlib,subprocess,sys,wave
seconds=int(sys.argv[1])
root=pathlib.Path('.tools')
info=json.loads((root/'youtube-source.info.json').read_text())
source=root/f"youtube-source.{info['ext']}"
output=root/f'cassini-italian-28-{28+seconds}.wav'
command=['ffmpeg','-v','error','-y']
acceleration=['-hwaccel','vaapi','-hwaccel_device','/dev/dri/renderD128','-hwaccel_output_format','vaapi']
if pathlib.Path('/dev/dri/renderD128').exists(): command+=acceleration
tail=['-ss','28','-i',str(source),'-t',str(seconds),'-ac','1','-ar','16000','-c:a','pcm_s16le',str(output)]
result=subprocess.run(command+tail)
if result.returncode: subprocess.run(['ffmpeg','-v','error','-y']+tail,check=True)
with wave.open(str(output)) as wav:
    assert wav.getnframes()==seconds*16000 and wav.getframerate()==16000
asset='youtube-long.wav' if seconds==180 else 'youtube-smoke.wav'
pathlib.Path('app/src/androidTest/assets',asset).write_bytes(output.read_bytes())
print(f'Prepared exactly {seconds*16000:,} samples ({seconds} seconds) from second 28:',info['title'])
PY
