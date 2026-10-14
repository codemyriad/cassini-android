Running the experiments on this Linux host is the fastest reliable option, and I've set it up end to end. It runs the app's own recognition pipeline (`Parakeet.transcribe`, with Silero VAD and the `DecodePolicy` options) on Ultra INT8, at about 14.5× real time. It takes jobs in batches, picks up where an interrupted run stopped, and runs several jobs in parallel. Two things to know before trusting it:
- **INT8 differences:** host and Pixel output differ in casing and number formatting. Final checks still need to run on the Pixel (option a).
- **VAD output:** cutting the audio into speech spans changes the output even on the same machine.

I made no repository edits; `git status` is clean, and Gradle only wrote its usual ignored build outputs.

## Host tools (checked)
- x86_64, AMD Ryzen AI 9 HX PRO 370, 24 threads; the CPU has avx2, avx512f, avx512_vnni and avx_vnni. 58 GB RAM, about 34 GB free.
- cmake 4.2.3, g++ 15.2.0, clang, python3 3.14.4, pip, uv, ffmpeg, curl, git-lfs, adb (Pixel `49101FDJH002K4` is connected).
- JDK 17 at `/usr/lib/jvm/java-17-openjdk-amd64` has `javac`. JDK 21 has no compiler, so Gradle fails with it ("does not provide the required capabilities: [JAVA_COMPILER]").
- Not installed: ninja, sox, `hf`/`huggingface-cli`.

## Option (b), tried and working

### Models (pinned and hash-checked)
Downloading took 3 min 25 s, almost all of it the 652 MB encoder.
```
R=b86784a3a5356cbd126f710b61cd90c5fe5898f1
curl -sSL -o $f https://huggingface.co/tpmaerk/parakeet-ultra-sherpa-onnx-int8/resolve/$R/$f   # encoder/decoder/joiner.int8.onnx, tokens.txt
```
- The SHA-256 hashes match the hub's LFS oids. The encoder's is `f4d06712…3697`.
- Silero VAD comes from the URL the app uses (`ModelCatalog.kt:107`). Its SHA-256 is `9e2449e1…1fd6`, which matches `ModelCatalog.kt:112`.
- Everything is under `.../scratchpad/investigation/models/`.

### 1. CLI build (sherpa checkout `dbbeef9c`, unmodified)
- Configure took 14.5 s. It downloads onnxruntime 1.27.1 linux-x64.
- Building `sherpa-onnx-offline` and `sherpa-onnx-vad-with-offline-asr` with `-j24` took 42 s.
```
cmake -S $SHERPA -B build -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=ON -DSHERPA_ONNX_ENABLE_PYTHON=OFF -DSHERPA_ONNX_ENABLE_TESTS=OFF -DSHERPA_ONNX_ENABLE_PORTAUDIO=OFF -DSHERPA_ONNX_ENABLE_WEBSOCKET=OFF -DSHERPA_ONNX_ENABLE_TTS=OFF -DSHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=OFF -DSHERPA_ONNX_ENABLE_BINARY=ON
cmake --build build -j24 --target sherpa-onnx-offline sherpa-onnx-vad-with-offline-asr
build/bin/sherpa-onnx-offline --encoder=$M/encoder.int8.onnx --decoder=$M/decoder.int8.onnx --joiner=$M/joiner.int8.onnx --tokens=$M/tokens.txt --model-type=nemo_transducer --num-threads=N fixtures/audio/fleurs-it-short.wav
```
Measured on `fleurs-it-short.wav` (15.84 s):

| Threads | Decode time | RTF | Process wall time | Peak memory |
|---|---|---|---|---|
| 1 | 1.551 s | 0.098 | 2.75 s | about 1.18 GB |
| 4 | 1.116 s | 0.070 | – | – |
| 8 | 0.712 s | 0.045 | – | – |

All three thread counts gave the same tokens:
> La parte settentrionale, conosciuta come catena della sentinella, vanta le montagne più alte dell'Antartide, il massiccio del Vinson, che raggiunge i 4.892 metri con il monte Vinson.

FLEURS reference: "…Catena della Sentinella … Massiccio del Vinson … 4892 m con il Monte Vinson."

### 2. Host build of the app's pipeline (recommended)
This builds the JNI library, compiles a small driver in scratch, and runs `Parakeet.transcribe` from the app's own `:engine`/`:core` classes on the host.

**JNI library**
- I exported `dbbeef9c` with `git archive` into `investigation/src-jni` and applied `scripts/streaming-diarization.patch` (SHA-256 `91b3f2c5…`).
- Build options: `-DSHERPA_ONNX_ENABLE_JNI=ON -DSHERPA_ONNX_ENABLE_BINARY=OFF` plus the Release and shared-library flags above.
- Configure took 22.5 s and the build 72.8 s. The result is `build-jni/lib/libsherpa-onnx-jni.so`, linked against `build-jni/_deps/onnxruntime-src/lib/libonnxruntime.so`.

**Classpath**
- An init script kept in scratch (`eval/cp.init.gradle.kts`) resolves `:engine` `debugUnitTestRuntimeClasspath` as `android-classes-jar`.
- It needs both `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` and `--no-configuration-cache`:
```
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 CP_OUT=$I/eval/classpath.txt ./gradlew -q --no-configuration-cache --init-script $I/eval/cp.init.gradle.kts :engine:printEvalCp
```

**Driver**
- `eval/src/HostEval.kt` is a variant of `tools/asr-eval/AsrEval.kt`. Environment variables: `POLICY` is `WHOLE_SPANS` or `REFERENCE`, `CUTTING` is `SPEECH`, `QUIET`, `WHOLE` or `FIXED`, and `CONTEXT_MS` and `TAIL_MS` are optional.
- It compiles in 5.7 s with `kotlin-compiler-embeddable` 2.4.21 from the Gradle cache. It runs under `java -Djava.library.path=$I/build-jni/lib`.
- To run it: `eval/run.sh <jobs.txt> [vad|-]`, where each job line is `<f32le file> <rate> <out.json>`.
- I did not copy into `engine/src/test` as the tool's README suggests, because the task forbids editing the repository.

**Measured on the host** (the pipeline uses 2 threads, set in `ParakeetDecoder.kt:39`):

| Policy | Cutting | Time | Output |
|---|---|---|---|
| WHOLE_SPANS | SPEECH | 2494 ms | "…catena della **Sentinella**… **4892** metri…" |
| REFERENCE | SPEECH | 2481 ms | same as WHOLE_SPANS + SPEECH |
| WHOLE_SPANS | WHOLE | 2777 ms | same as the CLI ("sentinella", "4.892") |
| REFERENCE | WHOLE | 3091 ms | same as the CLI |
| WHOLE_SPANS | QUIET | 3704 ms | same as the CLI |

So on the same machine, cutting by VAD spans changes casing and digit grouping. This matters for the preprocessing study.

Long form: the clip repeated 20 times (316.8 s), built the same way as `scripts/make-long-clip.sh:13-19`:

| Policy | Time | Words | CPU |
|---|---|---|---|
| WHOLE_SPANS + SPEECH | 21.8 s | 540 (exactly 27 × 20) | about 197% |
| REFERENCE + SPEECH | 23.7 s | 540 (exactly 27 × 20) | about 197% |

With about 1.2 GB per process, I estimate 8–10 jobs can run in parallel. I have not measured that.

**Python bindings:** not tried. Python 3.14 together with pybind11 is a risk, and the JNI route already runs the real pipeline.

## Option (a), Pixel bench
- I did not run or time it.
- It needs `:bench:assembleDebug` and an `adb install` for each change (`scripts/device-smoke.sh:152-160`). It reads models the app installed and runs only with `--class`.
- `ChunkingComparisonTest` compares cutting modes and gates on `audioSample=cutting` (`bench/.../ChunkingComparisonTest.kt:51-55`). Its reference is the single fixture, so it is not a dataset harness.
- Use it to confirm the finalists on arm64, not to sweep many settings.

## x86_64 vs arm64 INT8
- **What the repo records:** `app/src/androidTest/.../flows/NoteFlowTest.kt:46-50` says the Pixel 8 INT8 kernels give "Catena della Sentinella" where the x86_64 emulator golden has "catena della sentinella", with the same times.
- **Likely cause (not verified):** onnxruntime's dynamic-quantization kernels differ (x86 u8s8 with AVX2 or VNNI against arm64 sdot or i8mm). Results can also differ between x86 hosts with and without VNNI.
- **What I measured:** on this host the casing also changes with how the audio is cut, as shown above.
- **Recommendation:** score with WER after lowercasing, removing punctuation, and folding digit grouping, as `ChunkingComparisonTest.kt:33` does. Treat casing and punctuation as a separate metric to check on the Pixel.

## Evaluation data (I checked the HF API: none of these are gated, private or behind login)

**FLEURS** (`google/fleurs`, CC-BY-4.0, revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`, already pinned in `scripts/fetch-smoke-audio.py:16`)
- Files are per language: `data/<lang>/audio/{dev,test,train}.tar.gz` plus `<split>.tsv`. The TSV columns are id, file, raw text, normalized text, characters, sample count, gender.
- `it_it`: test is 658 MB and has 864 rows in `test.tsv`; dev is 289 MB.
- Test sizes for other languages: en_us 290 MB, es_419 582 MB, fr_fr 349 MB, de_de 569 MB, pt_br 621 MB.
- The same sentence id appears from several speakers. In the `it_it` test set, id 1987 has 3 recordings.
- **Building long-form audio:** the repo only repeats one clip (`scripts/make-long-clip.sh`: 20× concat with no gaps). A better test concatenates different test utterances in id order with controlled gaps, keeping one speaker or mixing speakers. Gap lengths to try: 0, 0.3, 1 and 3 s of real or quiet noise. The reference is the raw texts joined in order. This exercises VAD splits, the 28 s whole-span limit and the cutting at 20–25 s (`SpeechWindows.kt:51-55`).

**Long-form sets (all downloadable without auth as parquet):**

| Dataset | Revision | Size | License |
|---|---|---|---|
| `distil-whisper/tedlium-long-form` | `ea3a78479fb5` | test 285 MB, validation 180 MB | TED-LIUM 3 is CC BY-NC-ND 3.0 (from memory; the card does not state it). Fine for internal evaluation, not for redistribution. |
| `distil-whisper/earnings21` | `5956ebd0b2ba` | `full/` 773 MB | CC BY-SA 4.0 (from memory) |
| `distil-whisper/earnings22` | `0a034f9ed86d` | `chunked/` is 38 shards, about 0.6–1 GB each | CC BY-SA 4.0 (from memory) |
| `distil-whisper/rev16` | `3ebfc1780974` | `full/` about 1.4 GB | Unclear, so avoid it |
| `distil-whisper/meanwhile` | `5a6b431a2685` | 58 MB | YouTube clips with no license, so avoid it |

These sets are English only.

**Other sets:**
- `edinburghcstr/ami` (`46f28f2503e2`, CC-BY-4.0) contains segmented utterances (ihm/sdm parquet), not long-form. Full AMI meetings come from the Edinburgh AMI site under CC-BY-4.0 (from memory, not checked).
- `facebook/multilingual_librispeech` (CC-BY-4.0) and `facebook/voxpopuli` (CC0) are open and have Italian, useful for read speech and parliamentary speech.
- `mozilla-foundation/common_voice_17_0` showed `gated=False` in this API check, but Mozilla's terms apply.
- `LIUM/tedlium` returned an authentication error.

Everything is in `/tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/`:
- `build/bin/sherpa-onnx-offline`
- `build-jni/lib/libsherpa-onnx-jni.so`
- `models/parakeet-ultra-int8/`
- `models/vad/silero_vad.onnx`
- `eval/src/HostEval.kt`
- `eval/run.sh`
- `eval/classpath.txt`
- `eval/cp.init.gradle.kts`
- `data/fleurs-it-short.f32`
- `data/fleurs-it-x20.f32`
- `out/*/*.json`
- `run-t1.log`
- `run-t4.log`
- `run-t8.log`