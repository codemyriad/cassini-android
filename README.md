# Cassini Android prototype

Native Kotlin app: import audio, transcribe locally with **Parakeet TDT 0.6B v3**, display clickable timed words, play/search the recording, and save/open **complete Cassini `.opus` documents** containing audio, `cassini.words.v1` timed words, speakers, provenance and integrity metadata. Italian is the selected transcript language; Parakeet automatically recognizes among its supported languages rather than accepting a forced Italian decoder setting.

New installations default to **Automatic**, preferring **FP32** (2.37 GiB) when the device has enough memory and storage, otherwise **INT8** (640 MiB). Settings retain explicit precision choices. This resource policy avoids quantization when practical; it is not an Italian accuracy benchmark. Both use sherpa-onnx 1.13.7 on CPU, greedy decoding, two threads, and the stock frontend. Weights download from a pinned Hugging Face revision into private app storage and are SHA-256 checked before installation. After that, audio processing is offline. Models are installed separately; switching precision preserves each download.

Completion shows elapsed time and **audio duration / elapsed time**: 60 seconds processed in 30 seconds is **2× realtime**. “Ready” includes audio decoding, recognition and Cassini packaging; Info shows recognition-only speed. Recognition time is also stored in the selected variant's processing provenance, so it survives portable save/open. Older documents without that measurement do not invent one. Automatic currently requires a 64-bit process, at least 7 GiB physical RAM, 3 GiB available RAM, no Android low-memory signal, and enough space for remaining FP32 downloads plus 512 MiB. This is a conservative heuristic, not a per-device calibration.

The interface supports **English and Italian**, follows the phone language by default, and can be changed in **Settings → Interface language**. On Android 13+ it also integrates with Android's [per-app language settings](https://developer.android.com/guide/topics/resources/app-languages). Both translations are bundled for offline switching; changing the interface language preserves the recording, transcript and playback position, and does not change the transcript's language label.

The native tape-deck UI keeps playback/seek controls fixed at the bottom, with 10-second skips, sentence paragraphs, active-word/search highlights and a clear-search button. During playback the transcript smoothly scrolls to keep the active word visible. Seeking with the slider or 10-second buttons also reveals that word while paused; ordinary paused reading leaves the page in place. A dedicated Android settings screen has grouped preference rows, current language/model summaries, installation status and standard back navigation. Downloads and failures use localized messages. Imported recordings and documents are copied into private app storage. The latest document and playback position restore without relying on the original provider or a separate transcript JSON. Documents display speaker turns and offer a transcript chooser when several variants are present; **Info** shows processing details. Trust warnings distinguish unverified, stale, damaged and unsupported documents while retaining audio playback.

## Build and install

Requires JDK 17, Android SDK platform/build tools 34, and an ARM64 phone with Android 8+ (x86_64 is also packaged for emulator experiments). Set `ANDROID_HOME` or create `local.properties` with `sdk.dir=/path/to/Android/Sdk`.

```sh
./scripts/setup.sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n org.cassini.android/.MainActivity
```

The AAR is downloaded separately and verified by `setup.sh`; weights are downloaded on the phone. Neither is committed. Gradle uses its normal dependency repositories. This is a debug APK for local testing, not a Play Store release.

## Try it

1. Use Automatic or choose INT8/FP32 in **Settings → Transcription model**, then download the selected model once if needed. Keep the app open during download/inference.
2. Choose an audio file of **at most 3 minutes** and at most 64 MB. PCM16 WAV, MP3, M4A/AAC and Ogg Opus are the tested import paths. FLAC is routed through Android's decoder but has not been tested here. WAV supports 16-bit PCM mono/stereo; decoded sample rates can be 8–96 kHz.
3. Tap **Transcribe**. Words appear as native sentence paragraphs. Tap a word to seek/play; use the fixed playback controls or search field to inspect it.
4. Tap **Save Cassini** to choose a destination for one `.opus` file containing audio and transcript. Open it again using **Open file**, or Android’s **Open with → Cassini**. Opening an existing document needs no model download or transcription.
5. Existing sessions from version 0.2 are converted on their first save without running Parakeet again. The current session then points at the complete document.

Cassini is the stored document, not an export adapter. New transcription automatically encodes mono Opus at 48 kHz / 48 kb/s (or retains an existing valid Opus stream), embeds chunked payloads in OpusTags, and verifies the exact packet digest and sample count before adopting the document. A new transcription of a verified document adds a default variant and keeps previous variants. **Save Cassini** copies the full document unchanged, preserving unsupported variants, extensions, attachments and tags as well as known metadata. Unsupported display/annotation features are retained but not rendered.

Encoding uses Android’s platform Opus encoder, available from Android 10; older devices can open/save existing Cassini documents and tag an existing valid Ogg Opus recording. The app reports a localized error when conversion requires a missing encoder. Playback/reading still supports Android 8+.

There is no microphone recording, new speaker diarization, meeting library, or background service yet. New speech recognition labels a single unidentified speaker; imported document speaker labels are displayed. Transcription is limited to 3 minutes, whereas opening documents is bounded by file size (64 MiB), not duration. Header and uncompressed payload limits are 8 MiB and 16 MiB; oversized or unsupported bodies remain unavailable. Retranscription is disabled for stale/unverified/damaged documents so it cannot silently rebind old words to different audio. Saving such a document preserves it unchanged.

Private `files/documents/` holds owned source/document files; atomic `files/session.json` holds only the document reference, selected variant and app preferences/playback state. The portable manifest/body is authoritative on reopen. Legacy `latest.words.json` and `latest.processing.json` remain diagnostic ASR caches, not document storage. Previous owned files are retained; there is no history browser or storage cleanup UI yet.

## Device checks and supplied sample

Prepare the regression fixtures (one attributed Google FLEURS recording converted to several codecs) and the supplied YouTube excerpt:

```sh
python3 scripts/fetch-smoke-audio.py  # requires ffmpeg
./scripts/fetch-youtube-audio.sh     # uses uvx yt-dlp + ffmpeg
./scripts/device-smoke.sh int8 youtube
./scripts/device-smoke.sh fp32 youtube
# Longer viewer/seek check: same video, 00:28–03:28.
./scripts/fetch-youtube-audio.sh 180
./scripts/device-smoke.sh int8 youtube-long
```

The supplied sample is [il Pericolo Invisibile](https://www.youtube.com/watch?v=UmZwQf5TV3c), **00:28–00:58**. Extraction produces exactly 480,000 mono PCM16 samples at 16 kHz, with a 30.000-second duration. The optional three-minute excerpt is **00:28–03:28**, exactly 2,880,000 samples, saved as `.tools/cassini-italian-28-208.wav`. Audio/downloads are local ignored test artifacts, not distributed in this repository. `fetch-smoke-audio.py` records the FLEURS source, pinned revision, reference text and CC-BY-4.0 attribution alongside its local fixture.

`device-smoke.sh` installs the app and test APK and invokes instrumentation through adb, retaining model downloads. Avoid Gradle's `connectedDebugAndroidTest` on an installation you want to preserve: its managed device-test cleanup can uninstall the app and erase its data. Inference tests download the selected model if necessary. They verify actual JNI inference, basic Italian content on the FLEURS sample, and valid word timestamps; they do **not** establish ASR accuracy. Decoder checks compare duration and speech onset across WAV, MP3, AAC and Opus. Portable-document checks verify platform encoding duration/speech onset/tail, native open/save/reopen, byte-for-byte preservation of multiple variants/extensions, and actual Parakeet → sealed document creation. An interface test switches English → Italian → English while checking transcript/session restoration, playback position and search; it restores the previous interface language and session afterward.

Device outputs are `files/device-smoke.INT8.words.json`, `files/device-smoke.FP32.words.json` and corresponding `.metrics.json` files:

```sh
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.metrics.json
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.words.json > transcript.words.json
```

JVM tests also run all 26 published Cassini conformance vectors when `../cassini-format` is checked out. The conformance test is explicitly skipped if that sibling is absent; synthetic integrity/payload/round-trip tests always run. Cross-check a saved document independently:

```sh
python3 ../cassini-format/tools/cassini-extract.py DOCUMENT.opus --check
python3 ../cassini-format/tools/cassini-opus-digest.py DOCUMENT.opus
# Node 24+, with ../gocassini checked out: use the actual web viewer reader/adapter.
node scripts/check-portable-document.mjs DOCUMENT.opus
```

See [the feasibility assessment](docs/feasibility.md) for format/viewer compatibility, limitations and the path to a full app.
Measured runs on the connected Pixel 8 are in [the device results](docs/pixel8-results.md): about 7 seconds / 1.02 GiB peak RSS for INT8 and 19.4 seconds / 2.60 GiB for FP32 on the supplied 30-second clip.

## Dependencies and attribution

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx), Apache-2.0, with ONNX Runtime, MIT, in the upstream Android AAR. Packaging their complete notices is required before distributing a release.
- [NVIDIA Parakeet v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3), CC-BY-4.0 weights; ONNX bundles published by the sherpa maintainer. Model revision, sizes and SHA-256 hashes are pinned in `ModelStore.kt`.
- [Google FLEURS](https://huggingface.co/datasets/google/fleurs), CC-BY-4.0, used only for locally fetched device regression fixtures.
