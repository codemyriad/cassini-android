# Packaged Android JNI/DC experiment — 2026-11-08

**API 26 executes the pinned engine, but fails desktop parity. Included/default
remains blocked.** API 35 matches exact words/counts on both 4 KB and actual
16 KB pages. Each run covers 13 inputs (104 words): clean JFK plus six raw DC
and six raw control segments, prepared from the reviewed desktop boundaries.

| Guest API / page size | Exact words/counts | Max boundary difference | Privacy refusals | JNI load interval | PSS point samples |
| --- | --- | --- | --- | --- | --- |
| 26 / 4,096 bytes | 11/13 | 560 ms | 6/6 | 10.21 ms | 63.6–85.7 MiB |
| 35 / 4,096 bytes | 13/13 | 80 ms | 6/6 | 41.55 ms | 67.2–143.8 MiB |
| 35 / 16,384 bytes | 13/13 | 80 ms | 6/6 | 80.64 ms | 100.1–107.3 MiB |

The JNI load interval excludes asset reading/decompression/hash verification.
PSS point samples are not peak-memory acceptance measurements or phone results.
The corrected standalone lab APK is **19,986,063 bytes**, including raw test PCM
and reference JSON; it is not a production APK/AAB size delta. Its SHA-256 is
`435679b65d004d1a1e08bd7780829cc9b5b5230432850d222e83b39a6893591c`.

The API 35/4 KB row is the initial growing-buffer loader baseline, APK SHA-256
`23b4f19b8e7f1e23ee04a6843bbe26b4dd5d594f575e3a628d410a14c62780f7`.
API 26 and API 35/16 KB use the corrected exact-size loader and APK above.
The engine, JNI bootstrap, model and PCM are unchanged between those APKs.
The additional corrected-loader 4 KB repeat was canceled while waiting for the
shared host; no result is claimed for that repeat. `provenance.json` binds each
recorded guest result to its APK and image metadata.

## API 26 failures that must not be waived

- `dc-quiet-jfk-v1-3.f32`: Mac `Americans.` versus Android `American.`.
- `dc-quiet-jfk-v1-4.f32`: Mac `our` versus Android `Your`.
- `dc-quiet-jfk-v1-control-0.f32`: equal words/counts, but the `you`/`as`
  boundary is 560 ms later on Android (1.84 s versus 2.40 s).

All input hashes and word counts match. Eleven of 13 inputs match exact words;
the control timing failure is separate. No cause is claimed. These failures are
outside D11.3/D11.5's allowed scope; no golden, word exception or timing tolerance
was changed. The runner preserves results and exits nonzero for this comparison.

The first API 26 attempt failed before engine loading because a growing Java
ByteArrayOutputStream exceeded the guest's 48 MiB heap. The final loader reads
the exact pinned model size into one array, without a large-heap request. The
corrected run loads the real C API/model and completes every input.

## Provenance and limits

`mac-reference.json` contains candidate outputs measured from the exact D5 Mac
arm64 engine slice on identical input bytes. It freezes this experiment's inputs
and measured reference for coordinator review, not a replacement product golden.
`api26.json`, `api35.json` and `api35-16k.json` record actual packaged JNI results.
`engine-inspection.json` and `bootstrap-inspection.json` record API 26 strict
linking, PIC, exports, 16 KB ELF alignment, dependencies and explicit weak imports.
The engine uses a hidden private static C++ runtime and only libc/libm/libdl;
the 8,944-byte bootstrap uses only libc/libdl and has no Needle/ORT dependency.

The generator verifies Mac #167's clean FIR output hash and both complete-frame
DC filtered-VAD hashes before cutting desktop segment boundaries. Recognition
receives **unfiltered** PCM. DC and control transcripts can differ; Android is
compared only with its identical Mac input. No Android FIR/VAD runs here.

This is a permission-free lab package on owned AVDs. Every emulator uses
`-no-audio` and stopped after its run. After an observed stale-transport race,
the runner now also awaits ADB disconnect as an event; that final cleanup change
has not had another emulator run. The disconnect command was checked with the
owned transport already absent.
No production app/user data is touched, and no model/audio/APK/key/AVD bytes are
committed. Production-worker isolation, final size/peak-memory measurements,
full Android pipeline parity, low-memory recovery and Included/default are later
gates. x86_64 remains unavailable for Whistle and retains Parakeet.
