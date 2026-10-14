# Audio preprocessing for Parakeet Ultra with the reference frontend: synthesis

I did not re-run anything for this synthesis. It combines the four investigator reports. Tags:
- **[M]**: measured by an investigator in this run.
- **[C]**: read in code.
- **[D]**: stated in a repo document.
- **[S]**: a third-party claim that was not verified.

Scratch files are under `/tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/`.

## 1. Current pipeline and its knobs

| Stage | What it does now | Knob (file:line) |
|---|---|---|
| Decode | WAV (PCM16 only) or MediaCodec. Opus length is set from the EOS granule; MP3 is gapless-trimmed. Container gaps become zeros. | AudioDecoder.kt:42-259, WavDecoder.kt:41 |
| Level | Channels averaged, then int16/32768. No gain, DC removal, automatic gain control (AGC) or dither anywhere. | AudioDecoder.kt:229-236 |
| Resample | One `StreamingResampler` produces 16 kHz for ASR, VAD and diarization. Windowed sinc, cutoff 0.4375 × 16k = 7 kHz, 12 zero crossings. Response [M, Python port]: 0 dB at 6 kHz, −1.2 dB at 6.5 kHz, −6 dB at 7 kHz, −17.6 dB at 7.5 kHz. 16 kHz input is passed through untouched. | Resampler.kt:148-157, AudioLimits.kt:110 |
| Long-form | `IncrementalTranscript`: fixed 28 s windows (448,000 samples) with 2 s overlap, so a 26 s stride. Windows are joined with `SeamMerge.splice` over 2 s. | IncrementalTranscript.kt:25-26, :72 |
| Per window | A new Silero detector is created for every window, so VAD state is lost every 26 s and a span crossing a window edge is cut there. Without VAD, the whole window is one span. | Parakeet.kt:84-87, IncrementalRecognizer.kt:37 |
| VAD | Silero, threshold 0.18 (sherpa's off-threshold becomes 0.03), min silence 0.5 s, min speech 0.10 s, max speech 25 s, which is only a hint. Spans start 164 ms before the trigger and end with no closing silence. | SpeechFeed.kt:29-36; sherpa voice-activity-detector.cc:96-121 |
| Policy | `WHOLE_SPANS`: each span decoded alone, no recorded context, plus a 0.5 s zero tail. The quiet-cut path (`CUT_FROM`/`CUT_TO` at 20/25 s) never runs in production, because spans inside a 28 s window are always ≤ 28 s. An empty span is retried once with ±500 ms of recorded context. | SpeechWindows.kt:15-28, 47-59, 181-185; DecodeSession.kt:57, 82-88, 112 |
| Recognizer | sherpa `nemo_transducer`, greedy search, 2 threads, CPU, dither 0, one stream per decode. | ParakeetDecoder.kt:28-64 |
| Frontend | PR #4024 reference frontend [C]: preemphasis 0.97, centered symmetric Hann (400-sample window, 512-point FFT), 128 mels from 0 to 8 kHz, `log(x + 2^-24)`. Per-bin mean and sample std are computed over all frames, synthetic padding included. Frame count is floor(n/160); fewer than 2 frames gives an empty result. Hard limit of 5000 encoder frames: 401 s ends in SIGABRT [M]. On the Ultra export, whether the frontend actually switches on was checked on x86 but **not on the device**. | offline-stream.cc:361-421; offline-recognizer-transducer-nemo-impl.h:243-276 |
| Word gate | Absolute dBFS thresholds: peak ≥ −60, RMS ≥ −80, ≥ 5 ms at ≥ −66 dBFS, with margins of 100/200 ms. A word stamped inside the synthetic tail is clamped to the real end. | WordGate.kt:129-249 |
| Capture | `AudioSource.MIC` at 48 kHz, then Opus 48 kb/s (or AAC 32 kb/s). No `VOICE_RECOGNITION`/`UNPROCESSED` source and no noise suppression or AGC calls. The recognizer hears the lossy decode. | OpusCapture.kt:44-60, AacCapture.kt:33-38 |

Locked files (`scripts/check-verbatim.sh`):
- **"Package only":** `Resampler`, `SpeechWindows` (`DecodePolicy` and all window constants), `SeamMerge`, `WordGate`.
- **Locked with a listed set of allowed edits:** `IncrementalTranscript` (holds 28 s / 2 s), `SpeechFeed`/`SileroDetector` (VAD config), `Parakeet`/`IncrementalRecognizer`/`ParakeetDecoder`/`DecodeSession`, and the capture files.

## 2. Prior evidence and what Ultra plus the reference frontend invalidates

**No longer valid as evidence for current choices:**
- **Why `WHOLE_SPANS` was chosen** (pixel8-results.md:105-161 [D]): Parakeet v3 INT8, stock frontend, one run each. The FLEURS reference has only 27 words. The 3-minute clip was scored against YouTube captions by hand, 8 errors versus 10–14 for other modes. A 2–6 word gap is noise, and no clip went through the production 28 s incremental path.
- **"Long merged spans make Parakeet emit only a prefix"** (SpeechWindows.kt:118). With Ultra and the reference frontend this does not reproduce [M, x86]. Whole decodes of 25, 55, 90, 120, 180 and 300 s all emit words up to the end. The 400 s decode gave 1104 words. **Short windows were motivated by this, and that motivation is gone; peak memory is now the reason to keep windows short.**
- **The 0.5 s synthetic tail.** It was a fix for the stock frontend. gocassini saw an empty output from a 500 ms tail on a quiet crop. Under the reference frontend the tail feeds the normalization statistics instead [M]:

  | Span | RMS shift of speech frames from a 0.5 s zero tail |
  |---|---|
  | 0.4 s | 0.94 |
  | 0.8 s | 0.59 |
  | 1.5 s | 0.41 |
  | 3 s | 0.27 |
  | 12 s | 0.08 |

  For comparison, 30 ms of recorded context shifts them by 0.010. Only the total amount of padding matters, not where it goes.
- **Host WER table** (cassini-eval: 26.2% with the retry, 28.7% with 1 s context). It is v3 on the stock frontend, scored against v3 FP32 output as reference. Use it only as a historical baseline.
- **The `"batterica"` assertion** in ChunkingComparisonTest.kt:93-95 checks v3 output and may fail on Ultra.

**Still relevant, but carried over from v3 (to be confirmed):**
- **gocassini with the reference frontend** (v3, desktop, 712 tokens): whole VAD span + 30 ms context with no tail gave 94 disagreements, against 107 for the existing boundaries. Context of 0, 30 or 60 ms gave 93–96, while 1000 ms caused omissions and new false speech. Padding effects were not monotonic. This is the closest prior to the current stack, and it favours `PARAKEET_V3_REFERENCE`.
- **Empty-span retry with 500 ms** (26.2% vs 27.4%, v3). The mechanism is independent of the frontend.

**New facts that hold for Ultra [M, x86, no ground truth]:**
- **40 units cut from youtube-long, scored against a whole-file pseudo-reference:**

  | Variant | Pseudo-WER |
  |---|---|
  | tight | 1.32% |
  | ctx30 | 1.32% |
  | 0.5 s zero tail | 1.51% |
  | 0.5 s noise tail | 1.70% |

  The gaps are single-word swaps, so padding has no measurable effect on clean, continuous speech.
- **Cutting by VAD spans vs one whole decode:** on 180 s, the whole decode differs from the concatenated spans by 4 substitutions in about 420 words. On fleurs-it-short, cutting by VAD spans changed casing and digit grouping ("Sentinella" and "4892" vs "sentinella" and "4.892").
- **Batched decoding is not invariant:** 6 of 40 transcripts changed. The app decodes one stream at a time.
- **Gain:** mean subtraction removes constant gain. The PR README [S] claims that input attenuated by 60 dB still transcribes. **Do not add gain normalization for the ASR.** Gain still changes Silero and the absolute dBFS WordGate.
- **Memory on x86, int8, 8 threads:**

  | Input | Peak RSS |
  |---|---|
  | 25 s | 1.25 GB |
  | 55 s | 1.57 GB |
  | 90 s | 2.14 GB |
  | 180 s | 3.7 GB |
  | 300 s | 4.1 GB |
  | 400 s | 6.5 GB |

  The Pixel 8 is not measured.
- **Moondream [S]:** Photon cuts at pauses using the model's `vad_head` into segments of at most 30 s. The tpmaerk export dropped that head (82k parameters, a side branch, so transcripts are unaffected).

## 3. Hypotheses, ranked by expected impact × confidence

1. **H1, longer decode units.** Replace the 28 s/2 s windows, which reset VAD and cut spans, with longer units:
   - (a) merge adjacent VAD spans, keeping the real audio between them, into units of up to 30 s, as Photon does; or
   - (b) use windows of 60–120 s with VAD-merged units.

   Expected: fewer seam and boundary deletions, plus better normalization statistics on short fragments. This is the largest structural cause of errors still in place and the best-supported change. The cost is memory, and decodes must stay at or below about 60–120 s on the Pixel.
2. **H2, no synthetic padding.** `PARAKEET_V3_REFERENCE` (30 ms context, no zeros) should be at least as good as `WHOLE_SPANS` overall and better on spans under 2 s.
   - Mechanism: the measured feature shift of 0.4–0.9 RMS on short spans.
   - Prior: gocassini on v3 with the reference frontend, 94 vs 107.
   - Expected size: small, with the effect concentrated in the short-span bucket.
3. **H3, merge short spans.** With H1(a), join spans that are under 1 s apart, keeping the real gap audio. Expected to remove the degenerate statistics of short spans (two frames normalize to ±0.707 in every bin).
4. **H4, retry with real audio.** For an empty span, retry with 250–500 ms of recorded context rather than zeros (already true with `RETRY_CONTEXT_MS`); also check that the retry still fires once the tail is removed.
5. **H5, resampler cutoff.** The 6.5–8 kHz band arrives at −1.2 to −32 dB while the mel bank reaches 8 kHz. Raising the cutoff to about 0.475–0.49 (7.6–7.8 kHz) should be neutral to slightly positive for 44.1/48 kHz sources and all app recordings. Expected size: small.
6. **H6, word gate on quiet recordings.** Ultra is gain-invariant but WordGate is not. At −30 to −40 dB, real words are expected to be dropped by the gate rather than by ASR. Fix the gate (relative thresholds), not the gain.
7. **H7, VAD tuning.** Threshold 0.18/0.3/0.5 and min silence 0.25/0.5/1.0 s. Only worth sweeping if H1 still leaves cuts inside VAD spans.
8. **H8, codec.** The cost of Opus 48 kb/s and AAC 32 kb/s versus PCM, and of `MIC` versus `VOICE_RECOGNITION`. The source comparison can only be done on the device.
9. **H9, `vad_head` cut points.** Needs a re-export with subsampler outputs. Defer.

**Guard, regardless of results:** add a hard cap so that no decode can exceed 400 s (5000 encoder frames), well under it in practice. Going over aborts the process.

## 4. Experiment matrix

**Harness:** host option (b): `eval/run.sh` with `eval/src/HostEval.kt`, `build-jni/lib/libsherpa-onnx-jni.so` (dbbeef9c plus `streaming-diarization.patch`) and Ultra INT8 b86784a3, encoder sha256 `f4d06712…3697`. Throughput is about 14.5× real time per job at 2 threads (as the app uses) and about 1.2 GB per job. Plan 8 jobs in parallel for units up to 30 s, and 4 for units of 120 s or more.

**Required driver change (scratch only):**
- Add a `PIPELINE=incremental` mode that drives `IncrementalRecognizer`/`IncrementalTranscript` exactly as `JobAdapters.kt:37` does.
- Add scratch copies of `IncrementalTranscript` and `DecodeSession` with window length, overlap, merge gap and maximum unit as parameters, plus a resampler cutoff parameter.
- Today `HostEval` calls `Parakeet.transcribe` on the whole clip, which is not the production path.
- Log per-job peak memory (`/usr/bin/time -v`) and wall time.

**Data** (pinned; download only these files into fresh directories and read them with `python -I`):
- **D1, FLEURS** `google/fleurs@70bb2e84b976b7e960aa89f1c648e09c59f894dd`:
  - `it_it` test: 864 rows, 658 MB.
  - `en_us` test: 290 MB.
  - Both are native 16 kHz. Reference is the raw transcription column. Each utterance is decoded alone. Estimate about 2–2.5 h of audio per language (not verified).
- **D2, synthetic long-form**, built from D1 `it_it` test:
  - 24 files of about 5 min.
  - Utterances concatenated in id order, with gaps drawn per file from {0.3, 1, 3} s, filled with −60 dBFS pink noise, or with FLEURS leading/trailing room tone where there is any.
  - Reference is the texts joined in order; true boundaries are logged.
  - Total about 2 h. This exercises VAD spans over 28 s, seams and VAD resets.
- **D3, `distil-whisper/tedlium-long-form@ea3a78479fb5`**, test split (285 MB, English). Use for internal evaluation only (CC BY-NC-ND).
- **D4, private** gocassini meeting windows: the 40 × 3 min items in `~/.claude/jobs/1f0c1c89/tmp/asr/` (pcm 16k/48k). The references are v3 FP32 output, so the WER scores agreement with v3, not truth. Use only for a relative ranking on real meeting audio.
- **D5, channel variants of D1-it** (made with ffmpeg; sha256 of every derived file recorded):
  - 48 kHz upsample, then the app resampler at 0.4375 vs 0.49;
  - libopus 48 kb/s with 20 ms frames, and AAC 32 kb/s;
  - attenuation by −20, −30 and −40 dB.

**Conditions**

| ID | Question | Conditions | Data |
|---|---|---|---|
| E0 | Parity | `WHOLE_SPANS` vs `REFERENCE`, `SPEECH` and `WHOLE`, run on host and on Pixel | fleurs-it-short + 30 D1-it utterances |
| E1 | Padding (H2, H4) | 7 conditions, all with `SPEECH` cutting:<br>• tail 500 (production)<br>• tail 250<br>• tail 100<br>• tail 0 with synthetic padding<br>• ctx30, no padding (`REFERENCE`)<br>• ctx100, no padding<br>• `WHOLE` cutting (no VAD) | D1-it + D1-en |
| E2 | Unit length (H1, H3) | 6 conditions, all using the production pipeline path:<br>• (a) 28/2 + `WHOLE_SPANS` (production baseline)<br>• (b) 28/2 + `REFERENCE`<br>• (c) VAD spans merged to ≤ 30 s, gap ≤ 1 s kept, `REFERENCE`<br>• (d) 60/4 windows + (c)<br>• (e) 120/4 windows + (c)<br>• (f) `WHOLE` ≤ 300 s, used as an upper reference | D2, D3, D4 |
| E3 | Resampler, codec, gain (H5, H6, H8) | Best condition from E1/E2 on each D5 variant, with WordGate on and with WordGate off (scored before vs after the gate) | D5 |
| E4 | VAD sweep (H7) | Threshold {0.18, 0.3, 0.5} × min silence {0.25, 0.5, 1.0}, using the best E2 unit | D2 |
| E5 | Pixel | Finalists from E1/E2 plus production | see below |

**E5 on the Pixel:**
- Run `ChunkingComparisonTest`, after first saving `files/chunking-comparison.INT8.json`. Expect the `batterica` assertion to fail.
- Push a 60-utterance D1 subset and two D2 files through a bench test.
- Record decodes of 28, 60 and 120 s: RTF, PSS/VmHWM, and thermal throttling over a 30-minute run.

**Metrics:**
- **WER and CER:**
  - Normalization: NFKC, lowercase, curly apostrophe folded, punctuation stripped, and dot, comma or space between digits removed, so "4.892" equals "4892".
  - Report substitutions, deletions and insertions separately.
- **Casing and punctuation:** a separate error rate, used only to compare host and Pixel.
- **WER by span-length bucket:** <1, 1–2, 2–5, 5–15 and >15 s, using the VAD span each reference word falls into, through the alignment to hypothesis timestamps.
- **Boundary deletion rate:**
  - For D1/D2: deletions among the first 2 and last 2 words of each reference utterance.
  - For D2/D3: errors within ±2 s of each 26 s seam, or of each unit edge.
- **Empty-span count** and how many retries recovered the span.
- **RTF and peak RSS** per condition.
- **Statistics:** paired bootstrap over utterances (or 30 s blocks for long-form), 1000 resamples, 95% CI. Only call a change a win if the CI excludes 0.

**Compute estimate:**
- D1 (about 4.5 h of audio) takes about 2.5 min per condition at 8 in parallel (about 110× real time aggregate, unverified), so E1 is about 20 min.
- E2 is 6 conditions × about 5.5 h of audio at about 4–8 jobs: about 1.5 h.
- E3 is about 30 min, E4 about 40 min, so the host total is about 3–3.5 h.
- Pixel: about 1 h including installs.

## 5. Risks and pitfalls

- **x86 vs arm64 INT8.** Kernels differ (u8s8 with AVX2/VNNI vs sdot/i8mm). Casing and digit grouping are already known to differ (NoteFlowTest.kt:46-50), and they also change on the same host depending on how the audio is cut. Rank conditions on the host and confirm the finalists on the Pixel. Do not treat differences under the bootstrap CI as real.
- **Pseudo-references are biased toward the setup that produced them.**
  - The whole-file Ultra decode used as reference favours `WHOLE`.
  - D4 references favour v3-like output.
  - Base decisions on D1, D2 and D3, which have human references.
- **Normalization:**
  - FLEURS raw text mixes digits and words.
  - TED-LIUM references are lowercase and lack disfluencies.
  - Check 20 alignments by hand before trusting S/D/I.
- **FLEURS clips are trimmed and clean.** Their boundaries are unlike real recordings, so the padding effect on D1 may be understated; D2 and D4 cover that. Ultra may also have been trained on FLEURS or TED-LIUM, which affects absolute numbers but not relative ones.
- **Evidence bias toward the wrong path:** `ChunkingComparisonTest` and `AsrEval` both bypass the production 28 s path. Every decision must be measured through the incremental path.
- **Memory and a hard crash:**
  - 120 s units are about 2.5 GB on x86 at 8 threads; the Pixel 8 with 8 GB and the low-memory killer is not measured.
  - 401 s ends in SIGABRT, not an exception.
  - Keep a code-level cap; recommend at most 60 s per decode until measured on the Pixel.
- **Batching changes output** (6 of 40). Keep one stream per decode, including in the harness.
- **Gain experiments measure VAD and WordGate, not ASR.** Report results before and after the gate, otherwise the cause is misattributed.
- **The reference frontend is not confirmed on the device.** Before E5, confirm that Ultra's metadata turns on `parakeet_reference_frontend` there (log it, or compare a fixture against the host). If it is off, every Ultra decision falls back to stock-frontend behaviour.
- **Verbatim lock:**
  - Every likely change touches locked groups: `SpeechWindows`/`DecodePolicy` (package only), `IncrementalTranscript`, `Resampler` (package only), `WordGate` (package only), `SpeechFeed` and `IncrementalRecognizer`.
  - A "package only" row must first move to a described-edit row in the `groups` table of `scripts/check-verbatim.sh`. Then run `--update` and commit `scripts/check-verbatim.baseline` with the change (CONTRIBUTING.md:176-181).
  - With 0 users there is no migration concern, but the lock exists to keep parity with gocassini. Diverging from the desktop policy (`stt.go:204-213`: preserve span, ctx 30, no tail) should be a recorded decision.
- **Stale docs to fix along with any change:**
  - SpeechWindows.kt:43 (says sherpa gets unresampled audio)
  - SpeechWindows.kt:118 (the prefix-truncation claim)
  - Parakeet.kt:30 (spans "cut when longer than 28 s")
  - docs/notes/feasibility.md:47
- **Scratch state:**
  - The v3 bench JSON is saved under `investigation/prior/`, because running the Pixel bench overwrites `files/chunking-comparison.INT8.json`.
  - The host build in `/home/silvio/dev/sherpa-onnx-nemotron-android/build-host-jni` is commit `1f23907a`, which lacks #4024. Do not use it.