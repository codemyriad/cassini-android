# Development notes

The build commands are in the [README](../README.md). Runtime downloads, weights, audio fixtures and build outputs stay out of commits.

## Device checks

Use direct adb instrumentation to retain app data and downloaded models. Gradle’s managed connected-test cleanup can uninstall the app and erase them.

```sh
python3 scripts/fetch-smoke-audio.py  # requires ffmpeg
./scripts/fetch-youtube-audio.sh     # uses uvx yt-dlp + ffmpeg
./scripts/device-smoke.sh youtube
# Optional third argument selects test classes.
./scripts/device-smoke.sh fleurs org.cassini.android.LibraryTest
# Three-minute viewer/seek check.
./scripts/fetch-youtube-audio.sh 180
./scripts/device-smoke.sh youtube-long
```

Unlock the phone first. The script wakes it and dismisses an unsecured keyguard, but fails early if authentication is still required. Set `ANDROID_SERIAL` when several devices are connected. To verify CI’s actual APK, set `CASSINI_APK=/absolute/path/to/downloaded.apk`; the script detects debug/release and builds the matching instrumentation APK. To use both binaries from CI, also set `CASSINI_TEST_APK=/absolute/path/to/test.apk`. The `cassini-android-device-tests` artifact contains that test APK. Kotlin’s internal method names differ across variants, so a debug test APK cannot reliably exercise a release app.

The supplied sample is [il Pericolo Invisibile](https://www.youtube.com/watch?v=UmZwQf5TV3c), 00:28–00:58 (30 seconds), or 00:28–03:28 (three minutes). Local FLEURS fixtures record the pinned revision, reference text and CC-BY-4.0 attribution. These audio files are not distributed here.

Device tests check JNI inference, timestamps, codec decoding, complete-document integrity, microphone pause/resume, catalogue recovery and interface-language restoration. They do not establish ASR accuracy. Test rules restore the previous catalogue/session and remove only their own generated documents.

CI installs its built app and matching test APK on Android 8, 10 and 14 (API 26, 29 and 34) x86_64 emulators. All three run playback/settings, AAC microphone pause/resume and library playback, interruption recovery, legacy session upgrades, and encoder capability checks. Android 14 also requires successful Opus encoding and decoding with preserved duration and speech timing. The Android 10 Google APIs image lacks an Opus encoder; its check requires the explicit missing-encoder failure. These checks require no models. Settings checks verify language persistence and that the removed model selector and live transcription setting are absent. CI runs 11 checks on API 26/29 and 12 on API 34. CI fails if a selected check is skipped or the expected count changes, and saves instrumentation output and logcat. Release publication waits for all three emulators. Native inference and the complete recording/transcription flow still need the separate device checks above.

```sh
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.metrics.json
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.words.json > transcript.words.json
```

## Portable files

JVM tests run all 26 published vectors when `../cassini-format` is checked out. CI fetches a pinned revision and explicitly requires the fixtures, so these checks cannot silently skip. Synthetic integrity and round-trip tests always run. Use `-PcassiniConformanceDir=/path/to/spec/conformance -PrequireCassiniConformance=true` to require an explicit fixture checkout locally.

```sh
python3 ../cassini-format/tools/cassini-extract.py DOCUMENT.opus --check
python3 ../cassini-format/tools/cassini-opus-digest.py DOCUMENT.opus
# Node 24+, with ../gocassini checked out: use the web viewer’s reader.
node scripts/check-portable-document.mjs DOCUMENT.opus
```

A complete Cassini `.opus` contains the Opus audio stream plus timed words, speakers, processing provenance and integrity metadata in OpusTags. The diagnostic `*.words.json` files are only word bodies. Save Cassini copies the entire document unchanged, including unsupported variants, extensions and attachments. Retranscription adds a variant and preserves previous ones.

Android uses stock sherpa-onnx 1.13.7, greedy CPU decoding and two threads. It does not use the desktop runtime’s modified Parakeet frontend; see [feasibility](feasibility.md). Recordings are cut at speech pauses with the desktop’s Silero VAD settings and decoded span by span (`Parakeet.kt`, `SpeechDetector.kt`, `SpeechWindows.kt`, `SeamMerge.kt`, `WordGate.kt`). The window arithmetic, seam alignment and word gate are ported from `gocassini` `transcribe/stt.go` with their tests; the detector loop is ported and its tests are new. The detector model (`silero_vad.onnx`, 630 KB, pinned by SHA-256) is fetched into `files/vad/` after the Parakeet model, or once per process in the background on an installation that predates it, never during a transcription. A failed fetch is only logged: without the detector the recording is cut at quiet points. A document records how it was cut in `x-segmentation` of its speech-to-text provenance. To compare cutting policies against whole-recording decoding on a device: `./scripts/device-smoke.sh cutting org.cassini.android.ChunkingComparisonTest` (several minutes; results in `files/chunking-comparison.INT8.json`).

## Recording

Microphone capture uses mono AAC at 48 kHz / 96 kbps in a private M4A file. Pause excludes paused time from the recording clock. **Done** finalizes and catalogues the raw recording, then starts the INT8 batch pipeline when the model is installed. Recording itself needs no model. Leaving the screen saves the audio without starting background transcription; recognition failure retains it for retry.

Batch processing displays draft words after each decoder call, including the final filtered words while the complete portable Opus file is being created. Transcription during capture has been removed ahead of a separate Nemotron implementation.

```sh
./scripts/device-smoke.sh fleurs org.cassini.android.LibraryTest
```

Upgrades ignore retired `fp32` and `modelChoice` session fields and remove them on the next session save. Existing result precision and portable provenance remain historical facts. App startup removes only `files/parakeet-v3-fp32/` and the obsolete live-recording preference. INT8, VAD and speaker downloads, saved WAV/M4A recordings, and portable documents are retained. FP32-only installations need the INT8 download for new transcription.

## Local storage

Owned recordings/documents live in private `files/documents/`. Atomic `session.json` stores the current document reference, selected variant and playback position. Atomic `library.json` stores stable note IDs, dates, per-note state and a disposable text cache for search. The portable document is authoritative when opened.

An imported file is stored under the SHA-256 of its bytes. Opening the same file again reuses that copy and returns to its note with its playback position and selected variant. This applies to copies imported from this version on: earlier imports and documents created by transcription have random names, so opening the original of one of those starts a new note. A recreated screen returns to the note it was showing and imports its launch intent only when no note can be found for it. `session.json` records which screen wrote it last and is used when that is the recreated screen, because work that finished while the screen was stopped makes it newer than Android’s saved state (unless that last save failed). Otherwise the note is found in the catalogue by its saved ID, or by its file reference when it has none. One case is not recovered: if work finished after the state was saved and another screen then replaced `session.json`, the screen returns to the note it showed earlier; the newer note or revision is still in the library.

`library-migrated` marks recovery of older retained documents, grouped by meeting ID. Notes saved before that recovery keep their state and receive any missing search text, and no further card is made for other revisions of their meetings. A screen with no note does not write `session.json`, and a screen replaces a `session.json` it did not write only after that session is in the catalogue, so a session from before the library is never dropped. Previous owned revisions remain on disk. Legacy `latest.words.json` and `latest.processing.json` are diagnostic caches.

## Beta releases

Update `versionName`, increase `versionCode`, and refresh [release notes](release-notes.md). A matching `vVERSION` tag triggers the same build/test/lint checks, then publishes the APK, checksum, notices and native source archive. Packaging checks both supported 64-bit ABIs and each native library’s 16 KB ELF/ZIP alignment. These checks catch build regressions; runtime behavior still needs testing on actual devices. Tag builds require the repository’s `ANDROID_BETA_KEYSTORE` secret; missing signing material fails the build.

The signing key matches the existing development installation. Keep it stable for these beta updates. The beta release build disables debugging; the key comes from the earlier debug installation to allow upgrades. A production signing identity can be chosen for a future stable release. Never commit it.

## Speaker identification

Speaker identification is a separate action on a completed transcript, including imported documents and older notes. It does not repeat ASR. Choose automatic clustering (threshold 0.5) or a known count from 1 to 8. The anonymous labels do not identify people; errors and overlapping speech need listening checks.

The optional models are [pyannote segmentation 3.0](https://huggingface.co/pyannote/segmentation-3.0), converted to INT8 ONNX, and [3D-Speaker ERes2Net-Base](https://modelscope.cn/models/iic/speech_eres2net_base_sv_zh-cn_3dspeaker_16k). They total 41,134,267 bytes (about 40 MiB), stored in `files/speaker-models/`. `DiarizationModels.kt` pins their sizes, hashes and sources. Downloads use verified temporary files and require explicit consent; ASR model downloads do not install them. License texts and notices ship in the APK.

Stock sherpa-onnx 1.13.7 runs segmentation, embeddings and clustering on CPU with two threads. ASR and diarization share a native-model lease, so a cancelled call cannot overlap a new model load. The native callback reports embedding progress only after segmentation finishes, and its return value does not abort computation. Cancel returns to the library immediately and discards the result; the ongoing native call finishes and releases its own models. Destroying the viewer also cancels publication. No partially attributed variant is saved.

Word labels use the greatest overlap with a speaker's union of time intervals, or the nearest interval in a gap. Ties are deterministic. Text, word order and timestamps are retained. A mixed recording supplies one label per recognized word; this does not recover simultaneous voices or reproduce desktop attribution from separate participant tracks.

Each pass appends a portable word variant with new speaker IDs, preserving the audio digest, original variants and imported speaker names. The new variant's `provenance.speechToText` entry retains the ASR record and adds `x-derivedFromTranscript`, `x-sourceAttribution` and `x-speakerDiarization` (models, hashes, clustering settings and elapsed time). The document-wide attribution record describes the desktop cross-track audit and is preserved unchanged. Speaker clustering does not claim that audit ran. The export is a complete portable Opus document, not a word-body JSON file.

Fetch the pinned speaker models for native experiments with `python3 scripts/fetch-diarization-models.py`. `ANDROID_SERIAL=<serial> scripts/diarization-poc.sh` runs the public four-speaker fixture through `scripts/device-smoke.sh`, preserving app data. Existing optional two-voice/real-conversation diagnostics are in `DiarizationPocTest`; private audio stays in `.tools/` and is never included in an APK. Full UI/native checks are `SpeakerIdentificationTest#actualModelsAddAPlayableVariantWithoutChangingRecognitionOrAudio` and `#cancellingNativeIdentificationRetainsTheOriginalVariant`. They install the speaker models and do not need Parakeet. CI runs the three consent/availability checks without models.
