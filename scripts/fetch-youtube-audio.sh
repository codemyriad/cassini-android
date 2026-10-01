#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p .tools app/src/androidTest/assets
uvx yt-dlp --no-playlist -f bestaudio --write-info-json \
    -o '.tools/youtube-source.%(ext)s' 'https://www.youtube.com/watch?v=UmZwQf5TV3c'
python3 - <<'PY'
import json,pathlib,subprocess,wave
root=pathlib.Path('.tools')
info=json.loads((root/'youtube-source.info.json').read_text())
source=root/f"youtube-source.{info['ext']}"
output=root/'cassini-italian-28-58.wav'
command=['ffmpeg','-v','error','-y']
acceleration=['-hwaccel','vaapi','-hwaccel_device','/dev/dri/renderD128','-hwaccel_output_format','vaapi']
if pathlib.Path('/dev/dri/renderD128').exists(): command+=acceleration
tail=['-ss','28','-i',str(source),'-t','30','-ac','1','-ar','16000','-c:a','pcm_s16le',str(output)]
result=subprocess.run(command+tail)
if result.returncode: subprocess.run(['ffmpeg','-v','error','-y']+tail,check=True)
with wave.open(str(output)) as wav:
    assert wav.getnframes()==480000 and wav.getframerate()==16000
pathlib.Path('app/src/androidTest/assets/youtube-smoke.wav').write_bytes(output.read_bytes())
print('Prepared exactly 480,000 samples (30 seconds) from 00:28–00:58:',info['title'])
PY
