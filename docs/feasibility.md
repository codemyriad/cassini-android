# Feasibility: native Android Cassini

**Feasible on a recent ARM64 phone.** The native viewer is routine app work; on-device ASR quality, memory, thermal behavior and long-recording boundaries are the main uncertainties. This prototype establishes the import → native Parakeet → timed-word-body → native-viewer path. It does not yet produce or open complete portable meetings.

## What the sibling projects establish

`../cassini-format/SPEC.md` and its word schema define the public portable format. A complete artifact is ordinary Ogg Opus at 48 kHz, carrying gzipped/base64url JSON chunks in OpusTags. The manifest identifies speakers, transcript variants, timing/provenance and an `exact-opus-audio-v1` digest. `cassini.words.v1` is the transcript body: flat words with `speaker`, integer `startMs`/`endMs`, and `text`. This prototype implements that body.

The body is in **speaker-turn order**; a reader must not sort it by timestamps. Overlapping speakers can move the clock backwards across turns. Unknown speaker IDs remain visible. A full Android reader should use the format conformance vectors in `../cassini-format/spec/conformance`, including duplicate/missing chunks, checksums, unsupported versions, multiple transcripts and oversized headers. Bound decompressed data and report audio/metadata trust states separately.

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

- Whole clips, 200 ms–3 minutes; longer imports fail visibly. There is no segmentation or overlap reconciliation yet; longer recordings need further memory and recognition evaluation.
- One unidentified speaker. A mixed recording has no separate participant tracks; diarization needs another model and a validated attribution/alignment pipeline.
- Native platform UI, MediaPlayer for playback, PCM16 WAV parser and MediaExtractor/MediaCodec for compressed import. Original rates go to sherpa's resampler. Decoder timestamps preserve container gaps rather than concatenating sparse audio. Import/playback origin and Opus pre-skip need broader mechanical auditing before long-recording claims.
- Downloads/inference run on a worker thread while the activity stays open. Rotation is handled and the latest completed session restores after reopening or changing the interface language. There is no durable job queue, foreground processing service, inference cancellation, interrupted-download resume, or meeting history. Android can terminate a background process or kill it under native memory pressure.
- Models download via HTTPS from immutable revisions, with checked sizes/hashes and a verified-install marker. Audio is not uploaded. The prototype requires network only for downloading missing weights.
- The interface has complete English/Italian resources, an in-app language picker and Android 13+ per-app language integration. Recording language and interface language remain separate; the current transcript label remains Italian.
- Compile/target API 34 uses the SDK installed in this workspace. Shipping requires a current target SDK, release signing, dependency notices, further accessibility checks and validation on current devices.

## Path to a complete app

1. Compare INT8 and FP32 on the same human Italian clips, recording omissions, recognition errors, seeking accuracy, model-load/decode time and memory. Build the Cassini sherpa fork for Android and compare it with stock using identical audio and weights.
2. Add durable foreground transcription jobs and VAD-guided long-recording segmentation with recorded boundary context. Test continuous speech and silence, preserve the recording clock, and validate seams against listened audio before choosing overlap/reconciliation rules.
3. Implement portable Ogg Opus packing: encode mono Opus at 48 kHz, compute the canonical compressed-packet digest, assemble the manifest/speakers/provenance and payload chunks, and write OpusTags with correct Ogg lacing, sequence numbers and CRCs. Validate outputs with the independent readers in `cassini-format` and the web viewer. Android has [Opus encoder support from Android 10](https://developer.android.com/media/platform/supported-formats); supporting Android 8–9 encoding would need a native encoder or a higher minimum version.
4. Add the portable reader and its conformance adapter; present trust errors while keeping ordinary audio playable. Extend this native prototype into speaker-turn paragraphs, multiple transcripts, search/navigation and provenance.
5. Add microphone capture using AudioRecord, explicit recording controls/permission and durable foreground recording. Add diarization only if multi-speaker attribution is required; preserve unidentified speakers until attribution is supported.

The reference Python packer (`../cassini-format/tools/cassini-pack.py`) is useful for cross-checking a native writer. Its input requires a `speakers` array in addition to the body and does not automatically preserve this prototype's language/provenance, so feeding the exported JSON straight to it is insufficient.
