# Feasibility: native Android Cassini

**Feasible on a recent ARM64 phone.** The native viewer is routine app work; on-device ASR quality, memory, thermal behavior and long-recording boundaries are the main uncertainties. This prototype establishes the import → native Parakeet → complete Cassini document → native-viewer path. It produces and opens portable Ogg Opus meetings.

## What the sibling projects establish

`../cassini-format/SPEC.md` and its word schema define the public portable format. A complete artifact is ordinary Ogg Opus at 48 kHz, carrying gzipped/base64url JSON chunks in OpusTags. The manifest identifies speakers, transcript variants, timing/provenance and an `exact-opus-audio-v1` digest. `cassini.words.v1` is the transcript body: flat words with `speaker`, integer `startMs`/`endMs`, and `text`. The Android app reads/writes the portable document and projects a selected body into its native viewer.

The body is in **speaker-turn order**; a reader must not sort it by timestamps. Overlapping speakers can move the clock backwards across turns. Unknown speaker IDs remain visible. The Android reader is tested against all 26 format conformance vectors in `../cassini-format/spec/conformance`, including duplicate/missing chunks, checksums, unsupported versions, multiple transcripts and oversized headers. Decompressed data is bounded; the app reports verified, unverified, stale, damaged or unsupported document states. Payload checks and exact audio checks run independently.

`../gocassini/cassini-viewer/src/core/transcript.ts`, `src/viewer/portable.ts` and the transcript components separate the portable file from the viewer's internal `transcript.words.v1` structure. The viewer adapts the public flat body into its speaker/segment model. A native app can consume the public format directly: reconstruct speaker turns in file order, index timed words separately for playback, and display speaker paragraphs, search, word seeking and active highlights. Its existing display transcripts, annotations, provenance panels and meeting catalog are separate later features. There is no need to embed Svelte in a WebView to implement the playback/transcript interaction.

## Parakeet and Italian

[NVIDIA's model card](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) explicitly includes Italian among 25 languages, with automatic language detection and punctuation/capitalization. Its published Italian WER varies substantially across evaluation datasets; those figures are not a promise for this phone, quantized export, or a conversational recording.

[Sherpa's Android builds](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html) expose the offline transducer through Kotlin/JNI. Its standard CPU provider supports both quantized and floating-point ONNX bundles. CUDA is not an Android option; NNAPI/GPU acceleration for this model is not established by this prototype.

| Bundle | Pinned download size | Practical implication |
| --- | ---: | --- |
| [INT8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78) | 670,478,772 bytes / 639.4 MiB | Small enough for a first on-device experiment. |
| [FP32](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3/tree/1a468a35cbba69418f126de829e75261dea4a4e4) | 2,549,800,429 bytes / 2.37 GiB | Includes a 2.44 GB `encoder.weights` sidecar; downloading just the small `encoder.onnx` does not provide the weights. More RAM and storage are needed. |

Weight size is not peak process memory. ONNX sessions, optimized weights, encoder activations and audio buffers add memory; duration changes the working set. Start with short clips, measure on the target device, and avoid treating a successful small test as proof that hour-long meetings fit.

**Runtime parity matters independently of precision.** `../gocassini/deployment/sherpa/README.md` documents a fork correcting Parakeet v3 feature extraction. Its boundary investigation documents omissions affected by frontend, segmentation and decoder policy, including results where larger windows or padding regress. The Android AAR used here is stock upstream 1.13.7 and lacks Cassini's reference frontend marker/corrections. It uses INT8/FP32 greedy decoding without vocabulary hints. An FP32 run does not automatically recover feature parity with the desktop pipeline.

The prototype converts SentencePiece tokens to words using their timestamps and TDT durations. Punctuation remains in text but cannot extend a word to the next acoustic onset. These are token-derived estimates: no audio energy gate, alignment refinement, or `endsBoundedByAudio` guarantee is claimed.

## Prototype constraints

- Transcription uses whole clips, 200 ms–3 minutes; longer transcription requests fail visibly. Existing documents can be viewed beyond three minutes within the 64 MiB file limit. There is no segmentation or overlap reconciliation yet; longer recordings need further memory and recognition evaluation.
- New recognition produces one unidentified speaker; imported documents display their speaker turns. A mixed recording has no separate participant tracks; diarization needs another model and a validated attribution/alignment pipeline.
- Native platform UI, MediaPlayer for playback, PCM16 WAV parser and MediaExtractor/MediaCodec for compressed import. Original rates go to sherpa's resampler. Decoder timestamps preserve container gaps rather than concatenating sparse audio. Native Opus creation preserves pre-skip and trims EOS padding to the exact recording clock; device tests check duration and speech onset/tail. Broader long-recording and multichannel auditing remains useful.
- Downloads/inference run on a worker thread while the activity stays open. Rotation is handled and the latest completed session restores after reopening or changing the interface language. There is no durable job queue, foreground processing service, inference cancellation, interrupted-download resume, or meeting history. Android can terminate a background process or kill it under native memory pressure.
- Models download via HTTPS from immutable revisions, with checked sizes/hashes and a verified-install marker. Audio is not uploaded. The prototype requires network only for downloading missing weights.
- The interface has complete English/Italian resources, an in-app language picker and Android 13+ per-app language integration. Recording language and interface language remain separate; the current transcript label remains Italian.
- Compile/target API 34 uses the SDK installed in this workspace. Shipping requires a current target SDK, release signing, dependency notices, further accessibility checks and validation on current devices.

## Path to a complete app

1. Compare INT8 and FP32 on the same human Italian clips, recording omissions, recognition errors, seeking accuracy, model-load/decode time and memory. Build the Cassini sherpa fork for Android and compare it with stock using identical audio and weights.
2. Add durable foreground transcription jobs and VAD-guided long-recording segmentation with recorded boundary context. Test continuous speech and silence, preserve the recording clock, and validate seams against listened audio before choosing overlap/reconciliation rules.
3. Expand the completed portable reader/writer into display transcripts, annotations, document history and cleanup. The reader passes the 26 published conformance cases and new outputs pass the independent extractor, audio digest, public schemas and web viewer reader. Unknown metadata and transcript variants are retained on save.
4. Broaden device coverage for native Opus encoding and timed playback. Android has [Opus encoder support from Android 10](https://developer.android.com/media/platform/supported-formats); Android 8–9 conversion needs a native encoder or a higher minimum version. Existing documents remain viewable without an encoder.
5. Add microphone capture using AudioRecord, explicit recording controls/permission and durable foreground recording. Add diarization only if multi-speaker attribution is required; preserve unidentified speakers until attribution is supported.

The reference extractor and packet-digest tools independently check Android output. `scripts/check-portable-document.mjs` also passes the saved file through the sibling web viewer’s actual reader and word adapter.

## Android acceleration update (2026-10-01)

An isolated LiteRT 2.2.0 experiment successfully delegates multilingual Parakeet v3 INT8 to the Pixel 8 GPU and produces Italian output. Full GPU was slower than LiteRT CPU; GPU encoding with CPU decoding showed a small warm-run improvement but higher startup cost and duplicate model allocations. The stock sherpa app remains on CPU. See [the investigation, existing branches and measured limitations](android-acceleration.md).
