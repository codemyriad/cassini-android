I found three groups of prior work:

- **Android device bench:** `ChunkingComparisonTest`, run on the Pixel 8 with Parakeet v3 and the stock frontend.
- **Host WER runs:** about 40 meeting windows scored against the desktop transcripts, with Parakeet v3 and the stock frontend.
- **gocassini boundary investigation:** desktop Parakeet v3, first stock and then the corrected frontend (fork patch, see C).

None of them used Parakeet Ultra with the PR #4024 frontend. The one measurement of `PARAKEET_V3_REFERENCE` on Android ran on the stock frontend, so it does not test the case we care about now.

## A. cassini-android: what exists

**Current pipeline**
- **Production path.** `JobAdapters.kt:37` uses `IncrementalRecognizer`. That cuts the audio into 28 s PCM windows with 2 s of overlap (`IncrementalTranscript.kt:25-26`) and splices them at the seams (`:72`). Each window runs `Parakeet.transcribeWithDecoder(..., DecodePolicy.WHOLE_SPANS, ...)` (`IncrementalRecognizer.kt:37`). So VAD runs once per 28 s window, not once over the whole recording.
- **Bench and host eval take a different path.** Both call `Parakeet.transcribe` on the whole clip (`Parakeet.kt:47`), not the 28 s path. Neither has measured the production path.
- **Policies** (`SpeechWindows.kt:15-29`):
  - Defaults: 10 s windows, 0.5 s overlap, 0.5 s grace, 5 s minimum last window, 0.5 s synthetic tail.
  - `WHOLE_SPANS` = `preserveSpan=true`, which keeps the 0.5 s synthetic tail.
  - `PARAKEET_V3_REFERENCE` = `preserveSpan`, `contextMs=30`, `tailPaddingMs=0`, `syntheticPadding=false`.
- **Long spans** (`SpeechWindows.kt:51-57`): a span over 28 s is cut at its quietest 30 ms, 20 to 25 s after the previous cut, and each cut gets 1 s of recorded context on both sides. The safety split is 55 s (`:47`). A chunk under 20 ms is skipped when synthetic padding is off (`:181`).
- **VAD** (`SpeechFeed.kt:29-36`): threshold 0.18, 0.5 s minimum silence, 0.10 s minimum speech, 25 s maximum (only a hint, see below), 512-sample window, run on a 16 kHz copy.
- **Empty-span retry** (`DecodeSession.kt:82-88`, `RETRY_CONTEXT_MS=500` at `:112`): fires whenever `preserveSpan` is set. Commit 5f01a8c made it fire with synthetic padding too. gocassini only retries when padding is off (`stt.go:462`).
- **Decoder:** `ParakeetDecoder.kt:29` sets 16 kHz, 128 features, dither 0, greedy search, 2 threads. Zero padding is added in the PCM (`:56-57`).
- **Resampling:** `AudioDecoder` now delivers `Limits.ASR_RATE`=16000. `Resampler.kt:16,18` is a windowed sinc with `CUTOFF_FRACTION=0.4375`, which works out to about a 7.0 kHz cutoff at 16 kHz output, and 12 zero crossings. The #4024 frontend puts its top mel edge at 8 kHz, so the 7 to 8 kHz band arrives attenuated. That is a fact about the code; whether it matters is an untested hypothesis.

**1. `ChunkingComparisonTest`** (`bench/src/main/java/org/cassini/bench/ChunkingComparisonTest.kt`)
- **Modes** (`:59-67`):
  - `whole`
  - `fixed-15s`: 15 s windows, 0.5 s overlap
  - `quiet-cuts`: no VAD
  - `speech-10s-tail`: `DecodePolicy()`
  - `speech-span-notail`: `preserveSpan`, tail 0, `syntheticPadding` still true
  - `speech-span-context`: `PARAKEET_V3_REFERENCE`
  - `speech-span-tail`: `WHOLE_SPANS`
- **Clips** (`:70`): `fleurs-it-short.wav` and `.m4a`, `youtube-smoke.wav`, `youtube-long.wav`.
- **Metric:**
  - Word Levenshtein distance against `whole`, and against the FLEURS reference for the FLEURS clips.
  - Normalisation (`:35-36`): lowercase, curly apostrophe folded, digit groups removed, split on non-letters.
  - Also records elapsed time and time to first words.
- **Hard asserts:**
  - At least half the words of `whole` are kept.
  - The word `"batterica"` appears at 132.5–134.5 s in `youtube-long` (`:93-95`). This is v3 output and may fail on Ultra.
  - Progress is monotonic.
- **Output:**
  - A `CUT {json}` line per mode in the instrumentation stream.
  - `files/chunking-comparison.INT8.json` in the app's `filesDir` (`:105`), which overwrites the previous file.
- **Measured results** are in `docs/notes/pixel8-results.md:105-161`: Pixel 8, v3 INT8, stock sherpa 1.13.7, one run each, temperature not controlled.

  **3-minute YouTube excerpt** (no human reference; 492–498 words per mode):

  | Mode | Differs from whole | vs YouTube auto-captions (493 words) |
  |---|---|---|
  | whole | 0 | 14 |
  | fixed-15s | 14 | 12 |
  | quiet-cuts | 11 | 13 |
  | 10 s + tail | 14 | 13 |
  | span, no tail | 7 | 10 |
  | span + 30 ms context, no tail | 9 | 11 |
  | **span + 0.5 s tail (chosen)** | 9 | **8** |

  **FLEURS 15.8 s, errors against the 27-word human reference (wav / m4a):**

  | Mode | wav | m4a |
  |---|---|---|
  | whole | 5 | 5 |
  | fixed | 3 | 3 |
  | quiet | 3 | 4 |
  | 10 s + tail | 1 | 1 |
  | span + tail | 2 | 2 |
  | span, no tail | 4 | 1 |
  | span + context | 5 | 3 |

  What the notes record about failures:
  - 3 of the 4 no-tail runs misread "4892" as Spanish-sounding words.
  - 10 s windows left duplicated words at 4 seams.
  - The context/no-tail mode and 10 s windows cut off final words ("succed", "l'u").
  - FP32 runs: cutting brought no gain.
  - Quiet-point fallback: a cut at 133.58 s split "batterica" into "batteria". The 1 s cut context fixed this, but that was checked only on an x86_64 emulator.
- I pulled the raw per-run JSON from the phone. The INT8 numbers match the FLEURS figures in the doc, but the INT8 file uses the old asset name `italian-smoke.*`.

**2. `BenchSupport.kt`:** only helpers (an `audioDecoder` and an `InstalledModels` wrapper). The other bench tests (`DiarizationPocTest`, `VoiceprintCalibrationTest`) are unrelated to chunking.

**3. `tools/asr-eval/AsrEval.kt`:**
- It is a host JVM program that runs `Parakeet.transcribe` with `DecodePolicy.WHOLE_SPANS.copy(contextMs=$CASSINI_CONTEXT_MS)` (`:41`).
- Policy cannot be chosen any other way: `PARAKEET_V3_REFERENCE` needs a code edit.
- Output is `cassini.words.v1` plus `x-elapsedMs` and `x-durationMs`.

**4. Past host eval runs** (2026-10-08; results in `/home/silvio/dev/cassini-eval`, README and `results/asr-summary.txt`):
- **Data:** 40 three-minute windows from private desktop meetings in `../gocassini/media/recordings/*.opus` (130 files, 52.7 h, mixed audio), 14,005 reference words.
- **References:** desktop Parakeet v3 FP32 words, decoded per participant track. They are model output, not human transcripts.
- **Scorer:** `scripts/wer.py` (NFKC, lowercase, punctuation stripped, words inside window `[s, s+180 s]`) and `wer_detail.py` (S/D/I split).
- I recomputed every row today from the saved outputs and got the same numbers:

  | Configuration | WER | Sub / Del / Ins |
  |---|---|---|
  | v3 int8 48 kHz | 26.9% | 8.5 / 11.6 / 6.5 |
  | v3 int8 16 kHz | 27.4% | 8.2 / 12.1 / 6.9 |
  | + empty-span retry 500 ms | **26.2%** | 8.0 / 10.5 / 7.4 |
  | + empty-span retry 2 s | 26.2% | same |
  | + 1 s recorded context on every span | 28.7% | 8.5 / 12.5 / 7.4 |
  | v3 fp32 on the mix | 22.8% | 6.7 / 7.2 / 8.4 |
  | int8 encoder + fp32 joiner, or + fp32 decoder and joiner | 27.3% | — |
  | v2 int8 (English) | 27.4% | — |

  All rows use `WHOLE_SPANS` with the stock frontend.
- **Conclusions recorded then** (claims from that session):
  - INT8 loses whole spans, for example 145 → 157 s.
  - Some retried spans still lose words, possibly in the seam merge (`daily-meeting-2026-04-21` at 1260 s, around 77–80 s).
  - Accuracy work was stopped by decision on 2026-10-08.
- **Inputs still on disk:**
  - Windows list: `/home/silvio/.claude/jobs/1f0c1c89/tmp/asr/items.json`.
  - 16 kHz and 48 kHz f32 PCM: `.../tmp/asr/pcm/`.
  - References: `.../tmp/gt/*.json`.
  - Old job lists and outputs: `.../tmp/asr/{jobs-*,out/*}`.

**5. Historical notes**
- `docs/notes/feasibility.md:31-45` explains why `WHOLE_SPANS` was chosen and says the desktop reference policy "is validated only with its patched frontend".
- `docs/development.md:469` says that before a release, `WHOLE_SPANS` should be compared with `PARAKEET_V3_REFERENCE` on Ultra with `ChunkingComparisonTest`. It notes that the reference frontend normalises over the whole input, synthetic silence included.

## B. Reference transcripts and clips

| Clip | Reference | Licence / where |
|---|---|---|
| `fixtures/audio/fleurs-it-short.{wav,m4a,mp3,opus}` | Human, 27 words, in `fleurs-it-short.json` (also `goldens/*.txt/srt/vtt`) | FLEURS it dev, CC-BY-4.0, committed |
| `youtube-smoke.wav` (30 s), `youtube-long.wav` (180 s) | None. The captions comparison was done by hand and no captions file was kept. | Dario Bressanini video UmZwQf5TV3c, from 00:28. Rebuilt with `tools/fetch-youtube-audio.sh 30\|180`. Git-ignored, present locally. |
| `generated/fleurs-it-x20.wav` | FLEURS × 20 | Built by `scripts/make-long-clip.sh` |
| `app/src/androidTest/assets/meetings/m01–m06` (90–120 s, 16 kHz) | Speaker labels are ground truth; the `words` come from ASR | Private, git-ignored |
| gocassini recordings (via `extract_gt.py`) | Desktop v3 FP32 per-track words | Private |

## C. gocassini (read-only)

- **`deployment/sherpa/README.md`:** the fork frontend turns on only for exact v3 metadata.
- **`docs/parakeet-boundary-investigation-2026-09-17.md`** (desktop, private meeting excerpts, references from blind Gemini transcription, metric = normalised token disagreement):

  Stock frontend, conditions over 712 reference tokens:

  | Decoder | Window | Tail | Disagreements | Deletions |
  |---|---|---|---|---|
  | beam | 10 s | 500 ms | 201 | 145 |
  | greedy | 10 s | 500 ms | 137 | 71 |
  | greedy | 10 s | none | 121 | 39 |
  | greedy | 25 s | none | 97 | 37 |

  With the reference frontend, greedy:

  | Boundaries | Disagreements | Deletions |
  |---|---|---|
  | existing boundaries | 107 | 43 |
  | whole VAD span + 30 ms context, no tail | 94 | 34 |

  Other findings:
  - **INT8 CPU:** 244 (deletions 193) → 117 (49). Held-out: 48 → 31.
  - **Context sensitivity:** 0, 30 and 60 ms context gave 93–96. 1000 ms caused a large omission and new false speech.
  - **Padding is not monotonic:** across two passages, padding helped one and hurt the other.
  - **Mechanism:**
    - Per-feature normalisation runs over the whole input, so zeros change the features of all the speech, not only the boundary.
    - Leading padding shifts the frame alignment.
    - In a same-ONNX diagnostic, a 500 ms tail with stock features gave an empty output on a quiet crop.
- **`docs/parakeet-boundary-benchmark.md`** documents the `TestRecordedBoundaryBenchmark` tool (built with `-tags boundarybench`), with corpus and conditions JSON and `harness/bin/score-stt-boundaries.py`.
- **`docs/parakeet-boundary-literature-2026-09-17.md`:**
  - The sherpa offline v3 example adds no silence.
  - Silero's reference utility uses 30 ms of real-audio padding.
  - NeMo's buffered recipe (10 s left / 10 s new / 5 s right, with decoder state carried across chunks) is a different algorithm from independent overlapping decodes.
  - Neither 10 s, nor a 500 ms tail, nor 500 ms overlap is established as optimal.
- **`harness/patches/*.md`:** beam-search fixes (legal blank, score normalisation) did not recover the omissions in 32 of 32 cases.
- **`harness/bin`:** `check-parakeet-frontend.py`, `replay-stt-meetings.py`, `audit-stt-replays.py`.
- **Synthetic CI regressions:** `cassini-go-recorder/internal/transcribe/testdata/synthetic-boundary/` (garden.mkv, acknowledgement.wav). These are invented TTS audio and public.
- **Current desktop policy** (`stt.go:204-213`): `preserveVADSpan`, `contextMS=30`, tail 0, synthetic padding off. VAD settings are the same as Android (`stt.go:89-91, 225-229`).
- `reports/` and `benchmarks/` have nothing relevant.
- All gocassini corpora and references are private, and no corpus JSON is on this machine.

## D. How to run them today

**ChunkingComparisonTest**
```
tools/fetch-youtube-audio.sh 30; tools/fetch-youtube-audio.sh 180   # already present
scripts/device-smoke.sh --bench --sample cutting --class org.cassini.bench.ChunkingComparisonTest
adb exec-out run-as org.cassini.android cat files/chunking-comparison.INT8.json > out.json
```
- Ultra (marker `b86784a3`) and the VAD are already installed on the Pixel.
- The script fails if the test skips.
- The run overwrites the old v3 file. I saved copies under `.../scratchpad/investigation/prior/chunking-comparison.v3.{INT8,FP32}.json`.
- Expect the `"batterica"` assertion to be at risk with Ultra.

**asr-eval**
1. Build a host `libsherpa-onnx-jni` from `dbbeef9c` plus `scripts/streaming-diarization.patch`. The only existing host build, `/home/silvio/dev/sherpa-onnx-nemotron-android/build-host-jni/lib`, is commit `1f23907a` and does not have the #4024 frontend.
2. Copy `AsrEval.kt` into `engine/src/test/kotlin/org/cassini/tools/asreval/`.
3. Run `./gradlew :engine:compileDebugUnitTestKotlin`, then run `org.cassini.tools.asreval.AsrEval.main <ultra model dir> <silero_vad.onnx|-> <jobs>` with `-Djava.library.path=...`.
4. Each job line is `<f32le> <rate> <out.json>`.
5. Score with `cassini-eval/scripts/wer.py gt <outdir>` and `wer_detail.py`.
- The old flow used `:app:writeEvalClasspath`, which no longer exists.

Measured here means the A4 numbers I recomputed and the per-run JSON I pulled from the phone. The pixel8 table and the gocassini numbers are as their documents report them.

Files are in `/tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/prior/`:
- chunking-comparison.v3.INT8.json
- chunking-comparison.v3.FP32.json