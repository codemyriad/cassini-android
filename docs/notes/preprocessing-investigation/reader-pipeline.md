**Audio path in the Cassini Android app, from file to words (branch `parakeet-ultra`, read-only investigation)**

No repository files were edited. Scratch scripts are in `/tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/` (`resampler_response.py`, `padding_features.py`). Tags used below: [code] = read in source, [measured] = I ran it, [doc] = a claim in the repo docs.

## 1. End-to-end path in production
Imports and recordings both go through `TranscriptionJob` (engine/.../job/TranscriptionJob.kt:259-382).

1. **Decode** (`AudioDecoder.stream`, engine/.../decode/AudioDecoder.kt:42-52):
   - Content URIs are first copied to the cache; file URIs are read in place.
   - RIFF/WAVE goes to `WavDecoder`, which accepts PCM16 only (`format == 1 && bits == 16`, WavDecoder.kt:41), 8–96 kHz, 1–2 channels.
   - Everything else goes through MediaExtractor + MediaCodec, which outputs PCM16 or float (AudioDecoder.kt:91-259).
   - Ogg Opus length comes from the EOS granule (:98, :215). MP3 has its LAME delay and padding trimmed via `Mp3Gapless` (:110-125, :201).
   - Container gaps are kept as zeros (`stream.silence`, :226).
2. **Downmix and scaling:** plain channel mean, int16 / 32768 (AudioDecoder.kt:229-236; WavDecoder.kt:75-76). There is no gain, normalisation, DC removal, AGC or dither anywhere in the app [code].
3. **Resample** to 16 kHz once, with `StreamingResampler` (core/.../audio/Resampler.kt:205). ASR, VAD and diarization all share this one 16 kHz stream (`Limits.ASR_RATE`, AudioLimits.kt:110).
   - Filter: Hann-windowed sinc, cutoff `0.4375 × min(rate, 16k)` = 7 kHz, 12 zero crossings, unit gain at each output (:148-157).
   - [measured, Python port, 48 k and 44.1 k inputs]: 0.00 dB at 6 kHz, −1.23 dB at 6.5 k, −6.02 dB at 7 k, −17.6 dB at 7.5 k, −32.3 dB at 7.8 k; ≥ −64 dB at 9 k and above.
   - The reference mel bank reaches 8 kHz, so its top bins see 6.5–8 kHz attenuated. 16 kHz input is passed through untouched.
4. **Incremental windowing** (`IncrementalTranscript`, core/.../asr/IncrementalTranscript.kt:25-26, 104-132):
   - Fixed 28 s windows (448,000 samples) with 2 s of recorded overlap, so the stride is 26 s.
   - Each full window is decoded independently. Windows are joined with `SeamMerge.splice(words, found, offsetMs, 2000)` (:72).
   - `finish()` decodes the last partial window (:138).
   - `IncrementalRecognizer` reuses one `ParakeetDecoder` and calls, per window, `Parakeet.transcribeWithDecoder(window, decoder, detectorModel, DecodePolicy.WHOLE_SPANS, cutting(detectorModel))` (engine/.../asr/IncrementalRecognizer.kt:37).
5. **Per window, with VAD installed** (`Cutting.SPEECH`, Parakeet.kt:84-86):
   - A new native `SileroDetector` is created and reset for every 28 s window, so the VAD has no state across windows. A span that crosses a window edge is cut there.
   - `SpeechFeed.feed` (SpeechFeed.kt:44-72) feeds exact 512-sample frames, drains completed segments every 5 s, then flushes. The last partial frame is zero-padded and its length returned as `paddedTailMs`. A full 28 s window is exactly 875 × 512, so that padding is 0.
   - Each span goes to `DecodeSession.speech`.
   - Without VAD (`Cutting.QUIET`): the whole window is one span (Parakeet.kt:87).
6. **`DecodeSession`** (core/.../asr/DecodeSession.kt):
   - `speech()` calls `quietPieces` only if `preserveSpan` is set (:57). Spans ≤ 28 s (`WHOLE_SPAN_MS`) are never cut, and a span inside a 28 s window can never be longer. **So the quiet-cut machinery (`CUT_FROM`/`CUT_TO`/`CUT_CONTEXT`) is effectively dead in production.**
   - `piece()` (:77-95): `contextMs = 0` under `WHOLE_SPANS`, so the source is exactly the VAD span.
   - If a span decodes to nothing, it retries once with ±500 ms of recorded context (`RETRY_CONTEXT_MS`, :82-88, :112) and keeps only words overlapping the span.
   - `segment()` (:31-50) runs `SpeechWindows.windows(..., vadSegment = true, WHOLE_SPANS)`, which gives one window and one chunk per span (chunks are capped at 55 s, `MAX_SAFE_MS`). Under `WHOLE_SPANS` each chunk is `headPad = 0`, `tailPad = 8000` zeros (0.5 s) (SpeechWindows.kt:181-185).
7. **`ParakeetDecoder.decode`** (engine/.../asr/ParakeetDecoder.kt:51-64):
   - Builds `headPad + span + tailPad` as a new FloatArray and passes it to `acceptWaveform(…, 16000)`.
   - One `createStream` and one `decode` per chunk.
   - `TokenWords.timedWordsFromTokens` turns tokens into words, using sherpa timestamps and TDT durations. The extension cap is the end of the word's last piece, punctuation included (TokenWords.kt:20-67).
8. **Word placement**:
   - `WordGate.offsetDecoderWords` places words on the recording clock.
   - `clampWordsToTimelineEnd` turns a word stamped inside the 0.5 s synthetic tail into a zero-length word at the real end (WordGate.kt:142-171).
   - After each window: `WordGate.finalizeTranscriptWords(window audio, words, paddedTailMs)` (Parakeet.kt:90). This is the energy gate: words are dropped unless the margin-widened interval has peak ≥ −60 dBFS, RMS ≥ −80 dBFS and ≥ 5 ms at ≥ −66 dBFS. Kept word ends are extended over continuing audio up to the cap (WordGate.kt:129-139, 185-249).
   - **The gate uses absolute dBFS levels**, so any gain change upstream changes which words survive.

## 2. `DecodePolicy` and cutting modes
`DecodePolicy` defaults (SpeechWindows.kt:15-19): `windowMs` 10000, `overlapMs` 500, `graceMs` 500, `minTerminalMs` 5000, `tailPaddingMs` 500, `headPaddingMs` 0, `contextMs` 0, `preserveSpan` false, `syntheticPadding` true.

**Named policies:**
- `DecodePolicy()`: the desktop stock-runtime policy (10 s windows, 0.5 s overlap, 0.5 s tail). Used only in the bench as `speech-10s-tail`.
- `WHOLE_SPANS = DecodePolicy(preserveSpan = true)` (:28): whole span plus 0.5 s synthetic tail. **This is what the app uses.** It is hard-coded at IncrementalRecognizer.kt:37 and is the default of `Parakeet.transcribe` (Parakeet.kt:47).
- `PARAKEET_V3_REFERENCE = DecodePolicy(preserveSpan = true, contextMs = 30, tailPaddingMs = 0, syntheticPadding = false)` (:22): whole span plus 30 ms of recorded context, no synthetic silence, and chunks under 20 ms are skipped (:181). Used only in the bench (`speech-span-context`).
- Bench-only ad hoc: `DecodePolicy(preserveSpan = true, tailPaddingMs = 0)`. `tools/asr-eval/AsrEval.kt:41` uses `WHOLE_SPANS.copy(contextMs = $CASSINI_CONTEXT_MS)`.

**`Parakeet.Cutting`** (Parakeet.kt:22-33):
- `WHOLE`: one decode, at most 60 s (`WHOLE_MAX_MS`, :39).
- `FIXED`: 15 s windows with 0.5 s overlap (SpeechWindows.kt:140). Non-VAD decodes under 10 s get a 0.5 s tail (:149-153).
- `QUIET`: used when no VAD is installed.
- `SPEECH`: used when VAD is installed.
- Choice: `cutting(detectorModel)` (:33).

**Caveat on the evidence:** `ChunkingComparisonTest` (bench/.../ChunkingComparisonTest.kt:62-70) and `AsrEval` both call `Parakeet.transcribe` on the whole recording. They do not go through the production 28 s / 2 s incremental windowing with per-window VAD reset and per-window gate, so neither measures what the app actually does.

## 3. VAD (Silero)
- **Config** (SpeechFeed.kt:29-36; SileroDetector.kt:21-33): threshold 0.18, min silence 0.5 s, min speech 0.10 s, max speech 25 s, window 512 at 16 kHz, 1 thread, CPU, drain every 80,000 samples.
- **Model:** `silero_vad.onnx` from the k2-fsa `asr-models` release, pinned by sha256 (ModelCatalog.kt:107-115).
- **Sherpa 1.13.7 behaviour** [code, sherpa checkout]:
  - `neg_threshold = max(0.18 − 0.15, 0.01) = 0.03` (silero-vad-model.cc:137-142). While speech is on, any probability above 0.03 counts as speech.
  - A segment starts `2 × 512 + 1600` samples (164 ms) before the trigger (voice-activity-detector.cc:96-99).
  - A segment ends at `Tail − minSilenceSamples`. It carries roughly no trailing context; the closing silence is removed (:110-121).
  - Once the buffer passes 25 s, the threshold becomes 0.90 and min silence 0.1 s (:51-53, 228-229). The span is not force-closed.
- **Mapping back to the recording:** start rounded down, end rounded up and clamped (SpeechFeed.kt:92-96).

## 4. Sherpa `OfflineRecognizer` config
**App side** (ParakeetDecoder.kt:28-42):
- `FeatureConfig(sampleRate = 16000, featureDim = 128, dither = 0f)`. The Kotlin `FeatureConfig` has only these three fields; there is no normalize field.
- `modelType = "nemo_transducer"`, `provider = "cpu"`, `numThreads = 2`, `decodingMethod = "greedy_search"`. No hotwords and no blank penalty.

**Runtime:** AAR `1.13.7-dbbeef9c` (gradle/libs.versions.toml:40).

**Native side, `PostInit`** (offline-recognizer-transducer-nemo-impl.h:243-276):
- `feature_dim` and `nemo_normalize_type` come from the encoder metadata.
- `parakeet_reference_frontend` turns on when all of these hold: `IsParakeetV3OrUltra()` (URL metadata matches v3 or the known Ultra identities, and `feat_dim == 128`; offline-transducer-nemo-model.cc:223-245), `sampling_rate == 16000`, and `normalize_type == "per_feature"`.
- I did not confirm on the device that the Ultra export's metadata actually turns it on.

**Reference frontend** (offline-stream.cc:361-420):
- `floor(n / 160)` frames, nothing returned when fewer than 2.
- Preemphasis 0.97 on the waveform, then 200 zeros on each side.
- 400-sample Hann window, 512-point FFT, 128 librosa mels from 0 to Nyquist.
- `log(x + 2^-24)`.
- Per-bin normalisation over all frames, including any synthetic padding: sample variance, plus 1e-5.
- Streams with 0 frames get an empty result (impl.h:180-185).

## 5. Synthetic padding vs the reference frontend
Under `WHOLE_SPANS`, every decode has 50 frames of exact zeros, which sit at log(2^-24) = −16.64, and those frames are included in the per-bin mean and variance.

[measured: numpy port of the reference frontend on `fixtures/audio/fleurs-it-short.wav`, adding 0.5 s of zeros] Mean |Δz| over the real frames, where z is the normalised feature value:

| Span length | Mean \|Δz\| | Max \|Δz\| |
|---|---|---|
| 1 s | 0.20 | 1.94 |
| 2 s | 0.16 | 0.50 |
| 4 s | 0.12 | 0.93 |
| 8 s | 0.08 | 0.27 |
| 15.8 s | 0.04 | 0.10 |

The mean log-mel of the real frames on this clip is −11.9 to −16.1. A phone microphone with a higher noise floor should make the zero frames stand out more (inferred, not measured). Any effect on WER is unmeasured.

The only accuracy data, docs/notes/pixel8-results.md:105-155 [doc], is for **Parakeet v3 INT8 on the stock frontend**, via whole-recording `Parakeet.transcribe`, on 4 clean clips:
- 3-minute clip vs YouTube captions: whole spans + tail 8; 10 s windows 13; spans with no tail 10; spans + 30 ms context 11.
- FLEURS (WAV / M4A): whole spans + tail 2 / 2; 10 s windows 1 / 1; spans + context 5 / 3.

The docs say themselves that this needs redoing on Ultra with the reference frontend (docs/development.md:469).

## 6. Live recording
There is no live transcription.
- **API 29+ with a platform Opus encoder:** `AudioRecord(MediaRecorder.AudioSource.MIC)` at 48 kHz mono PCM16, platform Opus at 48 kb/s, 20 ms frames (OpusCapture.kt:44-60, 161).
- **Otherwise:** `MediaRecorder` with `MIC`, AAC in MP4 at 48 kHz and 32 kb/s (AacCapture.kt:33-38).
- No `VOICE_RECOGNITION` or `UNPROCESSED` source, and no NoiseSuppressor or AGC calls. Whatever the platform does to `MIC` audio applies.
- After stop, the file is handed to the same job (RecordingController.kt:403-414), so the recognizer hears the lossy Opus/AAC decode. An Opus recording is used directly as the sealed audio (TranscriptionJob.kt:285).

## 7. Tunable knobs (current value, file:line)
**Decode and resampling**
- Resampler: cutoff fraction 0.4375 (7 kHz), 12 zero crossings, 1024-step table (Resampler.kt:149-152).
- `Limits`: `ASR_RATE` 16000, `MAX_TRANSCRIBE_MS` 36 h (AudioLimits.kt:109-110).
- Decoder resume back-off: 1 s, doubling (AudioDecoder.kt:106).

**Incremental windowing**
- Window 28 s, overlap 2 s (IncrementalTranscript.kt:25-26).

**VAD**
- Threshold 0.18, min silence 0.5 s, min speech 0.10 s, max speech 25 s, window 512, `DRAIN_EVERY` 5 s (SpeechFeed.kt:29-36).
- Effective `neg_threshold` 0.03 (sherpa).

**Policy and windows**
- Policy in use: `WHOLE_SPANS` (IncrementalRecognizer.kt:37), with the `DecodePolicy` fields as listed above.
- `MAX_SAFE_MS` 55000, `TAIL_PAD_MIN_SECONDS` 10, `WHOLE_SPAN_MS` 28000, `CUT_FROM`/`CUT_TO` 20000/25000, `CUT_CONTEXT_MS` 1000, quiet frame/step 30/10 ms (SpeechWindows.kt:47-59).
- `FIXED` 15 s / 0.5 s (:140). `RETRY_CONTEXT_MS` 500 (DecodeSession.kt:112). `WHOLE_MAX_MS` 60 s (Parakeet.kt:39).

**Seams and gate**
- `SeamMerge`: duplicate tolerance 400 ms, singleton tolerance 200 ms, shift consistency 100 ms (SeamMerge.kt:16-20).
- `WordGate`: `MIN_PEAK` 0.001, `MIN_RMS` 0.0001, `MIN_ACTIVE` 0.0005, `MIN_ACTIVE_MS` 5, pre/post margins 100/200 ms, scan window 10 ms, gap tolerance 200 ms (WordGate.kt:129-139).

**Recognizer**
- Sample rate 16000, feature dim 128, dither 0, 2 threads, greedy search, CPU (ParakeetDecoder.kt:29-41).
- VAD: 1 thread (SileroDetector.kt:31).

**Capture**
- Opus: 48 kHz, 48 kb/s, `MIC`. AAC: 48 kHz, 32 kb/s, `MIC`.

## 8. Files locked by `scripts/check-verbatim.sh`
`--summary` currently shows every group as ok.
- **"package only"** (any body change fails CI): `Resampler.kt`, `SpeechWindows.kt` (`DecodePolicy`, `WHOLE_SPANS`/`REFERENCE` and all window constants), `SeamMerge.kt`, `WordGate.kt`, `TranscriptionProgress.kt`, `CheckpointData.kt`, `OggOpus.kt`, `CassiniPayload.kt`, `SpeakerMerges.kt`.
- **Verbatim with a listed set of permitted edits:**
  - `AudioDecoder.kt` + `WavDecoder.kt`
  - `AudioLimits.kt` + `PcmBuilder.kt`
  - `PcmSource.kt`
  - `IncrementalTranscript.kt` (holds the 28 s / 2 s window)
  - `SpeechFeed.kt` + `SileroDetector.kt` (the VAD config)
  - `Parakeet.kt` + `IncrementalRecognizer.kt` + `ParakeetDecoder.kt` (recognizer config) + `DecodeSession.kt`
  - `TokenWords.kt` (with `Transcript.kt`)
  - The capture files: `MicrophoneCapture`, `OpusCapture`, `AacCapture`, `CaptureFactory`

  Changing any of these means extending that group's allowed-edit text.
- **Not locked:** `ModelCatalog`, `TranscriptionJob`, `JobAdapters`, `RecordingController`, bench, tools.
- **Procedure** (CONTRIBUTING.md:176-181):
  1. Read the group's diff: `scripts/check-verbatim.sh <Group>`.
  2. Add the new edit to the `groups` table in the script.
  3. Run `scripts/check-verbatim.sh --update <Group>`.
  4. Commit `scripts/check-verbatim.baseline` together with the change.

  A "package only" group cannot be accepted with `--update`. Its row in the table must first move to a described-edit row.

## 9. Things that look wrong or stale
- The `SpeechWindows` KDoc says "Android hands sherpa the samples unresampled" (SpeechWindows.kt:43). That is false now: decoded audio is always 16 kHz (AudioDecoder.kt:253).
- docs/notes/feasibility.md:47 still says original rates go to sherpa's resampler.
- The `SPEECH` provenance text says spans are "cut … when longer than 28 s" (Parakeet.kt:30). That cannot happen inside 28 s windows.
- The VAD state reset every 26 s, and spans being cut at window edges, are untested for accuracy. They are reconciled only by a word-level splice over 2 s.