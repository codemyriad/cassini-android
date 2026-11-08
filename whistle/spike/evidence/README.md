# First packaged Android JNI/DC run — 2026-11-08

The lab APK loaded the actual pinned Android arm64 engine and bundled Whistle
weights on an owned API 35 arm64 emulator with **4,096-byte pages**. No production
app or installed model data was involved. The emulator stopped after the run.

| Check | Observed result |
| --- | --- |
| Public inputs | Clean JFK plus six DC and six control raw PCM segments |
| Mac/Android exact words and counts | All 13 inputs, 104 words matched |
| Maximum word-boundary difference | 80 ms (binary64 display `0.08000000000000007`) |
| Missing/wrong telemetry flags | All six refused before `dlopen` |
| JNI model-load interval | 41.55 ms; excludes asset reading/decompression/hash verification |
| Point-in-time PSS samples | 67.2–143.8 MiB; not a peak-memory measurement |
| Standalone signed lab APK | 19,986,063 bytes, includes raw test PCM and reference JSON |
| APK SHA-256 | `23b4f19b8e7f1e23ee04a6843bbe26b4dd5d594f575e3a628d410a14c62780f7` |
| Engine SHA-256 | `f067271e71fb8aec52aca4cc41db135f6642d40eec5a32ab69d81fd3e4446a79` |
| Bootstrap | 8,944 bytes; `libc.so` and `libdl.so` only; four 16 KB-aligned load segments |
| Bootstrap SHA-256 | `d5c4554421521d56ca9696ba3e5c3e1eb7e13ce6d9379978ad2304a5c817ec9f` |

`mac-reference.json` contains candidate outputs measured from the exact D5 Mac
arm64 engine slice on identical input bytes. It freezes this experiment's inputs
and measured reference for coordinator review, not a replacement product golden.
`api35.json` records the actual JNI results and comparisons. `engine-inspection.json`
records API 26 strong-symbol linking, PIC, the four public C exports, 16 KB ELF
alignment, private static C++ ownership, dependencies and explicit weak imports.

The generator verified Mac #167's clean FIR output hash and both complete-frame
DC filtered-VAD hashes before cutting the reviewed desktop segment boundaries.
Those segments fed **unfiltered** PCM to recognition. DC and control transcripts
can differ; each Android result is compared only with its identical Mac input.

This proves packaged-engine compatibility for these inputs on API 35/4 KB. It
does not execute Android FIR/VAD, establish a production isolated-worker privacy
boundary, measure phone performance/peak memory/final app-size deltas, or enable
Included/default in Settings. API 26 and actual 16 KB-page execution are separate
gates. No x86_64 engine is claimed. No model/audio/APK/key/AVD bytes are committed.
