# Packaged arm64 JNI experiment (D75.2)

This is a standalone lab APK, not a production app integration. Its package is
`io.codemyriad.cassini.whistlespike`. It requests no permissions, opens no audio
input/output and uses no network. Its debug signing key is generated locally.
Model weights, generated raw PCM, keys, APKs and reports stay outside Git.

The APK carries the pinned arm64 engine, Whistle weights and public clean/DC
segments. A separate bootstrap has no dependency on Needle/ORT. Java checks the
packaged model and extracted engine hashes before entering that bootstrap. Six
missing/wrong telemetry-flag cases must fail before `dlopen`, then all three flags
are set before the actual engine load. The process retains the model buffer until
exit because the C API has no explicit unload ownership contract. Inference is
serial, bounded to 30 seconds per segment and a 1 MiB UTF-8 result. x86_64 has no
lab engine and remains unavailable; the production Parakeet path is unchanged.

## Reproduce on a lab host

Use JDK 17, NDK 28.2.13676358, Android platform 34 and build-tools 36.0.0. The
runner also needs the installed API 35 Google APIs arm64 image. Choose an output
directory on the external disk, including `TMPDIR`. On the shared Mac use the
`dm2` tmux server, whose process can access that disk. Hold the host lock for each
command, and execute these commands in order:

```sh
export TMPDIR=/Volumes/DrMeyers2/cassini-android-d75/tmp
export PYTHONDONTWRITEBYTECODE=1
mkdir -p "$TMPDIR"
scripts/heavy-lock.sh python3 whistle/spike/prepare.py \
  --coord ../cassini-coordination --mac ../cassini-mac \
  --assets /path/to/verified/desktop/Whistle \
  --output /Volumes/DrMeyers2/cassini-android-d75/reference
scripts/heavy-lock.sh python3 whistle/spike/build.py \
  --sdk "$ANDROID_HOME" --java-home "$JAVA_HOME" \
  --fixtures /Volumes/DrMeyers2/cassini-android-d75/reference \
  --output /Volumes/DrMeyers2/cassini-android-d75/apk
scripts/heavy-lock.sh python3 whistle/spike/run.py \
  --sdk "$ANDROID_HOME" \
  --apk /Volumes/DrMeyers2/cassini-android-d75/apk/whistle-spike.apk \
  --output /Volumes/DrMeyers2/cassini-android-d75/device-api35
```

`prepare.py` verifies the exact D5 model and Mac arm64 engine slice, compiles
Mac #167's ordered FIR with FP contraction off, checks the published clean FIR
hash and both DC filtered-VAD hashes, and transcribes one clean JFK input plus
six raw DC and six raw control segments through the pinned Mac C API. The
resulting Mac outputs are **candidate reference measurements for review**. They
do not replace any existing golden. Recognition always receives unfiltered PCM.

The APK reports exact word/count equality and maximum word-boundary differences
for every identical input. A completed experiment can report mismatches: keep
them and investigate; never regenerate a golden or add a broad text exception.
The run records per-segment elapsed time and point-in-time PSS. These are lab
observations, not phone performance or peak-memory acceptance measurements.

The runner creates its own AVD/image copy in its output directory, refuses an
existing transport on its reserved port, installs only the lab package there,
waits on Android boot events and instrumentation completion, and stops its own
emulator in `finally`, awaiting the ADB disconnect event before returning. It uses `-no-audio`, no snapshots and no physical devices.
There are no fixed settling sleeps, Gradle connected tasks, app-data reads,
uninstalls or user browser actions. No generated AVD is registered in the user's
normal Android AVD directory.

CI uses `build.py --compile-only` to compile the C JNI and Java sources without
Mac fixtures/model data. This is a compilation check, not Android execution.

## Evidence boundary

The [recorded experiment](evidence/README.md) executes API 26 and API 35 with
both 4 KB and actual 16 KB pages. API 26 completes inference but fails desktop
parity on two word substitutions and one 560 ms timing difference. That blocks
Included/default; no golden or tolerance is changed. The foundation's API 26 strict link, PIC, symbol closure,
private C++ runtime and 16 KB ELF alignment checks remain separate evidence.
This experiment does not run Android's own resampler or VAD, prove network
isolation of a production worker, measure final app size or enable Included/default
in Settings. Those are later small PRs. Keep the existing app and user models intact.

For other installed external-disk images, pass `--image` plus the exact `--api`
and `--tag`: API 26 uses `default`; API 35/16 KB uses `google_apis_ps16k`. The
runner checks the actual API and page size and refuses a mismatch. The API 26
comparison currently exits nonzero and preserves its failure results.
