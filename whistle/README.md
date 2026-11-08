# Whistle native input (D75.2, first increment)

This directory pins the published Needle 3.3.0 Android arm64 archive and C header, the D5 Whistle weights and the Apache-2.0 license. Published, hash-verified artifacts are accepted inputs under D75.2; an unavailable native-source mapping is a follow-up, not a rebuild prerequisite. No upstream contact is needed. `x86_64: null` means there is no approved Android engine for that ABI. A desktop Linux wheel is not an Android replacement.

The native-input foundation builds and checks a private runtime. It does **not** package weights into the production APK, load an engine in the app, add Settings choices or change the Parakeet path. Follow-up PRs add the guarded worker, measured size/memory, desktop/DC parity and Included/default only after their gates pass. On x86_64, Whistle must remain honestly unavailable and Parakeet remains usable.

## Build and inspect

Python 3.9+ and the pinned NDK 28.2.13676358 suffice; no Gradle, JDK, sherpa build or model download is needed:

```sh
python3 scripts/build-whistle-runtime.py --ndk "$ANDROID_HOME/ndk/28.2.13676358"
python3 scripts/build-whistle-runtime.py --ndk "$ANDROID_HOME/ndk/28.2.13676358" --offline
python3 -m unittest discover -s whistle -p 'test_*.py' -v
```

The first command fetches the pinned static archive, header and license into ignored `.tools/whistle/inputs`. Existing corrupt inputs fail; they are never silently replaced. Downloads are bounded and verified before publication. Offline mode never fetches anything. A separately recorded model pin will be used by the later bundled-weight step, never a runtime/dist download. Outputs and model bytes are not committed.

`aarch64-linux-android26-clang++` links the complete archive with `--no-undefined` against the API 26 NDK sysroot. `-z text` rejects non-PIC text relocations. ELF inspection independently checks AArch64, 16 KB load-segment alignment/congruence, no TEXTREL/RPATH/RUNPATH, and the exact exported C ABI. C++ is statically linked and localized by the export map; no C++ object ownership crosses the C interface. The only permitted DT_NEEDED entries are Android's `libc.so`, `libm.so` and `libdl.so`, not a system Needle/ORT/C++ runtime. Unexpected dependency/symbol changes fail. The compiled header assertions protect the desktop-compatible load/transcribe signatures. The ELF report records compiler, commands, imported symbols, size and output SHA-256; it does not claim a native-source rebuild.

The version map exports only `needle_load`, `needle_models`, `needle_last_error` and `needle_transcribe`. The production app has no JNI entry point. The separate [lab APK](spike/README.md) exercises a guarded bootstrap and packaged model on an owned emulator. The later worker must validate all three privacy flags **before** loading this library, including its constructors; a guard inside a function called after load would be too late. It must use the verified packaged private path, retain model memory and serialize calls, respecting the engine's global model state.

## Explicit native load test

On an owned arm64 lab device/emulator only, with no app installation/uninstall/data clearing:

```sh
python3 scripts/check-whistle-device.py --serial emulator-5584 --adb "$ANDROID_HOME/platform-tools/adb"
```

The serial is mandatory. The tool uses a fresh UUID directory in `/data/local/tmp`, removes only that directory, and never reads app recordings/models. It checks target ABI/API and the local library hash, copies the two native probe files, and waits on ADB transport events. Six missing/wrong privacy-flag cases must return the guard error before trying a nonexistent library path. With `NEEDLE_TELEMETRY=0`, `DO_NOT_TRACK=1`, `ORT_DISABLE_TELEMETRY=1`, the actual library must load with `RTLD_NOW|RTLD_LOCAL`, expose the four functions and report zero loaded models. `device-report.json` includes actual API/page size and explicitly limits the claim. These are tests of the native probe, not yet proof of a production worker's privacy boundary.

Use the shared host heavy-job lock when starting emulators or subsequent model/Gradle jobs. App instrumentation continues to use `scripts/device-smoke.sh`, preserving installed app/model data. No Gradle connected/managed-device cleanup is introduced.

## Initial evidence and remaining gates

Local NDK 28.2 build on macOS produced a stripped arm64 library of **1,856,488 bytes**, SHA-256 `f067271e71fb8aec52aca4cc41db135f6642d40eec5a32ab69d81fd3e4446a79`. All four load segments have 16,384-byte alignment; no text relocations, extra dependency or exported C++ symbol. The actual C API loaded on a read-only arm64 API 35 emulator with **4,096-byte** pages, `models=0`; all six pre-load guard cases passed. The emulator was stopped afterward. Build-host differences are recorded in reports rather than hidden by accepting a new pinned input.

API 26 **strong-symbol linking** is checked against the API 26 sysroot; a run on API 35 is not an API 26 runtime test. The report also lists weak imports (`__cxa_thread_atexit_impl@LIBC`, `memfd_create` in this build), which a strict link does not require to exist; runtime/model tests must exercise fallback behavior on the supported minimum OS. ELF alignment is not a 16 KB-page runtime test. No model/inference, unsupported-CPU, peak memory, full APK/AAB size, network isolation, desktop/DC parity or Included/default claim follows from this probe. Those remain release/default gates. In particular, no app worker loads this library before a separate guarded-worker PR, and actual model execution must be tested on arm64.

The pinned weights are 16,919,407 bytes. Their bytes plus this native library total 18,775,895 bytes, before the worker, VAD/private dependencies, APK compression/alignment or any extracted copy. This is an incremental input total, not an APK/AAB size or peak-RSS result. The app retains sherpa for its current functions; adding Whistle does not remove that cost automatically.

Android's current resampler/VAD model/thresholds differ from desktop D11. Follow-ups must use the committed clean and DC fixtures, raw recognition PCM with HPF on VAD input only, exact desktop FIR/segment input hashes, and reviewed desktop word expectations. No regeneration of goldens or fuzzy word comparisons. See coordination `REPLIES-ANDROID-whistle-2026-11-08.md` with D75.2 overriding its original source/x86_64 prerequisite.

## Packaged JNI experiment

The [standalone spike](spike/README.md) records actual packaged arm64 inference on identical desktop clean/DC raw PCM. Its [evidence](spike/evidence/README.md) keeps candidate pinned-Mac references, Android outputs and exact input hashes for review. API 35 passes these engine comparisons on 4 KB and actual 16 KB pages. API 26 executes the engine but fails on two word substitutions and one 560 ms timing difference. These failures block Included/default; no exception or golden is changed. This does not enable Whistle in the production app or establish Android FIR/VAD parity.

## Production capability policy

The Included Whistle path requires **API 29 or newer**, an **arm64 process**,
and the kernel's real **HWCAP_ASIMDDP** bit (bit 20). `WhistleCapability.assess`
contains the policy; `WhistleDeviceCapability` reads it through a small,
system-only JNI library. It neither loads Needle nor runs an optional instruction.
An absent JNI library, unresolved symbol, denied load or failed HWCAP query makes
the CPU probe unknown and Whistle ineligible. The device's supported-ABI list is
not evidence that the app is running an arm64 process.

The controlled #79 experiment establishes this conservative policy: the
unmodified API 29 and 35 guests match the frozen desktop words and timing bound;
API 26 does not. API 27/28 are unvalidated. Disabling dot-product dispatch breaks
parity on the newer guests too. An API floor alone is insufficient. The API 26
lab override must not be used as a production feature detector, even if SDOT can
execute. No capability override or on-device instruction smoke is shipped.

Eligibility is necessary, not Included readiness. Verified packaged model and
runtime, accepted desktop-equivalent preprocessing/worker, and memory/size gates
remain required before exposing Whistle as ready or making it the default.
This increment adds the policy and real probe with distinct unavailable reasons;
Settings and the current Parakeet worker are unchanged. Later selection must
check this gate before any Needle load, honestly show Whistle unavailable on
unsupported devices, keep Parakeet available, and never relabel a fallback as
Whistle. No download, removal or durable-job migration follows from this probe.
