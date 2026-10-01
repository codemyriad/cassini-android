#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
audio="${1:-.tools/cassini-italian-28-58.wav}"
if [[ ! -f "$audio" ]]; then printf 'Prepare the sample with scripts/fetch-youtube-audio.sh first.\n' >&2; exit 1; fi
mkdir -p .tools/litert-probe-results
# Keep these AARs isolated from the production runtime and dependency graph.
python3 - <<'PY'
import hashlib,pathlib,urllib.request
root=pathlib.Path('.tools')
artifacts=[
 ('litert-2.2.0.aar','https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/2.2.0/litert-2.2.0.aar','624518d72f8a249711a19e9901f480e74f823ca7818260a739cb2c023024807c'),
 ('litert-api-2.2.0.aar','https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert-api/2.2.0/litert-api-2.2.0.aar','b785714414d7af54ad1e8f4a40a382f2809b538b88a2246dcdeb9039394bc38d'),
 ('parakeet-v3-litert-int8.tflite','https://huggingface.co/litert-community/parakeet-tdt-0.6b-v3/resolve/50dae0cb8c7b39dda477966eff7150cd7fe206ae/parakeet_tdt_0.6b_v3_5s_i8_stateful.tflite','334745b8bc7fd372b1c213516f0b6338bb827b1a2abb3e77ad35fe6fea5cd16b'),
 ('parakeet-tokenizer.json','https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3/resolve/541d1f99c6b0c3cd0b11a95167540bb8edefd82b/tokenizer.json','bd321b096832a3f270bd3b2a88823957920f1a5c5ada71114a26ea729d0cbe91')]
for name,url,expected in artifacts:
 p=root/name
 if not p.exists():
  print('Downloading',name,flush=True); tmp=p.with_suffix(p.suffix+'.part');urllib.request.urlretrieve(url,tmp);tmp.replace(p)
 digest=hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
 if digest!=expected:raise SystemExit(f'Checksum mismatch: {p}')
PY
uv run --with librosa==1.0.0 --with numpy==2.5.3 scripts/generate-litert-probe-features.py "$audio" .tools/litert-probe-results
# Use ANDROID_HOME/ANDROID_SDK_ROOT or the repository's existing local.properties.
if [[ -f local.properties ]]; then cp local.properties experiments/litert-gpu-probe/local.properties; fi
./gradlew -p experiments/litert-gpu-probe :app:assembleDebug
adb install -r experiments/litert-gpu-probe/app/build/outputs/apk/debug/app-debug.apk
adb shell run-as org.cassini.probe mkdir -p files
stage() {
    adb push "$1" /data/local/tmp/cassini-probe-input >/dev/null
    adb shell run-as org.cassini.probe cp /data/local/tmp/cassini-probe-input "files/$2"
    adb shell rm /data/local/tmp/cassini-probe-input
}
stage .tools/parakeet-v3-litert-int8.tflite model.tflite
stage .tools/parakeet-tokenizer.json tokenizer.json
for chunk in 0 1 2 3 4 5; do stage ".tools/litert-probe-results/features-$chunk.bin" "features-$chunk.bin"; done
for mode in cpu gpu hybrid; do
    adb shell am force-stop org.cassini.probe
    sleep 1 # Allow Android's asynchronous process/task teardown before relaunch.
    adb shell run-as org.cassini.probe rm -f "files/report-$mode.json"
    adb shell am start -W -n org.cassini.probe/.ProbeActivity --es accelerator "$mode"
    ready=false
    for attempt in $(seq 1 120); do
        if adb exec-out run-as org.cassini.probe cat "files/report-$mode.json" > ".tools/litert-probe-results/$mode.json" 2>/dev/null &&
            python3 -c 'import json,sys; json.load(sys.stdin)' < ".tools/litert-probe-results/$mode.json" 2>/dev/null; then ready=true; break; fi
        sleep 1
    done
    adb logcat -d -s tflite litert CassiniGpuProbe > ".tools/litert-probe-results/$mode.log"
    if [[ "$ready" != true ]]; then printf 'Probe timed out: %s\n' "$mode" >&2; exit 1; fi
    python3 - ".tools/litert-probe-results/$mode.json" <<'PY'
import json,sys
x=json.load(open(sys.argv[1]))
if not x.get('success'):raise SystemExit(x.get('error',x))
print(x['accelerator'],'compile',round(x['compileMs']),'ms')
for r in x['inference']:print('pass',r['pass'],round(r['elapsedMs']),'ms',round(r['realtime'],2),'x realtime')
PY
done
python3 - <<'PY'
import json,pathlib
p=pathlib.Path('.tools/litert-probe-results');base=json.loads((p/'cpu.json').read_text())['inference'][-1]['chunks']
for name in ['gpu','hybrid']:
 chunks=json.loads((p/(name+'.json')).read_text())['inference'][-1]['chunks'];same=True;deltas=[]
 for a,b in zip(base,chunks):
  same &= [t['id'] for t in a['tokens']]==[t['id'] for t in b['tokens']]
  deltas += [abs(t['frame']-u['frame']) for t,u in zip(a['tokens'],b['tokens'])]
 print(name,'identical CPU token IDs:',same,'maximum timestamp difference:',max(deltas,default=0)*80,'ms')
PY
# This removes only the disposable probe. Cassini's models and documents are retained.
adb uninstall org.cassini.probe
adb shell am start -n org.cassini.android/.MainActivity
