## Ultra and the reference frontend: how they change audio preprocessing

All files I wrote are under `/tmp/claude-1000/-home-silvio-dev-cassini-android/ae1a675a-1b65-49f6-846b-36b2e07f7d5d/scratchpad/investigation/frontend-stats/`. Results are in `out/` and the scripts in `scripts/`; `reffe.py` is a numpy copy of the frontend. I edited no repository files.

The measured runs used the investigation build's `sherpa-onnx-offline`, which carries the Ultra detection from dbbeef9c. The model was the tpmaerk int8 export at `~/dev/qwen-asr/models/parakeet-ultra-int8`; its encoder sha256 is `f4d06712…` and matches b86784a3. Everything ran on an x86 CPU, not on the Pixel 8.

**Tags:** [M] means I measured it here. [C] means I read it in code. [S] means a published or third-party statement I did not verify.

### 1. What PR #4024 changes compared with the stock path [C]
- **Selection.** The new frontend is used only when all of these hold (`offline-recognizer-transducer-nemo-impl.h:692-695`, `offline-transducer-nemo-model.cc:228-245`):
  - the encoder `url` is v3 or one of four exact Ultra strings;
  - `feat_dim == 128`;
  - `sampling_rate == 16000`;
  - normalization is `per_feature`.
- **Buffering.** All samples are held in `samples_` and features are computed once, in `GetFrames()` (`offline-stream.cc:141-145, 227-228`).
- **Frontend math** (`offline-stream.cc:361-421`):
  - Preemphasis 0.97 runs over the whole waveform, and the first sample is left unchanged.
  - 200 zeros are added on each side, so a 400-point symmetric Hann window is centered at i·160.
  - FFT size 512, 128 Slaney mel bins from 0 to 8 kHz, `log(P + 2^-24)`.
  - Frame count n = floor(samples/160). If n < 2 the frontend returns nothing.
  - Per-bin mean and **sample** standard deviation (n−1), plus 1e-5, over **all n frames**. Accumulation is in float64.
- **Stock path, for comparison:**
  - kaldi `povey` window, `snip_edges=false`, high_freq −400 (7.6 kHz), `remove_dc_offset` true by default (the NeMo recognizer sets it false at `impl.h:688`);
  - normalization with **population** variance in float32 (`math.cc:134-151`).
- **Resampling.** Input that is not 16 kHz is passed through sherpa's `LinearResample` (cutoff 0.99·0.5·min rate, filter width 6) before the reference frontend (`offline-stream.cc:146-165`). The app already passes 16 kHz to `ParakeetDecoder.kt:56-57`.
- **Empty inputs.** A stream with fewer than 2 frames (fewer than 320 samples) gets an empty result and is removed from the batch (`impl.h:599-604`). The app skips chunks shorter than 20 ms only when `syntheticPadding=false` (`SpeechWindows.kt:181`).
- **Gain.** Mean subtraction removes any constant gain exactly, except near the 2^-24 floor. The PR README states that English audio attenuated by 60 dB, and by ×0.001 with Ultra, still transcribes correctly (`README.md:135-138, 196-200`). Treat this as a claim backed by the PR author's runs; I did not repeat it. **Gain normalization or AGC is therefore not needed.** Time-varying AGC is still not undone.

### 2. How padding moves the normalization statistics [M]
Digital zeros become `log(2^-24) = -16.64` in every bin. Speech log-mel has a mean of about −11.4 (fleurs) and −10.5 (youtube), with per-bin standard deviations of 1.3 to 3.6. After normalization a zero frame lands at a median of −1.65 to −1.97, close to the 1st percentile of speech frames (about −2.0). So the padding looks like very quiet audio, not like an outlier.

The cost is the shift it causes on the real speech frames, and that grows with the share of the input that is padding:

| Span | Pad | Bin mean shift | Std ratio | RMS shift of speech frames |
|---|---|---|---|---|
| 0.4 s | +0.5 s zeros | −3.15 | 1.29 | **0.94** |
| 0.8 s | +0.5 s zeros | −2.00 | 1.16 | 0.59 |
| 1.5 s | +0.5 s zeros | −1.33 | 1.17 | 0.41 |
| 3 s | +0.5 s zeros | −0.76 | 1.14 | 0.27 |
| 6 s | +0.5 s zeros | −0.40 | 1.08 | 0.15 |
| 12 s | +0.5 s zeros | −0.20 | 1.04 | 0.08 |
| 12 s | +0.1 s zeros | −0.04 | 1.01 | 0.015 |

- On a 13.6 s utterance:
  - 0.5 s of zeros at the head, at the tail, or 0.25 s at each end all shift speech frames by the same RMS of 0.072. Only the total amount of padding matters, not where it goes.
  - A 0.5 s tail of −60 dBFS white noise shifts them by RMS 0.043.
  - 30 ms of recorded context on each side shifts them by RMS 0.010.

**What this means for span padding:** synthetic silence affects short spans most (VAD fragments of 0.3 to 2 s), which are exactly the spans it was meant to help. A 2-frame input normalizes every bin to ±0.707, so statistics from spans shorter than a few hundred milliseconds are degenerate.

**Decoding effect [M]:** I made 40 units from youtube-long.wav (27 sentences, 13 clauses of 0.66 to 2.4 s) and decoded each one in its own process. The pseudo-reference is the 180 s whole-file decode; there is no ground truth.

| Variant | Pseudo-WER |
|---|---|
| tight | 1.32% |
| `ctx30` (`PARAKEET_V3_REFERENCE`) | 1.32% |
| 0.5 s zero tail (`WHOLE_SPANS`) | 1.51% |
| 0.25 s zeros at head and tail | 1.51% |
| 0.5 s −60 dB noise tail | 1.70% |

- No variant produced an empty result or dropped a final word. The differences are single-word swaps such as `uovo`/`uova`, `film`/`fin`, `dotato`/`adottato`.
- **On this sample the padding policy has no measurable effect with Ultra.**
- The ctx250 and ctx500 numbers (4.5% and 18.7%) are artefacts. This speech is continuous, so recorded context pulls in neighbouring words that the metric counts as insertions. Do not use them.

### 3. Long inputs [M] unless tagged
- **Hard limit at 400.0 s.** One whole decode of 400 s works (1104 words, last timestamp 399.44 s). At **401 s, ONNX Runtime aborts the process with SIGABRT**: `Attempting to broadcast an axis by a dimension other than 1. 13 by 5013`. The positional-encoding table is fixed at 5000 encoder frames of 80 ms; the Ultra config has `max_position_embeddings: 5000` [C]. On Android this would kill the process, not throw. The app's caps stay far below it: `MAX_SAFE_MS` 55 s, `WHOLE_MAX_MS` 60 s, `WHOLE_SPAN_MS` 28 s.
- **No prefix truncation with Ultra and the reference frontend.** Whole decodes of 25, 55, 90, 120, 180 and 300 s all emit words up to the end (last timestamps 24.88, 54.88, 89.76, 119.92, 179.84, 299.92 s). The app comment at `SpeechWindows.kt:118` ("long merged spans make Parakeet emit only a prefix") came from the stock runtime. **It does not reproduce here.**
- The whole 180 s decode differs from the concatenated 0.18-threshold Silero spans by only 4 word substitutions over about 420 words.
- **Memory (x86, 8 threads, int8):**

  | Input | Max RSS |
  |---|---|
  | 25 s | 1.25 GB |
  | 55 s | 1.57 GB |
  | 90 s | 2.14 GB |
  | 180 s | 3.7 GB |
  | 300 s | 4.1 GB |
  | 400 s | 6.5 GB |

  This is full attention, roughly quadratic. The Pixel 8 numbers are not measured.
- **Batching is not invariant.** Decoding the 40 tight units in one `DecodeStreams` call changed 6 of 40 transcripts compared with one stream per call. Each mode is deterministic on its own (a repeat matched; a reversed batch order matched). The app decodes one stream at a time, so this does not affect it today; keep it that way or measure first.
- **NeMo and NVIDIA [S]:** the v3 card says full attention handles "up to 24 minutes … (on A100 80GB)", and local attention "up to 3 hours" via `change_attention_model(self_attention_model="rel_pos_local_attn", att_context_size=[256, 256])`. That is 256 frames, about 20.5 s, on each side. The card points to chunked streaming with `chunk_secs=2, left_context_secs=10, right_context_secs=2`. sherpa's `export_onnx.py` exports the default full-attention encoder (`scripts/nemo/parakeet-tdt-0.6b-v3/export_onnx.py:76`), so local attention would need a re-export. I did not check how NeMo's chunked script normalizes features per buffer.

### 4. What Moondream says for Ultra [S]
- Model card: "Long audio is segmented by the model itself: the weights carry a small voice-activity head on the encoder's subsampler … Photon uses it to cut recordings at pauses into segments of at most 30 seconds. No external VAD model is needed."
- The blog says the same: chunks of "at most 30 seconds".
- All accuracy numbers are "Photon on an NVIDIA GPU". The TED-LIUM long-form WER is 1.94% (v3: 2.71%).
- Neither page gives a maximum length, attention type or preprocessing details.

**The `vad_head` [M, from the safetensors header]:**
- `proj` 1024→128 (k=1), `ctx` 128→128 (k=5), `out` 128→1, F16, about 82k parameters.
- It reads the 1024-dimensional output of the subsampler (80 ms frames) and is a side branch. The tpmaerk README says the six `vad_head.*` tensors were dropped.
- **Dropping it cannot change the transcripts.** What it loses is Moondream's own way of choosing pause points. Using it would need an export that also outputs the subsampler activations; I have not checked the subsampler output name.

### 5. Segmentation facts in the app and sherpa [C]
- A sherpa Silero segment starts `2·512 + min_speech` samples (164 ms at 0.1 s) before detection (`voice-activity-detector.cc:97`). It ends at `tail − min_silence`, which drops the closing silence (`:112`).
- At threshold 0.18, spans in dense speech ran to 35.8, 30.0, 28.1 and 54.2 s [M]. `max_speech_duration` does not close a span, as `SpeechWindows.kt:64` already says.
- Current app policy:
  - `WHOLE_SPANS` decodes the span plus a 0.5 s zero tail (`SpeechWindows.kt:28`).
  - Spans over 28 s are cut at the quietest point between 20 and 25 s, with 1 s of recorded context (`:52-57`).
  - The empty-result retry uses 500 ms of context (`DecodeSession.kt:82-88, 112`).

### 6. Hypotheses to test, with ground truth and on the Pixel 8
1. **H1:** `PARAKEET_V3_REFERENCE` (30 ms context, no synthetic pad) is at least as good as `WHOLE_SPANS` overall, and better on spans shorter than 2 s, because 0.5 s of padding shifts speech frames by RMS 0.4 to 0.9 there. Test with the FLEURS-it and en test splits, cut by Silero at threshold 0.18, and bucket WER by span length.
2. **H2:** padding in proportion to the span, or no more than 10% of it (for example 0.1 s), is no worse than 0.5 s on any bucket. Test: tail 0 / 100 / 250 / 500 ms.
3. **H3:** if a span decodes to nothing, retrying with more recorded context (250 to 500 ms of real room tone) beats retrying with synthetic zeros. Room tone is the −60 dB noise case, which shifts the statistics about 40% less than zeros.
4. **H4:** longer decodes help Ultra. Merge adjacent VAD spans up to 30 s, as Photon does, or raise `WHOLE_SPAN_MS` to 30 to 60 s, and compare WER, seam errors and phone memory against per-span decoding. The truncation that motivated short windows does not reproduce. Keep a hard cap well below 400 s; 60 s is a reasonable ceiling for memory.
5. **H5:** with very short spans, normalization gets better if a short span is decoded together with its neighbours as one input. Concatenate spans that are less than 1 s apart, keeping the real audio between them.
6. **H6:** the dropped vad_head gives better cut points than Silero at 0.18 for long recordings. This needs an export with the subsampler output.
7. **H7:** batched decoding changes outputs (6 of 40 measured). If batching is ever added for throughput, measure WER first.
8. **Guard:** add a code-level check that no decode exceeds 400 s (5000 encoder frames), because exceeding it aborts the process.

Sources:
- [moondream/parakeet-ultra](https://huggingface.co/moondream/parakeet-ultra)
- [Moondream blog: Parakeet Redux and Ultra](https://moondream.ai/blog/introducing-parakeet-redux-and-ultra)
- [nvidia/parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
- [NeMo ASR inference docs](https://docs.nvidia.com/nemo/speech/latest/asr/inference.html)
- [parakeet-tdt-0.6b-v2 discussion #3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v2/discussions/3)
- [NbAiLab nb-sami-asr-north-parakeet](https://huggingface.co/NbAiLab/nb-sami-asr-north-parakeet)