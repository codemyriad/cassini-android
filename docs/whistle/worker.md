# Private Whistle worker

This increment packages the pinned arm64 Needle engine and Whistle weights,
Silero v6.2.1 model, and private ONNX Runtime 1.23.2 library. `scripts/setup.sh`
prepares verified build inputs under the host lock. `scripts/package-whistle.py`
checks the artifact/member/header pins and linked Needle hash before publishing its package manifest.
The installed worker has no download code or system-runtime lookup.

`WhistleWorker` binds a non-exported isolated-UID `:whistle` service from the inference thread.
The private Application process sets all three privacy flags before any neural
load and does not initialize the main AppGraph/sherpa runtime. The worker checks
API/architecture/dot-product eligibility, runtime ZIP-entry hashes and both
model hashes before dlopen/session creation. Models are read-only APK mappings;
the libraries and mappings remain alive until the private process exits. The
ORT file is named separately and loaded from its verified APK entry; sherpa's
ORT is never loaded in that process. Before either neural load, it verifies a
separate UID, denied INTERNET permission and actual EACCES/EPERM from creating
IPv4/IPv6 TCP/UDP sockets, with no host connection. Parent and bench verify the
returned worker UID. A failed isolation check refuses loading.

The synchronous protocol permits one outstanding command. A bounded PCM chunk
produces segment messages before its acknowledgement. The client waits on Binder
and queue events, rejects re-entrant/concurrent commands, and serializes worker
lifetimes. Cancellation/unbind kills only the dedicated worker; the client waits
for its Binder death before releasing its worker permit. Errors, process death,
bad assets and capability refusal are failures, never a Parakeet fallback.

The stream implements desktop FIR, VAD-only high-pass, native Silero probabilities,
desktop SpeechGate and raw-PCM Needle segment recognition. A fixed raw ring retains
pre-roll/current speech without a full-track allocation or recording scratch.
Segment results preserve raw engine JSON and input hashes; placement into portable
transcript versions remains the job adapter's responsibility.

The app's selected recognizer and Settings are unchanged in this increment.
The subsequent durable-job adapter must record engine/pipeline pins, preserve
existing Parakeet jobs and reject incompatible checkpoints before Included/default.
This worker API does not claim compatibility with a Parakeet RecognitionState.
Size/memory acceptance and final Settings/onboarding remain separate gates.

## Native parity run

The bench test `WhistleWorkerParityTest` requires an eligible owned arm64 emulator
and the unchanged public DC/control WAVs plus #78 Mac reference under
`.tools/whistle-probe-assets/whistle-probe/`. It is not part of model-free CiSmoke.
Missing required assets fail. Run through direct adb instrumentation/device-smoke
with the host lock already held, no audio, and all emulator images/outputs external.
Use `whistleCase=dc-quiet-jfk-v1` or `dc-quiet-jfk-v1-control`.

The test writes measured output before parity assertions, checks six exact desktop
segments and raw PCM hashes, then exact word strings/counts and the existing 80 ms
boundary bound. It checks the complete-frame HPF hash separately and records VAD
frame/probability diagnostics. The frozen GNOME/Mac raw-engine discrepancy remains
103 versus104 total words. Four unchanged raw inputs differ in words/case:
`dc-quiet-jfk-v1-2.f32`, `dc-quiet-jfk-v1-4.f32`,
`dc-quiet-jfk-v1-control-0.f32`, and `dc-quiet-jfk-v1-control-5.f32`.
The bench's Mac reference does not establish equality with frozen GNOME output;
no existing reference is rewritten or generalized into an exception. Actual results, not a mocked native engine, determine readiness.


## Review hardening and ABI delivery

Needle hashes are source-pinned for the pinned Linux and Darwin NDK outputs;
the generated manifest cannot authorize another runtime. Native OPEN also
requires an isolated UID before any neural load. Admission waits have a timeout.
Cleanup unbinds a failed bind, waits for a linked worker's death, restores
interrupts and attaches cleanup errors to the original failure. A failed reply
binder marks the worker failed without throwing on its main looper.

Models reside in a pinned arm64-only read-only ELF data container. It is never
executed or passed to dlopen. The worker verifies the whole container and maps
its stored ZIP entry at pinned model offsets, then checks both original model
hashes. This places models together with Needle/private ORT in the ARM ABI
split; x86_64 retains Parakeet without the ARM payload. APK builds produce ABI
and universal outputs; AAB ABI splitting is explicit. See the recipe and exact
sizes in `whistle/worker/review_measure.py` and the review evidence.

To reproduce unsigned package measurements, prepare the pinned inputs, set
JDK17 and external output/cache paths, then run the build under the shared
heavy-job lock:

```sh
./gradlew --no-daemon :app:assembleRelease :app:bundleRelease
python3 whistle/worker/review_measure.py \
  --apk-dir app/build/outputs/apk/release \
  --bundle app/build/outputs/bundle/release/app-release.aab \
  --output /external/path/review-packages.json
```

The report verifies ABI sets, ZIP/ELF alignment and pinned payload bytes before
recording exact bytes and hashes. It reports package ZIP sizes, not installed
disk usage or Play-served download sizes. Do not set release signing credentials
for this local measurement.
