# Feasibility: native Android Cassini

> Historical note: this file records work on the 0.1.0 prototype, written from 2026-10-01 to 2026-10-09. Nobody updates it. Its statements about the app can be out of date. For the current app, read [the architecture](../architecture.md) and [the development notes](../development.md).

**A native Android Cassini is possible on a recent ARM64 phone.** The native viewer is usual app work. The main unknown items are the ASR quality on the device, the memory, the temperature behavior and the limits of long recordings. This prototype shows the path import → native Parakeet → complete Cassini document → native viewer. It makes and opens portable Ogg Opus meetings.

## What the sibling projects show

`../cassini-format/SPEC.md` and its word schema define the public portable format. A complete artifact is ordinary Ogg Opus at 48 kHz, with gzipped/base64url JSON chunks in OpusTags. The manifest identifies the speakers, the transcript variants, the timing and provenance, and an `exact-opus-audio-v1` digest. `cassini.words.v1` is the transcript body: a flat list of words with `speaker`, integer `startMs`/`endMs`, and `text`. The Android app reads and writes the portable document and shows a selected body in its native viewer.

The body is in **speaker-turn order**. A reader must not sort it by timestamps. Speakers that overlap can move the clock back between turns. Unknown speaker IDs stay visible. The tests of the Android reader use all 26 format conformance vectors in `spec/conformance` of the pinned `third_party/cassini-format` submodule. These vectors include duplicate and missing chunks, checksums, unsupported versions, more than one transcript and headers that are too large.

The decompressed data has a limit. The app reports the document states verified, unverified, stale, damaged and unsupported. The payload checks and the exact audio checks operate independently.

`../gocassini/cassini-viewer/src/core/transcript.ts`, `src/viewer/portable.ts` and the transcript components keep the portable file separate from the internal `transcript.words.v1` structure of the viewer. The viewer changes the public flat body into its speaker and segment model.

A native app can use the public format directly. It makes the speaker turns again in the sequence of the file, and it indexes the timed words separately for the playback. It shows the speaker paragraphs, the search, the word seek and the active highlights. Its existing display transcripts, annotations, provenance panels and meeting catalog are separate features for later. It is not necessary to put Svelte in a WebView for the playback and transcript interaction.

## Parakeet and Italian

[The model card of NVIDIA](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3) explicitly includes Italian in 25 languages, with automatic language detection and punctuation and capitalization. Its published Italian WER is very different on different evaluation datasets. These figures are not a promise for this phone, for a quantized export or for a recorded conversation.

[The Android builds of sherpa](https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html) give the offline transducer through Kotlin/JNI. Its standard CPU provider supports quantized and floating-point ONNX bundles. CUDA is not an option on Android. This prototype does not show NNAPI or GPU acceleration for this model.

| Bundle | Pinned download size | Practical implication |
| --- | ---: | --- |
| [INT8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tree/2bda32ec70b097a55adaa07d9a7173915b43cc78) | 670,478,772 bytes / 639.4 MiB | The only ASR bundle of the app. |

The size of the weights is not the peak memory of the process. ONNX sessions, optimized weights, encoder activations and audio buffers add memory, and the duration changes the working set. Start with short clips and measure on the target device. A successful small test does not show that meetings of one hour fit.

**The parity of the runtime is important, also without a change of precision.** `../gocassini/deployment/sherpa/README.md` documents a fork that corrects the Parakeet v3 feature extraction. Its boundary investigation documents missing words that come from the frontend, the segmentation and the decoder policy. It includes results where larger windows or padding make the result worse.

The Android AAR of this prototype is stock upstream 1.13.7, without the reference frontend marker and the corrections of Cassini. It uses INT8 greedy decoding without vocabulary hints. The historical [device results](pixel8-results.md) keep the earlier FP32 comparisons. The app does not support FP32 at this time. A change of precision does not give feature parity with the desktop pipeline. Android cuts recordings as the desktop does, but with its own boundary policy, which we measured on the phone (see "Prototype constraints").

The app changes SentencePiece tokens into words with their timestamps and TDT durations. The punctuation stays in the text, but it cannot extend a word to the next acoustic onset. After the decode, the energy gate of the desktop pipeline (ported from `gocassini` `transcribe/stt.go`) removes a word whose own audio is silence or a click. It extends the end of a word over continuing audio, up to the end with the punctuation. The app does no other alignment refinement, and the documents do not claim the `endsBoundedByAudio` guarantee of the desktop.

## Prototype constraints

- Length: the transcription accepts 200 ms–2 hours. A longer request fails, and the user sees the failure. The same occurs for a request whose 16 kHz audio does not fit in the free heap. The app reads the documents as a stream from the disk, without a 64 MiB limit.
- Pieces: the app cuts a recording into pieces and decodes them one after the other on one recognizer. Thus the screen can show the progress, the pace, the time left and the words until that time.
- Speech spans: Silero VAD (with the settings of the desktop: threshold 0.18, 0.5 s minimum silence, with a 16 kHz copy of the audio) finds the speech spans. The app does not decode the silence. It decodes each span whole, with 0.5 s of synthetic silence at the end.
- Long spans: in sherpa-onnx 1.13.7, the 25 s maximum of the detector is not a limit. Spans close a little after it, and with noise they can be much longer. Thus the app cuts a span longer than 28 s at its quietest 30 ms, between 20 and 25 s after the previous cut.
- Cuts: the quietest point can be a closure inside a word. Thus the app decodes each side of a cut with 1 s of the recording after the cut. Then it joins the two decodes at one word that they agree on, the word nearest to the cut (the word alignment of the desktop finds it). The text up to this word comes from the earlier decode, and the text after it comes from the later decode. Thus a word that the two decodes time or split differently is not doubled and not lost.
- Difference from the desktop: this policy is different on purpose. On a runtime without the patch, the desktop cuts spans into 10 s windows with 0.5 s overlap. On the Pixel 8, that left duplicated words at the window seams (see [device results](pixel8-results.md)). The preferred policy of the desktop, whole spans with recorded context and no synthetic silence, is validated only with its patched frontend.
- Without the detector: without the 630 KB detector model, the app uses the full recording as one span and cuts it at quiet points in the same way. The window plan of the desktop is ported and tested, but only the device comparison uses it. The app accepts recordings up to 2 hours. We evaluated the accuracy over long meetings on the host, against desktop transcripts.
- Speakers: a new recognition gives one unidentified speaker. Imported documents show their speaker turns. A mixed recording has no separate participant tracks. Diarization needs a different model and a validated pipeline for attribution and alignment.
- Media: a native platform UI, MediaPlayer for the playback, a PCM16 WAV parser, and MediaExtractor/MediaCodec for compressed imports. The original rates go to the resampler of sherpa. The decoder timestamps keep the gaps of the container and do not join sparse audio. The native Opus creation keeps the pre-skip and trims the EOS padding to the exact recording clock. Device tests examine the duration and the speech onset and tail. More audits of long recordings and of audio with more than one channel are still useful.
- Threads and lifetime: the downloads and the inference operate on a worker thread while the activity stays open. The app handles rotation. After a new start or a change of the interface language, the app shows the latest completed session again. There is no durable job queue, no foreground processing service, no cancel of the inference and no resume of an interrupted download.
- Library: the local notes library keeps the recordings and the selected transcripts, with the playback state of each note. The microphone capture operates in a foreground service (streaming Opus on Android 10+, AAC on 8–9) and continues in the background. The transcription does not. Android can stop a background process, or kill it when the native memory is low.
- Downloads: Parakeet downloads through HTTPS from fixed Hugging Face revisions, with checked sizes and hashes and a marker for a verified installation. The speech detector comes from a release asset of sherpa-onnx on GitHub, which its maintainer can replace. Its size and SHA-256 are pinned, so the app refuses a changed file and cuts at quiet points. The app does not upload audio. It uses the network only to download missing models: Parakeet on request, and the detector after it, or one time in the background on an installation that is older than the detector.
- Languages: the interface has complete English and Italian resources, a language picker in the app and the per-app language integration of Android 13+. The recording language and the interface language stay separate. The label of the current transcript is still Italian.
- Build: compile and target API 34 use the SDK that is installed in this workspace. A release needs a current target SDK, release signing, dependency notices, more accessibility checks and a validation on current devices.

## Path to a complete app

1. Evaluate INT8 on Italian clips of human speech: missing words, recognition errors, seek accuracy, the time to load the model and to decode, and the memory. Build the Cassini sherpa fork for Android, and compare it with stock with the same audio and weights.
2. Add durable transcription and diarization jobs that can resume, with partial results. Cuts with the VAD cover recordings up to 2 hours. Test continuous speech and silence, and validate the span boundaries against audio that a person listened to. A later evaluation is for a future live transcription with Nemotron.
3. Extend the completed portable reader and writer to display transcripts, annotations and a cleanup of the library storage. The reader passes the 26 published conformance cases. New outputs pass the independent extractor, the audio digest, the public schemas and the reader of the web viewer. A save keeps unknown metadata and transcript variants.
4. Test native Opus encoding and timed playback on more devices. Android has [Opus encoder support from Android 10](https://developer.android.com/media/platform/supported-formats). The conversion on Android 8–9 needs a native encoder or a higher minimum version. Without an encoder, the user can still view existing documents.
5. Durable recording (foreground service, pause for audio focus, crash recovery, 2 h) and Nemotron diarization are done. Still to do: in-process Opus decoding for speed, and processing that can resume.

The reference extractor and the packet-digest tools examine the Android output independently. `scripts/check-portable-document.mjs` also sends the saved file through the real reader and word adapter of the sibling web viewer.

## Android acceleration update (2026-10-01)

An isolated LiteRT 2.2.0 experiment delegates the multilingual Parakeet v3 INT8 to the GPU of the Pixel 8 and gives Italian output. The full GPU was slower than the LiteRT CPU. A GPU encoder with a CPU decoder was a little faster in warm runs, but it had a higher start cost and duplicate model allocations. The stock sherpa app stays on the CPU. See [the investigation, the existing branches and the measured limits](android-acceleration.md).
