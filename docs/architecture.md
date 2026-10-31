# Cassini for Android: architecture (0.2)

This document describes the rebuilt Cassini Android app. It gives the modules, the storage, the background work, the UI system, and the rules that keep the portable format and the privacy promises. Its readers are the contributors and the coding agents that work from the phased plan.

Two companion documents are binding for the parts that they cover:

- `docs/design/features.md`: each screen, behavior, banner and export, with its data source and its acceptance criteria.
- `docs/design/ui.md`: the design tokens, fonts, components, screen contracts, navigation, accessibility and screenshot tests.

If the three documents do not agree, `features.md` has priority for the behavior that the user sees. `ui.md` has priority for the visuals. This document has priority for the module boundaries, the storage layout, the threads, the services and the build.

## 0. Owner decisions and phases

The owner made two decisions on 2026-10-09 that give the shape of this version:

1. Greenfield. Cassini Android has no installations at this time. We build nothing whose only purpose is to read or keep the app data of 0.0.x or 0.1.0. Thus there is no migrator, no legacy reader, no frozen `library.json` or `session.json`, no tombstone and no upgrade check. The on-disk layout is new. Two things stay external contracts: the portable `.opus` document format (with its conformance vectors) and the privacy rules.
2. New visual design. The UI follows the boards in `design/reference/` (11 boards at 390 x 844 dp, PNG renders and the source markup). The fonts are Instrument Sans and IBM Plex Mono.

The work came in phases. Each phase operated in one git worktree. Phases that operated at the same time used separate worktrees and changed different files. All phases are done.

| Phase | Worktree (branch) | Content | State |
|---|---|---|---|
| P0 | `rebuild` | Toolchain, native packaging, CI, detekt. | Done |
| P1 | `rebuild` | The pure-JVM `:core` module and the `:engine` Android library. | Done |
| P2 | `rebuild` | Fork preparation: these documents, the design references, module stubs for `:data` and `:ui`, catalog entries, the `ciTest` aggregate task, and the removal of the fixtures that only a migration used. | Done |
| P3 | `rebuild` | Pure `:core` additions: statuses, search, trash, exports, speaker stats, model plan, `retitle`, download redirect policy. | Done |
| P4 | `rebuild` | `:data`: Room catalogue, DataStore, file repositories. | Done |
| P5 | `engine-job` | `TranscriptionJob`, `ModelInstaller`, `ProgressTracker` and the job seams in `:engine`, behind ports. At the same time as P4. | Done |
| P6 | `rebuild` | The background work of `:app`: `AppGraph`, coordinators, services, controllers. | Done |
| P7 | `ui` | The `:ui` design system: fonts, tokens, icons, components. At the same time as P3 to P6. | Done |
| P8 | `ui` | The `:ui` screens, drawn from fixture `UiState` values, and the screenshot goldens. | Done |
| P9 | `docs` | The open-source project documents. At the same time as other phases. | Done |
| P10 | `rebuild` | Integration: merge of `ui` and `docs`, ViewModels, navigation, the `:bench` module, removal of the legacy UI, targetSdk 36. | Done |
| P11 | `rebuild` | CI consolidation: the models workflow, the rules for each emulator API level, and the release notes from `CHANGELOG.md`. | Done |
| P12 | `rebuild` | End-to-end acceptance on emulator-5554, screenshots, README. | Done |

Until P10, the old view-based app built and operated on its own storage (`files/documents/`, `library.json`). We built the new layers next to it and tested them with JVM, Robolectric and instrumentation tests. We did not connect new code to the old Activities, and P10 deleted them.

Only the owner makes the 0.2.0 release, changes `versionCode` and `versionName`, and pushes tags. Only the owner changes GitHub releases and GitHub settings. The rebuild commits only on local branches.

Disk space: on 2026-10-09, the root file system had 12 GB of free space. Each phase examined `df -h /` before it started, and it kept at least 6 GB free. Before you make a new worktree or AVD, remove the build directories of the worktrees that you do not use. In a worktree that you remove, use `./gradlew clean` or `rm -rf */build build .gradle/configuration-cache`.

## 1. Scope and constraints

Cassini records audio and makes the transcript on the phone with sherpa-onnx and Parakeet Ultra INT8 (a post-train of Parakeet TDT 0.6B v3 by Moondream). It labels the speakers with Nemotron-3 Sortformer streaming diarization (diarization finds who speaks when). It recognizes named voices with CAM++ voiceprints. It saves all of this as one portable `.opus` document. The engine is good and has tests. Version 0.2 keeps the engine and replaces the app around it.

These constraints are mandatory:

- The portable document format stays byte-compatible with `../cassini-format` and gocassini. Each build runs all conformance vectors at the pinned revision of `third_party/cassini-format`. The tests read the number of vectors from the vector index. The code never contains this number (26 at the current pin).
- The privacy rules apply. The list that follows gives them.
- Native runtime: `minSdk 26`. The ABIs are `arm64-v8a` and `x86_64` (16 KB aligned). The app uses `libcassini_opus.so` and the custom sherpa-onnx AAR. We build this AAR from the pinned fork and `scripts/streaming-diarization.patch`.
- Android stays native Kotlin. The iOS app is separate Swift code. The two apps share only the portable format.
- `applicationId org.cassini.android` and the JNI class name `org.cassini.android.NativeOpus` do not change. The native symbols use the second name. The old plan kept a launcher alias for the library activity of 0.1.0. The app does not need this alias, because no installed shortcuts exist.
- UI stack: Jetpack Compose, with Material 3 components under the Cassini tokens. The tokens give all of the style: there is no stock Material look and no dynamic color. The app uses ViewModels, `StateFlow`, a unidirectional data flow, coroutines and manual dependency injection (`AppGraph`).
- Languages and accessibility: English and Italian, TalkBack support and 48 dp touch targets. The app never shows a state by color alone. It has the light theme of the boards and a dark theme that we made from it. It is edge-to-edge and has predictive back with targetSdk 36 (from P10).

The privacy rules are:

- The app never writes voiceprints into documents or exports. All voiceprints and voice matches are under `voices/` (section 3.2).
- Backup and device transfer do not include `filesDir`.
- The app downloads no model before the user agrees.
- The app uses the network only for model downloads. It connects only to the three model hosts (huggingface.co, github.com, dist.gocassini.com) and to the download hosts that they redirect to. `ArtifactFetcher` compares each redirect host with an allow-list (section 5.6). `PRIVACY.md` lists the hosts and the allow-list.

## 2. Modules

The build has six Gradle modules:

```
:core    Pure Kotlin/JVM. The verbatim engine plus pure app rules (statuses, search,
         trash arithmetic, exports, speaker stats, model plan). No android.*, no sherpa,
         no coroutines. org.json is compileOnly.
:data    Android library. Room catalogue, DataStore settings, file repositories.
:engine  Android library. Native wrappers (sherpa-onnx, libcassini_opus), MediaCodec
         decoder, microphone capture, TranscriptionJob, ModelInstaller, ProgressTracker.
:ui      Android library. Compose design system and stateless screens rendered from
         UiState. Depends only on Compose and kotlinx-collections-immutable.
:app     Android application. AppGraph, ViewModels and routes, navigation, services,
         coordinators, controllers, notifications, the XML app theme.
:bench   com.android.test module (from P10). Experiments and calibration on a device.
         Never in CI.
```

The dependencies go in this direction:

```
app -> ui
app -> data -> core
app -> engine -> core
bench -(instruments)-> app
```

`:engine` does not depend on `:data`. `TranscriptionJob` uses small ports in `:engine` to get to the storage (section 5.4). `:app` implements these ports with adapters over the `:data` repositories. Thus the tests of the two modules can use fakes, and P4 and P5 operated at the same time.

`:ui` does not depend on `:core`, `:data` or `:engine`. Its screens take semantic `UiState` values (enums, numbers, user text). They format these values with their own string resources. The ViewModels in `:app` change the domain state into these values. Each resource name of `:ui` starts with `ui_` (`resourcePrefix = "ui_"`). Thus a resource of `:app` cannot replace a `:ui` resource without notice. In CI, `scripts/check-resource-collisions.py` also makes sure of this.

`./gradlew --configure-on-demand :core:test` needs a JDK and the `third_party/cassini-format` submodule. It needs nothing else: no NDK, no AAR and no device.

Native seams: `:core` has the interfaces that the verbatim engine already uses: `WordDecoder` (`asr/DecodeSession.kt`), `SpeechFeed.Stream` and `PcmSource`. The other native parts are concrete classes in `:engine`: `SortformerStream`, the `VoiceprintExtractor` object, `AudioDecoder` and `StreamingOpusEncoder`. P5 added interfaces in `org.cassini.engine.job` that the engine owns: `Recognizer`, `StreamingDiarizer`, `PrintExtractor`, `AudioSource` and `OpusSink`. Thin adapters connect these interfaces to the native classes. Thus the JVM tests of `TranscriptionJob` can use fakes.

### 2.1 `:core` (package `org.cassini.core`)

The P1 move kept the bodies of the files, except for the listed edits. `scripts/check-verbatim.sh` compares each verbatim file with its 0.1.0 origin. It ignores the package and import lines, and it reports only the listed edits.

| Package | Files | Notes |
|---|---|---|
| `format` | `OggOpus`, `CassiniPayload`, `CassiniDocument`, `Transcript`, `OggRepair`, `SpeakerAttribution`, `SpeakerMerges` | Verbatim. `WordBodyBudget` is new: it counts the bytes of a word body without the body (section 3.7). `CassiniDocument` has a typed `DocumentState` (`PLAIN_AUDIO`, `UNKNOWN_FORMAT`, `INVALID_METADATA`, `UNVERIFIED`, `STALE_AUDIO`, `OK`, `UNREADABLE`). `UNREADABLE` means an I/O error. A note in this state stays openable, and you can transcribe it again. `seal` with an `existing` document uses its manifest again. Thus Transcribe again ignores the `title` argument. A new seal adds a `TITLE` tag only when the source has none. P3 added `CassiniDocument.retitle` next to the verbatim `relabel`. It has the same shape and requires `OK`. It sets `meeting.title` and replaces or adds the `TITLE` tag. It changes nothing else. |
| `audio` | `Resampler`, `PcmSource`, `AudioLimits`, `PcmBuilder`, `WavDecoder` | P3 added `RecordingSpace` and `RecordingPolicy`. On 2026-10-11, `Limits.MAX_RECORDING_MS` (2 h) became `MAX_TRANSCRIBE_MS` (36 h), and `MAX_IMPORT_BYTES` became 4 GiB. We removed `RECORDING_WARNING_MS` (section 3.7). |
| `asr` | `SpeechWindows`, `SeamMerge`, `WordGate`, `IncrementalTranscript`, `SpeechFeed`, `DecodeSession`, `TokenWords`, `TranscriptionProgress`, `ProcessingSpeed`, `BatteryProjection` | The algorithms did not change. |
| `speakers` | `SpeakerAssignment`, `TurnHistory`, `VoiceMatcher`, `VoiceprintSelection`, `VoiceClipCache`, `SpeakerBudget`, `VoiceCodec`, `NoteVoiceCodec`, `DefaultLabels` | `DefaultLabels.PREFIXES` = `Speaker `, `Voce ` (the placeholder label of each shipped language). P3 added `SpeakerStats`, `SpeakerTint`, `NameMatch`, `VoiceStaleness`, and an `excluded` parameter on `VoiceMatcher.match`. |
| `models` | `ModelCatalog`, `ArtifactFetcher` | `ArtifactFetcher` continues `.part` files with HTTP Range (section 5.6). P3 added `ModelPart`, `ModelPlan`, a progress callback for the digest, `VOICEPRINT_DIM = 512` and the redirect allow-list. |
| `checkpoint` | `CheckpointData`, `CheckpointState` | The codec of `checkpoint.json`. It reads version 1 and version 2, and writes version 2 (section 3.5). `WordLog` is the codec of `words.log`. P3 added two optional members to `names.json` (section 3.6). |
| `library` | `NoteRules` (P10 removed it) | P3 added the shared enums (`Origin`, `AudioKind`, `JobState`, `JobKind`, `PauseReason`, `VariantKind`), `NoteStatus`, `DayGroups`, `SearchQuery`, `TrashPolicy` and `PreviewText`. Two rules refine features.md section 1. Their KDoc gives the details. `PreviewText` stops at the first turn end after it holds 40 characters (a shorter turn continues, and board 00 stops before Speaker 3). It uses the transcript start at or after the end of the last word. `SearchQuery.fts` changes each character that is not a letter, a digit or a mark into a space inside the phrase (`e-mail` → `"e mail*"`, `giov.` → `"giov*"`), as unicode61 makes tokens. It removes terms without a letter or a digit. |
| `export` | New in P3 | `TranscriptTurns`, `ExportClock`, `TextExport`, `SubtitleCues`, `SrtWriter`, `VttWriter`, `ExportNames` (features.md section 9). |
| (root) | `Failure` | Failure codes without string resources. `FailureMessages` in `:app` is the only place where a failure gets a string. |

### 2.2 `:engine` (package `org.cassini.engine`, and `org.cassini.android.NativeOpus`)

| Package | Content |
|---|---|
| `opus` | `NativeOpus`, `StreamingOpusEncoder`, `src/main/cpp/opus-jni.cpp`, `CMakeLists.txt`. 24 kb/s, complexity 5. The JNI code has guards for `ptr == 0`, for a null result of `FindClass` and for the return codes of `opus_encoder_ctl`. |
| `decode` | `AudioDecoder`: the verbatim MediaCodec/MediaExtractor loop. It takes a `ContentResolver` and a cache directory. |
| `capture` | `MicrophoneCapture` (an interface with a suspend `stop()`), `OpusCapture` (the platform MediaCodec Opus encoder, API 29+), `AacCapture` and `CaptureFactory`. The `MediaCodecList` probe of `CaptureFactory` operates one time, not on the main thread. |
| `asr` | `ParakeetDecoder` (implements `WordDecoder`), `SileroDetector` (implements `SpeechFeed.Stream`), `IncrementalRecognizer`. |
| `speakers` | `SortformerStream`, `VoiceprintExtractor`. |
| `job` | `InferenceDispatcher` (one thread) and `NativeBudget` (the `Mutex` for model loads). From P5, also these items: the seams of section 2, `TranscriptionJob`, `ModelInstaller`, `ProgressTracker`, the ports (`JobStorage`, `JobVoices`, `ModelAccess`, `ModelStore`, `BatterySource`), `JobEngines` (the factories of the seams), `JobEnvironment` (dispatchers, clock, logger, placeholder label), `DraftSources` and the job state types (section 5). A JVM test makes sure that `ModelCatalog.VOICEPRINT_DIM` is the print length that the voiceprint adapter accepts. The adapter refuses a print of a different length. No test operates the real CAM++ model. |

The sherpa-onnx AAR is the Maven coordinate `org.cassini.native:sherpa-onnx-nemotron:1.13.7-dbbeef9c` in the repository `native/m2/`, which git ignores (section 8).

### 2.3 `:data` (package `org.cassini.data`)

This module holds Room, DataStore and the file repositories of section 4. Each repository takes `AppPaths`, `CassiniDispatchers` and the shared `NoteLocks`. The repositories give `suspend` functions and cold `Flow`s. Functions with the name `…Blocking` are only for the inference thread.

### 2.4 `:ui` (package `org.cassini.ui`)

This module holds these parts:

- `theme/`: tokens, type, shapes, icons and fonts.
- `components/`.
- One package for each screen, with its `XxxScreen(state, onEvent)`, `XxxUiState` and `XxxEvent`.
- `format/`: durations, dates, plurals and spoken descriptions.
- All UI string resources, in English and Italian.
- The bundled fonts, the drawables of the logo and the launcher icon, and the color resources for the window background.
- The Roborazzi screenshot tests.

`:ui` has no XML theme. `Theme.Cassini` is in `:app`, because it needs AppCompat. `ui.md` is the specification of `:ui`.

### 2.5 `:app` (package `org.cassini.android`)

- `CassiniApplication` makes `AppGraph`. The graph holds the paths, dispatchers, locks, repositories, engine wrappers, port adapters, `ProcessingCoordinator`, `ModelInstallOwner`, `RecordingController`, `PlaybackController` and `NoticeCenter`. `AppGraph` is constructor wiring (about 550 lines).
- `AppGraph.ready: Deferred<Unit>` completes after the startup work of section 4.8. The start of a job, an import or a recording waits for it.
- There is no DI framework. A small number of ViewModels and about twelve singletons do not need an annotation processor. The instrumentation runner replaces the graph with a graph on a scratch root. In debug builds only, a file `files/debug-root.txt` can name a scratch root that stays after process death. The kill-and-resume device check uses it (section 9).
- The root package has `FailureMessages` and `BatteryReader`. `res/values{,-night}/themes.xml` holds `Theme.Cassini`. The debug source set has `DebugCommandActivity` and `DebugStatus` (section 9).

The packages of `:app` are:

- `processing/`: the coordinator, the install owner, the service and the notifications.
- `recording/`: the controller, the service, the notification, and the ViewModel and route of the Recording screen.
- `playback/`: `PlaybackController`.
- One package for each other screen, with its ViewModel and its `XxxRoute` composable: `library/`, `note/`, `models/`, `settings/`, `voices/`, `trash/` and `licenses/`.
- `adapters/`: the adapters of the `:engine` ports over the `:data` repositories.
- `nav/`: the keys, `CassiniApp`, the deep links and the system bars.
- `notices/`: `NoticeCenter`.

## 3. Storage (greenfield)

### 3.1 What is authoritative where

| Data | Authoritative | Cache or index | Lost if `cassini.db` is made again |
|---|---|---|---|
| Audio of a note | The file in `notes/<id>/` | `notes.audio_file`, `duration_ms` | – |
| Transcript variants, speaker labels, meeting ID, created time, provenance | The sealed `notes/<id>/document.opus` | `variants`, `speakers`, `note_text`, `notes.*` | – |
| Note title | Room `notes.title` | The `meeting.title` and the `TITLE` tag of the document (a copy that `retitle` keeps the same) | A rename that the app did not write into the document yet. The new title comes from `meeting.title`, or it is "Recovered recording <date>" for plain audio. |
| Trash state and time, and the job state before the trash | Room `notes.trashed_at`, `notes.trashed_job` | – | Yes. The notes in the trash come back as live notes. |
| Playback position, selected variant, preview excerpt, the "Play anyway" acknowledgement | Room | – | Yes (the defaults) |
| Transcription queue, pause reason, attempts | Room `jobs` | – | Yes. A note with a valid checkpoint comes back PAUSED. |
| Partial transcription (checkpoint, draft names, sealed output that waits for adopt) | `notes/<id>/work/` | `jobs.done_ms` | – |
| Saved people (embeddings, names, counts) | `voices/people.json` | – | – |
| Voiceprints and matches of each note | `voices/notes/<noteId>.json` (sealed), `voices/pending/<noteId>.json` (waits for adopt) | `note_voice_links` (no embeddings, made the same as the files at each start) | – |
| Model installations | The model directories and the marker files | The state of `ModelRepository` | – |
| Consent, estimates, one-time notices, queue hold | DataStore | – | Not applicable (a separate file) |
| UI language | The per-app locales of AppCompat (the system on API 33+, `autoStoreLocales` on earlier versions) | – | – |

These rules apply:

- When the app writes a document (adopt, relabel, retitle, merge), the repository makes all cache rows of that note again from the document. It does this in the same Room transaction that sets the new file signature in the row (`DocumentSignature`, section 3.3).
- When a Note screen opens, `refreshIfStale` compares `notes.document_signature` with the file. If they are different, it makes the cache again. This rule covers a crash between the rename of the file and the update of the row.
- `refreshIfStale` never writes over `notes.title`. If the document title is different, it sets `title_dirty`.
- While a job row exists for a `DOCUMENT` note (QUEUED, RUNNING, PAUSED, or FAILED with a checkpoint), `document.opus` is the audio source of the job, and the checkpoint holds its signature. During this time, nothing writes the file again. The repository refuses relabel and merge on the sealed document, and the UI disables them (features.md section 3). A rename only updates the row and sets `title_dirty`. Adopt writes the title later.

### 3.2 File layout under `filesDir`

Backup and device transfer do not include `filesDir` (`data_extraction_rules.xml`, `fullBackupContent=false`).

```
cassini.db, cassini.db-wal, cassini.db-shm   Room catalogue (opened by absolute path under AppPaths.root)
settings.preferences_pb                      DataStore Preferences
notes/<noteId>/                              one directory for each note; <noteId> = UUID v4 string
    capture.opus | capture.m4a               microphone capture (live while audio_kind = LIVE_CAPTURE)
    source.<ext>                             imported non-Cassini audio; ext limited to [a-z0-9]{1,5}, "bin" if unknown
    document.opus                            sealed Cassini document; when it exists, it is the audio of the note
    document.opus.tmp, source.tmp            temporary writes (fsync, then rename into place)
    work/                                    transcription state, only while a job is not finished
        checkpoint.json (+.bak)              CheckpointState version 2
        words.log                            the settled words of the checkpoint, one JSON array for each line
        names.json (+.bak)                   draft labels, merges, remembered, automatic, generation, live, rejected
        audio.opus.part                      libopus output for non-Opus sources
        clips.pcm                            selected voice clips (at most 8 x 45 s), raw 16 kHz f32le (audio, not voiceprints)
        sealed.opus                          the finished document, written by the seal step of the job
        sealed                               marker: sealed.opus is complete and verified; adopt can install it
voices/                                      all voiceprints and voice matches, and nothing else
    people.json                              VoiceCodec version 1 (saved people)
    generation                               integer, increased by "Forget everyone"
    notes/<noteId>.json                      NoteVoiceCodec version 2 (keys <variantId>/<speakerId>)
    pending/<noteId>.json                    final prints and matches of a sealed job before adopt (same codec)
parakeet-ultra/  vad/  speaker-models/       models, names and marker files exactly as ModelCatalog pins them
```

`cacheDir/exports/` holds temporary export files. The app deletes them after each write and at each start.

These rules apply to a note directory:

- A note has exactly one current audio file. `notes.audio_file` gives its name.
- The job never writes `document.opus`. It seals the document into `work/sealed.opus` and makes sure that this file is correct. Then it writes the marker `work/sealed`. Adopt (section 5.4, step 10) installs the file in the sequence that follows this list.
- For a source that is not Opus, the document holds a 24 kb/s libopus encode of the audio. `PRIVACY.md` and the CHANGELOG tell this to the users.
- An edit outside a job (relabel, retitle, merge) writes `document.opus.tmp` and does an fsync. Then it renames the file over `document.opus` and does an fsync of the directory. After the file changes, `PlaybackController` prepares the player again and keeps its position.
- The lock of the note in `NoteLocks` serializes the writes to one note (section 7).

Adopt installs the sealed file in this sequence:

1. It renames `work/sealed.opus` over `document.opus`.
2. It does an fsync of the directory.
3. It updates the row and the cache in one transaction.
4. It deletes the previous `capture.*` or `source.*` file.
5. It deletes `work/`.

After a crash at any point, one of two conditions is true. Either the old audio did not change (no marker), or a marker exists and the next start finishes adopt (section 5.3).

The model directories stay at the root of `filesDir`. `vad/` and `speaker-models/` have their 0.1.0 names. `ModelCatalog` pins these names and the markers. The marker `parakeet-ultra/verified` holds the Hugging Face revision of Parakeet Ultra. Versions 0.1.0 to 0.2.0 kept Parakeet TDT 0.6B v3 in `parakeet-v3/`. This build neither reads nor deletes that directory. The markers `vad/verified`, `speaker-models/verified` and `speaker-models/verified-voiceprint` hold the SHA-256. The default of `AppPaths.modelRoot` is `root`, but it is a separate value. Thus an instrumentation test can use a scratch root and the installed models at the same time. A test can also use an empty scratch model root to show the download screens.

On emulator-5554, the files of the old app (for example `documents/`, `library.json` and `voices.json`) stay where they are. The new app does not read them and does not delete them.

### 3.3 Room schema (`org.cassini.data.db.CassiniDatabase`, version 1)

`exportSchema = true`, and the schemas are in `data/schemas/`. `TypeConverter`s store the enums as `TEXT` with stable names, never as ordinals. The enums are in the `library/` package of `:core`, so that `:data` and `:engine` share them.

The table `notes` has these columns:

- `id`: TEXT primary key. It is a UUID and also the name of the directory.
- `title`: 1-200 characters, trimmed, never blank. This column is authoritative.
- `title_dirty`: the document title is different, and the app must write it again.
- `created_at`: the time of the recording, in epoch ms. It is the capture start, the `meeting.createdAtUtc` of the document, or else the import time.
- `added_at`.
- `origin`: `RECORDED | IMPORTED_AUDIO | IMPORTED_DOCUMENT | RECOVERED`.
- `audio_file`.
- `audio_kind`: `LIVE_CAPTURE | PLAIN | DOCUMENT | UNPLAYABLE`.
- `duration_ms`: 0 means unknown.
- `source_sha256`: the hex SHA-256 of the imported bytes, to find a second import of the same file. It is null for recordings.
- `document_state`: the `DocumentState` of `:core`. It is null unless `audio_kind` is `DOCUMENT`.
- `document_error`.
- `document_warnings`: the warnings of the reader for the document with this signature.
- `document_signature`: a `DocumentSignature`, `<length>:<mtime>:<sha256>`. The SHA-256 is of the first 64 KiB and the last 64 KiB of the file.
- `meeting_id`, `selected_variant`, `position_ms`.
- `preview`: the Library preview excerpt (features.md section 1). Null means the start of the transcript.
- `trust_acknowledged`.
- `trashed_at`: null means that the note is live.
- `trashed_job`: the job state and kind when the user moved the note to the trash, for Restore. Null means no job.
- `updated_at`.

The table has indices on `created_at`, `trashed_at` and `source_sha256`.

The table `variants` has the primary key `note_id, variant_id` and cascades from `notes`. It has these columns:

- `ordinal`: the sequence in the manifest.
- `is_default`, `created_at_utc`.
- `language`: `""` when the language is not identified.
- `kind`: `TRANSCRIBED | MERGED`. It is MERGED when the provenance has `x-speakerMerges`.
- `derived_from`: `x-derivedFromTranscript`.
- `word_count`, `speaker_count`.
- `speakers_told_apart`: true when the provenance has `x-speakerDiarization`.
- `inference_ms`: `x-inferenceMs`.
- `readable`, `error`.

The table `speakers` has the primary key `note_id, variant_id, speaker_id` and cascades from `variants`. It has these columns:

- `ordinal`: the sequence of the first appearance in the words.
- `label`: the label of the manifest, verbatim.
- `is_placeholder`: `DefaultLabels.isDefault`.
- `placeholder_number`, `speaking_ms`, `word_count`, `turn_count`, `first_ms`.
- `sample_start_ms`, `sample_end_ms`: the window of "Play a sample". They are null when no turn is 1 s or longer.

The table `words` has the primary key `note_id, variant_id, idx` and cascades from `variants`. Its columns are `start_ms`, `end_ms`, `speaker` and `text`. These rows are the cache of the parsed transcript. They hold the words of one variant in canonical order. The repository writes them in the same transaction as the other cache rows and `notes.document_signature`. The Note screen shows the transcript from these rows. Thus a note with an unchanged document opens without a read or a check of the `.opus` file. If the count of cached words of a variant is not `variants.word_count`, the repository makes the rows again from the document.

We selected a Room table and not a cache file for each note. A measurement with `WordCacheDeviceTest` gave this decision (9,500 words, which is one hour of speech, debug test APK, API 34 x86_64 emulator, 2026-10-10). The first 200 rows load in 7-9 ms. All rows load in approximately 210 ms. A `DataOutputStream` file loads in approximately 160 ms. A parse of the `cassini.words.v1` JSON takes approximately 2.9 s. Thus the file is not much faster than the table. The table has three advantages. The signature and the rows change in one transaction. The rows cascade with their variant. A `LIMIT` query gives the first turns before the other rows load.

The table `note_text` has an INTEGER `rowid` primary key, so that VACUUM cannot change the numbers. It has a unique `note_id` and cascades from `notes`. Its columns `title` and `body` hold the title and the words of the selected variant, with spaces between the words. Without a transcript, `body` is `""`. `note_fts` is an external-content FTS4 table over `note_text`, with `tokenizer = unicode61` and `remove_diacritics=1`. Room writes the triggers that keep the two tables the same. `note_text.title` changes with `notes.title` in one transaction. The repository writes `body` again when the selected variant or the document changes.

The table `jobs` has the primary key `note_id`, so a note has at most one job. It cascades from `notes`. It has these columns:

- `seq`: the FIFO order, `max(seq)+1` at enqueue.
- `kind`: `TRANSCRIBE | TRANSCRIBE_AGAIN`.
- `state`: `QUEUED | RUNNING | PAUSED | FAILED | NEEDS_CONSENT`.
- `pause_reason`: `USER | SYSTEM | MEMORY | INTERRUPTED`.
- `failure`: the name of a `Failure` of `:core`, `DOWNLOAD_` and the name of a download error, `DOCUMENT_` and the name of a document state, or `CHECKPOINT_MISMATCH`. No two failures have the same code.
- `done_ms`, `total_ms`.
- `words`: from the last checkpoint.
- `attempts`: the automatic restarts since the last user action. Only `reconcileOnStart` increases it.
- `enqueued_at`, `updated_at`.

The table has indices on `state` and `seq`.

The table `note_voice_links` has the primary key `note_id, variant_id, speaker_id`, an index on `voice_id`, and cascades from `notes`. It is a copy of the matches in `voices/notes/<id>.json`. Its columns are `voice_id` (null for no match), `state` (the name of a `Match.State`), `score` and `seconds`. Room never holds embeddings. Each write to the voice file of a note writes the links of that note again in the same call. At each start, the app makes all links the same as the files (section 4.8).

These queries are necessary:

- `libraryFlow`: the live notes by `created_at` DESC, with a left join on `jobs` and on the selected `variants` row. Each row also has `notes.preview` or the first 160 characters of `note_text.body`.
- `search(fts)`: with `snippet(note_fts, char(2), char(3), '…', 1, 15)`. The results do not include the notes in the trash.
- `trashFlow`: by `trashed_at` ascending, so that the note that expires first is at the top.
- `expired(cutoff)`, `noteFlow(id)`, `queueFlow` and `counts`.
- `voiceNoteCounts`: the live notes whose selected variant links a speaker to the voice as AUTO or CONFIRMED.

The standalone model download (from Settings or from a banner, with no note) is not a `jobs` row. It is a state in the memory of `ModelInstallOwner`. The `.part` files keep what is necessary to continue it.

Schema migrations: the app ships version 1. Each later version needs a written `Migration` with a `MigrationTestHelper` test. The code must not use `fallbackToDestructiveMigration`. If the database does not open or the migration fails, the app renames it `cassini.db.corrupt-<epoch>` and rebuilds the catalogue (section 4.9).

Some errors do not show a damaged file: a full disk, an I/O error, a locked database, a file that does not open, a permission error, no memory, or a read-only database. For these errors, `CassiniDatabaseOpener` does not rename the file. It throws the error, and the file stays. The startup work (P5) then shows an error and does not rebuild the catalogue. The next start tries to open the file again.

The code writes an FTS prefix query with the star inside the quotes (`"consegn*"`). This is the phrase-prefix syntax of FTS3/4. A star after a closing quote is FTS5 syntax, and the code never makes it.

The unicode61 tokenizer must operate on API 26. `NoteFtsDeviceTest` is a `:data` device test for this tokenizer: "giovedi" finds "giovedì", "CONSEGNA" finds "consegna", and "consegn" finds "consegna". CI runs it on the API 26 emulator (`scripts/ci-emulator-smoke.sh`). To run it on a device, do these steps:

1. Build it with `./gradlew :data:assembleDebugAndroidTest`.
2. Install `data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk` with `adb install -r`.
3. Start `adb shell am instrument -w org.cassini.data.test/androidx.test.runner.AndroidJUnitRunner`.

The test APK instruments itself and does not touch the data of the app. If the SQLite of a device has no unicode61, the database does not open. The app logs this as a bug and does not use a different tokenizer. If the SQLite of Robolectric has no such tokenizer, the DAO tests fail. They never skip. The device test is the authority.

### 3.4 DataStore keys (`settings.preferences_pb`)

| Key | Type | Meaning |
|---|---|---|
| `models.consentAt` | Long | The user pushed "Download and transcribe" or "Download". Consent means that this key exists or that Parakeet is installed. |
| `models.declinedAt` | Long | The user pushed "Not now — keep the audio". After later recordings, the consent screen does not open automatically. |
| `processing.finalizationMs` | Long | The estimate of the time for matching and saving, `(old*3 + new)/4`. |
| `queue.hold` | String | `USER`, `SYSTEM`, or no key (section 5.2). |
| `recording.lastFormat` | String | The last capture format. The format chip shows it before the encoder probe gives its result. |
| `notices.recoveredAck` | Long | The time of the latest recovered recording that the user saw or dismissed. |
| `notices.opusNativeAck` | Boolean | The user dismissed "This phone can't create Cassini files". |
| `notifications.asked` | Boolean | The app asked for `POST_NOTIFICATIONS` one time. |
| `trash.lastPurgeAt` | Long | The time of the last purge (only for diagnostics). |

### 3.5 Checkpoint contract (`notes/<id>/work/`)

`checkpoint.json` version 1 has the keys of 0.1.0: `version`, `source`, `signature` (`length:mtime`), `model` (the Parakeet revision), `speakers` (the SHA-256 of the diarizer), `recognition{inferenceMs, chunks[{start, end, window (base64 f32le), words (cassini.words.v1)}]}`, `encoder{…libopus history, Ogg LiveState, length}`, `diarizer{cache (base64, at most 200000 floats), elapsedMs, turns}`, `speakerPrefix`, `speakerFailure`, `clipSamples`, `voiceClips`, `positionSamples`, `doneMs`, `durationMs` and `elapsedMs`.

Version 1 holds all the words in `chunks.words`, and the job writes the file again after each 28 s window. Thus the bytes of each write increase with the recording, and the total work increases with the square of the length (section 3.7). Version 2 adds one key, `wordLog{count, bytes}`, and the file `work/words.log`:

- A word is settled when it ends 10 s or more before the start of the open window (`WordLog.SETTLE_MS`). Only the merge of the next window changes words, and only in its 2 s overlap.
- `words.log` has one settled word on each line: a compact JSON array `["<speaker>",<startMs>,<endMs>,"<text>"]`.
- `wordLog` names the valid prefix of the log: its first `count` words, in its first `bytes` bytes. `chunks.words` holds only the words after this prefix.
- `CheckpointRepository.saveBlocking` appends the words that settled since the last save and does an fsync of the log. Then it writes `checkpoint.json`. Thus a checkpoint never names bytes that are not on the disk.
- After a crash between the append and the checkpoint, the log has more bytes than the prefix. The reader ignores them, and the next save writes over them.
- The first save of a note in a process writes the whole log again, through `words.log.tmp` and a rename. The old prefix stays the same, because settled words do not change. A save whose words do not continue the log also writes it again.
- `CheckpointState.validate` also makes sure that the log has at least `wordLog.bytes` bytes. A short or damaged log is a mismatch (`MismatchReason.WORDS`).

The codec writes version 2 and reads both versions. A version 1 file resumes with all its words inline, and the next save writes version 2.

In the new layout, `source` holds the name of the audio file of the note (`capture.opus`, `source.mp3`, `document.opus`, …). `CheckpointState.validate` compares `source`, the signature, the Parakeet revision and the SHA of the diarizer with values from the note row. Thus a checkpoint that Parakeet TDT 0.6B v3 made does not resume with Parakeet Ultra: the job fails with "Saved progress no longer matches" (MismatchReason.MODEL), and the recording stays. The words of two models never mix in one transcript.

The name of the directory comes from the note ID. The app deletes the directory at the end of adopt (first the row, then the directory), at "Start over" and at purge. A committed golden `checkpoint.json` from a 0.1.0 device run stays in `core/src/test/resources/checkpoint/` as a test of the codec.

### 3.6 `names.json` (draft speaker state)

The members of 0.1.0 are `labels`, `merges`, `remembered`, `automatic` and `generation`. P3 added two optional members:

```json
"live":     { "<speakerId>": { "voice": "<voiceId>", "score": 0.61, "state": "SUGGESTED" } },
"rejected": { "<speakerId>": ["<voiceId>", "..."] }
```

`live` is the latest live match of each speaker (AUTO, SUGGESTED or CONFIRMED). `rejected` lists the voices for which the user answered "Not X". The codec reads a missing member as empty. It writes a member only when the member is not empty. The file is internal, and the app never ships it.

Each running or paused note has one owner of its draft names: `DraftNames`. This class is in `:data`, and `CheckpointRepository` holds it. It keeps an `AtomicReference<CheckpointNames>`. Each change, from the UI or from the job, is an `updateAndGet` with a pure reducer of `CheckpointNames` in `:core`. The reducers are `confirm`, `reject`, `rename`, `merge`, `remember` and `applyLive`. `applyLive` applies the live matches of the job and also sets the `automatic` names. At step 6, the job closes naming with `DraftNames.close()` (P5). This function is not a reducer of `:core`.

After each change, the code writes the current value to `names.json` under the file lock of the note. It reads the reference inside the lock, so the last write always holds the latest state. No code writes a copy that it read earlier.

### 3.7 Length limits

A recording has no time limit. It continues until the user stops it, or until the free space of the device gets to a floor. A transcription has real limits that come from the code and from the format. The table gives each length limit of the app, its cause, and the decision of 2026-10-11 (owner: "If the user started the recording and did not stop it, we should keep recording").

"Real" means that the code or the platform cannot go past the limit. "Policy" means that we selected the value.

| Limit | Where | Value | Kind | Decision |
|---|---|---|---|---|
| Time cap of a recording | `Limits.MAX_RECORDING_MS`, `RecordingPolicy` | 2 h | Policy | Removed. The recording continues until Finish or the storage floor. |
| Warning before the time cap | `Limits.RECORDING_WARNING_MS`, REC_NEAR_LIMIT | 5 min | Policy | Removed, with the banner and the "Stops at 2 hours" hint. |
| Storage floor of a recording | `Limits.MIN_RECORDING_FREE_BYTES` | 200 MiB | Policy | Kept. At the floor, the controller stops the capture, saves the file and shows REC_STOPPED_STORAGE. The space is for the end of the file, the database and the rest of the phone. |
| Wake lock of a recording | `RecordingController.WAKE_LOCK_TIMEOUT_MS` | 2 h 10 min | Policy | Changed to a 10 min timeout. The policy loop renews the lock each minute. Thus the lock lasts as long as the recording, and a dead process cannot keep it. |
| Opus capture file (API 29+) | `OggOpus.LiveMuxer`, `OpusCapture` | 2^63 samples, 2^32 pages | Real, not reachable | No change. The granule and the sample counts are `Long`. One page holds one 20 ms packet, so the 32-bit page sequence lasts 994 days. |
| AAC capture file (API 26-28, and phones without an Opus encoder) | MediaRecorder MP4 (AOSP `MPEG4Writer`) | 4 GiB (about 298 h at 32 kb/s) | Real (calculation) | The MP4 file has 32-bit chunk offsets. MediaRecorder also keeps the index of the file in memory until the stop: 4 bytes for each 21.3 ms frame. We selected a lower limit, `RecordingPolicy.AAC_MAX_FILE_BYTES` (1 GiB, about 74 h). 15 min before it, the storage banner shows the file copy ("Recording file almost full"). At the limit, the policy loop stops the capture and saves it (REC_STOPPED_FILE_LIMIT). The copy says that Cassini ends M4A recordings at 1 GB. The space hint counts the room left in the file. We did not measure this limit on a device. |
| Import size | `Limits.MAX_IMPORT_BYTES` | 2 GiB | Policy | Changed to 4 GiB. The copy, the hash and the WAV reader use `Long` sizes. A WAV file cannot be larger than 4 GiB (32-bit sizes). At a speech bitrate, 4 GiB of compressed audio is more than 36 h. |
| Free space for an import, a cache copy and the job | `Limits.MIN_FREE_BYTES` | 64 MiB | Policy | Kept. |
| Duration of one transcription | `Limits.MAX_TRANSCRIBE_MS` (was `MAX_RECORDING_MS`) | 2 h | Real at 37.28 h | Changed to 36 h. Two 16 kHz sample counts of the verbatim engine are 32-bit: the constant `Limits.MAX_SAMPLES`, and the sample count of `PcmFile`. 2^31 samples are 37.28 h. Other code (`IncrementalTranscript`, `StreamingOpusEncoder`, `AudioDecoder`) uses `Long` positions and compares them with `MAX_SAMPLES`. The job refuses longer audio before the download and the model load (`Failure.LONG`). A recording that is longer does not go into the queue, and its Note screen tells why. |
| Frame positions of the decoder | `AudioDecoder`, `WavDecoder.framePosition` | 2^31 frames at the source rate | Real | Changed to `Long` (a permitted verbatim edit). Before, an `Int` overflowed after 12.4 h of 48 kHz audio and after 6.2 h of 96 kHz audio. |
| Word body of one transcript | `CassiniPayload.MAX_RAW` (verbatim format code) | 16 MiB of JSON | Real | Kept. The body is pretty-printed JSON of about 151 bytes for each word (measured, with the speaker IDs of the job). 16 MiB holds about 111,000 words: 11.6 h at 9,500 words an hour. The job counts the body after each window (`WordBodyBudget`). When the words do not fit, it stops with `Failure.LONG` at that window, not at the seal. `Failure.LONG` is a final failure: the banner has no Try again and no Start over, because each new try fails at the same point. When the user dismisses it, the app removes the job and its work directory. The message tells the user that the audio stays and that Save a copy exports it. |
| Header of the document | `OggOpus.MAX_HEADER_BYTES`, the chunk count, the comment count | 8 MiB, 4,096 chunks of each body, 20,000 comments | Real | Kept. A body at its limit is about 1.1 MiB after gzip and 384 chunks (measured). Thus about five long versions fit in one document. |
| `checkpoint.json` | `CheckpointState` version 1 | Grew with the recording | Real (time and wear) | Changed to version 2 with `work/words.log` (section 3.5). The measurements follow this table. |
| Sortformer state | `SpeakerBudget.DIARIZER_STATE_FLOATS` | 200,000 floats | Real, fixed size | No change. The saved state has a fixed size: 7 + 188 x 512 + 188 x 4 + 188 x 512 = 193,271 floats for the pinned model. It does not grow with the recording. |
| Voice clips | `SpeakerBudget` | 8 speakers x 45 s | Policy, fixed size | No change. It does not grow with the recording. |
| Word cache in Room | table `words` | One row for each word | No limit | No change. 9,500 rows load in about 210 ms (section 3.3), so 111,000 rows load in about 2.5 s. The first 200 rows load in less than 10 ms. One `note_text.body` row holds about 7 bytes for each word (0.8 MB at the body limit), less than the 2 MB cursor window. The queries read only a prefix or a snippet of it. |
| Exports | `TextExport`, `SrtWriter`, `VttWriter` | No limit | – | No change. They write 11 h of words in less than 1 s on the JVM (`LongExportTest`). |
| Progress and time left | `TranscriptionProgress`, `ProgressTracker` | No limit | – | No change. All times are `Long` milliseconds. The text form is "N h M min". |
| Background budget of Android 15+ | `mediaProcessing` foreground service type | About 6 h in each 24 h | Real (platform) | No change in the mechanism (section 5.7). The job pauses with its checkpoint (PAUSED, `SYSTEM`). The banner and the notification now tell the user about the daily limit of Android. Resume continues from the checkpoint. |
| Free space for the seal | `TranscriptionJob.requireSpace` | Audio length + 64 MiB | Real | No change. The sealed document is a copy of the audio (21.6 MB for each hour of Opus at 48 kb/s). A recording that stopped at the storage floor can only be transcribed after the user frees space (`Failure.SPACE`). |

Measurements of the checkpoint of each window (`LongRunMeasurementTest.checkpointPathOfATenHourRun`, JVM on the development computer, 2026-10-11). The time is the whole path of the job: the state of the recognizer and of the diarizer, the word log, and the encoding of `checkpoint.json`. The synthetic run has these properties:

- 10 h of speech at 9,500 words an hour
- A checkpoint after each 26 s window (1,384 windows)
- The Sortformer state of the pinned model
- One speaker turn for each 23 words

| Variant | `checkpoint.json` at 9 h | Bytes written in 10 h | Time of the path in 10 h | Time of the path in hour 10 |
|---|---|---|---|---|
| Version 1: every word inline, the recognizer state as JSON | 10.6 MB | 8,469 MB | 2,215 s | 344 s |
| Version 2 with `words.log`, the recognizer state as JSON | 1.29 MB | 1,655 MB | 1,310 s | 247 s |
| Version 2 with `words.log`, the typed recognizer state (the job now) | 1.29 MB | 1,655 MB | 82 s | 8.5 s |

In the first two variants, the recognizer gave its state as JSON: each window wrote all words as a pretty-printed word body, and the job parsed it again. Thus the time of each window increased with the words, and the memory peak was several copies of the transcript. `IncrementalRecognizer` now gives a typed `RecognitionState` (a permitted verbatim edit), and the job makes JSON only for the open words. The time of each window is now the same for each hour.

With version 2, the bytes that each window writes do not grow with the words. Most of the remaining 1.2 MB of each checkpoint is the Sortformer state and the 2 s of audio of the open window. This part is the same for each window, so the total increases linearly with the recording (about 160 MB for each hour). The diarizer turns stay inline, at about 22 bytes for each turn. A phone is slower than the development computer.

Measurements of the word body (`LongRunMeasurementTest`, the same synthetic words):

| Speech | Words | Body | After gzip | Chunks |
|---|---|---|---|---|
| 1 h | 9,523 | 1.35 MB | 0.10 MB | 36 |
| 8 h | 76,190 | 10.96 MB | 0.82 MB | 280 |
| 11 h | 104,761 | 15.09 MB | 1.12 MB | 384 |
| 12 h | 114,285 | 16.47 MB | more than the limit | – |

These changes can make the transcription limit longer. They are open decisions for the owner:

- A body in compact JSON, as the specification requires (section 12). This gives space for about two times more words.
- Shorter speaker IDs. The ID of the job (`diar_<UUID>_N`) is 43 of the 151 bytes of each word.
- The 64 MiB limit of the specification for a body. Other Cassini readers accept it, but the Android reader keeps the parsed words in memory.
- More than one note for a very long recording.

## 4. Repositories (`:data`)

```kotlin
class AppPaths(val root: File, val cache: File, val modelRoot: File = root) {
    val database = File(root, "cassini.db"); val settings = File(root, "settings.preferences_pb")
    val notes = File(root, "notes"); fun note(id: String) = File(notes, id); fun work(id: String) = File(note(id), "work")
    val voices = File(root, "voices"); val people = File(voices, "people.json"); val generation = File(voices, "generation")
    fun notePrints(id: String) = File(File(voices, "notes"), "$id.json")
    fun pendingPrints(id: String) = File(File(voices, "pending"), "$id.json")
    val exports = File(cache, "exports")
}
```

These rules apply to all repositories:

- The `suspend` functions operate on IO. The `Flow`s are cold Room or DataStore flows.
- The `…Blocking` functions have `@WorkerThread`. They assert that they are not on the main thread. Only the inference thread uses them.
- The locks are `java.util.concurrent.locks.ReentrantLock`s. `NoteLocks` gives one lock for each note (striped by note ID), and each store that uses files has one lock. The suspend paths (inside `withContext(IO)`) and the blocking paths take the same lock.
- Code never suspends while it holds a lock: `withContext(io) { lock.withLock { blockingWork() } }`.
- The code uses a Kotlin `Mutex` only where each holder is a coroutine (`NativeBudget`, `ModelInstallOwner`).
- Errors go to the UI as sealed outcomes with a `Failure` of `:core`.
- No file I/O occurs on the main thread. Debug builds set `StrictMode.detectAll().penaltyLog()`.

### 4.1 `NoteRepository`

- Reads: `library()`, `search(query)`, `note(id)`, `trash()` and `counts()`. `search` uses `SearchQuery.fts`. For a blank query, it gives `library()`.
- Recording: `createRecording(title, createdAt, format)` makes the directory and a `LIVE_CAPTURE` row before the capture starts. The other functions are `finishRecording(id, durationMs)` and `markUnplayable(id)`.
- Rename: `rename(id, title)` updates `notes.title` and `note_text` in one transaction. For a `DOCUMENT` note, it then writes the document title again with `DocumentRepository.retitle`, in the background. It does this only when no job row exists and the document state is `OK`. Else it sets `title_dirty`, and adopt applies the title later. A document that is not `OK` keeps the new title in Room only, and a later new seal uses it. If the background write does not occur or fails, `DocumentRepository.retitleDirty` at the next start, or `exportCopy`, writes the title.
- Position and variants: `setPosition(id, ms, final)` records the playback position. With `final = true` (at pause and when the user leaves the screen), it calculates `notes.preview` again with `PreviewText` of `:core`, from the words of the selected variant. `selectVariant` writes `note_text.body` and `preview` again. The last function in this group is `acknowledgeTrust`.
- Trash: `moveToTrash` records `trashed_job`. `restore` puts a QUEUED `trashed_job` again at the end of the queue (the trash kept the PAUSED jobs). The other functions are `deleteNow`, `emptyTrash` and `purgeExpired(now, zone)` (section 4.10).
- Adopt: `adoptSealed(id, selectVariant)` and `deleteAdoptedWork(id)`. The paragraphs after this list describe them.
- Other functions: `refreshIfStale(id)`, `titleBlocking(id)` (the seal reads the title with it) and `rowBlocking(id)`.

`adoptSealed(id, selectVariant)` installs `work/sealed.opus` as section 3.2 describes. One transaction updates the row, the variants, the speakers and `note_text`, and removes the job row. Then the function deletes the previous plain file. If the note title is different from the document title or from its first `TITLE` tag, the function writes the note title into the document. The links are not in this transaction: `VoiceRepository.adoptPending` writes them after it. `work/` and its `sealed` marker stay.

`adoptSealed` is idempotent (you can run it again without side effects). If `document.opus` already has the sealed signature, it does only the remaining steps.

`deleteAdoptedWork(id)` is the last step of an adopt. It deletes `work/`, and the last file that it deletes is the marker. `SealedAdopter.of` does `adoptSealed`, `VoiceRepository.adoptPending` and `deleteAdoptedWork` in this sequence. Then it releases the draft names in memory, so that the next job of the note starts with no names.

If a crash or an I/O error occurs before the end, the marker stays, and the next start does the adopt again. If `adoptPending` cannot read a voice file at that time (`Kept(UNREADABLE)`), the marker also stays. A voice file from a newer Cassini (`Kept(NEWER)`) does not stop the adopt. The pending file stays until the next job of the note writes a new one. `JobRepository.enqueue` also finishes such an adopt before it adds a job (section 4.7).

### 4.2 `ImportRepository`

`import(uri): ImportOutcome` gives `Created(noteId)`, `Existing(noteId)` or `Rejected(failure, detail)`. It does these steps:

1. Read `OpenableColumns.SIZE` and `DISPLAY_NAME`. Reject a file larger than `Limits.MAX_IMPORT_BYTES` (4 GiB) with `LARGE`. `Limits` is in `core/audio/AudioLimits.kt`.
2. Make sure that `usableSpace ≥ size + Limits.MIN_FREE_BYTES`. If not, reject the file with `SPACE` and the necessary bytes. If the space goes during the copy, `SPACE` also gives `size + MIN_FREE_BYTES`, because the import deletes the copied part.
3. Copy the stream into `notes/<newId>/source.tmp`. Calculate the SHA-256 and apply the size limit during the copy.
4. If the hash is equal to the `source_sha256` of a live note, delete the new directory and return `Existing`. Ignore a match with a note in the trash.
5. Classify the file. The paragraph after this list gives the classes.
6. Get the duration (`OggOpus.scan` or `MediaExtractor`) and insert the row. The paragraphs after this list give the title.
7. If a storage error occurs after the copy (a failed rename, read or insert), give `Rejected(SAVE)`. A full database gives `Rejected(SPACE)`. Then delete the new directory.

The classes of step 5 are:

- Ogg Opus with Cassini metadata becomes `document.opus`, with `readChecked` and the origin `IMPORTED_DOCUMENT`.
- Other Ogg Opus becomes `source.opus`.
- For RIFF/WAVE, `WavDecoder` examines the header. Each format except 16-bit PCM gives `Rejected(WAV)`.
- Each other file needs an audio track in `MediaExtractor` (behind an `AudioProbe` interface for the tests). Without a track, the result is `Rejected(OPEN)`.

The title of step 6 is the `meeting.title` of a document. For other files, it is the display name without the extension. It is never an existing `TITLE` tag, so that one source sets the title. If a `content://` Uri has no display name, the note gets the fallback title ("Imported audio", localized). The last path segment of such a Uri is an ID, not a name. The adopt of the first seal writes the note title into the `TITLE` tag (section 4.1).

An import can operate while a job runs.

### 4.3 `DocumentRepository`

- `sealBlocking(noteId, audio, transcript, title, processing, existing, labels)` writes `work/sealed.opus` with `CassiniDocument.create` and does an fsync. It reads the file again and makes sure that it is correct. Then it writes the marker `work/sealed`. It never touches `document.opus`. `TranscriptionJob` does not use `sealBlocking`, because the job must write the pending prints before the marker (section 5.4).
- `markSealedBlocking(noteId, document)` writes only the marker and does an fsync of the marker and the directory. It puts the checked `document` in the LRU cache, with its signature as the key. Thus adopt does not read and hash the sealed file again (amendment FAST NOTE OPENING, item 1). The `:app` adapter of `JobStorage.markSealedBlocking` calls it.
- `read(noteId)`: `readChecked`, with a check of the digest. An LRU cache in memory holds 4 entries, with the signature as the key.
- `relabel(noteId, labels)`.
- `retitle(noteId)` reads the title from the row and sets `meeting.title` and `TITLE`. It requires `OK`, and it refuses while a job row exists.
- `mergeSpeakers(noteId, variantId, source, target)` makes a new variant with `SpeakerMerges.apply` and `SpeakerMerges.processing`. The new variant becomes the default and the selected variant. The function refuses while a job row exists.
- `exportCopy(noteId, dest)` copies the document byte for byte. A plain note copies its audio file. If an `OK` document has `title_dirty`, the function first writes the title into the document.
- `exportTranscript(noteId, request: TranscriptExport, dest)`: `TranscriptExport` holds the variant, the format, the labels and the header.
- `deleteFiles(noteId)` and `sweep()`.
- `retitleDirty()` writes the title of each `OK` document note that has `title_dirty` and no job row.

### 4.4 `CheckpointRepository`

- `loadBlocking(noteId, expected)` gives `Valid(state)`, `Missing` or `Mismatch(reason)`.
- `saveBlocking(noteId, state)` writes with AtomicFile, after the fsync of `audio.opus.part` and `clips.pcm`, and after the append and the fsync of `words.log` (section 3.5).
- `draftNames(noteId): DraftNames` (section 3.6) loads `names.json` one time for each process.
- `discard(noteId)`.
- `dropSealed(noteId)` and `dropSealedBlocking(noteId)` (section 5.2, rule 5) delete the `sealed` marker, do an fsync of `work/` and then delete `sealed.opus`. The checkpoint, `names.json`, `clips.pcm` and `audio.opus.part` stay.
- `status(noteId)` gives done/total, a mismatch, or a sealed document that waits for adopt.
- `loadDraft(noteId)` gives the words of the saved chunks with the names applied, for a paused note.
- `load(noteId)` and `identity(noteId)`. The identity comes from the note row.
- `sealedMarker(noteId)` is true when `work/sealed` exists.
- `hasWork(noteId)`.
- `release(noteId)` removes the draft names from memory. It takes the note lock on IO.

### 4.5 `VoiceRepository`

This repository has all files under `voices/`, with one lock for each file.

- `people(): Flow<SavedVoices>` gives the list of `SavedVoice` with the note counts, the confirmed seconds and the stale flag (`VoiceStaleness`). It also gives the problem of `people.json`, if there is one.
- `rename`.
- `forget(voiceId)` removes the person. It removes the matches of the person from each file under `voices/notes/` and `voices/pending/`, and from the links. It also removes them from `live`, `remembered` and `automatic` of each `DraftNames` in memory and of each `names.json` on the disk.
- `forgetEveryone()` first writes `generation` = old + 1. Then it deletes all other files under `voices/` (`pending/` too) and clears the links. It clears `live`, `remembered` and `automatic` in each draft, as `forget` does.
- `prints(noteId, variantId)`, `setMatch`, `enrol`, `unenrol` and `copyPrints` (for a merge).
- `deleteForNote` deletes the sealed file, the pending file and the links.
- `discardPending(noteId)` deletes only the pending file. The sealed file and the links stay.

`enrol` writes the match in the note file first and `people.json` second. If the second write fails, it clears the match again. `unenrol` writes `people.json` first and clears the match second. If the second write fails, it puts the enrolment back. Thus, after a write error, a new attempt changes the person only one time.

The job has blocking entry points: `peopleBlocking`, `generationBlocking`, `savePendingBlocking(noteId, prints, generation)` and `discardPendingBlocking(noteId)`. The job uses them through the `JobVoices` port (section 5.4): `peopleBlocking`, `generationBlocking`, `noteVoicesBlocking(noteId)`, `savePendingBlocking(noteId, variantId, model, prints, generation)` and `discardPendingBlocking(noteId)`. The `:app` adapter `RepositoryJobVoices` connects the port to this repository.

`adoptPending(noteId)` compares the generation of the pending file with the current generation. If they are equal, it merges the file into `voices/notes/<id>.json`, without the matches to voices that do not exist at that time. If they are different, it does not merge the file. Then it deletes the pending file. The pending file stays (`Kept`) in these conditions:

- A voice file of the note is newer, or the repository cannot read it.
- The repository cannot read `generation`.
- The repository cannot read `people.json`, and the draft has Remember choices.

A voice file whose `version` is newer than the codec stays in place, read-only, and the repository reports it. Only a file with JSON that does not parse gets the name `.corrupt-<epoch>`. Then the Saved voices screen shows an error banner and disables the edits. A damaged `people.json` is read-only too. No edit and no enrolment writes a new list over it. The problem stays, also after a restart, while the copy `people.json.corrupt-<epoch>` exists. Only "Forget everyone" deletes that copy and lets the user edit the list again (`SavedVoices.canForgetEveryone`).

### 4.6 `ModelRepository`

`status: StateFlow<Map<ModelPart, ModelStatus>>` gives the state of three parts: `SPEECH` (Parakeet, and the VAD as best effort), `SPEAKERS` (the diarizer) and `VOICES` (the voiceprint model). A best-effort part can fail, and the job continues without it. `ModelStatus = Ready | Missing(consented) | Downloading(pct, bytes, total) | Verifying(pct) | Failed(error)`. The repository also has `consented()`, `recordConsent()`, `recordDeclined()` and `plan()` (`ModelPlan` of `:core`). This version has no delete API, because the design has no screen for it.

`ModelInstaller` does not call `ModelRepository` directly. It uses the `ModelStore` port in `:engine` (`ModelInstaller.kt`). `:app` implements this port over `ModelRepository` and the connectivity service. All functions of `ModelStore` block, and they operate on IO:

- `consented()`: the app recorded consent, or Parakeet is installed.
- `hasValidatedNetwork()`: the active network has `NET_CAPABILITY_VALIDATED`.
- `ready(model: ModelDescriptor)`: the marker of the model holds the pinned value.
- `usableBytes()`: the free bytes under `AppPaths.modelRoot`.
- `installBlocking(model: ModelDescriptor, progress: (Long, Boolean) -> Unit)`: it installs one model (not one part) through `ArtifactFetcher`. The progress gives the bytes of this model and a flag for the check of the files ("verifying").

### 4.7 `JobRepository`, `SettingsRepository`, `RecoveryRepository`

`JobRepository` uses the Room table `jobs`. Only `ProcessingCoordinator` uses this repository. It has these functions:

- `queue()`.
- `enqueue(noteId, kind)`. If the row is QUEUED or RUNNING, nothing occurs. A PAUSED, FAILED or NEEDS_CONSENT row goes again to the end of the queue, with no failure, no pause reason and 0 attempts. If the note has no job row but has a `work/sealed` marker, an adopt did not finish. Then `enqueue` first runs `SealedAdopter`. If the marker stays, `enqueue` refuses with `ADOPT_PENDING`.
- `claimNext()`: one transaction. It fails if a RUNNING row exists.
- `markRunning`, `markPaused(reason, done, total, words)`, `markFailed`, `markNeedsConsent` and `remove`.
- `reconcileOnStart(activeNoteId)` (section 5.3). It is the only function that increases `attempts`.

`SettingsRepository` has typed accessors for the keys of section 3.4.

`RecoveryRepository.recover(activeNoteId)` repairs the `LIVE_CAPTURE` rows that are not the active capture. `OggRepair` repairs an `.opus` file, and the row becomes `PLAIN`, with the duration from the end granule. An `.m4a` row becomes `PLAIN` if `MediaExtractor` opens the file, else `UNPLAYABLE`. The function returns the IDs of the recovered notes for the "recording recovered" banner.

### 4.8 Startup work

`CassiniApplication` does these steps on IO, in the application scope. It does them one time for each process, also when only a service starts the process. Then it completes `AppGraph.ready`.

1. Open Room. If this fails, rename the file and rebuild the catalogue (section 4.9). Also rebuild the catalogue when a note directory with audio (`document.opus`, `capture.*` or `source.*`) has no row. Thus the next start finishes a rebuild that a crash or an error stopped. Some errors do not show a damaged file: a full disk, an I/O error, a lock error, a permission error or a read-only database. For these errors, the opener throws the error, and the file stays (section 3.3). Then show an error and do not rebuild.
2. Run `RecoveryRepository.recover(RecordingController.activeNoteId)`.
3. Run `JobRepository.reconcileOnStart` (section 5.3). It finishes the adopts that a stop interrupted. It does not delete the `work/` of a note that has no row.
4. Make `note_voice_links` the same as `voices/notes/*.json` again, in one transaction. Delete the voice files of a note when the note row and the note directory do not exist.
5. Do the trash purge (section 4.10).
6. Do the sweep. Delete the `*.tmp` files older than 1 h that no job or import owns. Delete `cacheDir/exports/`. Delete a note directory without a row only if it holds only `*.tmp` files and is older than 1 h. A sweep never deletes audio.
7. Run `DocumentRepository.retitleDirty()`. It writes the titles that a rename did not write into the document.

The step `models` (between steps 1 and 2) reads the readiness of the models.

### 4.9 Catalogue rebuild

`CatalogueRepository.rebuild()` is idempotent and never deletes audio. It adds only the notes that have no row. Thus a second run finishes a rebuild that stopped. An I/O or SQLite error in one directory does not stop the other directories. The function logs the error (`RebuildReport.failed`). For each `notes/<id>/`, it does these steps:

- `document.opus`: `readChecked`, then a row and the cache rows. The origin is `RECORDED` if the vendor is cassini-android, else `IMPORTED_DOCUMENT`. The title comes from `meeting.title`.
- `capture.*` or `source.*`: it repairs a `capture.opus` that does not end correctly. The row gets `PLAIN` (`UNPLAYABLE` for an m4a file that does not open), `title = "Recovered recording <d MMM>"` and `origin = RECOVERED`.
- A `work/checkpoint.json` that decodes: a PAUSED job with `pause_reason = INTERRUPTED`. The rebuild reads the checkpoint before it writes the row. It writes the row and the job in one Room transaction. Thus a crash or an SQLite error cannot leave a row without its job. Without this rule, the next rebuild does not see the note, and `reconcileOnStart` deletes the checkpoint as orphaned work.
- A `work/sealed` marker: after the row, the rebuild finishes the adopt (`SealedAdopter.of`). The adopt removes the job row. If the rebuild cannot read the sealed output, the PAUSED job stays.
- `voices/notes/*.json`: `note_voice_links` rows. The function deletes a file whose note directory does not exist.

### 4.10 Trash purge

`TrashPolicy` (`:core`, `java.time` in the time zone of the device) calculates `daysLeft = 30 − daysBetween(localDate(trashedAt), localDate(now))`, with a minimum of 0. A note expires when `daysLeft == 0`.

The purge operates at each process start (after the database opens) and when the Trash screen opens. It operates immediately for "Delete now" and "Empty". For each note, it does these steps in this sequence:

1. It cancels and removes the job, if there is one.
2. It calls `VoiceRepository.deleteForNote`.
3. It deletes `notes/<id>/` recursively (audio, document, checkpoint, clips, sealed output).
4. It deletes the row. The deletion cascades to the variants, speakers, text, FTS, links and jobs.

The files go before the row. After a crash in the middle, an expired row stays in the trash, and the next run finishes it. With the opposite sequence, the catalogue rebuild can bring the audio back. The purge does not unenrol saved people. This is an open question for the owner.

The purge reads its list of notes first, and Restore can operate after that. Thus, before step 1, the purge claims the note under the lock of the note in `NoteLocks`. Under the lock, it reads the row again. It continues only if the note is still in the trash and the trash time agrees with the request: older than the cutoff for the expiry purge, the same time as in the list for "Empty". Restore takes the same lock. It refuses a note that a purge claimed. The claim ends when the purge ends. Thus, if the purge stops, the note stays in the trash, and Restore can bring it back. Such a restored note keeps its document, but it can lose what the purge deleted before it stopped: the job row, the voice files and the links to saved voices. The claim is in memory in `NoteRepository`, because the app has one `NoteRepository`.

The purge does not use WorkManager, for these reasons:

- The copy promises "after 30 days".
- The data never leaves the private storage of the app.
- Each start of the app or of a service runs the purge.
- WorkManager adds a scheduler and a database for one idempotent query.

`PRIVACY.md` says: "Cassini deletes a note in Trash the first time that Cassini starts on or after the 30th day."

## 5. Background work

### 5.1 Components

| Component | Module | Owns |
|---|---|---|
| `JobRepository` | `:data` | The durable queue (Room `jobs`). |
| `TranscriptionJob` | `:engine` | One run of the pipeline for one note: the `MainActivity.transcribe()` of 0.1.0 as named steps. |
| `ModelInstaller` | `:engine` | The installation sequence: Parakeet, then the VAD, the diarizer and the voiceprint model (each as best effort). |
| `ModelInstallOwner` | `:app` | The only caller of `ModelInstaller`. It does one installation at a time. The jobs and the standalone download share it. |
| `ProgressTracker` | `:engine` | It changes the job events into `RunningProgress` and `DraftState`, at 1 Hz. It owns `TranscriptionProgress` and `BatteryProjection`. |
| `InferenceDispatcher`, `NativeBudget` | `:engine` | One `cassini-inference` thread, and the `Mutex` for model loads. |
| `ProcessingCoordinator` | `:app` | The queue logic, the drain loop, `StateFlow<ProcessingState>`, the draft actions and the holds. |
| `ProcessingService` | `:app` | Only the foreground lifetime: the type, the wake lock, the notification and `onTimeout`. |
| `RecordingController` | `:app` | `MicrophoneCapture`, `StateFlow<RecordingState>`, the levels, the policy loop and `activeNoteId`. |
| `RecordingService` | `:app` (`recording/`) | The foreground lifetime of the microphone, the notification, and the hand-off to the processing. |
| `PlaybackController` | `:app` | `MediaPlayer`, the position flow, the audio focus and `playRange`. |
| `NoticeCenter` | `:app` (P10) | It merges the state of the coordinator, the recorder, the recovery, the models and the imports into the banner list of each screen. |

### 5.2 The transcription queue

1. The process runs one job at a time, on the inference thread. The queue is FIFO by `seq`. This version cannot change the sequence of the queue.
2. Seven actions add a job to the queue. Finish of a recording with consent adds it automatically. Transcribe on a plain note and Transcribe again on a document (it adds a variant) add it. Resume, Try again (FAILED) and Start over add it. Restore of a note in the trash adds it if its `trashed_job` was QUEUED. Resume puts a PAUSED job at the end of the queue, and the job starts immediately if no job runs. Start over discards the checkpoint and then adds the job.
3. These notes cannot go into the queue: a note that records, a note in the trash, and an `UNKNOWN_FORMAT` document.
4. When a job ends (Done, Paused, Failed, NeedsConsent, Cancelled), the coordinator claims the next QUEUED job. This job operates in the same service, which is already in the foreground. The service stops when the queue is empty or has a hold.
5. Stop and keep progress (in the app, for one note): during the decode, the job saves a checkpoint at its next sample block and throws `ProcessingPaused`. It saves the checkpoint only when at least one window is done. A stop before the first window leaves no checkpoint. After the decode, the final checkpoint stays, and the job stops at its next check. The row becomes PAUSED(USER), and the next note in the queue starts. A stop after the seal step calls `CheckpointRepository.dropSealed`. This function deletes the marker first and then `work/sealed.opus`, and it keeps the complete checkpoint. The job did not touch `document.opus`.
6. Stop in the notification (`stopAll`): it stops the running note as in rule 5 and sets `queue.hold = USER`. The notes in the queue stay QUEUED, but they do not start. The Library shows "Transcription paused · N waiting" with Continue.
7. Remove from queue on a QUEUED note deletes the row, and the note is "Audio only" again. If the note has a checkpoint, it goes back to PAUSED(USER).
8. Cancel is internal. Start over uses it, and Move to trash of a RUNNING note uses it when the user selects to stop. Cancel is `Job.cancel()`, which interrupts the inference thread. A sherpa call that runs at that time finishes first. Then the `finally` blocks release the native handles. After `join()`, `CheckpointRepository.discard` deletes the work directory, and `VoiceRepository.discardPending` deletes `voices/pending/<id>.json`. The coordinator calls the two functions.
9. Move to trash: the app stores the job state in `notes.trashed_job`. It deletes a QUEUED job row, but the `work/` of the job stays. It stops a RUNNING job (PAUSED/USER, and the checkpoint stays) before it moves the note to the trash. It keeps a PAUSED job. Restore puts a stored QUEUED job at the end of the queue, with the numbers of its checkpoint. A PAUSED job stays PAUSED.
10. Holds (`queue.hold`): with a hold, the drain loop does not start the next job. A hold never touches a running job. Continue, or a Transcribe or Resume of the user, clears `USER`. `onTimeout` sets `SYSTEM`. Only Continue or Resume on a visible screen clears `SYSTEM`.
11. The transcription continues while the app records another note. The capture thread runs at `MAX_PRIORITY`. P12 examined the capture for overruns on the emulator (`docs/development.md`, "Acceptance 0.2", check 4).

### 5.3 Restart and process death

When the app makes the coordinator, the coordinator runs `reconcileOnStart` on IO. Until this step ends, it gives only `Loading`.

| Found | Action |
|---|---|
| A `work/sealed` marker with a `sealed.opus` that reads OK (in each job state) | Finish adopt (`adoptSealed` of section 4.1, and `VoiceRepository.adoptPending`) and remove the job. The row never becomes FAILED. If the marker has a `sealed.opus` that does not read correctly, delete the marker and the file. Then the next rules apply to the row. |
| A RUNNING row with no checkpoint (the process stopped during the download, the model load or the first window) | The row becomes QUEUED and keeps its `seq`. `attempts + 1`. After more than 2 attempts, the row becomes PAUSED(INTERRUPTED) with `done_ms = 0`. Thus a crash during the model load cannot start the job again without end. |
| A RUNNING row with a valid checkpoint | The row becomes QUEUED and keeps its `seq` (at the start of the queue). `attempts + 1`. After more than 2 attempts, the row becomes PAUSED(INTERRUPTED), and the "Transcription stopped" notification gives Resume. |
| A PAUSED row with no checkpoint | The row stays PAUSED with `done_ms = 0`. Resume starts from the beginning. |
| A RUNNING or PAUSED row with a checkpoint that fails `validate` | FAILED(`CHECKPOINT_MISMATCH`): "Saved progress no longer matches". |
| A `work/` directory with no job row and no marker | Delete it, because the app makes each work directory after its row. Two exceptions apply. The `work/` of a note in Trash stays, because Trash deleted the row of its QUEUED job, and Restore needs the checkpoint. The `work/` of a note without a row also stays, because the catalogue rebuild uses it. |

Then the queue starts in one of two ways:

- Android delivers the service intent again (`START_REDELIVER_INTENT`). The service calls `coordinator.drain()`, and it ignores the action of the intent. Android sends each pending intent again, and Room and the settings already keep the queue and the holds. Thus the service does not do a Resume, a Stop or a Cancel a second time. On all API levels, the system restart of a foreground service after a crash is best effort. Android 12 and later can refuse the foreground start, and section 5.7 catches this error.
- `ProcessLifecycleOwner` gives ON_START (the app is visible). If QUEUED rows exist, no job runs, consent exists and no hold is set, the visible app starts the service. PAUSED jobs never continue automatically.

| Event | Result |
|---|---|
| Android makes the activity again (rotation, locale, dark mode, font scale) | Nothing occurs. The ViewModels collect again by note ID. |
| Android trims the UI while the service runs | Nothing occurs. The process stays alive. |
| Android kills the process (low-memory kill, native crash, or `kill -9` in a test) | The job continues from the checkpoint of the last window. It loses at most one 28 s window of work. At most 2 automatic attempts occur. |
| Force-stop | No redelivery. At the next launch, the RUNNING row becomes QUEUED and continues at ON_START. |
| Reboot | Nothing runs until the user opens the app. Then the work in the queue continues as this section describes. |
| Java `OutOfMemoryError` | The job boundary catches it. The job becomes PAUSED(MEMORY), and the checkpoint stays. The checkpoint and the redelivery cover the native out-of-memory kills. |

`adb shell am kill` does not kill a process that runs a foreground service. Thus the device checks use `adb shell run-as org.cassini.android kill -9 <pid>` (section 9).

### 5.4 `TranscriptionJob` and its ports

`TranscriptionJob` moves the `MainActivity.transcribe()` of 0.1.0 (lines 249-538) into named steps. These items do not change: the sequence of the steps, the checkpoint cadence, the provenance keys and the label precedence. One exception applies to the cadence: the job saves no checkpoint before the first window is done (section 12). The provenance keys are `x-inferenceMs`, `x-segmentation`, `x-resumedFromMs`, `x-speakerDiarization`, `x-incrementalDiarization`, `x-speakerMerges` and `x-speakerIdentification`. The label precedence is the draft name, then the AUTO match, then `Speaker N`. The seal target and the adopt sequence change, as section 3.2 describes.

The ports are interfaces in the `job/` package of `:engine`. `:app` implements them over `:data`. `JobPorts.kt` is the contract. The KDoc of each port function gives its thread.

- `JobStorage`: `loadCheckpoint`, `saveCheckpoint`, `names` (the `AtomicReference` of the `DraftNames` of the note), `mutateNames(reducer)`, `openNames`, `closeNames`, `audioFile`, `knownDurationMs` (`notes.duration_ms`, the first total of the progress), `workFiles` (`audio.opus.part` and `clips.pcm`), `sealTarget` (`work/sealed.opus`), `withNoteLockBlocking`, `existingDocumentBlocking`, `titleBlocking`, `markSealedBlocking(noteId, document)` (with the checked document that `CassiniDocument.create` returned, for the LRU cache), `discardSealedBlocking` (it deletes the marker and `work/sealed.opus`) and `adopt(noteId, Adoption)` (a suspend function that runs in `NonCancellable`).
- `JobVoices`: `peopleBlocking`, `generationBlocking`, `noteVoicesBlocking`, `savePendingBlocking` and `discardPendingBlocking`. The port has no `enrol`, because adopt enrols the draft "Yes, Luca" choices from `Adoption.remembered`.
- `ModelAccess`: `consented`, `ready(part)`, `detectorReady` (the VAD), `plan`, `hasValidatedNetwork` and `install(parts, onProgress)` (it delegates to `ModelInstallOwner`). If Parakeet is ready but the VAD is not, and the network is validated, the job asks for SPEECH again. Then the installer gets only the VAD, as best effort.

The job seals the document itself. Under `withNoteLockBlocking`, it reads the existing document and the title. It writes `sealTarget` with `CassiniDocument.create`, which makes sure that the file is correct. Then it does an fsync of the file, writes or discards the pending prints, and calls `markSealedBlocking`. `:app` does not implement a `sealBlocking` for the job (section 4.3).

`existingDocumentBlocking` gives the document in its current state. The job adds a variant only to an `OK` document. For a STALE_AUDIO, INVALID_METADATA or UNREADABLE document (the list of features.md section 6), the job seals a new document over the same audio. First, it copies the audio, packet for packet, into `work/audio.opus.part` without the `CASSINI_*` tags, so that no old transcript body stays. The names of such a document do not go into the new document.

The job refuses an UNVERIFIED or UNKNOWN_FORMAT document with `JobFailure.DocumentRefused` (code `DOCUMENT_UNVERIFIED` or `DOCUMENT_UNKNOWN_FORMAT`). It does this at step 1, and again under the lock at step 9. Such a document cannot take a new variant. A new document does not keep the earlier versions, and features.md permits this only after a confirmation of the user. 0.1.0 refused each state except `ok`. The owner can add UNVERIFIED to the list of features.md, with the same confirmation. Then the job must permit it.

If the diarizer fails after some windows, the words of those windows keep their speakers, as in 0.1.0. The seal still records `x-speakerDiarization`, and the matching operates on their clips. Only a failure at the first window gives no turns and no diarization record, and thus `speakersSeparated = false`. `JobEvent.NamingClosed(matching)` tells the tracker if step 7 runs.

The live matching, the final matching and the labels use the saved voices as they are at that time, not the list of step 1. Thus the job does not recognize again a person that the user forgets during the run. If "Forget everyone" changes the generation during the run, the job uses no saved voices.

`JobOutcome` is `Done`, `AdoptPending`, `Paused(reason)`, `Failed(failure)`, `NeedsConsent(missingBytes)` or `Cancelled`. `AdoptPending` means that the marker and `work/sealed.opus` exist, but adopt failed. Then the coordinator finishes adopt as `reconcileOnStart` does (section 5.3), and the row never becomes FAILED. `JobFailure.Download` has `requiredFreeBytes`, the figure that the space check of the installer used ("Free about N MB").

The seams are interfaces in the `job/` package of `:engine` (section 2): `Recognizer`, `StreamingDiarizer`, `PrintExtractor`, `AudioSource` and `OpusSink`.

| # | Step | Thread | Core `Stage` | Design step |
|---|---|---|---|---|
| 1 | `prepare`: load and validate the checkpoint, the voices and the names. Memory check before the start. | IO | – | – ("Starting…") |
| 2 | `installModels` | IO | – | Not a stage: `JobPhase.Downloading` |
| 3 | `restore`: the encoder, the diarizer state, the clips | inference | READING | Reading |
| 4a | Decode until the first 28 s window is full | inference | READING | Reading |
| 4b | The Parakeet decoder loads | inference | PREPARING | Preparing |
| 4c | For each window: recognition | inference | WORDS | Words & voices |
| 4d | For each window: Sortformer, assignment, clip cache, live voice matching | inference | SPEAKERS | Words & voices |
| 4e | Progress, and the checkpoint save (only after the first window is done, so a stop before that leaves no checkpoint) | inference | – | – |
| 5 | `finish`: the last partial window, the final checkpoint, the end of the encoder | inference | WORDS/SPEAKERS | Words & voices |
| 6 | `attribute`: close naming, merges, attribution | inference | – | → Matching |
| 7 | `matchVoices`: voiceprints and `VoiceMatcher.match(excluded = rejected)` | inference | MATCHING | Matching. The stepper shows it as skipped when the job does no matching: no model, no diarizer, or `NamingClosed(matching = false)`. |
| 8 | The labels and `x-speakerIdentification` | inference | – | Matching |
| 9 | `seal` into `work/sealed.opus`. The pending prints go to `voices/pending/<id>.json` with the generation. | inference | SAVING | Saving |
| 10 | `adopt`: install the sealed file, update the row, apply a dirty title with `retitle`, `adoptPending`, enrolment, the finalization estimate, and then delete `work/` | IO, `NonCancellable` | SAVING | Saving |

The position of the stepper only increases: `step = max(step, map(stage))`. After a resume, it starts at Reading again, because the model loads again, and this load is a real Preparing step.

The decode loop is a blocking function on the inference thread, inside `withContext(inference) { runInterruptible { … } }`. It calls the blocking entry points of the ports directly. It sends `JobEvent`s with `trySend` on an unlimited `Channel`. It never uses `runBlocking`. `stop()` sets the stop reason (an `AtomicReference<PauseReason?>`). `onSamples` examines the stop reason at each sample block. `checkActive()` examines it only after the final checkpoint. `cancel()` interrupts the thread. While a cancelled job still holds `NativeBudget`, the next job shows `JobPhase.WaitingForPrevious` ("Finishing the previous note…").

Title: for a new document, the seal uses `titleBlocking`. For Transcribe again, the verbatim seal keeps the existing manifest. Thus adopt compares the sealed title (`meeting.title` and `TITLE`) with `notes.title`. If they are different, adopt calls `retitle`. Then it clears `title_dirty`. The same check corrects a `TITLE` tag that a new seal of an imported Ogg Opus kept from its source.

Live voice matching operates for each window. It operates only for the speakers with at least 4 s of clips and at least 10 s more than at the last attempt. It excludes the rejected voices. It ignores the speakers with a CONFIRMED `live` entry or a label from the user. An AUTO result sets `automatic[id]`, and the label shows an "auto" badge. The Note screen shows a SUGGESTED result as an inline "Is this Luca?" card. It shows the card only while the label is a placeholder, the voice still exists and naming is open.

Some voices are synthetic and local to the note. Their IDs start with `note:`, and Transcribe again uses them for "Speaker names carry over". The job stores a match to such a voice as no match, and the label holds the name. The job changes the names only through `DraftNames`: `applyLive` for the matches and the automatic names, and `close()` at step 6.

At step 6, the job calls `DraftNames.close()` and uses the value that it returns ("Saving — you can rename when it's done"). Each run calls `openNames` at step 3, as 0.1.0 did. If a run closes naming and then ends without adopt (a stop, a failure or a cancel), the job opens naming again. Thus a PAUSED note accepts names.

At step 9, the job writes the final prints and matches to `voices/pending/<id>.json`, with the voices `generation`. Adopt merges them only if the generation did not change. It does not keep the matches to voices that do not exist at that time. Thus a job that finishes cannot cancel a "Forget" or a "Forget everyone". A draft "Yes, Luca" enrols the voice at adopt only if the generation did not change and the voice still exists. A draft "Not Luca" never unenrols a voice, because the draft enrolled nothing.

### 5.5 Progress numbers (`ProgressTracker`)

`ProgressTracker` is one coroutine on `Dispatchers.Default.limitedParallelism(1)`, with an injected clock. `elapsedMs = checkpoint.elapsedMs + (elapsedRealtime − runStart)`. At a resume, `TranscriptionProgress.restore` does not use the first completion after the cold start.

- Percent: `Snapshot.percent`. It only increases. `null` gives an indeterminate bar. The UI shows at most 99 until Done.
- Processed: `doneMs / totalMs`.
- Words so far: the size of the merged word list.
- Headline and window range: `Snapshot.stage/startMs/endMs` (with a 400 ms debounce in `:core`), mapped to `ProgressHeadline` (ui.md section 7). The headline never goes back to Reading after the step is Words & voices or later. Without a diarizer, the WORDS stage shows "Finding words".
- Time left: `Calculating` until 3 windows are done since the start or the restart, or while the total is unknown. `Finishing` at 0. Else `Range(roundedRemaining(low + fin), roundedRemaining(high + fin))`, with `fin = processing.finalizationMs`.
- Battery: `BatteryReader` reads the battery each 10 s, into a new `BatteryProjection` for each run. No reading: hidden. Charging: PluggedIn. Less than 120 s, or a decrease of less than 0.5 %: Measuring. Else: Projected.
- Draft: `tentativeFromMs = doneMs − 2000`. The UI shows the words that end after this time as unsettled.

### 5.6 Models and consent

- Consent means that `models.consentAt` exists or that Parakeet is installed. Only the primary button of the Model download screen records it.
- The app enforces consent two times: in `ProcessingCoordinator.transcribe`, and again in `TranscriptionJob.installModels` through `ModelAccess.consented`. Without consent, the job ends in NEEDS_CONSENT and makes no network call. JVM tests make sure that no code calls `ArtifactFetcher` in this case.
- `ModelInstallOwner` runs at most one installation. A request during an installation joins it. Each set of parts has one shared `Deferred`, and a `Mutex` protects it. A job in `installModels` shows the shared progress as `JobPhase.Downloading`.
- Cancel in a banner cancels the shared installation only when no job waits for it. Else Cancel stops the job, and the `.part` files stay. The note goes back to its earlier state (features.md 13.4): PAUSED(USER) when it has a checkpoint, else the app removes the row.
- The installation goes through `ArtifactFetcher`, with Range resume, in this sequence: SPEECH (4 Parakeet artifacts, required, and the VAD as best effort), SPEAKERS (the diarizer `.zst`, best effort) and VOICES (the voiceprint model, best effort, only with the diarizer). Without a validated network, the installer does not try the best-effort parts, and thus no 30 s timeout occurs. A failed best-effort part never causes the job to fail. Then the result has one speaker or no names.
- `ArtifactFetcher` keeps the `.part` file after a failure and after process death. It continues with `Range: bytes=<len>-`. It accepts a `206` only when the `Content-Range` total is equal to the pinned size. It starts again on a `200`. Before the rename, it makes sure that the size and the SHA-256 are correct. If they are not correct, it deletes the `.part` file. It never replaces a checked file. It keeps the 30 s read timeout, because an interrupt of the thread does not release `HttpURLConnection`. P3 added a progress callback for the digest, for "Checking files — 80%".
- Redirects: `ArtifactFetcher` sets `instanceFollowRedirects = false` and follows the redirects itself. It follows at most 5 HTTPS redirects, and only to the hosts in `ModelCatalog.DOWNLOAD_HOSTS`: `huggingface.co` and its subdomains, the subdomains of `hf.co` (the Xet/LFS CDN), `github.com`, `*.githubusercontent.com` (release assets) and `dist.gocassini.com`. A redirect to a different host gives a download error for the part. P3 recorded in the KDoc the redirect hosts that it saw for each pinned URL (`curl -sIL`). `PRIVACY.md` lists the same domains.
- Free space: `ModelPlan.requiredFreeBytes` = the sum over the missing parts of (the download bytes + the installed bytes when the download is compressed) + `ArtifactFetcher.SLACK_BYTES` (32 MiB). The Model download screen shows this sum ("Needs about 860 MB free").
- Before the first byte of each part, `ModelInstaller` compares the free space at that time with the figure of that part. Thus SPEECH fails with NOT_ENOUGH_STORAGE only when the speech part alone does not fit, and `JobFailure.Download.requiredFreeBytes` is the figure of the speech part. A best-effort part without space goes into `failed` and never causes the job to fail.
- After these checks, only the check of `ArtifactFetcher` before each file occurs: what that file still needs, plus `SLACK_BYTES`. `ModelPlanTest` makes sure that the plan covers each such check. The installer does not use the 128 MiB margin for Parakeet of `InstalledModels` in 0.1.0. Thus free space that is sufficient for the plan is also sufficient for each later check. This is not true if something else fills the disk at the same time.
- Sizes: the UI shows the sizes with `ModelCatalog.mebibytes` and the unit "MB", as in 0.1.0 and the boards (640, 62, 28). The total on the Model download screen is the sum of the rounded part sizes on the screen (730). Thus the numbers on the screen add up. The free-space figure is `mebibytes(requiredFreeBytes)`. It rounds to the nearest 10, as all other sizes do. For a new installation, it is 860, and the installation needs about 861 MiB. The copy says "about", and the check compares bytes.

### 5.7 `ProcessingService` and the behavior of Android 14-16

`ProcessingService : LifecycleService` has only the foreground lifetime.

The manifest declares `foregroundServiceType="dataSync|mediaProcessing"` and the permissions `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `WAKE_LOCK` and `POST_NOTIFICATIONS`.

The intents are `ACTION_DRAIN` and `ACTION_STOP_ALL`. `ACTION_DRAIN` has no note ID, because the queue is in Room. Android delivers this intent again after process death.

`onStartCommand` calls `startForeground` immediately, with the current notification. A `try`/`catch` for `ForegroundServiceStartNotAllowedException`, `SecurityException` and `InvalidForegroundServiceTypeException` is around each `startForeground` call: the first call and each change of type.

- If the first call fails, the service posts "Ready to transcribe" (with an Activity intent), calls `stopSelf(startId)` and returns `START_NOT_STICKY`. The rows stay QUEUED, and the next ON_START starts them again.
- If a change of type fails, the service keeps the previous type and writes a log entry.
- If no error occurs, the service calls `coordinator.onServiceForeground(true)` and `coordinator.drain()`, and returns `START_REDELIVER_INTENT`.

The callers also keep their own catch. The service does not increase `attempts`. Only `reconcileOnStart` does.

Both services call `startForegroundWithType` (`Foreground.kt`). From API 29, this function calls `Service.startForeground(id, notification, type)` directly. The app does not use `ServiceCompat.startForeground` for this call, because androidx.core 1.19.1 removes the types that it does not know. It changes `FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING` (0x2000) to 0, and Android refuses a start with type 0.

The service type follows `coordinator.foregroundNeed`:

- During a download: `DATA_SYNC`.
- From the start of the inference, on API 35+: `MEDIA_PROCESSING`, with a budget of 6 h in each 24 h.
- From the start of the inference, on API 29-34: `DATA_SYNC` ("local file processing"). No better type exists, and there is no timeout callback.
- On API levels less than 29: no type.

`onTimeout(startId, fgsType)` (API 35+) does these steps synchronously:

1. It posts "Transcription paused by Android" with an Activity `PendingIntent` (content and Resume).
2. It calls `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()`. It does not wait for the job.
3. It calls `coordinator.systemStop(noteId)` as best effort. The job gets the stop before `onTimeout` returns, thus it starts no new window. A window that decodes at that time ends first. Then the job saves a checkpoint and stops. When the job ends and the "Transcription paused by Android" notification is on the screen, the coordinator puts the numbers of this checkpoint into it.
4. It sets `queue.hold = SYSTEM`.
5. It writes the row as PAUSED(SYSTEM), so that each screen shows Paused immediately.
6. If a standalone download runs, it stops, because no foreground service keeps it. `ModelInstallOwner.stopDownloads` cancels each standalone wait. The installation stops when no job waits for it. The `.part` files stay for a Range resume. Each part that is not installed shows `Failed(DOWNLOAD, STOPPED_BY_SYSTEM)` until the next installation, and `lastOutcome` is STOPPED_BY_SYSTEM. Thus the Library shows DL_STOPPED with Retry and the body "Android stopped the download. Try again to continue where it stopped.", and Settings shows "Download failed". In Settings, a "Download failed" row opens Model download, and Download there starts the download again.

The app never shows a timeout as an error. After a timeout, Resume operates only from a visible screen. The notification and the TX_PAUSED banner tell the user why: "Android limits background work to about 6 hours a day." (`BannerArgs.bySystem` for a PAUSED row with `pause_reason = SYSTEM`). The budget applies only to API 35 and later. A long job can thus pause more than one time. Each Resume continues from the last checkpoint.

The service stops only after the drain job of the coordinator completed its `join()`. Thus a native call that finishes never runs outside a foreground service.

The wake lock is `PARTIAL_WAKE_LOCK "Cassini:processing"`, without reference counts. The service acquires it with a 10 min timeout when the inference starts, and acquires it again at each window. It releases it at Paused, Failed and Done, during idle download waits and when the service stops. Downloads hold no wake lock.

Memory check before the start: `ActivityManager.MemoryInfo.lowMemory`, `isLowRamDevice` or `!Limits.memoryAllows` sets a low-memory hint on the progress card. The job continues. `largeHeap` stays until we measure its effect.

The decode refuses audio whose samples do not fit in the heap (`Limits.requireDuration`, MEMORY), and so does the growth of the sample buffer (`PcmBuilder`). The free heap counts garbage as used until a collection runs. Thus when the heap looks too short, `Limits.heapAllows` runs one garbage collection and measures again. Garbage that earlier work left does not refuse an import or a transcription. The collection runs only in this case.

Android permits these start paths:

- A visible screen: Transcribe, Resume or Continue, through `ContextCompat.startForegroundService`. On API 33+, the app asks for `POST_NOTIFICATIONS` one time. A denial does not stop the start.
- The `PendingIntent` of a notification action. Resume in "Transcription stopped" uses `PendingIntent.getForegroundService`. Finish in the recording notification goes to `RecordingService`. Its hand-off then starts the processing inside the temporary allow-list.
- ON_START with a queue.

The app tries each other start with the catch of this section. If the start fails, the user gets "Ready to transcribe".

If the user denies `POST_NOTIFICATIONS`, the service still runs. The progress card in the app and the Library banner show the information. Settings tells the user about it, with a deep link.

### 5.8 Recording

`RecordingController` owns the `MicrophoneCapture` on a dispatcher with one thread. It gives these values:

- `StateFlow<RecordingState>`: `Idle`, `Starting`, `Active(noteId, title, format, elapsedMs, pause, space, warning)`, `Finishing` or `Ended(noteId, outcome)`.
- `levels`: 56 values of 125 ms each, with the captured time of the newest sample (`WaveformLevels` of `:ui`). Each value is the maximum of the 100 ms samples. The controller calculates the levels only while a collector subscribes.
- `activeNoteId`.

The visible Recording screen calls `start(title)`, because Android 14 requires a visible start. The function does these steps:

1. It makes the note row (`LIVE_CAPTURE`, `capture.opus` or `capture.m4a`).
2. It sets `activeNoteId`.
3. It starts `RecordingService`, which first calls `startForeground(MICROPHONE)`.
4. It makes the capture.
5. It acquires the wake lock (10 min). The policy loop acquires it again each minute of loop time, also during a pause.
6. It starts the policy loop of 1 s.

If the start fails, the function deletes the empty directory and the row (`CouldNotStart`).

The controller writes a title edit to the row 500 ms after the last keystroke. `finish(title)` writes the final value.

`finish(title)` is asynchronous. It calls `capture.stop()` on the capture dispatcher (up to 10 s). Then the row becomes `PLAIN` with the duration. If a transcription follows, the hand-off occurs. Then the state becomes `Ended(Saved)`.

A recording has no time limit. `RecordingPolicy.decide(usableBytes, bytesPerSecond, fileBytes, maxFileBytes)` is a pure function in `:core`. The nominal rates are 6000 B/s for Opus 48 kb/s and 4000 B/s for AAC 32 kb/s. After 10 s, the controller uses the measured growth of the file. `RecordingSpace` gives the remaining time until the storage floor, or until the size limit of an AAC file when that comes first. The hint shows it, with no upper limit ("Space for about 31 h more."). The policy gives these results:

- 15 min of space or less: StorageLow.
- 15 min or less of room in an AAC file: FileLimitNear. It shows as REC_STORAGE_LOW with the copy of the file limit. When the space and the file are both low, the one that ends first warns.
- Less than the floor of 200 MiB: stop and save (`StoppedForSpace`).
- An AAC file at `RecordingPolicy.AAC_MAX_FILE_BYTES` (1 GiB): stop and save (`StoppedAtFileLimit`, section 3.7).

Back, the chevron and the Library button do not stop the recording. Only Finish stops it.

A pause has one of two causes: USER or OTHER_APP. On API 29+, OTHER_APP comes from `AudioManager.registerAudioRecordingCallback`, for the client session ID of the app (`isClientSilenced`). On API 26-28, it comes from the transient focus listener, which the controller requests again after each resume. When the silence ends, the recording stays paused until the user resumes it. On API 29+, the app does not request audio focus, so the music of the user continues.

Hand-off from recording to processing: `RecordingService` writes the row as QUEUED and calls `startForegroundService(ProcessingService)` inside a `try`/`catch`. It waits for `coordinator.state.first { it.serviceForeground }` (10 s timeout). After this, it stops its own foreground state. The start is permitted when Finish came from the visible screen or from the notification action (temporary allow-list). After a timeout or a failure, the note stays QUEUED, and the user gets "Ready to transcribe". The next ON_START starts the note.

After Finish, these rules apply:

- With consent, the note goes into the queue.
- Without consent and without `models.declinedAt`, the Note screen opens with the Model download screen on top.
- Else the note is "Audio only".
- After a stop for storage, a stop at the AAC file limit or a microphone failure, the note never goes into the queue automatically.
- A recording longer than `Limits.MAX_TRANSCRIBE_MS` (36 h) never goes into the queue. It is "Audio only", and the plain banner gives the text of `Failure.LONG` and no Transcribe.

`RecordingService` is `START_NOT_STICKY`. After a crash, `RecoveryRepository` repairs the recording at the next start.

### 5.9 Playback

`PlaybackController` keeps `MediaPlayer`. We did not use Media3, because we tested the behavior of `MediaPlayer` on devices, and the app only seeks inside one local file. During playback, a position flow gives the position each 150 ms. The controller saves the position each 5 s. It also saves it with `final = true` at pause and when the user leaves the screen, and this save also updates the Library preview. It requests `AUDIOFOCUS_GAIN` (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`). It pauses when it loses the focus and at `ACTION_AUDIO_BECOMING_NOISY`. `playRange(noteId, startMs, endMs)` plays a speaker sample, stops at `endMs` and puts back the previous position.

Two Note screens can open the player at the same time, for example when the user leaves note A and opens note B (or note A again) before the file of A is prepared. For this reason, each Note screen is the owner of the player that it opens. `open` and `leave` increase a generation counter. After `prepare`, an `open` or a `reload` that finds a different generation releases its new player and returns false. Thus only the player of the last `open` stays, and the controller releases each other player. A `leave` names its owner. It does nothing when a newer screen owns the player, also when that screen shows the same note.

### 5.10 Notifications

The text comes from `inAppLanguage()` (`AppLanguage.kt`). Below API 33, this function uses `ContextCompat.getContextForLanguage`. From API 33, it gets the app language from `LocaleManager` and makes a context with this language. The application context does not always have the app language: on a Pixel 8 (API 37), the screens were in Italian and the notifications were in English. Each notification is `VISIBILITY_PRIVATE`, with a public version without the note title. The channels are `recording` (LOW), `processing` (LOW) and `results` (DEFAULT, silent). The small icon is the monochrome logo (`ui_ic_stat_logo`). The PendingIntents use `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`. features.md section 12 gives the content and the copy.

## 6. UI architecture

- The app has one `MainActivity : AppCompatActivity`. It uses AppCompat only for `AppCompatDelegate.setApplicationLocales`. On API levels less than 33, `AppLocalesMetadataHolderService` has `autoStoreLocales`.
- The theme `Theme.Cassini` is in `app/src/main/res/values{,-night}/themes.xml`. Its parent is `Theme.AppCompat.DayNight.NoActionBar`, and its `android:windowBackground` is `@color/ui_window_bg` of `:ui`.
- The activity calls `enableEdgeToEdge()`. It has `android:enableOnBackInvokedCallback="true"`, `launchMode="singleTop"` and no `configChanges`. The exported ACTION_VIEW filter stays: the content and file schemes, and `audio/ogg`, `audio/opus` and `application/ogg`. targetSdk is 36 from P10.
- Navigation 3: `navigation3-runtime` and `navigation3-ui` 1.2.0, and `lifecycle-viewmodel-navigation3` 2.11.0. The `@Serializable` keys are `LibraryKey`, `NoteKey(noteId, query?)`, `RecordingKey`, `ModelDownloadKey(noteId?)`, `SettingsKey`, `SavedVoicesKey`, `TrashKey` and `LicensesKey`. `NavDisplay` has `rememberSaveableStateHolderNavEntryDecorator()` and `rememberViewModelStoreNavEntryDecorator()`.
- A note opened from the Library (a stack that ends in `LibraryKey`, `NoteKey`) is one scene, `LibraryNoteScene` (`nav/LibraryNoteScene.kt`), that holds the Library under the note; the other stacks use the single-pane scene. Navigation 3 renders each entry through one `movableContentOf`, and in a transition it renders an entry that two scenes share in the target scene only, in the composition that adds that target. So the Library's composition moves into the note's scene on open and back into its own scene as the close starts (a cancelled predictive back moves it back again): closing a note does not compose the Library from scratch. Under the note the Library is capped at `CREATED` (no state collection; its search back handler is off), and while the note covers it, it is not placed: it draws nothing, takes no touch and has no semantics. The note animates alone over it: the container morph from its row (`NoteTransitions.kt`), or, when the row bounds are unknown, the slide and fade of the other pushes. `AppShellTest` checks that the Library's layout nodes survive a close and a predictive back.
- Sheets and dialogs are ViewModel state (in `SavedStateHandle`), not destinations. "Transcribing" is a mode of the Note screen.
- State holders: each destination has one ViewModel, made in a `viewModel { }` factory from `AppGraph`. Each screen has one immutable `UiState` as a `StateFlow` (`combine` + `stateIn(WhileSubscribed(5_000))`). The events of each screen are a sealed interface, and the screen gives them to `onEvent`. `XxxRoute(viewModel)` in `:app` collects the state with `collectAsStateWithLifecycle()` and calls the stateless `XxxScreen(state, onEvent)` in `:ui`.
- The ViewModels give semantic values: enums, durations in milliseconds, `LocalDate`/`LocalTime`, counts and user text. `:ui` changes them into localized text. Thus the ViewModels do not need `Resources`, and the Italian and font-scale screenshot tests can use the same fixtures.
- The state machine for speaker naming is a pure reducer in `:app`: `note/SpeakerNaming.kt` (package `org.cassini.android.note`), with unit tests. It covers rename, the selection of a saved person, the same-person prompt, the rename of the saved voice, the merge with a confirmation, Remember and "Not X".
- Back: an open sheet, a dialog, the search mode or a rename uses back first. On Recording, back minimises the screen, and the recording continues. Library is the root. On Library, back leaves the app with the predictive back-to-home of the system.
- Deep links go through `MainActivity` (`onCreate` with no saved state, and `onNewIntent`). The activity marks a handled intent, so that a new activity after a configuration change does not handle it again. ACTION_VIEW imports the file on IO and pushes `NoteKey(id)` on top of the existing stack. If the stack was empty, Library is under the Note screen. Notification intents have `open = recording | note` and `noteId`.
- `ModelDownloadKey` when no model is missing: the entry points do not open it then. If it opens (for example from an old back stack), the route removes itself from the stack. If it has a note, it first puts the note into the queue.
- After Move to trash, the app shows the Library with the Undo snackbar.
- System bars: in the light theme, the light screens have dark icons. Recording always has light icons.
- Wide screens: the content has a width of at most 560 dp, at the center. The backgrounds fill the screen.
- `ui.md` gives the visual system, the components and the screen contracts.

## 7. Threading rules

| Thread or dispatcher | Runs | Never |
|---|---|---|
| Main | Compose, `StateFlow` collection, `MediaPlayer` control, `startForeground`/`stopSelf`, notification posts | File I/O (StrictMode logs it), sherpa, `capture.stop()` |
| `cassini-inference` (`InferenceDispatcher`, one thread) | Each sherpa call, `StreamingOpusEncoder`, decoder callbacks, the checkpoint and `names.json` writes of the job, the seal | `runBlocking`, suspension inside the native loop |
| IO | Room, DataStore, each repository call, `ModelInstaller` (`runInterruptible(IO)`), adopt, recovery, imports, exports | sherpa |
| `ProgressTracker` (`Default.limitedParallelism(1)`) | `TranscriptionProgress`, `BatteryProjection`, battery reads, the construction of `RunningProgress`/`DraftState` | Blocking I/O |
| Coordinator (`appScope`, Default) | Queue transitions (each one a Room transaction on IO), the drain loop, the holds | Native calls |
| Recording controller (one thread) | Capture start and stop, the policy loop, the recording callback | – |
| `cassini-recording` capture thread (MAX_PRIORITY) | `AudioRecord.read` → MediaCodec → `LiveMuxer` | – |

The app uses these locks:

- `NoteLocks`: a striped `ReentrantLock` for each note.
- One `ReentrantLock` for each store that uses files. The suspend callers and the blocking callers share these locks. No caller holds them across a suspension point.
- `NativeBudget`: a coroutine `Mutex` around the model loads.
- The `Mutex` of `ModelInstallOwner`.
- The `JobRepository.claimNext` transactions, for the rule of one RUNNING job.

The draft names have one owner, `DraftNames` (section 3.6). The UI and the job change them only through its reducers.

## 8. Native runtime and build

AAR: `scripts/build-native-aar.sh` builds the pinned fork and the patch with ONNX Runtime 1.27.1. It installs the AAR and a generated POM into `native/m2/org/cassini/native/sherpa-onnx-nemotron/1.13.7-dbbeef9c/`. Git ignores this directory, but `native/m2/README.md` is committed. `scripts/fetch-native-aar.sh` downloads the AAR from the GitHub release `native-sherpa-onnx-1.13.7-nemotron.3`. The `sherpa-onnx-aar` entry of `scripts/native-dependencies.json` pins its SHA-256 and the hash of the patch. The release `.2` is a build of the fork without the frontend of upstream pull request #4024. Do not use it with Parakeet Ultra. The older release `.1` is a build without the patch (SHA-256 `d03a06f8…`, without `processStreamingChunk`, `saveStreamingState` and `restoreStreamingState`). Never use it.

`scripts/setup.sh` gets the libopus source. Then it gets the AAR from the first of these sources that operates:

1. `native/m2` already holds the pinned AAR.
2. The legacy local file `app/libs/sherpa-onnx-1.13.7-nemotron.aar` agrees with the pin.
3. `fetch-native-aar.sh`.
4. `build-native-aar.sh`.

`--from-source` goes directly to source 4. With `--prebuilt-only`, the script fails and does not use source 4. `scripts/verify-native-runtime.sh` compares the installed AAR, the patch and the catalog with the pin.

The other rules of the build are:

- `libcassini_opus.so`: AGP `externalNativeBuild` in `:engine` (CMake 3.22.1, NDK 28.2.13676358, `-DOPUS_SOURCE=<root>/.tools/opus/source` from `scripts/fetch-opus-source.sh`, `-DANDROID_STL=c++_static`). Each variant uses `-DCMAKE_BUILD_TYPE=Release`, so the debug builds encode exactly as the release builds do. If the source is missing, `checkOpusSource` stops `preBuild` with "run scripts/setup.sh". It also refuses an old `app/src/main/jniLibs`.
- ABIs: `ndk { abiFilters += listOf("arm64-v8a", "x86_64") }` stays in the `defaultConfig` of `:app`, because the `abiFilters` of a library do not filter the packaged ABIs. `scripts/apk_compatibility.py` (64-bit ABIs only, 16 KB ELF and ZIP alignment) must pass on the debug APK in each phase.
- Toolchain (we tested these versions together with JDK 17): Gradle 9.7.0 (wrapper with `distributionSha256Sum`), AGP 9.3.1 with `android.builtInKotlin=false` and `android.newDsl=false`, KGP 2.4.21, KSP 2.3.12, Room 2.8.5, Compose BOM 2026.09.00 (Compose 1.12.1), Navigation 3 1.2.0, compileSdk 37, build-tools 36.0.0, CMake 3.22.1, NDK 28.2.13676358, minSdk 26 and Java 17.
- The two AGP flags are deprecated, and AGP 10 removes them. They are a temporary opt-out. The documented next step is AGP 9.4.1 and Gradle 9.8.1 with built-in Kotlin. Always run Gradle with `JAVA_HOME` set to JDK 17.
- targetSdk was 34 until P9. It is 36 from P10, with the Compose UI (edge-to-edge enforced, predictive back). It is not 37, because we did not review the behavior changes of Android 17 yet.
- The version catalog is `gradle/libs.versions.toml`. Bundles contain only artifacts with an explicit version. Each module declares the artifacts that the BOM manages (`compose-ui-test-junit4`, `compose-ui-test-junit4-accessibility`) next to `platform(libs.compose.bom)`. `kotlinx-collections-immutable` is 0.5.2. The app does not use `material-icons-extended`, because the icons come from the SVG paths of the design.
- Screenshot tests: Roborazzi 1.76.0 with the native graphics of Robolectric, in `:ui` (ui.md section 10). The P2 scaffold test shows that it operates in an AGP 9 library module with `android.newDsl=false`. The earlier spike operated only in an application module.
- Accessibility checks: `androidx.compose.ui:ui-test-junit4-accessibility` (the BOM manages it, and it includes the Accessibility Test Framework). P2 found that `enableAccessibilityChecks()` does not operate under Robolectric 4.17/SDK 35. Thus the checks are in device tests on an emulator: `flows/ScreenAccessibilityTest` and `flows/AppAccessibilityTest` (features.md section 14, `docs/development.md`).
- Fonts: the unmodified static TTFs of Instrument Sans and IBM Plex Mono. `third_party/fonts/fonts.json` pins them, and `scripts/fetch-fonts.sh` gets them (ui.md section 3).
- JVM tests: `src/test/resources/robolectric.properties` pins Robolectric 4.17 to SDK 35 in each module that uses it. One test in each such module asserts `Build.VERSION.SDK_INT == 35`. SDK 36 and later need a JDK 21 runtime. The tests use `--add-opens` jvmArgs and `isIncludeAndroidResources = true`.
- `ciTest` (a root task) runs `:core:test`. For each Android module, it runs `verifyRoborazziDebug` if the module has the Roborazzi plugin, else `testDebugUnitTest`. CI and the green check of each phase run it.
- Release: R8 (`isMinifyEnabled`, `isShrinkResources`, `proguard-android-optimize.txt`). The keep rules keep the members of `com.k2fsa.sherpa.onnx.** { *; }`, `com.github.luben.zstd.** { *; }`, `org.cassini.android.NativeOpus { *; }`, the classes with `native` methods, and kotlinx-serialization for the navigation keys. `scripts/package-apk.py` copies the R8 mapping into `dist/`.
- `releaseTest` is a build type: the release build without R8, with the same signing. The device tests of a tag build use it. `scripts/launch-check.sh` makes sure that the minified release APK starts.
- zstd-jni 1.5.7-23: the `@aar` in the Android modules, and the plain jar in the JVM tests.
- Lint: `warningsAsErrors`, `abortOnError` and `checkDependencies`. `MissingTranslation` is an error. Lint ignores `OldTargetApi`, the version checks that need a network, and `UsableSpace`, on purpose. Each Android module has an explicit lint block. The adaptive icons are in `mipmap-anydpi/`, not in `-v26`, because `-v26` causes ObsoleteSdkInt at minSdk 26. After P10, the lint baseline is empty. The attributes on `<application>` that are newer than minSdk have `tools:targetApi`.
- Style: detekt 1.23.8 with `detekt-formatting`. Autocorrect is off, because it changes the verbatim code. Also `.editorconfig` (4 spaces, 140 columns), ruff for Python, shellcheck and actionlint. detekt reads the code with a Kotlin 2.0 parser. Thus the code does not use `when` guards, non-local `break`/`continue`, context parameters or explicit backing fields.
- Signing: the build makes the release signing configuration only when `CASSINI_RELEASE_KEYSTORE_FILE` is set. It reads `CASSINI_RELEASE_STORE_PASSWORD`, `CASSINI_RELEASE_KEY_PASSWORD` and `CASSINI_RELEASE_KEY_ALIAS` from the environment. The Gradle files have no literals, and CI has no fallbacks ([`release.md`](release.md#signing)).
- Dependabot watches Gradle, GitHub Actions and the submodule.

CI has three workflows in `.github/workflows/`. `docs/development.md` ("Continuous integration") gives the details.

- `android.yml`, job `checks`: the wrapper validation, detekt, actionlint, shellcheck, ruff, the Markdown link check, `fetch-fonts.sh --check`, the resource collision check, the verbatim diff, and on tags the CHANGELOG section.
- `android.yml`, job `core-only`: `:core:test` without `setup.sh`.
- `android.yml`, job `build`: the prebuilt AAR, `ciTest`, lint, the debug APK, and the device test APKs of `:app` and `:data`. On tags, also `release` and `releaseTest`. If the job fails, it uploads the Roborazzi diffs.
- `android.yml`, job `emulator`: API 26, 29, 34 and 35, with `scripts/ci-emulator-smoke.sh`. It runs `@CiSmoke`, makes sure that the platform Opus encoder is as expected for each API level, and runs `NoteFtsDeviceTest` on API 26. If the emulator or adb fails before the tests start, the job tries one more time.
- `android.yml`, job `release`, on tags: after `checks`, `core-only`, `build` and `emulator`. The notes come from the section of the tag in `CHANGELOG.md`, through `scripts/changelog-section.py`.
- `models.yml` (each week, and by hand): the models of `scripts/ci-models.json`, which `ModelCatalogTest` keeps equal to `ModelCatalog`. A cache keeps the models, with their SHA-256 values as the key. `scripts/push-models.sh` copies them into the `files/` of the debug app with `run-as`. Then the job runs `@NeedsModels` on API 34.
- `native.yml` (each week, by hand, and on changes under `scripts/` or `engine/src/main/cpp/` and some build files): `setup.sh --from-source`. It compares the result with the pin. A difference gives a warning.

## 9. Testing

1. JVM tests (`:core:test`, `:engine:testDebugUnitTest`, `:app:testDebugUnitTest`): the moved engine tests, the codec goldens, and the pure rules of P3 with golden strings. Also `TranscriptionJobTest` with fakes, `ProgressTrackerTest`, `ProcessingCoordinatorTest`, `ModelInstallOwnerTest`, `DraftNamesTest`, `RecordingControllerTest`, `SpeakerNamingTest`, and ViewModel tests with `kotlinx-coroutines-test` and Turbine.
2. Robolectric tests (SDK 35): the repository and DAO tests of `:data` (FTS prefix and diacritics, snippet markers, cascades, purge sequence, catalogue rebuild, import classes, adopt crash points, reconcile rows). Also `ContrastTest`, the font and license asset tests, and the SDK 32 test that the notification text follows the app locale.
3. Screenshot tests (`:ui`, Roborazzi): each board, compared with `design/reference/png`, and the dark, Italian, 1.5x font scale, state variant and component goldens. The goldens are committed. `verifyRoborazziDebug` is a CI gate.
4. Device tests (`scripts/device-smoke.sh --annotation <name> [--class <c>] [--serial <s>]`). The paragraphs after this list describe them.
5. Process-death and persistence checks with host scripts. The paragraphs after this list describe them.
6. Acceptance (P12): `scripts/acceptance-walk.sh`, an instrumented walk through each board on emulator-5554 with device screenshots. A reviewer compared the screenshots with the design (`docs/ui-fidelity.md`).
7. Device safety: never run the Gradle `connected*` or managed-device tasks. They uninstall the app and erase about 770 MB of models on emulator-5554. Install with `adb install -r`. Run the instrumentation with `adb shell am instrument` or `scripts/device-smoke.sh`. Never use `adb uninstall org.cassini.android` or `pm clear`. Before a test that `CassiniTestRunner` or a debug root does not isolate, make a backup of the `files/` metadata (not the models) with `run-as` and `tar`.
8. Pre-release gate (by hand, `docs/development.md`): an end-to-end run on a Pixel 8 (API 37). Music that plays during a recording does not pause the recording. An incoming call pauses the capture. An API 35/36 AVD for `mediaProcessing` and `onTimeout`. `node scripts/check-portable-document.mjs` and `cassini-extract.py --check` accept a document that the new engine sealed.

`TranscriptionJobTest` covers these items:

- The window cadence, and one checkpoint for each window.
- A stop and a resume give the same transcript as a run without a stop.
- A cancel inside a native call.
- An OOM gives a pause.
- No network without consent.
- The provenance is the same as in 0.1.0 for the same inputs.
- The seal never touches `document.opus`.
- The length limits of section 3.7: audio longer than the limit fails before any work, and words that outgrow the word body fail at that window.
 `ModelInstallOwnerTest` makes sure that each artifact has one fetch when a standalone download and a job overlap. `DraftNamesTest` makes sure that UI and job reducers that operate at the same time lose nothing.

Device tests: a `CassiniTestRunner` sets `AppPaths.root` to `filesDir/androidTest-<run>`. It keeps `modelRoot = filesDir`, or it gives an empty scratch model root to the tests of the download screens. Thus the tests never touch the data of the user, and they still use the installed models. The committed CC-BY-4.0 FLEURS clip is the audio input. The flow tests in `flows/` start the real `MainActivity` over the graph of the runner (`docs/development.md`, "Device flow tests").

The annotations are:

- `@CiSmoke`: no models and no microphone. `scripts/ci-emulator-smoke.sh` selects these tests for the CI matrix.
- `@NeedsModels`: emulator-5554, and the weekly `models.yml` workflow on API 34.
- `@NeedsMicrophone`.
- `@Manual`.

A dry run (`-e log true`) of the same selection gives the expected count. The script fails when a selected test fails or skips, or when the count changes. `--class` can occur more than one time. `--sample` and `--arg KEY=VALUE` give arguments to the 0.1.0 tests. The argument `-e emptyModelRoot true` gives the run an empty model root. The argument `-e keepRoot NAME` keeps the scratch root `filesDir/androidTest-kept-NAME` after the run. Thus a second run can open the same notes in a new process (`scripts/note-opening-timing.sh`).

Process-death and persistence checks are host scripts, not instrumentation. The instrumentation force-stops the app when it ends, and `am kill` does not kill a process with a foreground service.

`scripts/device-kill-resume.sh` does these steps:

1. It writes `files/debug-root.txt`. Debug builds read this file. The models stay at `filesDir`.
2. It copies the long clip into the cache of the scratch root.
3. It starts a job through the debug-only `DebugCommandActivity` (`adb shell am start -n org.cassini.android/.debug.DebugCommandActivity --es cmd import --es file …`).
4. It kills the process with `adb shell run-as org.cassini.android kill -9 $(pidof org.cassini.android)`.
5. It waits for the redelivery, or it opens the app again with `--es cmd open`.
6. It reads the status file `<root>/debug-status.json` with `run-as cat`.
7. It makes sure that the sealed document passes the format tools.
8. It removes the scratch root and `debug-root.txt`.

The entry point is an exported activity, not a receiver. `am` in the shell cannot get to a component that is not exported, and a visible start lets the app start the processing service. The activity does nothing when no debug root is set. `DebugStatusProvider` starts the status writer in each process of a run with a debug root. It also does this in a process that Android starts for a redelivered intent. androidx.sqlite keeps the lock file of the database at `cacheDir/<absolute database path>.lck`. Thus the runner and the script also remove this file of their scratch root.

`scripts/device-language-check.sh` changes the app language through the UI (uiautomator) and kills the app with `kill -9`. Then it starts the app again and examines the language with `uiautomator dump`.

## 10. Open-source project documents

The repository has these documents:

- `CONTRIBUTING.md`: a new clone with submodules, `scripts/setup.sh`, `./gradlew :core:test`, `./gradlew :app:assembleDebug`, when the NDK is necessary, the test tiers, the screenshot goldens, the style and the DCO sign-off.
- `CODE_OF_CONDUCT.md`: the Contributor Covenant 2.1.
- `SECURITY.md`.
- `PRIVACY.md`: the data categories and the storage paths. The three hosts and the download domains that they redirect to. The retention, the trash, and the deletion of a note and of a person. The Licenses screen shows it, and the Model download screen has a link to it.
- `CHANGELOG.md`: Keep a Changelog format. We made the entries of 0.0.x and 0.1.0 again from old records. 0.2 is under "Unreleased", with the entry "0.2 starts with a new storage layout and does not read the app data of 0.1.x. Exported `.opus` documents open as before."
- The issue and PR templates, `CODEOWNERS` and the fastlane texts in `metadata/en-US/`.
- In `docs/`: this file, `docs/design/`, `docs/development.md`, `docs/native-runtime.md`, `docs/data-formats.md` (storage tables, exports, differences from gocassini), `docs/release.md`, `docs/ui-fidelity.md` and `docs/notes/` (dated logs with a historical note at the top).
- `THIRD_PARTY_NOTICES.md` and its copy in the APK: the origin of the AAR, AndroidX, Room, kotlinx and Compose (Apache-2.0), and the fonts (SIL OFL 1.1).
- `tools/model-host/`: the OpenMDW-1.1 text, a NOTICE and the provenance for the Nemotron export on dist.gocassini.com. The owner uploads these files.

F-Droid: the app has no proprietary SDKs. `docs/release.md` gives the open problems: the prebuilt ONNX Runtime in the build scripts of sherpa-onnx, and the bundled `.so` files of zstd-jni.

## 11. Decisions and rejected alternatives

- Greenfield storage: one directory for each note, and Room as a catalogue and cache. The sealed document is the authority for the transcript content, and Room is the authority for the title. A shared content-addressed `documents/`, reference counts for `retire`, and `sha256(uri)` checkpoint keys are gone.
- The job seals into `work/sealed.opus`, and adopt installs the file. The job does not seal over `document.opus`, because the old file is the audio source and the checkpoint signature of the job. If the job writes over it before adopt, a crash gives a false mismatch.
- All voiceprints and matches are under `voices/` (also the pending ones). Thus "Forget everyone" is the deletion of one directory, and a purge deletes two files for each note.
- `ReentrantLock`s that the suspend callers and the blocking callers share, not coroutine `Mutex`es, because the inference thread must not suspend or use `runBlocking`.
- One installation owner for the models, so that a standalone download and a job never write the same `.part` file.
- A redirect allow-list in `ArtifactFetcher`, so that the code enforces the privacy promise about the hosts.
- The model directories stay at the root of `filesDir`, with their 0.1.0 names. `ModelCatalog` pins them, and the checked installation on the emulator stays valid.
- A foreground service, not WorkManager, for transcriptions and downloads. The job is interactive (draft naming, immediate Stop and Resume). After process death, WorkManager restarts the work in the background, where `setForeground` can throw an exception. The trash purge also does not use WorkManager (section 4.10).
- A persistent FIFO queue in Room with one RUNNING job, not the claim set in the process memory of 0.1.0.
- `:engine` behind ports, with no dependency on `:data`. Thus the JVM tests of the job are possible, and P4 and P5 operated at the same time.
- A separate `:ui` module with semantic `UiState` and a `ui_` resource prefix. Thus we built the design system and the screens, and tested them with screenshots, at the same time as the data and service layers.
- Roborazzi, not Paparazzi (alpha, bound to layoutlib) and not Compose Preview Screenshot Testing (alpha, previews only). A spike with this toolchain at 780 x 1688 px, the size of the boards, showed that Roborazzi operates.
- The unmodified static TTFs from upstream, not the woff2 web subsets of the design. A subset is a Modified Version under the OFL, and "Plex" is a Reserved Font Name.
- Icons as `ImageVector`s from the SVG paths of the design. The build does not have `material-icons-extended`.
- A manual `AppGraph`, not Hilt. org.json stays in `:core`. kotlinx-serialization is only for the navigation keys. `MediaPlayer` stays. AppCompat is only for the locales, and for this reason the XML theme is in `:app`. No dynamic color. StrictMode logs, and it does not kill. `singleTop`, not `singleTask`.
- The `:core` string scan `DefaultLabels.isDefault` finds the default speaker labels, not a stored flag. The documents store only the label text.
- We deleted `OpusEncoder.kt` (MediaCodec, 48 kb/s). Sources that are not Opus go through the 24 kb/s libopus path.
- We rejected AGP 8.13, because it cannot compile against SDK 37. AGP 9.4/Gradle 9.8 is for later. Robolectric at SDK 35, not JDK 21. We rejected a committed AAR in favor of a release asset with a hash pin and a weekly check from source. We rejected a copy of the conformance vectors in the repository in favor of a pinned submodule.

## 12. Deviations that we keep on purpose

- The verbatim format code keeps four known deviations from the specification. A warning makes a document unverified. The bodies are pretty-printed JSON. There is no DATE tag. The signature of a URI that is not a file is `null:null`. We keep them because the pinned conformance vectors and gocassini agree with the current behavior. Each deviation is a tracked issue.
- The TXT, SRT and VTT exports are word-body derivatives without integrity data. Only the sealed `.opus` is a portable Cassini artifact. The VTT of Android uses cues with word times and `<v Label>` spans. The `WriteCaptionsVTT` of gocassini writes one cue for each segment as `<Label> text`. `docs/data-formats.md` records the two forms.
- ui.md section 11 lists the visual deviations from the boards.
- Checkpoint cadence: 0.1.0 saved a checkpoint at the first sample block. The job saves no checkpoint before the first window is done. Thus a stop before that leaves no checkpoint ("Paused before any words", features.md TX_PAUSED_EARLY).
- The job refuses Transcribe again over an UNVERIFIED or UNKNOWN_FORMAT document (`DOCUMENT_<state>`), as 0.1.0 refused each state except `ok` (section 5.4). The UI does not give this action. The actions sheet shows Transcribe again as disabled ("This file can't take a new version here."), and no banner gives it. A `DOCUMENT_<state>` failure shows its message with no Try again. When the user dismisses the message, the app removes its job row.
- A diarizer failure after the first window keeps the earlier speakers and the diarization record, as 0.1.0 did. The plan says "Failure.SPEAKERS degrades to speakersSeparated = false" (section 5.4).
- The model installer examines the free space for each part, not one time for the whole plan (section 5.6).
- features.md section 13 has no banner for a database that does not open (section 4.8, step 1). The app adds LIB_UNAVAILABLE (an error, not on the board): "Your notes can't be opened" — "Your files are kept. Free some storage, then open Cassini again." The Library shows it in place of LIB_EMPTY.
