# Development notes

The build commands are in the [README](../README.md). Runtime downloads, weights, private or YouTube-derived audio and build outputs stay out of commits. The committed test inputs are the CC-BY-4.0 FLEURS clip in [`fixtures/`](../fixtures/README.md), the golden checkpoint in `core/src/test/resources/checkpoint/` and the conformance vectors in the `third_party/cassini-format` submodule.

## Modules

| Module | What it holds | Build needs |
|---|---|---|
| `:core` | Plain Kotlin on the JVM, package `org.cassini.core`: the portable document format, audio arithmetic, recognition windows and seams, speaker assignment and voiceprint selection, model pins and downloads (`ModelCatalog`, `ArtifactFetcher`), the checkpoint codec (`CheckpointState`) | JDK 17 and the `third_party/cassini-format` submodule |
| `:engine` | Android library, package `org.cassini.engine`: the sherpa-onnx wrappers (`ParakeetDecoder`, `SileroDetector`, `SortformerStream`, `VoiceprintExtractor`), the MediaCodec decoder, microphone capture, the Opus encoder and its JNI library (`libcassini_opus.so`, built with CMake), `NativeBudget` | Android SDK, NDK 28.2.13676358, CMake 3.22.1, and `scripts/setup.sh` (AAR and libopus source) |
| `:data` | Android library, package `org.cassini.data`: the Room catalogue, the DataStore settings and the file repositories. At this time it has only the module skeleton. | Android SDK |
| `:ui` | Android library, package `org.cassini.ui`: the Compose design system and the stateless screens. It has Roborazzi screenshot tests (see "Screenshot tests"). | Android SDK |
| `:app` | The 0.1.0 Activities and services on top of `:core` and `:engine`, with thin adapters (`InstalledModels`, `ProcessingCheckpoint`, `FailureMessages`) | everything `:engine` needs |

The target dependency graph is in `docs/architecture.md`, section 2: `app -> ui`, `app -> data -> core` and `app -> engine -> core`. At this time `:app` does not use `:data` or `:ui`. `docs/architecture.md` describes the target layout.

`tools/asr-eval/` holds a host program that runs the app's speech recognition pipeline on PCM files. Gradle does not build it; its README says how to run it.

## Engine tests without the native toolchain

The `:core` module is plain Kotlin on the JVM: the portable document format, audio arithmetic, recognition windows and seams, speaker assignment and voiceprint selection, the model pins and download code, and the checkpoint codec. Its tests need JDK 17 and the `third_party/cassini-format` submodule. They need no Android SDK, NDK, native AAR, libopus source or device:

```sh
git submodule update --init
JAVA_HOME=/path/to/jdk-17 ./gradlew --configure-on-demand :core:test
```

`--configure-on-demand` stops Gradle from configuring `:app` and `:engine`. Configuring them needs the Android SDK, and when the SDK has no NDK, the engine's native build makes the Android Gradle plugin download NDK 28.2.13676358 (more than 1 GB) before any task runs. With an Android SDK that has NDK 28.2.13676358 installed, plain `./gradlew :core:test` works as well; `scripts/setup.sh` is not needed for it. The conformance vectors are required: without the submodule the tests fail, they do not skip. This command was checked on 2026-10-09 in a fresh clone with only the README in `native/m2`, no `.tools/`, no `local.properties` and no `ANDROID_HOME`: every `:core` test ran and none was skipped.

## What changed in the verbatim code

The engine code moved from the 0.1.0 app into `:core` and `:engine` with a package rename and a short list of edits. The last field of each row in the `groups` table of `scripts/check-verbatim.sh` gives the permitted edits for that file. To see every remaining difference from the 0.1.0 code at the phase-0 base commit:

```sh
scripts/check-verbatim.sh --summary   # changed line counts per file group
scripts/check-verbatim.sh Parakeet    # the diff of one group
```

The script removes `package` and `import` lines and the `internal` modifier before it compares, and it ignores changes in the amount of white space (`--exact` shows them). It needs the base commit in the clone, so a shallow clone needs `git fetch --unshallow` first.

The script is also a gate, and CI runs it in the static-checks job. It fails when a group whose allowed edit is "package only" shows any difference, and when the changed lines of any other verbatim group differ from the reviewed ones in `scripts/check-verbatim.baseline` (one SHA-256 per group). When you change kept engine code on purpose, read the group's diff, make sure that the `groups` table in `scripts/check-verbatim.sh` permits every edit (if an edit is new, add it to that table), then record it and commit the baseline with the change:

```sh
scripts/check-verbatim.sh Parakeet            # read the diff
scripts/check-verbatim.sh --update Parakeet   # record it as reviewed
```

Restructured groups (`Models`, `ProcessingCheckpoint`) are shown for reference and not checked.

## Screenshot tests

The `:ui` tests draw Compose content with Robolectric and compare it with committed PNG files (goldens) in `ui/src/test/screenshots/`. The root `ciTest` task includes `:ui:verifyRoborazziDebug`, which fails when a picture changes. We record and compare the goldens on Linux x86_64 only. On other systems, font rendering can change the pixels.

On macOS or Windows, do not run `ciTest` or `verifyRoborazziDebug`. Run the tests without the pixel comparison and let CI do the pixel check:

```sh
./gradlew :core:test :engine:testDebugUnitTest :data:testDebugUnitTest :ui:testDebugUnitTest :app:testDebugUnitTest
```

`:ui:testDebugUnitTest` draws the screens but does not compare them with the goldens. Do not record or commit goldens from these systems.

If you change a screen on purpose, update its goldens:

```sh
./gradlew :ui:recordRoborazziDebug   # write new goldens
git diff --stat ui/src/test/screenshots
```

Examine each changed PNG, then commit the goldens with the change. `:ui:compareRoborazziDebug` writes the actual pictures and the differences to `ui/build/outputs/roborazzi/`. If the check fails in CI, CI uploads that directory as the `roborazzi-diffs` artifact.

The Compose accessibility checks (`enableAccessibilityChecks()`) do not operate under Robolectric. Thus they are not in the `:ui` tests. The device flow tests of `:app` will do them on an emulator.

## Device checks

Use direct adb instrumentation to retain app data and downloaded models. Gradle’s managed connected-test cleanup can uninstall the app and erase them.

```sh
./tools/fetch-youtube-audio.sh       # optional YouTube clips; uses uvx yt-dlp + ffmpeg
./scripts/device-smoke.sh youtube
# Optional second argument (after the audio sample) selects test classes.
./scripts/device-smoke.sh fleurs org.cassini.android.LibraryTest
# Opt-in recovery check after force-stopping the app mid-recording:
adb shell am instrument -w -r -e class org.cassini.android.RecoveryCheckTest org.cassini.android.test/androidx.test.runner.AndroidJUnitRunner
# Three-minute viewer/seek check.
./tools/fetch-youtube-audio.sh 180
./scripts/device-smoke.sh youtube-long
```

Unlock the phone first. The script wakes it and dismisses an unsecured keyguard, but fails early if authentication is still required. Set `ANDROID_SERIAL` when several devices are connected. To verify the APK CI tested, set `CASSINI_APK=/absolute/path/to/downloaded.apk`; the script detects debug or `releaseTest` (a non-debuggable APK that R8 did not minify) and builds the matching instrumentation APK. It refuses an R8-minified APK. A `releaseTest` build without a supplied key is signed with `~/.android/debug.keystore`, so it installs over a debug build. To use both binaries from CI, also set `CASSINI_TEST_APK=/absolute/path/to/test.apk`. The `cassini-android-device-tests` artifact contains the APK under test and its test APK. Kotlin’s internal method names differ across variants, so a debug test APK cannot reliably exercise a release app. The minified `release` APK cannot be instrumented at all, because R8 removes app and Kotlin library members that only the tests call. `releaseTest` is the release build (same signing, not debuggable) without R8. On tags CI instruments `releaseTest`, then installs the published release APK over it and checks that it launches (`scripts/launch-check.sh APK` does the same by hand).

The supplied sample is [il Pericolo Invisibile](https://www.youtube.com/watch?v=UmZwQf5TV3c), 00:28–00:58 (30 seconds), or 00:28–03:28 (three minutes). The YouTube clips are not distributed here; tests that need them skip or fail without them. The FLEURS clip is committed under `fixtures/audio/` with its pinned revision, reference text and CC-BY-4.0 attribution, and the androidTest assets include it.

Device tests check JNI inference, timestamps, codec decoding, complete-document integrity, microphone pause/resume, catalogue recovery and interface-language restoration. They do not establish ASR accuracy. Test rules restore the previous catalogue/session and remove only their own generated documents.

CI runs the static checks (Gradle wrapper validation, detekt, actionlint, shellcheck, ruff and the verbatim diff) in a job of their own, in parallel with the build. Another job runs `:core:test` with only a JDK: no `scripts/setup.sh`, and the runner's Android SDK hidden. The build job fetches the pinned prebuilt AAR (`scripts/setup.sh --prebuilt-only`) and fails at once when it cannot; it never falls back to a source build. CI then installs its built app and matching test APK on Android 8, 10 and 14 (API 26, 29 and 34) x86_64 emulators. All three run playback/settings, AAC microphone pause/resume and library playback, interruption recovery and encoder capability checks. Android 14 also requires successful Opus encoding and decoding with preserved duration and speech timing. The Android 10 Google APIs image lacks an Opus encoder; its check requires the explicit missing-encoder failure. These checks require no models. Settings checks verify language persistence and that the removed model selector and live transcription setting are absent. CI runs 11 checks on API 26/29 and 12 on API 34. CI fails if a selected check is skipped or the expected count changes, and saves instrumentation output and logcat. Release publication waits for the static checks, the `:core` job, the build and all three emulators. A weekly job, also available from a manual run, rebuilds the sherpa-onnx AAR from the pinned source, builds and tests the app with it, and records its SHA-256 beside the pinned release build's. A mismatch raises a warning annotation; it does not fail the job. Native inference and the complete recording/transcription flow still need the separate device checks above.

```sh
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.metrics.json
adb exec-out run-as org.cassini.android cat files/device-smoke.INT8.words.json > transcript.words.json
```

## Portable files

The `:core` JVM tests run every published vector from the `third_party/cassini-format` submodule, pinned to the revision this app conforms to. A missing checkout fails the tests instead of skipping them (`git submodule update --init`). Use `-PcassiniConformanceDir=/path/to/spec/conformance` to run another checkout's vectors, for example `../cassini-format` at a newer revision. Synthetic integrity and round-trip tests always run.

```sh
python3 third_party/cassini-format/tools/cassini-extract.py DOCUMENT.opus --check
python3 third_party/cassini-format/tools/cassini-opus-digest.py DOCUMENT.opus
# A sibling ../cassini-format checkout has the same tools at a newer revision.
# Node 24+, with ../gocassini checked out: use the web viewer’s reader.
node scripts/check-portable-document.mjs DOCUMENT.opus
```

A complete Cassini `.opus` contains the Opus audio stream plus timed words, speakers, processing provenance and integrity metadata in OpusTags. The diagnostic `*.words.json` files are only word bodies. Save Cassini copies the entire document unchanged, including unsupported variants, extensions and attachments. Retranscription adds a variant and preserves previous ones.

Android uses sherpa-onnx 1.13.7 rebuilt with Sortformer diarization (see [native runtime](native-runtime.md); speech recognition code is unchanged), greedy CPU decoding and two threads. It does not use the desktop runtime’s modified Parakeet frontend; see [feasibility](feasibility.md). Recordings are cut at speech pauses with the desktop’s Silero VAD settings and decoded span by span (`engine/.../asr/Parakeet.kt` and `SileroDetector.kt`; `core/.../asr/SpeechFeed.kt`, `SpeechWindows.kt`, `SeamMerge.kt`, `WordGate.kt`). The window arithmetic, seam alignment and word gate are ported from `gocassini` `transcribe/stt.go` with their tests; the detector loop is ported and its tests are new. The detector model (`silero_vad.onnx`, 630 KB, pinned by SHA-256) is fetched into `files/vad/` after the Parakeet model, or once per process in the background on an installation that predates it, never during a transcription. A failed fetch is only logged: without the detector the recording is cut at quiet points. A document records how it was cut in `x-segmentation` of its speech-to-text provenance. To compare cutting policies against whole-recording decoding on a device: `./scripts/device-smoke.sh cutting org.cassini.android.ChunkingComparisonTest` (several minutes; results in `files/chunking-comparison.INT8.json`).

## Recording

On Android 10 and later, `RecordingService` (a foreground service of type `microphone`, with a notification and a partial wake lock) captures mono 48 kHz audio with `AudioRecord` and streams it to Ogg Opus at 48 kb/s (about 21.6 MB per hour) in `files/documents/<id>.rec.opus`. Pages are flushed every second and the file is synced every 5 s. Recording continues with the screen off, after **Home** or with the recording screen closed; **Done** in the app or the notification stops it. Losing audio focus (for example a call) pauses it. Recordings stop at the 2 h cap (`Limits.MAX_RECORDING_MS`) or when free space drops below 200 MiB, with a warning 5 minutes before the cap. Android 8 and 9 have no platform Opus encoder: there the service records AAC at 32 kb/s in an MP4 file, which a crash loses because its index is written on stop.

At startup, `RecoveryScanner` repairs any `*.rec.opus` that has no end-of-stream page and is not being recorded: it truncates the file to its last valid page, writes the end page and catalogues it as a recovered note. Pause excludes paused time from the recording clock. **Done** finalizes and catalogues the recording, then starts the INT8 batch pipeline when the model is installed. Recording itself needs no model.

Imports and recordings are decoded straight to 16 kHz mono, which the speech detector, Parakeet and diarization all use. Documents are read and written as streams of Ogg pages, so file size is bounded by `Limits` (2 h, 2 GiB, free space) rather than by memory. Imports that are not Ogg Opus are re-encoded to 16 kHz Opus. Decoding a long Opus file is limited by the platform decoder: a 55 min capture took about 9 min on the Pixel 8. Transcription and speaker identification still run in the activity and are not resumable.

Batch processing displays draft words after each decoder call, including the final filtered words while the complete portable Opus file is being created. Transcription during capture has been removed ahead of a separate Nemotron implementation.

```sh
./scripts/device-smoke.sh fleurs org.cassini.android.LibraryTest
# Opt-in recovery check after force-stopping the app mid-recording:
adb shell am instrument -w -r -e class org.cassini.android.RecoveryCheckTest org.cassini.android.test/androidx.test.runner.AndroidJUnitRunner
```

Upgrades ignore retired `fp32` and `modelChoice` session fields and remove them on the next session save. Existing result precision and portable provenance remain historical facts. App startup removes only `files/parakeet-v3-fp32/` and the obsolete live-recording preference. INT8, VAD and speaker downloads, saved WAV/M4A/Opus recordings, and portable documents are retained. FP32-only installations need the INT8 download for new transcription.

## Local storage

Owned recordings/documents live in private `files/documents/`. Atomic `session.json` stores the current document reference, selected variant and playback position. Atomic `library.json` stores stable note IDs, dates, per-note state and a disposable text cache for search. The portable document is authoritative when opened.

An imported file is stored under the SHA-256 of its bytes. Opening the same file again reuses that copy and returns to its note with its playback position and selected variant. This applies to copies imported from this version on: earlier imports and documents created by transcription have random names, so opening the original of one of those starts a new note. A recreated screen returns to the note it was showing and imports its launch intent only when no note can be found for it. `session.json` records which screen wrote it last and is used when that is the recreated screen, because work that finished while the screen was stopped makes it newer than Android’s saved state (unless that last save failed). Otherwise the note is found in the catalogue by its saved ID, or by its file reference when it has none. One case is not recovered: if work finished after the state was saved and another screen then replaced `session.json`, the screen returns to the note it showed earlier; the newer note or revision is still in the library.

`library-migrated` marks recovery of older retained documents, grouped by meeting ID. Notes saved before that recovery keep their state and receive any missing search text, and no further card is made for other revisions of their meetings. A screen with no note does not write `session.json`, and a screen replaces a `session.json` it did not write only after that session is in the catalogue, so a session from before the library is never dropped. Previous owned revisions remain on disk. Legacy `latest.words.json` and `latest.processing.json` are diagnostic caches.

## Beta releases

Update `versionName`, increase `versionCode`, and refresh [release notes](release-notes.md). A matching `vVERSION` tag triggers the same build/test/lint checks, then publishes the APK, checksum, notices and native source archive. Packaging checks both supported 64-bit ABIs and each native library’s 16 KB ELF/ZIP alignment. These checks catch build regressions; runtime behavior still needs testing on actual devices. Tag builds require the repository’s `ANDROID_BETA_KEYSTORE` secret; missing signing material fails the build. The keystore's secrets are the `CASSINI_RELEASE_STORE_PASSWORD`, `CASSINI_RELEASE_KEY_ALIAS` and `CASSINI_RELEASE_KEY_PASSWORD` repository secrets. Until they are set, CI falls back to the legacy debug-keystore values (`android`, `androiddebugkey`, `android`); this fallback is temporary and goes once the owner rotates the passwords. To build a signed release locally, export `CASSINI_BETA_KEYSTORE_FILE` and the three `CASSINI_RELEASE_*` variables: when the keystore is set and one of the others is missing, Gradle stops with an error.

The signing key matches the existing development installation. Keep it stable for these beta updates. The beta release build disables debugging; the key comes from the earlier debug installation to allow upgrades. A production signing identity can be chosen for a future stable release. Never commit it.

## Speaker identification

Superseded: **Transcribe** now labels speakers in the same job, and there is no separate speaker step (`SpeakerIdentificationTest#aTranscriptLeavesNoSeparateTranscribeOrSpeakerStep`). The notes below describe the diarizer itself; P5 rewrites this section. [NVIDIA Nemotron-3-Diarization](https://huggingface.co/nvidia/Nemotron-3-Diarization), an end-to-end streaming Sortformer, labels up to 8 speakers in order of first arrival. It needs no speaker embeddings, no clustering and no speaker count; the earlier count dialog is gone. The anonymous labels do not identify people; errors and overlapping speech need listening checks.

The optional model is an INT8 ONNX export (103,941,758 bytes, about 99 MiB), stored in `files/speaker-models/`. It downloads as a seekable Zstandard file from `dist.gocassini.com` (64,982,914 bytes, about 62 MiB). Both hashes are verified: the download before zstd-jni decompresses it, and the model before it replaces anything. `core/.../models/ModelCatalog.kt` pins sizes, hashes, the source revision and the URL. Downloads require explicit consent; ASR model downloads do not install it. App startup removes the retired pyannote and ERes2Net files. License texts and notices ship in the APK.

sherpa-onnx 1.13.7, rebuilt with the Sortformer runtime (see [native runtime](native-runtime.md)), runs the model on CPU with two threads. It processes 27.2 s chunks with the offline settings of the model card, keeps speaker identities across chunks with the Arrival-Order Speaker Cache, and reports progress per chunk. Activity above probability 0.5 is speech; turns shorter than 0.3 s are dropped and gaps shorter than 0.5 s merged. ASR and diarization share a native-model lease, so a cancelled call cannot overlap a new model load. The callback's return value does not abort computation. Cancel returns to the library immediately and discards the result; the ongoing native call finishes and releases its own model. Destroying the viewer also cancels publication. No partially attributed variant is saved.

Word labels use the greatest overlap with a speaker's union of time intervals, or the nearest interval in a gap. Ties are deterministic. Text, word order and timestamps are retained. A mixed recording supplies one label per recognized word; this does not recover simultaneous voices or reproduce desktop attribution from separate participant tracks.

Each pass appends a portable word variant with new speaker IDs, preserving the audio digest, original variants and imported speaker names. The new variant's `provenance.speechToText` entry retains the ASR record and adds `x-derivedFromTranscript`, `x-sourceAttribution` and `x-speakerDiarization` (model, revision, hash, threshold, duration settings and elapsed time). The document-wide attribution record describes the desktop cross-track audit and is preserved unchanged. Speaker diarization does not claim that audit ran. The export is a complete portable Opus document, not a word-body JSON file.

Fetch the pinned speaker model for native experiments with `python3 scripts/fetch-diarization-models.py`. `ANDROID_SERIAL=<serial> scripts/diarization-poc.sh` runs the public four-speaker fixture through `scripts/device-smoke.sh`, preserving app data. Existing optional two-voice/real-conversation diagnostics are in `DiarizationPocTest`; private audio stays in `.tools/` and is never included in an APK. Full UI/native checks are `SpeakerIdentificationTest#transcribingLabelsEachVoice` and `#cancellingDuringSpeakerIdentificationSavesNothing`; they need the downloaded models. CI runs two speaker checks without models: consent before downloading, and no separate Transcribe or speaker step.
