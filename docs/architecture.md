# Cassini for Android: architecture (0.2)

This document describes the rebuilt Cassini Android app. It covers the modules, the storage, the background work, the UI system, and the rules that protect the portable format and the privacy promises. It is written for contributors and for the coding agents that work from the phased plan.

Two companion documents are binding for the parts they cover:

- `docs/design/features.md`: every screen, behaviour, banner and export, with its data source and acceptance criteria.
- `docs/design/ui.md`: design tokens, fonts, components, screen contracts, navigation, accessibility and screenshot testing.

When the three documents disagree, `features.md` wins for user-visible behaviour, `ui.md` wins for visuals, and this document wins for module boundaries, storage layout, threading, services and the build.

## 0. Owner decisions and phases

Two owner decisions of 2026-10-09 shape this version:

1. **Greenfield.** Cassini Android has no current installs. Nothing is built only to read or keep 0.0.x/0.1.0 app data: no migrator, no legacy readers, no frozen `library.json`/`session.json`, no tombstones, no upgrade checks. The on-disk layout is designed fresh. Two things stay external contracts: the portable `.opus` document format (with its conformance vectors) and the privacy rules.
2. **New visual design.** The UI is rebuilt to the boards in `design/reference/` (11 boards at 390 x 844 dp, PNG renders plus the source markup). Fonts are Instrument Sans and IBM Plex Mono.

The work lands in phases. Each phase runs in one git worktree; parallel phases use separate worktrees and touch disjoint files.

| Phase | Worktree (branch) | Content |
|---|---|---|
| P0 | `rebuild` | Toolchain, native packaging, CI, detekt. Done. |
| P1 | `rebuild` | Pure-JVM `:core` and the `:engine` Android library. In progress. |
| P2 | `rebuild` | Fork preparation: these documents, design references, module stubs for `:data` and `:ui`, catalog entries, the `ciTest` aggregate task, removal of migration-only fixtures. |
| P3 | `rebuild` | Pure `:core` additions (statuses, search, trash, exports, speaker stats, model plan, `retitle`, download redirect policy). |
| P4 | `rebuild` | `:data`: Room catalogue, DataStore, file repositories. |
| P5 | `engine-job` | `TranscriptionJob`, `ModelInstaller`, `ProgressTracker` and the job seams in `:engine`, behind ports. Parallel with P4. |
| P6 | `rebuild` | `:app` background: `AppGraph`, coordinators, services, controllers. |
| P7 | `ui` | `:ui` design system: fonts, tokens, icons, components. Parallel with P3-P6. |
| P8 | `ui` | `:ui` screens rendered from fixture `UiState`s, screenshot goldens. |
| P9 | `docs` | Open-source citizenship documents. Parallel. |
| P10 | `rebuild` | Integration: merge `ui` and `docs`, ViewModels, navigation, delete the legacy UI, targetSdk 36. |
| P11 | `rebuild` | CI consolidation and `:bench`. |
| P12 | `rebuild` | End-to-end acceptance on emulator-5554, screenshots, README. |

Until P10 the old view-based app still builds and runs on its own storage (`files/documents/`, `library.json`). The new layers are built next to it and tested with JVM, Robolectric and instrumentation tests. Nothing new is wired into the old Activities, and P10 deletes them.

Cutting 0.2.0, bumping `versionCode`/`versionName`, tags, GitHub releases and GitHub settings stay with the owner. The rebuild commits only on local branches.

Disk: the root filesystem had 12 GB free on 2026-10-09. Every phase checks `df -h /` before it starts and keeps at least 6 GB free: remove the build directories of finished worktrees (`./gradlew clean` or `rm -rf */build build .gradle/configuration-cache` inside a worktree being removed) before creating new worktrees or AVDs.

## 1. Scope and constraints

Cassini records audio and transcribes it on the phone with sherpa-onnx Parakeet TDT v3 INT8. It labels speakers with Nemotron-3 Sortformer streaming diarization, recognises named voices with CAM++ voiceprints, and saves everything as one portable `.opus` document. The engine is good and tested; 0.2 keeps it and replaces the app around it.

Hard constraints:

- The portable document format stays byte-compatible with `../cassini-format` and gocassini. Every conformance vector at the pinned `third_party/cassini-format` revision runs on every build. The number of vectors is read from the vector index, never hard-coded (26 at the current pin).
- Privacy rules:
  - Voiceprints are never written into documents or exports. All voiceprints and voice matches live under `voices/` (section 3.2).
  - `filesDir` is excluded from backup and device transfer.
  - No model is downloaded before the user consents.
  - Network access only for model downloads, only to the three model hosts (huggingface.co, github.com, dist.gocassini.com) and the download hosts they redirect to, which `ArtifactFetcher` checks against an allow-list (section 5.6). `PRIVACY.md` lists both.
- Native runtime: `minSdk 26`; ABIs `arm64-v8a` and `x86_64` (16 KB aligned); the custom sherpa-onnx AAR built from the pinned fork plus `scripts/streaming-diarization.patch`; `libcassini_opus.so`.
- Android stays native Kotlin. The iOS app is separate Swift code and shares only the portable format.
- `applicationId org.cassini.android` and the JNI class name `org.cassini.android.NativeOpus` do not change. The second one is bound to native symbols. The `.LibraryActivity` launcher alias of the old plan is not needed (no installed shortcuts).
- UI stack: Jetpack Compose with Material 3 components underneath, styled entirely by the Cassini tokens (no stock Material look, no dynamic colour). ViewModel + `StateFlow` + unidirectional data flow. Coroutines. Manual dependency injection (`AppGraph`).
- English and Italian. TalkBack support, 48 dp touch targets, state never shown by colour alone, a light theme as designed and a derived dark theme, edge-to-edge and predictive back with targetSdk 36 (from P10).

## 2. Modules

Six Gradle modules:

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

Dependency direction:

```
app -> ui
app -> data -> core
app -> engine -> core
bench -(instruments)-> app
```

`:engine` does not depend on `:data`. `TranscriptionJob` talks to storage through small ports defined in `:engine` (section 5.4), and `:app` implements them with adapters over the `:data` repositories. This keeps both modules testable with fakes and lets P4 and P5 run in parallel.

`:ui` does not depend on `:core`, `:data` or `:engine`. Its screens take semantic `UiState` values (enums, numbers, user text) and format them with their own string resources. ViewModels in `:app` map domain state to these values. Every `:ui` resource name starts with `ui_` (`resourcePrefix = "ui_"`), so `:app` resources can never silently override them.

`./gradlew --configure-on-demand :core:test` needs a JDK and the `third_party/cassini-format` submodule, and nothing else (no NDK, no AAR, no device).

Native seams. `:core` has the interfaces the verbatim engine already uses: `WordDecoder` (`asr/DecodeSession.kt`), `SpeechFeed.Stream` and `PcmSource`. The other native pieces are concrete classes in `:engine` (`SortformerStream`, the `VoiceprintExtractor` object, `AudioDecoder`, `StreamingOpusEncoder`). P5 adds engine-owned interfaces in `org.cassini.engine.job` (`StreamingDiarizer`, `PrintExtractor`, `AudioSource`, `OpusSink`, and `Recognizer` if `IncrementalRecognizer` needs one), with thin adapters over the native classes, so `TranscriptionJob` is JVM-testable with fakes.

### 2.1 `:core` (package `org.cassini.core`)

The P1 move keeps file bodies unchanged except for the listed edits. `scripts/check-verbatim.sh` diffs every verbatim file against its 0.1.0 origin, ignoring package and import lines, and reports only the listed edits.

| Package | Files | Notes |
|---|---|---|
| `format` | `OggOpus`, `CassiniPayload`, `CassiniDocument`, `Transcript`, `OggRepair`, `SpeakerAttribution`, `SpeakerMerges` | Verbatim. `CassiniDocument` has a typed `DocumentState` (`PLAIN_AUDIO`, `UNKNOWN_FORMAT`, `INVALID_METADATA`, `UNVERIFIED`, `STALE_AUDIO`, `OK`, `UNREADABLE`). `UNREADABLE` means an I/O error; such a note stays openable and can be transcribed again. `seal` with an `existing` document reuses its manifest, so the `title` argument is ignored for Transcribe again, and a fresh seal adds a `TITLE` tag only when the source has none. P3 adds `CassiniDocument.retitle` next to the verbatim `relabel` (same shape; requires `OK`; sets `meeting.title` and replaces or adds the `TITLE` tag; nothing else changes). |
| `audio` | `Resampler`, `PcmSource`, `AudioLimits`, `PcmBuilder`, `WavDecoder` | P3 adds `RecordingSpace` and `RecordingPolicy`. |
| `asr` | `SpeechWindows`, `SeamMerge`, `WordGate`, `IncrementalTranscript`, `SpeechFeed`, `DecodeSession`, `TokenWords`, `TranscriptionProgress`, `ProcessingSpeed`, `BatteryProjection` | Algorithms unchanged. |
| `speakers` | `SpeakerAssignment`, `TurnHistory`, `VoiceMatcher`, `VoiceprintSelection`, `VoiceClipCache`, `SpeakerBudget`, `VoiceCodec`, `NoteVoiceCodec`, `DefaultLabels` | `DefaultLabels.PREFIXES` = `Speaker `, `Voce ` (the placeholder label in every shipped language). P3 adds `SpeakerStats`, `SpeakerTint`, `NameMatch`, `VoiceStaleness`, and an `excluded` parameter on `VoiceMatcher.match`. |
| `models` | `ModelCatalog`, `ArtifactFetcher` | `ArtifactFetcher` resumes `.part` files with HTTP Range (section 5.6). P3 adds `ModelPart`, `ModelPlan`, a digest progress callback, `VOICEPRINT_DIM = 512`, and the redirect allow-list. |
| `checkpoint` | `CheckpointData`, `CheckpointState` | `checkpoint.json` version 1 codec; P3 adds two optional `names.json` members (section 3.6). |
| `library` | `NoteRules` (retired in P10) | P3 adds the shared enums (`Origin`, `AudioKind`, `JobState`, `JobKind`, `PauseReason`, `VariantKind`), `NoteStatus`, `DayGroups`, `SearchQuery`, `TrashPolicy`, `PreviewText`. Two rules refine features.md section 1 (their KDoc has the detail): `PreviewText` stops at the first turn end once it holds 40 characters (a shorter turn continues; board 00 stops before Speaker 3) and uses the transcript start at or after the end of the last word; `SearchQuery.fts` turns every character that is not a letter, digit or mark into a space inside the phrase (`e-mail` → `"e mail*"`, `giov.` → `"giov*"`), as unicode61 tokenizes, and drops terms without a letter or digit. |
| `export` | new in P3 | `TranscriptTurns`, `ExportClock`, `TextExport`, `SubtitleCues`, `SrtWriter`, `VttWriter`, `ExportNames` (features.md section 9). |
| (root) | `Failure` | Failure codes without string resources. `:app` `FailureMessages` is the only place a failure meets a string. |

### 2.2 `:engine` (package `org.cassini.engine`, plus `org.cassini.android.NativeOpus`)

| Package | Content |
|---|---|
| `opus` | `NativeOpus`, `StreamingOpusEncoder`, `src/main/cpp/opus-jni.cpp`, `CMakeLists.txt`. 24 kb/s, complexity 5. JNI guards for `ptr == 0`, a null `FindClass` and `opus_encoder_ctl` return codes. |
| `decode` | `AudioDecoder`: the MediaCodec/MediaExtractor loop verbatim, taking a `ContentResolver` and a cache directory. |
| `capture` | `MicrophoneCapture` (interface, suspend `stop()`), `OpusCapture` (platform MediaCodec Opus, API 29+), `AacCapture`, `CaptureFactory` (the `MediaCodecList` probe runs once, off the main thread). |
| `asr` | `ParakeetDecoder` (implements `WordDecoder`), `SileroDetector` (implements `SpeechFeed.Stream`), `IncrementalRecognizer`. |
| `speakers` | `SortformerStream`, `VoiceprintExtractor`. |
| `job` | `InferenceDispatcher` (one thread), `NativeBudget` (model-load `Mutex`), and from P5: the seams of section 2, `TranscriptionJob`, `ModelInstaller`, `ProgressTracker`, the ports (`JobStorage`, `JobVoices`, `ModelAccess`, `ModelStore`, `BatterySource`), `JobEngines` (the seam factories), `JobEnvironment` (dispatchers, clock, logger, placeholder label), `DraftSources` and the job state types (section 5). A JVM test makes sure that `ModelCatalog.VOICEPRINT_DIM` is the print length that the voiceprint adapter accepts. The adapter refuses a print of a different length. No test operates the real CAM++ model. |

The sherpa-onnx AAR is the Maven coordinate `org.cassini.native:sherpa-onnx-nemotron:1.13.7-1f23907a` in the git-ignored repository `native/m2/` (section 8).

### 2.3 `:data` (package `org.cassini.data`)

Room, DataStore and the file repositories of section 4. Every repository takes `AppPaths`, `CassiniDispatchers` and the shared `NoteLocks`. Repositories expose `suspend` functions and cold `Flow`s; functions named `…Blocking` exist only for the inference thread.

### 2.4 `:ui` (package `org.cassini.ui`)

`theme/` (tokens, type, shapes, icons, fonts), `components/`, one package per screen with its `XxxScreen(state, onEvent)`, `XxxUiState` and `XxxEvent`, `format/` (durations, dates, plurals, spoken descriptions), all UI string resources in English and Italian, the bundled fonts, the logo and launcher icon drawables, colour resources for the window background, and the Roborazzi screenshot tests. `:ui` has no XML theme: `Theme.Cassini` lives in `:app` (it needs AppCompat). `ui.md` is its specification.

### 2.5 `:app` (package `org.cassini.android`)

- `CassiniApplication` creates `AppGraph`: paths, dispatchers, locks, repositories, engine wrappers, port adapters, `ProcessingCoordinator`, `ModelInstallOwner`, `RecordingController`, `PlaybackController`, `NoticeCenter`. It is about 150 lines of constructor wiring.
- `AppGraph.ready: Deferred<Unit>` completes after the startup work of section 4.8. Starting a job, an import or a recording awaits it.
- There is no DI framework: a handful of ViewModels and a dozen singletons do not justify an annotation processor. The instrumentation runner swaps the graph for one built on a scratch root. In debug builds only, a `files/debug-root.txt` file names a scratch root that survives process death (used by the kill-and-resume device check, section 9).
- `processing/` (coordinator, install owner, service, notifications), `recording/` (controller, service, notification), `playback/`, `ui/<screen>/` (ViewModels and `XxxRoute` composables), `nav/` (keys, `CassiniApp`, deep links, system bars), `notices/` (`NoticeCenter`), `FailureMessages`, `BatteryReader`, `res/values{,-night}/themes.xml` (`Theme.Cassini`). Debug source set: `DebugCommandActivity` and `DebugStatus` (section 9).

## 3. Storage (greenfield)

### 3.1 What is authoritative where

| Data | Authoritative | Cache or index | Lost if `cassini.db` is rebuilt |
|---|---|---|---|
| Audio of a note | the file in `notes/<id>/` | `notes.audio_file`, `duration_ms` | – |
| Transcript variants, speaker labels, meeting id, created time, provenance | the sealed `notes/<id>/document.opus` | `variants`, `speakers`, `note_text`, `notes.*` | – |
| Note title | Room `notes.title` | the document's `meeting.title` and `TITLE` tag (a copy kept in step by `retitle`) | a rename not yet written into the document; the rebuilt title comes from `meeting.title`, or "Recovered recording <date>" for plain audio |
| Trash state and time, the job state before trashing | Room `notes.trashed_at`, `notes.trashed_job` | – | yes; trashed notes come back as live notes |
| Playback position, selected variant, preview excerpt, "Play anyway" acknowledgement | Room | – | yes (defaults) |
| Transcription queue, pause reason, attempts | Room `jobs` | – | yes; a note with a valid checkpoint comes back PAUSED |
| Partial transcription (checkpoint, draft names, sealed output awaiting adopt) | `notes/<id>/work/` | `jobs.done_ms` | – |
| Saved people (embeddings, names, counts) | `voices/people.json` | – | – |
| Per-note voiceprints and matches | `voices/notes/<noteId>.json` (sealed), `voices/pending/<noteId>.json` (awaiting adopt) | `note_voice_links` (no embeddings; re-synced at every start) | – |
| Model installs | model directories and marker files | `ModelRepository` state | – |
| Consent, estimates, one-time notices, queue hold | DataStore | – | n/a (separate file) |
| UI language | AppCompat per-app locales (system on API 33+, `autoStoreLocales` below) | – | – |

Rules:

- Whenever a document is written (adopt, relabel, retitle, merge), the repository rebuilds every cache row of that note from the document in the same Room transaction that points the row at the new file signature (`DocumentSignature`, section 3.3). When a Note screen opens, `refreshIfStale` compares `notes.document_signature` with the file and re-derives the cache when they differ. This covers a crash between the file rename and the row update. `refreshIfStale` never overwrites `notes.title`; it sets `title_dirty` when the document title differs.
- While a job row exists for a `DOCUMENT` note (QUEUED, RUNNING, PAUSED, FAILED with a checkpoint), `document.opus` is the job's audio source and its signature is in the checkpoint. Nothing rewrites it then: relabel and merge on the sealed document are refused (the UI disables them, features.md section 3), and a rename only updates the row and sets `title_dirty`; adopt writes the title afterwards.

### 3.2 File layout under `filesDir`

`filesDir` is excluded from backup and device transfer (`data_extraction_rules.xml`, `fullBackupContent=false`).

```
cassini.db, cassini.db-wal, cassini.db-shm   Room catalogue (opened by absolute path under AppPaths.root)
settings.preferences_pb                      DataStore Preferences
notes/<noteId>/                              one directory per note; <noteId> = UUID v4 string
    capture.opus | capture.m4a               microphone capture (live while audio_kind = LIVE_CAPTURE)
    source.<ext>                             imported non-Cassini audio; ext sanitised to [a-z0-9]{1,5}, "bin" if unknown
    document.opus                            sealed Cassini document; once it exists it is the note's audio
    document.opus.tmp, source.tmp            transient writes (fsync, then rename into place)
    work/                                    transcription state, only while a job is unfinished
        checkpoint.json (+.bak)              CheckpointState version 1
        names.json (+.bak)                   draft labels, merges, remembered, automatic, generation, live, rejected
        audio.opus.part                      libopus output for non-Opus sources
        clips.pcm                            selected voice clips (at most 8 x 45 s), raw 16 kHz f32le (audio, not voiceprints)
        sealed.opus                          the finished document, written by the job's seal step
        sealed                               marker: sealed.opus is complete and verified; adopt may install it
voices/                                      all voiceprints and voice matches, and nothing else
    people.json                              VoiceCodec version 1 (saved people)
    generation                               integer, bumped by "Forget everyone"
    notes/<noteId>.json                      NoteVoiceCodec version 2 (keys <variantId>/<speakerId>)
    pending/<noteId>.json                    final prints and matches of a sealed job awaiting adopt (same codec)
parakeet-v3/  vad/  speaker-models/          models, names and marker files exactly as ModelCatalog pins them
```

`cacheDir/exports/` holds transient export files and is deleted after each write and at start.

Note-directory rules:

- A note has exactly one current audio file, named by `notes.audio_file`.
- The job never writes `document.opus`. It seals into `work/sealed.opus`, verifies it, then writes the `work/sealed` marker. Adopt (section 5.4 step 10) installs it: rename `work/sealed.opus` over `document.opus`, fsync the directory, update the row and cache in one transaction, delete the previous `capture.*`/`source.*` file, then delete `work/`. A crash at any point either leaves the old audio untouched (no marker) or leaves a marker that the next start finishes (section 5.3).
- For a non-Opus source the document holds a 24 kb/s libopus re-encode. `PRIVACY.md` and the CHANGELOG say so.
- Edits outside a job (relabel, retitle, merge) write `document.opus.tmp`, fsync, rename over `document.opus`, fsync the directory. `PlaybackController` re-prepares after a replace and keeps its position.
- Writes to one note are serialised by the note's lock in `NoteLocks` (section 7).

The model directories stay at the `filesDir` root with their 0.1.0 names. `ModelCatalog` pins these names and markers (`parakeet-v3/verified` = Hugging Face revision, `vad/verified`, `speaker-models/verified` and `speaker-models/verified-voiceprint` = SHA-256). `AppPaths.modelRoot` defaults to `root` but is separate, so instrumentation tests can use a scratch root while sharing the installed models, or an empty scratch model root to show the download screens.

On emulator-5554 the old app's files (`documents/`, `library.json`, `voices.json`, and so on) stay where they are. The new app neither reads nor deletes them.

### 3.3 Room schema (`org.cassini.data.db.CassiniDatabase`, version 1)

`exportSchema = true`, with schemas in `data/schemas/`. Enums are stored as `TEXT` through `TypeConverter`s using stable names, never ordinals. The enums are defined in `:core` `library/` so that `:data` and `:engine` share them.

**`notes`**: `id` TEXT PK (UUID; also the directory name), `title` (1-200 chars, trimmed, never blank; authoritative), `title_dirty` (the document title differs and must be rewritten), `created_at` (epoch ms the audio was recorded: capture start, the document's `meeting.createdAtUtc`, else import time), `added_at`, `origin` (`RECORDED | IMPORTED_AUDIO | IMPORTED_DOCUMENT | RECOVERED`), `audio_file`, `audio_kind` (`LIVE_CAPTURE | PLAIN | DOCUMENT | UNPLAYABLE`), `duration_ms` (0 = unknown), `source_sha256` (hex SHA-256 of imported bytes, for dedupe; null for recordings), `document_state` (core `DocumentState`, null unless `DOCUMENT`), `document_error`, `document_warnings` (the warnings of the reader for the document with this signature), `document_signature` (`DocumentSignature`: `<length>:<mtime>:<sha256>`, where the SHA-256 is of the first 64 KiB and the last 64 KiB of the file), `meeting_id`, `selected_variant`, `position_ms`, `preview` (the Library preview excerpt, features.md section 1; null = start of the transcript), `trust_acknowledged`, `trashed_at` (null = live), `trashed_job` (the job state and kind when the note was trashed, for Restore; null = none), `updated_at`. Indices on `created_at`, `trashed_at`, `source_sha256`.

**`variants`** (PK `note_id, variant_id`; cascade from `notes`): `ordinal` (manifest order), `is_default`, `created_at_utc`, `language` (`""` when not identified), `kind` (`TRANSCRIBED | MERGED`; MERGED when the provenance has `x-speakerMerges`), `derived_from` (`x-derivedFromTranscript`), `word_count`, `speaker_count`, `speakers_told_apart` (provenance has `x-speakerDiarization`), `inference_ms` (`x-inferenceMs`), `readable`, `error`.

**`speakers`** (PK `note_id, variant_id, speaker_id`; cascade from `variants`): `ordinal` (first appearance in words), `label` (manifest label verbatim), `is_placeholder` (`DefaultLabels.isDefault`), `placeholder_number`, `speaking_ms`, `word_count`, `turn_count`, `first_ms`, `sample_start_ms`, `sample_end_ms` (the "Play a sample" window; null when no turn reaches 1 s).

**`words`** (PK `note_id, variant_id, idx`; cascade from `variants`): `start_ms`, `end_ms`, `speaker`, `text`. These rows are the parsed-transcript cache. They hold the words of one variant in canonical order. The repository writes them in the same transaction as the other cache rows and `notes.document_signature`. The Note screen shows the transcript from these rows. Thus, a note with an unchanged document opens without a read or a verification of the `.opus` file. If the count of cached words of a variant is not `variants.word_count`, the repository makes the rows again from the document.

We selected a Room table and not a cache file for each note. A measurement with `WordCacheDeviceTest` gave this decision (9,500 words, which is one hour of speech, debug test APK, API 34 x86_64 emulator, 2026-10-10). The first 200 rows load in 7-9 ms. All rows load in approximately 210 ms. A `DataOutputStream` file loads in approximately 160 ms. A parse of the `cassini.words.v1` JSON takes approximately 2.9 s. Thus, the file is not much faster than the table. The table has three advantages: the signature and the rows change in one transaction, the rows cascade with their variant, and a `LIMIT` query gives the first turns before the other rows load.

**`note_text`** (INTEGER `rowid` PK so VACUUM cannot renumber it; unique `note_id`; cascade) with `title` and `body` (the selected variant's words joined by spaces, `""` without a transcript). **`note_fts`** is an external-content FTS4 table over it with `tokenizer = unicode61` and `remove_diacritics=1`. Room writes the sync triggers. `note_text.title` changes with `notes.title` in one transaction; `body` is rewritten whenever the selected variant or the document changes.

**`jobs`** (PK `note_id`, at most one job per note; cascade): `seq` (FIFO order, `max(seq)+1` at enqueue), `kind` (`TRANSCRIBE | TRANSCRIBE_AGAIN`), `state` (`QUEUED | RUNNING | PAUSED | FAILED | NEEDS_CONSENT`), `pause_reason` (`USER | SYSTEM | MEMORY | INTERRUPTED`), `failure` (core `Failure` name, `DOWNLOAD_` plus a download error name, `DOCUMENT_` plus a document state name, or `CHECKPOINT_MISMATCH`; no two failures share a code), `done_ms`, `total_ms`, `words` (from the last checkpoint), `attempts` (automatic restarts since the last user action; bumped only by `reconcileOnStart`), `enqueued_at`, `updated_at`. Indices on `state` and `seq`.

**`note_voice_links`** (PK `note_id, variant_id, speaker_id`; index on `voice_id`; cascade): a mirror of the matches in `voices/notes/<id>.json`, with `voice_id` (null for no match), `state` (`Match.State` name), `score`, `seconds`. Room never holds embeddings. Every write to a per-note voice file rewrites that note's links in the same call, and startup re-syncs all links from the files (section 4.8).

Required queries: `libraryFlow` (live notes by `created_at` DESC, left-joined with `jobs` and the selected `variants` row, plus `notes.preview` or the first 160 characters of `note_text.body`), `search(fts)` (with `snippet(note_fts, char(2), char(3), '…', 1, 15)`, trashed notes excluded), `trashFlow` (by `trashed_at` ascending: the soonest to expire first), `expired(cutoff)`, `noteFlow(id)`, `queueFlow`, `voiceNoteCounts` (live notes whose selected variant links a speaker to the voice as AUTO or CONFIRMED) and `counts`.

The standalone model download (from Settings or a banner, with no note) is not a `jobs` row. It is in-memory state of `ModelInstallOwner`; the `.part` files carry its resume.

Schema migrations: version 1 ships. Later versions need a written `Migration` with a `MigrationTestHelper` test; `fallbackToDestructiveMigration` is forbidden. A database that fails to open or migrate is renamed `cassini.db.corrupt-<epoch>` and the catalogue is rebuilt (section 4.9). Some errors do not show a damaged file: a full disk, an I/O error, a locked database, a file that cannot be opened, a permission error, no memory, or a read-only database. For these errors, `CassiniDatabaseOpener` does not rename the file. It throws the error, and the file stays. The startup (P5) then shows an error and does not rebuild the catalogue. The next start tries to open the file again.

FTS prefix queries are written with the star inside the quotes (`"consegn*"`), the FTS3/4 phrase-prefix syntax; a star after a closing quote is FTS5 syntax and is never produced. The unicode61 tokenizer must work on API 26. `NoteFtsDeviceTest` is a `:data` device test ("giovedi" finds "giovedì", "CONSEGNA" finds "consegna", "consegn" finds "consegna"). At this time, CI does not run it. The CI phase (P11) adds it to the API 26 emulator job. To run it on a device, build it with `./gradlew :data:assembleDebugAndroidTest`, install `data/build/outputs/apk/androidTest/debug/data-debug-androidTest.apk` with `adb install -r`, and start `adb shell am instrument -w org.cassini.data.test/androidx.test.runner.AndroidJUnitRunner`. The test APK instruments itself and does not touch the data of the app. A SQLite without unicode61 fails to open the database; that is logged as a bug, with no silent fallback. Robolectric DAO tests fail, never skip, when its SQLite lacks the tokenizer; the device test is the authority.

### 3.4 DataStore keys (`settings.preferences_pb`)

| Key | Type | Meaning |
|---|---|---|
| `models.consentAt` | Long | The user pressed "Download and transcribe" or "Download". Consent = this key present, or Parakeet installed. |
| `models.declinedAt` | Long | "Not now — keep the audio" was pressed; suppresses the automatic consent screen after later recordings. |
| `processing.finalizationMs` | Long | Running estimate of matching plus saving time, `(old*3 + new)/4`. |
| `queue.hold` | String | `USER` or `SYSTEM`, or absent (section 5.2). |
| `recording.lastFormat` | String | Last capture format, for the format chip before the encoder probe returns. |
| `notices.recoveredAck` | Long | Latest recovered-recording time the user has seen or dismissed. |
| `notices.opusNativeAck` | Boolean | "This phone can't create Cassini files" dismissed. |
| `notifications.asked` | Boolean | POST_NOTIFICATIONS requested once. |
| `trash.lastPurgeAt` | Long | Last purge run (diagnostics only). |

### 3.5 Checkpoint contract (`notes/<id>/work/`)

`checkpoint.json` version 1 is the 0.1.0 key set: `version`, `source`, `signature` (`length:mtime`), `model` (Parakeet revision), `speakers` (diarizer SHA-256), `recognition{inferenceMs, chunks[{start, end, window (base64 f32le), words (cassini.words.v1)}]}`, `encoder{…libopus history, Ogg LiveState, length}`, `diarizer{cache (base64, at most 200000 floats), elapsedMs, turns}`, `speakerPrefix`, `speakerFailure`, `clipSamples`, `voiceClips`, `positionSamples`, `doneMs`, `durationMs`, `elapsedMs`. In the new layout `source` holds the note's audio file name (`capture.opus`, `source.mp3`, `document.opus`, …). `CheckpointState.validate` checks it, the signature, the Parakeet revision and the diarizer SHA against values built from the note row.

The directory is keyed by note id. It is deleted at the end of adopt (row first, then directory), on "Start over", and on purge. A committed golden `checkpoint.json` (from a 0.1.0 device run) stays as a codec test in `core/src/test/resources/checkpoint/`.

### 3.6 `names.json` (draft speaker state)

Members: `labels`, `merges`, `remembered`, `automatic`, `generation` (0.1.0), plus two optional members added in P3:

```json
"live":     { "<speakerId>": { "voice": "<voiceId>", "score": 0.61, "state": "SUGGESTED" } },
"rejected": { "<speakerId>": ["<voiceId>", "..."] }
```

`live` is the latest live match per speaker (AUTO, SUGGESTED or CONFIRMED). `rejected` lists the voices the user answered "Not X" to. The codec reads missing members as empty and writes them only when non-empty. The file is internal and never shipped.

One owner per running or paused note: `DraftNames` (in `:data`, held by `CheckpointRepository`) keeps an `AtomicReference<CheckpointNames>`. Every change, from the UI or from the job, is `updateAndGet` with a pure reducer of `CheckpointNames` in `:core` (`confirm`, `reject`, `rename`, `merge`, `remember`, and `applyLive` for the job's live matches, which also sets the `automatic` names); closing naming at step 6 is `DraftNames.close()` (P5), not a `:core` reducer; then the current value is written to `names.json` under the note's file lock, reading the reference inside the lock so the last write always holds the latest state. Nobody writes a snapshot read earlier.

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

Shared rules:

- `suspend` functions run on IO; `Flow`s are cold Room or DataStore flows.
- `…Blocking` functions are `@WorkerThread`, assert that they are not on the main thread, and exist only for the inference thread.
- Locks are `java.util.concurrent.locks.ReentrantLock`s from `NoteLocks` (one per note, striped by note id) and one per file-backed store. Both suspend paths (inside `withContext(IO)`) and blocking paths take the same lock. Code never suspends while holding one: `withContext(io) { lock.withLock { blockingWork() } }`. Kotlin `Mutex` is used only where every holder is a coroutine (`NativeBudget`, `ModelInstallOwner`).
- Errors reach the UI as sealed outcomes that carry a core `Failure`.
- No file I/O on the main thread. Debug builds set `StrictMode.detectAll().penaltyLog()`.

### 4.1 `NoteRepository`

- Reads: `library()`, `search(query)` (via `SearchQuery.fts`; a blank query yields `library()`), `note(id)`, `trash()`, `counts()`.
- Recording: `createRecording(title, createdAt, format)` creates the directory and a `LIVE_CAPTURE` row before capture starts; `finishRecording(id, durationMs)`; `markUnplayable(id)`.
- Edits: `rename(id, title)` updates `notes.title` and `note_text` in one transaction. For a `DOCUMENT` note it then rewrites the document title through `DocumentRepository.retitle` in the background, but only when no job row exists and the document state is `OK`; otherwise it sets `title_dirty` (adopt applies it; a non-OK document keeps the new title in Room only and a fresh seal later uses it). If the background write does not run or fails, `DocumentRepository.retitleDirty` at the next start or `exportCopy` writes the title. Also `setPosition(id, ms, final)` (with `final = true` on pause and on leaving, it recomputes `notes.preview` with core `PreviewText` from the selected variant's words), `selectVariant` (rewrites `note_text.body` and `preview`), `acknowledgeTrust`.
- Trash: `moveToTrash` (records `trashed_job`), `restore` (re-enqueues a QUEUED `trashed_job` at the back; PAUSED jobs were kept), `deleteNow`, `emptyTrash`, `purgeExpired(now, zone)` (section 4.10).
- `adoptSealed(id, selectVariant)`: installs `work/sealed.opus` as in section 3.2 in one transaction for the row, variants, speakers, `note_text` and the removal of the job row; then deletes the previous plain file. If the note title and the document title or its first `TITLE` tag are different, it writes the note title into the document. The links are not in this transaction: `VoiceRepository.adoptPending` writes them after it. `work/` and its `sealed` marker stay. Idempotent: if `document.opus` already has the sealed signature, it only finishes the remaining steps. `deleteAdoptedWork(id)` is the last step of an adopt: it deletes `work/`, and the marker is the last file that it deletes. `SealedAdopter.of` does `adoptSealed`, `VoiceRepository.adoptPending` and `deleteAdoptedWork` in this sequence. Then it releases the draft names in memory, so that the next job of the note starts with no names. If a crash or an I/O error occurs before the end, the marker stays and the next start does the adopt again. If `adoptPending` cannot read a voice file now (`Kept(UNREADABLE)`), the marker also stays. A voice file from a newer Cassini (`Kept(NEWER)`) does not stop the adopt: the pending file stays until the next job of the note writes a new one. `JobRepository.enqueue` also finishes such an adopt before it adds a job (section 4.7).
- `refreshIfStale(id)`; `titleBlocking(id)` (read at seal time); `rowBlocking(id)`.

### 4.2 `ImportRepository`

`import(uri): ImportOutcome` = `Created(noteId) | Existing(noteId) | Rejected(failure, detail)`:

1. Read `OpenableColumns.SIZE` and `DISPLAY_NAME`. Reject `LARGE` above `Limits.MAX_IMPORT_BYTES` (2 GiB; `Limits` is in `core/audio/AudioLimits.kt`).
2. Require `usableSpace ≥ size + Limits.MIN_FREE_BYTES`, else reject `SPACE` with the needed bytes. If the space goes during the copy, `SPACE` also gives `size + MIN_FREE_BYTES`, because the import deletes the copied part.
3. Stream-copy into `notes/<newId>/source.tmp` while hashing SHA-256 and enforcing the cap during the copy.
4. If the hash matches a live note's `source_sha256`, delete the new directory and return `Existing`. A match with a trashed note is ignored.
5. Classify: Ogg Opus with Cassini metadata → `document.opus`, `readChecked`, `IMPORTED_DOCUMENT`. Other Ogg Opus → `source.opus`. RIFF/WAVE → `WavDecoder` header check; anything but 16-bit PCM is `Rejected(WAV)`. Anything else needs an audio track in `MediaExtractor` (behind an `AudioProbe` interface for tests), else `Rejected(OPEN)`.
6. Fill the duration (`OggOpus.scan` or `MediaExtractor`) and insert the row. Title: a document's `meeting.title`; otherwise the display name without extension (never an existing `TITLE` tag, so one source decides). If a `content://` Uri has no display name, the note gets the fallback title ("Imported audio", localized). The last path segment of such a Uri is an id, not a name. The adopt of the first seal writes the note title into the `TITLE` tag (section 4.1).
7. A storage error after the copy (a failed rename, read or insert) gives `Rejected(SAVE)`. A full database gives `Rejected(SPACE)`. The import then deletes the new directory.

Imports run regardless of a running job.

### 4.3 `DocumentRepository`

- `sealBlocking(noteId, audio, transcript, title, processing, existing, labels)`: writes `work/sealed.opus` with `CassiniDocument.create`, fsyncs, re-reads and verifies, then writes the `work/sealed` marker. It never touches `document.opus`. `TranscriptionJob` does not use `sealBlocking`, because the job must write the pending prints before the marker (section 5.4). `markSealedBlocking(noteId, document)` writes only the marker, fsyncs it and the directory, and puts the verified `document` in the LRU by its signature. Then adopt does not read and hash the sealed file again (amendment FAST NOTE OPENING, item 1). The `:app` adapter of `JobStorage.markSealedBlocking` calls it.
- `read(noteId)`: `readChecked` with the digest verified; an in-memory LRU of 4 entries keyed by signature.
- `relabel(noteId, labels)`, `retitle(noteId)` (reads the title from the row; sets `meeting.title` and `TITLE`; requires `OK`; refused while a job row exists), `mergeSpeakers(noteId, variantId, source, target)` (a new variant from `SpeakerMerges.apply` and `SpeakerMerges.processing`, which becomes the default and the selected variant; refused while a job row exists).
- `exportCopy(noteId, dest)` (byte for byte; plain notes copy their audio file; if `title_dirty` is set on an `OK` document, it first writes the title into the document) and `exportTranscript(noteId, request: TranscriptExport, dest)` (`TranscriptExport` holds the variant, the format, the labels and the header).
- `deleteFiles(noteId)`, `sweep()`, `retitleDirty()` (writes the title of each `OK` document note that has `title_dirty` and no job row).

### 4.4 `CheckpointRepository`

`loadBlocking(noteId, expected): Valid(state) | Missing | Mismatch(reason)`; `saveBlocking(noteId, state)` (AtomicFile, after `audio.opus.part` and `clips.pcm` are fsynced); `draftNames(noteId): DraftNames` (section 3.6; loads `names.json` once per process); `discard(noteId)`; `dropSealed(noteId)` and `dropSealedBlocking(noteId)` (section 5.2 rule 5: delete the `sealed` marker, fsync `work/`, then delete `sealed.opus`; the checkpoint, `names.json`, `clips.pcm` and `audio.opus.part` stay); `status(noteId)` (done/total, mismatch, or sealed-awaiting-adopt); `loadDraft(noteId)` (words of the saved chunks with names applied, for a paused note); `load(noteId)` and `identity(noteId)` (the identity comes from the note row); `sealedMarker(noteId)` (true when `work/sealed` exists); `hasWork(noteId)`; `release(noteId)` (forgets the draft names in memory; it takes the note lock on IO).

### 4.5 `VoiceRepository`

All files under `voices/`, one lock per file.

- `people(): Flow<SavedVoices>`: the list of `SavedVoice` with note counts, confirmed seconds and the stale flag (`VoiceStaleness`), and the problem of `people.json` if there is one.
- `rename`, `forget(voiceId)`: removes the person; scrubs their matches from every file under `voices/notes/` and `voices/pending/`, from the links, and from `live`, `remembered` and `automatic` of every `DraftNames` in memory and every `names.json` on disk.
- `forgetEveryone()`: writes `generation` = old + 1 first, then deletes everything else under `voices/` (including `pending/`), clears links, and clears `live`, `remembered` and `automatic` in every draft as above.
- `prints(noteId, variantId)`, `setMatch`, `enrol`, `unenrol`, `copyPrints` (merge case), `deleteForNote` (both the sealed and the pending file, and the links), `discardPending(noteId)` (only the pending file; the sealed file and the links stay).
- `enrol` writes the match in the note file first and `people.json` second; if the second write fails, it clears the match again. `unenrol` writes `people.json` first and clears the match second; if the second write fails, it puts the enrolment back. Thus, after a write error, a retry changes the person one time only.
- Blocking entry points for the job: `peopleBlocking`, `generationBlocking`, `savePendingBlocking(noteId, prints, generation)`, `discardPendingBlocking(noteId)`. The job uses them through the `JobVoices` port (section 5.4): `peopleBlocking`, `generationBlocking`, `noteVoicesBlocking(noteId)`, `savePendingBlocking(noteId, variantId, model, prints, generation)` and `discardPendingBlocking(noteId)`. The `:app` adapter `RepositoryJobVoices` connects the port to this repository.
- `adoptPending(noteId)`: if the pending file's generation equals the current one, merge it into `voices/notes/<id>.json`, dropping matches to voices that no longer exist; otherwise delete it without merging. Then delete the pending file. The pending file stays (`Kept`) when a voice file of the note is newer or cannot be read, when `generation` cannot be read, and when `people.json` cannot be read and the draft has Remember choices.
- A voice file whose `version` is newer than the codec knows stays in place, read-only, and is reported. Only unparseable JSON is renamed `.corrupt-<epoch>`. The Saved voices screen then shows an error banner and disables edits. A damaged `people.json` is read-only too. No edit and no enrolment writes a new list over it. The problem stays, also after a restart, while the `people.json.corrupt-<epoch>` copy exists. Only "Forget everyone" deletes that copy and makes the list editable again (`SavedVoices.canForgetEveryone`).

### 4.6 `ModelRepository`

`status: StateFlow<Map<ModelPart, ModelStatus>>` for `SPEECH` (Parakeet, plus VAD best effort), `SPEAKERS` (diarizer) and `VOICES` (voiceprint). `ModelStatus = Ready | Missing(consented) | Downloading(pct, bytes, total) | Verifying(pct) | Failed(error)`. Also `consented()`, `recordConsent()`, `recordDeclined()` and `plan()` (core `ModelPlan`). There is no delete API in this version: the design has no surface for it.

`ModelInstaller` does not call `ModelRepository` directly. It uses the `ModelStore` port in `:engine` (`ModelInstaller.kt`), which `:app` implements over `ModelRepository` and the connectivity service. All functions of `ModelStore` block and operate on IO:

- `consented()`: consent recorded, or Parakeet installed.
- `hasValidatedNetwork()`: the active network has `NET_CAPABILITY_VALIDATED`.
- `ready(model: ModelDescriptor)`: the model marker holds the pinned value.
- `usableBytes()`: free bytes under `AppPaths.modelRoot`.
- `installBlocking(model: ModelDescriptor, progress: (Long, Boolean) -> Unit)`: installs one model (not one part) through `ArtifactFetcher`. The progress gives the bytes of this model and a "verifying" flag.

### 4.7 `JobRepository`, `SettingsRepository`, `RecoveryRepository`

- `JobRepository` (Room `jobs`; used only by `ProcessingCoordinator`): `queue()`, `enqueue(noteId, kind)` (no-op if QUEUED or RUNNING; PAUSED, FAILED and NEEDS_CONSENT rows are re-queued at the back with failure and pause cleared and attempts 0. If the note has no job row but has a `work/sealed` marker, an adopt did not finish. `enqueue` then runs `SealedAdopter` first. If the marker stays, it refuses with `ADOPT_PENDING`.), `claimNext()` (one transaction; fails if a RUNNING row exists), `markRunning`, `markPaused(reason, done, total, words)`, `markFailed`, `markNeedsConsent`, `remove`, `reconcileOnStart(activeNoteId)` (section 5.3; the only place that bumps `attempts`).
- `SettingsRepository`: typed accessors for section 3.4.
- `RecoveryRepository.recover(activeNoteId)`: `LIVE_CAPTURE` rows that are not the active capture. `.opus` is repaired with `OggRepair` and becomes `PLAIN` with the duration from the end granule. `.m4a` becomes `PLAIN` if `MediaExtractor` opens it, else `UNPLAYABLE`. Returns the recovered ids for the "recording recovered" banner.

### 4.8 Startup work

`CassiniApplication` runs this on IO in the application scope, once per process (also for service-only starts), and then completes `AppGraph.ready`:

1. Open Room. If that fails, rename the file and rebuild (section 4.9). Rebuild also when a note directory that holds audio (`document.opus`, `capture.*` or `source.*`) has no row. Thus the next start finishes a rebuild that a crash or an error stopped. A full disk, an I/O, lock or permission error, or a read-only database is thrown, and the file stays (section 3.3). Then show an error and do not rebuild.
2. `RecoveryRepository.recover(RecordingController.activeNoteId)`.
3. `JobRepository.reconcileOnStart` (section 5.3), which finishes interrupted adopts. It does not delete the `work/` of a note that has no row.
4. Re-sync `note_voice_links` from `voices/notes/*.json` in one transaction; delete per-note voice files whose note row and note directory are both gone.
5. Trash purge (section 4.10).
6. Sweep: delete `*.tmp` older than 1 h that no job or import owns; delete `cacheDir/exports/`; delete a note directory without a row only if it holds nothing but `*.tmp` files and is older than 1 h. A sweep never deletes audio.
7. `DocumentRepository.retitleDirty()`: write the titles that a rename did not write into the document.

### 4.9 Catalogue rebuild

`CatalogueRepository.rebuild()` is idempotent and never deletes audio. It adds only the notes that have no row, so a second run finishes a rebuild that stopped. An I/O or SQLite error in one directory is logged and does not stop the others (`RebuildReport.failed`). For each `notes/<id>/`:

- `document.opus` → `readChecked` → a row (`IMPORTED_DOCUMENT` unless the vendor is cassini-android, else `RECORDED`; title from `meeting.title`) plus cache rows.
- `capture.*` or `source.*` → repair a `capture.opus` that does not end cleanly; the row gets `PLAIN` (`UNPLAYABLE` for an m4a that does not open), `title = "Recovered recording <d MMM>"`, `origin = RECOVERED`.
- A `work/sealed` marker → the rebuild finishes the adopt (`SealedAdopter.of`) and makes no job row. If the sealed output is unreadable, the checkpoint rule applies. A `work/checkpoint.json` that decodes → a PAUSED job with `pause_reason = INTERRUPTED`.
- `voices/notes/*.json` → `note_voice_links` rows. A file whose note directory is gone is deleted.

### 4.10 Trash purge

`TrashPolicy` (`:core`, `java.time` in the device zone): `daysLeft = 30 − daysBetween(localDate(trashedAt), localDate(now))`, clamped at 0. A note expires when `daysLeft == 0`.

The purge runs at every process start (after the DB opens), when the Trash screen opens, and immediately for "Delete now" and "Empty". Per note, in this order:

1. Cancel and remove any job.
2. `VoiceRepository.deleteForNote`.
3. Delete `notes/<id>/` recursively (audio, document, checkpoint, clips, sealed output).
4. Delete the row (cascades variants, speakers, text, FTS, links, jobs).

Files go before the row. A crash midway leaves an expired trashed row that the next run finishes; the reverse order would let the catalogue rebuild resurrect the audio. Saved people are not unenrolled (an open question for the owner). WorkManager is not used: the copy promises "after 30 days", the data never leaves app-private storage, every app or service start runs the purge, and WorkManager would add a scheduler and a database for one idempotent query. `PRIVACY.md` states: "Notes in Trash are deleted the first time Cassini runs on or after the 30th day."

## 5. Background work

### 5.1 Components

| Component | Module | Owns |
|---|---|---|
| `JobRepository` | `:data` | The durable queue (Room `jobs`). |
| `TranscriptionJob` | `:engine` | One run of the pipeline for one note: `MainActivity.transcribe()` of 0.1.0 as named steps. |
| `ModelInstaller` | `:engine` | Install sequence: Parakeet, then VAD, diarizer, voiceprint (each best effort). |
| `ModelInstallOwner` | `:app` | The only caller of `ModelInstaller`. One install at a time; jobs and the standalone download share it. |
| `ProgressTracker` | `:engine` | Turns job events into `RunningProgress` and `DraftState` at 1 Hz. Owns `TranscriptionProgress` and `BatteryProjection`. |
| `InferenceDispatcher`, `NativeBudget` | `:engine` | One `cassini-inference` thread; the model-load `Mutex`. |
| `ProcessingCoordinator` | `:app` | Queue logic, drain loop, `StateFlow<ProcessingState>`, draft actions, holds. |
| `ProcessingService` | `:app` | Foreground lifetime only: type, wake lock, notification, `onTimeout`. |
| `RecordingController` | `:app` | `MicrophoneCapture`, `StateFlow<RecordingState>`, levels, the policy loop, `activeNoteId`. |
| `RecordingService` | `:app` (`recording/`) | Microphone foreground lifetime, notification, the hand-off to processing. |
| `PlaybackController` | `:app` | `MediaPlayer`, position flow, audio focus, `playRange`. |
| `NoticeCenter` | `:app` (P10) | Merges coordinator, recorder, recovery, model and import state into the banner list of each screen. |

### 5.2 The transcription queue

1. One job at a time, process-wide, on the inference thread. FIFO by `seq`. No reordering in this version.
2. Enqueue sources: Finish recording with consent (automatic); Transcribe on a plain note; Transcribe again on a document (adds a variant); Resume (PAUSED → back of the queue, starts at once if nothing runs); Try again (FAILED); Start over (discard the checkpoint, then enqueue); Restore of a trashed note whose `trashed_job` was QUEUED.
3. A note that is recording, a trashed note or an `UNKNOWN_FORMAT` document cannot be enqueued.
4. When a job ends (Done, Paused, Failed, NeedsConsent, Cancelled), the coordinator claims the next QUEUED job inside the same, already-foreground service. The service stops when the queue is empty or held.
5. **Stop and keep progress** (in-app, per note): during the decode, the job saves a checkpoint at its next sample block and throws `ProcessingPaused`. It saves the checkpoint only when at least one window is done. A stop before the first window leaves no checkpoint. After the decode, the final checkpoint stays and the job stops at its next check. The row becomes PAUSED(USER) and the next queued note starts. A stop after the seal step calls `CheckpointRepository.dropSealed`: it deletes the marker first, then `work/sealed.opus`, and keeps the complete checkpoint; `document.opus` was never touched.
6. **Stop in the notification** (`stopAll`): stops the running note as in rule 5 and sets `queue.hold = USER`. Queued notes stay QUEUED but do not start; the Library shows "Transcription paused · N waiting" with Continue.
7. **Remove from queue** on a QUEUED note deletes the row (the note is "Audio only" again), or returns it to PAUSED(USER) when it has a checkpoint.
8. **Cancel** (internal: Start over, Move to trash of a RUNNING note when the user chose to stop) is `Job.cancel()`, which interrupts the inference thread; a sherpa call in flight finishes first, then `finally` blocks release native handles. After `join()`, `CheckpointRepository.discard` deletes the work directory, and `VoiceRepository.discardPending` deletes `voices/pending/<id>.json`. The coordinator calls the two functions.
9. **Move to trash**: the job state is stored in `notes.trashed_job`. A QUEUED job row is deleted, but its `work/` stays; a RUNNING job is stopped (PAUSED/USER, checkpoint kept) before the note is trashed; a PAUSED job is kept. Restore re-enqueues a stored QUEUED job at the back, with the numbers of its checkpoint; PAUSED stays PAUSED.
10. Holds (`queue.hold`) stop the drain loop from starting the next job; they never touch a running job. `USER` is cleared by Continue or any explicit Transcribe/Resume. `SYSTEM` (set by `onTimeout`) is cleared only by Continue or Resume from a visible screen.
11. Transcription keeps running while another note is recording. The capture thread runs at `MAX_PRIORITY`. P12 checks for capture overruns on the emulator.

### 5.3 Restart and process death

When the coordinator is created it runs `reconcileOnStart` on IO before emitting anything but `Loading`:

| Found | Action |
|---|---|
| A `work/sealed` marker with a `sealed.opus` that reads OK (any job state) | Finish adopt (section 4.1 `adoptSealed`, `VoiceRepository.adoptPending`), remove the job. Never FAILED. A marker with an unreadable `sealed.opus` is deleted with the file, and the row is handled by the next rules. |
| A RUNNING row with no checkpoint (killed during download, model load or the first window) | Becomes QUEUED, keeping its `seq`; `attempts + 1`. Above 2 attempts it becomes PAUSED(INTERRUPTED) with `done_ms = 0`, so a crash during model load cannot start the job again without end. |
| A RUNNING row with a valid checkpoint | Becomes QUEUED, keeping its `seq` (head of the queue); `attempts + 1`. Above 2 attempts it becomes PAUSED(INTERRUPTED) and the "Transcription stopped" notification offers Resume. |
| A PAUSED row with no checkpoint | Stays PAUSED with `done_ms = 0`; Resume starts from the beginning. |
| A RUNNING or PAUSED row whose checkpoint exists but fails `validate` | FAILED(`CHECKPOINT_MISMATCH`): "Saved progress no longer matches". |
| A `work/` directory with no job row and no marker | Deleted (every work directory is created after its row). The `work/` of a note in Trash stays: Trash deleted the row of its QUEUED job, and Restore needs the checkpoint. The `work/` of a note without a row also stays: the catalogue rebuild uses it. |

The queue then starts in one of two ways:

- A redelivered service intent (`START_REDELIVER_INTENT`) calls `coordinator.drain()`, whatever its action. Android sends every pending intent again, and Room and the settings already keep the queue and the holds. Thus the service does not do a Resume, a Stop or a Cancel a second time. The system restart of a crashed foreground service is best effort on every API level (Android 12+ may refuse the foreground start; section 5.7 catches it).
- `ProcessLifecycleOwner` ON_START (the app is visible) while QUEUED rows exist, nothing runs, consent exists and no hold is set: the visible app starts the service. PAUSED jobs never resume on their own.

| Event | Result |
|---|---|
| Activity recreation (rotation, locale, dark mode, font scale) | Nothing; ViewModels re-collect by note id. |
| UI trimmed while the service runs | Nothing; the process stays alive. |
| Process killed (low-memory kill, native crash; simulated with `kill -9`) | Resumes from the last window checkpoint, losing at most one 28 s window of work. At most 2 automatic attempts. |
| Force-stop | No redelivery. On the next launch the RUNNING row becomes QUEUED and resumes at ON_START. |
| Reboot | Nothing runs until the user opens the app; then queued work continues as above. |
| Java `OutOfMemoryError` | Caught at the job boundary; PAUSED(MEMORY) with the checkpoint intact. Native out-of-memory kills are covered by the checkpoint plus redelivery. |

`adb shell am kill` does not kill a process that runs a foreground service, so device checks use `adb shell run-as org.cassini.android kill -9 <pid>` (section 9).

### 5.4 `TranscriptionJob` and its ports

`TranscriptionJob` lifts 0.1.0 `MainActivity.transcribe()` (lines 249-538) into named steps. Step order, checkpoint cadence (except that no checkpoint is saved before the first window is done, section 12), provenance keys (`x-inferenceMs`, `x-segmentation`, `x-resumedFromMs`, `x-speakerDiarization`, `x-incrementalDiarization`, `x-speakerMerges`, `x-speakerIdentification`) and label precedence (draft name, then AUTO match, then `Speaker N`) do not change. The seal target and the adopt order change as described in section 3.2.

Ports (interfaces in `:engine` `job/`, implemented in `:app` over `:data`):

`JobPorts.kt` is the contract. Each port function has KDoc that gives its thread.

- `JobStorage`: `loadCheckpoint`, `saveCheckpoint`, `names` (the `AtomicReference` of the note's `DraftNames`), `mutateNames(reducer)`, `openNames`, `closeNames`, `audioFile`, `knownDurationMs` (`notes.duration_ms`, the first total of the progress), `workFiles` (`audio.opus.part` and `clips.pcm`), `sealTarget` (`work/sealed.opus`), `withNoteLockBlocking`, `existingDocumentBlocking`, `titleBlocking`, `markSealedBlocking(noteId, document)` (the verified document that `CassiniDocument.create` returned, for the LRU), `discardSealedBlocking` (deletes the marker and `work/sealed.opus`), `adopt(noteId, Adoption)` (a suspend function run in `NonCancellable`).
- `JobVoices`: `peopleBlocking`, `generationBlocking`, `noteVoicesBlocking`, `savePendingBlocking`, `discardPendingBlocking`. The port has no `enrol`: adopt enrols the draft "Yes, Luca" choices from `Adoption.remembered`.
- `ModelAccess`: `consented`, `ready(part)`, `detectorReady` (the VAD), `plan`, `hasValidatedNetwork`, `install(parts, onProgress)` (delegates to `ModelInstallOwner`). If Parakeet is ready but the VAD is not, and the network is validated, the job asks for SPEECH again. The installer then fetches only the VAD, as best effort.

The job seals the document itself. Under `withNoteLockBlocking` it reads the existing document and the title, and writes `sealTarget` with `CassiniDocument.create`, which verifies the file. Then it fsyncs the file, writes or discards the pending prints, and calls `markSealedBlocking`. `:app` does not implement a `sealBlocking` for the job (section 4.3).

`existingDocumentBlocking` gives the document in its current state. The job adds a variant only to an `OK` document. For a STALE_AUDIO, INVALID_METADATA or UNREADABLE document (the list of features.md section 6), the job seals a fresh document over the same audio. It first copies the audio packet for packet into `work/audio.opus.part` without the `CASSINI_*` tags, so that no old transcript body stays. Names do not carry over from such a document. The job refuses an UNVERIFIED or UNKNOWN_FORMAT document with `JobFailure.DocumentRefused` (code `DOCUMENT_UNVERIFIED` or `DOCUMENT_UNKNOWN_FORMAT`), at step 1 and again under the lock at step 9. Such a document cannot take a new variant, and features.md does not let a fresh document drop its versions without the confirmation. 0.1.0 refused every state except `ok`. The owner can add UNVERIFIED to the features.md list (with the same confirmation) instead; then the job must allow it.

If the diarizer fails after some windows, the words of those windows keep their speakers, as in 0.1.0. The seal still records `x-speakerDiarization`, and matching runs on their clips. Only a failure at the first window gives no turns, no diarization record and thus `speakersSeparated = false`. `JobEvent.NamingClosed(matching)` tells the tracker whether step 7 runs.

Live matching, the final matching and the labels use the saved voices as they are at that time, not the list of step 1. Thus a person that the user forgets during the run is not recognized again. If "Forget everyone" changes the generation during the run, the job uses no saved voices.

`JobOutcome` is `Done`, `AdoptPending`, `Paused(reason)`, `Failed(failure)`, `NeedsConsent(missingBytes)` or `Cancelled`. `AdoptPending` means that the marker and `work/sealed.opus` exist but adopt failed. The coordinator then finishes adopt as `reconcileOnStart` does (section 5.3), and the row never becomes FAILED. `JobFailure.Download` carries `requiredFreeBytes`, the figure that the space check of the installer used ("Free about N MB").

Seams (interfaces in `:engine` `job/`, section 2): `Recognizer`, `StreamingDiarizer`, `PrintExtractor`, `AudioSource`, `OpusSink`.

| # | Step | Thread | Core `Stage` | Design step |
|---|---|---|---|---|
| 1 | `prepare`: load and validate the checkpoint, voices and names; memory preflight | IO | – | – ("Starting…") |
| 2 | `installModels` | IO | – | not a stage: `JobPhase.Downloading` |
| 3 | `restore`: encoder, diarizer state, clips | inference | READING | Reading |
| 4a | decode until the first 28 s window is full | inference | READING | Reading |
| 4b | Parakeet decoder loads | inference | PREPARING | Preparing |
| 4c | each window: recognition | inference | WORDS | Words & voices |
| 4d | each window: Sortformer, assignment, clip cache, live voice matching | inference | SPEAKERS | Words & voices |
| 4e | progress, checkpoint save (only after the first window is done: a stop before that leaves no checkpoint) | inference | – | – |
| 5 | `finish`: last partial window, final checkpoint, encoder finish | inference | WORDS/SPEAKERS | Words & voices |
| 6 | `attribute`: close naming, merges, attribution | inference | – | → Matching |
| 7 | `matchVoices`: voiceprints and `VoiceMatcher.match(excluded = rejected)` | inference | MATCHING | Matching (shown as skipped when the job will not match: no model, no diarizer, or `NamingClosed(matching = false)`) |
| 8 | labels and `x-speakerIdentification` | inference | – | Matching |
| 9 | `seal` into `work/sealed.opus`; pending prints to `voices/pending/<id>.json` with the generation | inference | SAVING | Saving |
| 10 | `adopt`: install the sealed file, row update, apply a dirty title with `retitle`, `adoptPending`, enrolment, finalization estimate, then delete `work/` | IO, `NonCancellable` | SAVING | Saving |

The stepper position is monotonic: `step = max(step, map(stage))`. After a resume it starts at Reading again, because the model reload is a real Preparing.

The decode loop is a blocking function on the inference thread inside `withContext(inference) { runInterruptible { … } }`. It calls the blocking port entry points directly, sends `JobEvent`s with `trySend` on an unlimited `Channel`, and never uses `runBlocking`. `stop()` sets the stop reason (an `AtomicReference<PauseReason?>`). `onSamples` examines it at each sample block. `checkActive()` examines it only after the final checkpoint. `cancel()` interrupts the thread. While a cancelled job still holds `NativeBudget`, the next job shows `JobPhase.WaitingForPrevious` ("Finishing the previous note…").

Title: for a fresh document the seal uses `titleBlocking`. For Transcribe again the verbatim seal keeps the existing manifest, so adopt compares the sealed title (`meeting.title` and `TITLE`) with `notes.title` and calls `retitle` when they differ, then clears `title_dirty`. The same check aligns a `TITLE` tag that a fresh seal of an imported Ogg Opus kept from its source.

Live voice matching runs per window for speakers with at least 4 s of clips and at least 10 s more than the last attempt, excludes rejected voices, and skips speakers with a CONFIRMED `live` entry or a user label. AUTO results set `automatic[id]` (the label shows an "auto" badge). SUGGESTED results are shown as an inline "Is this Luca?" card only while the label is a placeholder, the voice still exists and naming is open. Matches to synthetic note-local voices (ids prefixed `note:`, used for "Speaker names carry over" in Transcribe again) are stored as no match; the label carries the name. The job changes names only through `DraftNames`: `applyLive` for the matches and automatic names, and `close()` at step 6.

At step 6 the job calls `DraftNames.close()` and uses the value it returns ("Saving — you can rename when it's done"). Each run calls `openNames` at step 3, as 0.1.0 did. If a run closes naming and then ends without adopt (a stop, a failure or a cancel), the job opens naming again. Thus a PAUSED note accepts names. Final prints and matches are written to `voices/pending/<id>.json` with the voices `generation` at step 9; adopt merges them only if the generation is unchanged, dropping matches to voices that no longer exist, so "Forget" and "Forget everyone" cannot be undone by a finishing job. A draft "Yes, Luca" enrols at adopt only if the generation is unchanged and the voice still exists. A draft "Not Luca" never unenrols (nothing was enrolled).

### 5.5 Progress numbers (`ProgressTracker`)

One coroutine on `Dispatchers.Default.limitedParallelism(1)` with an injected clock. `elapsedMs = checkpoint.elapsedMs + (elapsedRealtime − runStart)`; on resume `TranscriptionProgress.restore` skips the cold first completion.

- Percent: `Snapshot.percent`, monotonic; `null` gives an indeterminate bar. The UI caps it at 99 until Done.
- Processed: `doneMs / totalMs`.
- Words so far: size of the merged word list.
- Headline and window range: `Snapshot.stage/startMs/endMs` (debounced 400 ms by core), mapped to `ProgressHeadline` (ui.md section 7); the headline never goes back to Reading once the step is Words & voices or later. Without a diarizer the WORDS stage reads "Finding words".
- Time left: `Calculating` until 3 windows completed since the (re)start or while the total is unknown; `Finishing` at 0; else `Range(roundedRemaining(low + fin), roundedRemaining(high + fin))` with `fin = processing.finalizationMs`.
- Battery: `BatteryReader` every 10 s into a new `BatteryProjection` per run. No reading → hidden; charging → PluggedIn; fewer than 120 s or less than 0.5 % drop → Measuring; else Projected.
- Draft: `tentativeFromMs = doneMs − 2000`; words ending after it are drawn as unsettled.

### 5.6 Models and consent

- Consent is `models.consentAt` present or Parakeet installed. It is recorded only by the Model download screen's primary button.
- Enforcement happens twice: in `ProcessingCoordinator.transcribe` and again in `TranscriptionJob.installModels` through `ModelAccess.consented`. Without consent the job ends NEEDS_CONSENT without any network call. JVM tests prove that `ArtifactFetcher` is never called.
- `ModelInstallOwner` runs at most one install. A request while one runs joins it (a shared `Deferred` per part set, guarded by a `Mutex`); a job in `installModels` shows the shared progress as `JobPhase.Downloading`. Cancel from a banner cancels the shared install only when no job waits on it; otherwise it stops the job, which keeps the `.part` files, and the note goes back to its state from before (features.md 13.4): PAUSED(USER) when it has a checkpoint, else the row goes.
- Install sequence through `ArtifactFetcher` with Range resume: SPEECH (4 Parakeet artifacts, required; VAD best effort), SPEAKERS (diarizer `.zst`, best effort), VOICES (voiceprint, best effort, only with the diarizer). Without a validated network the best-effort parts are skipped at once (no 30 s timeout). A failed best-effort part never fails the job; it degrades to one speaker or no names.
- `ArtifactFetcher` keeps the `.part` across failures and process death, resumes with `Range: bytes=<len>-`, accepts a `206` only when the `Content-Range` total equals the pinned size, restarts on a `200`, verifies size and SHA-256 before the rename (deleting the `.part` on a mismatch), never replaces a verified file, and keeps the 30 s read timeout (interrupting the thread does not unblock `HttpURLConnection`). P3 adds a digest progress callback for "Checking files — 80%".
- Redirects: `ArtifactFetcher` sets `instanceFollowRedirects = false` and follows at most 5 HTTPS redirects itself, only to hosts in `ModelCatalog.DOWNLOAD_HOSTS`: `huggingface.co` and its subdomains, `hf.co` subdomains (the Xet/LFS CDN), `github.com`, `*.githubusercontent.com` (release assets), `dist.gocassini.com`. Any other target fails the part with a download error. P3 records the redirect hosts actually seen for every pinned URL (`curl -sIL`) in the KDoc. `PRIVACY.md` lists the same domains.
- `ModelPlan.requiredFreeBytes` = sum over missing parts of (download bytes + installed bytes when the download is compressed) + `ArtifactFetcher.SLACK_BYTES` (32 MiB). The Model download screen shows this sum ("Needs about 860 MB free"). `ModelInstaller` checks each part before its first byte, against that part's own figure and the space free at that time. So SPEECH fails with NOT_ENOUGH_STORAGE only when speech alone does not fit, and `JobFailure.Download.requiredFreeBytes` is the speech figure. A best-effort part without room goes into `failed` and never fails the job. After these checks, only `ArtifactFetcher`'s check before each file runs (what that file still needs + `SLACK_BYTES`), and `ModelPlanTest` proves the plan covers every such check. The installer does not carry over the 128 MiB Parakeet margin of the 0.1.0 `InstalledModels`, so free space that passes the plan also passes each later check, unless something else fills the disk meanwhile.
- Sizes are shown with `ModelCatalog.mebibytes` and labelled "MB", as in 0.1.0 and the boards (640, 62, 28). The Model download total is the sum of the rounded part sizes shown (730), so the numbers on screen add up; the free-space figure is `mebibytes(requiredFreeBytes)`, which rounds to the nearest 10 like every other size (860 for a fresh install, which needs about 861 MiB; the copy says "about", and the check itself compares bytes).

### 5.7 `ProcessingService` and Android 14-16 behaviour

`ProcessingService : LifecycleService` holds only the foreground lifetime.

- Manifest: `foregroundServiceType="dataSync|mediaProcessing"`; permissions `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PROCESSING`, `WAKE_LOCK`, `POST_NOTIFICATIONS`.
- Intents: `ACTION_DRAIN` (no note id; the queue is in Room; this is the redelivered intent) and `ACTION_STOP_ALL`.
- `onStartCommand`: call `startForeground` at once with the current notification. Every `startForeground` call (the first one and each type change) is wrapped in `try`/`catch` for `ForegroundServiceStartNotAllowedException`, `SecurityException` and `InvalidForegroundServiceTypeException`. On the first call: post "Ready to transcribe" (Activity intent), `stopSelf(startId)`, return `START_NOT_STICKY`; the rows stay QUEUED and the next ON_START restarts them. On a type change: keep the previous type and log. Otherwise `coordinator.onServiceForeground(true)`, `coordinator.drain()`, return `START_REDELIVER_INTENT`. Callers keep their own catch as well. The service does not bump `attempts` (only `reconcileOnStart` does).
- Service type follows `coordinator.foregroundNeed`: `DATA_SYNC` while downloading; `MEDIA_PROCESSING` on API 35+ from inference on (6 h per 24 h budget), `DATA_SYNC` on API 29-34 ("local file processing"; no better type, no timeout callback), untyped below 29.
- `onTimeout(startId, fgsType)` (API 35+), synchronously: post "Transcription paused by Android" with an Activity `PendingIntent` (content and Resume); `stopForeground(STOP_FOREGROUND_REMOVE)` and `stopSelf()` without waiting for the job; `coordinator.systemStop(noteId)` best effort (the job checkpoints at its next block); set `queue.hold = SYSTEM`; write the row as PAUSED(SYSTEM) so every screen shows Paused at once. A timeout is never shown as an error. After a timeout, Resume works only from a visible screen.
- The service stops only after the coordinator's drain job has `join()`ed, so a finishing native call never runs outside a foreground service.
- Wake lock `PARTIAL_WAKE_LOCK "Cassini:processing"`, not reference counted: acquired with a 10 min timeout when inference begins, re-acquired at every window, released on Paused, Failed, Done, idle download waits and service stop. Downloads hold no wake lock.
- Memory preflight: `ActivityManager.MemoryInfo.lowMemory`, `isLowRamDevice` or `!Limits.memoryAllows` sets a low-memory hint on the progress card; the job is not blocked. `largeHeap` stays until measured.
- Start paths that Android allows: a visible screen (Transcribe, Resume, Continue, via `ContextCompat.startForegroundService`; `POST_NOTIFICATIONS` requested once on API 33+, denial does not block); a notification action `PendingIntent` (the "Transcription stopped" Resume uses `PendingIntent.getForegroundService`; the recording notification's Finish reaches `RecordingService`, whose hand-off then starts processing within the temporary allow-list); ON_START with a queue. Any other start is attempted with the catch above and falls back to "Ready to transcribe".
- `POST_NOTIFICATIONS` denied: the service still runs; the in-app progress card and Library banner carry the information; Settings explains it with a deep link.

### 5.8 Recording

`RecordingController` owns the `MicrophoneCapture` on a single-thread dispatcher and exposes `StateFlow<RecordingState>` (`Idle`, `Starting`, `Active(noteId, title, format, elapsedMs, pause, space, warning)`, `Finishing`, `Ended(noteId, outcome)`), `levels` (56 values, 125 ms each, max-pooled from 100 ms samples, sampled only while subscribed) and `activeNoteId`.

- `start(title)` is called from the visible Recording screen (Android 14 rule): create the note row (`LIVE_CAPTURE`, `capture.opus` or `capture.m4a`); set `activeNoteId`; start `RecordingService` (`startForeground(MICROPHONE)` first); create the capture; acquire the wake lock (2 h 10 min); start the 1 s policy loop. A failed start deletes the empty directory and row (`CouldNotStart`).
- Title edits are written to the row 500 ms after the last keystroke; `finish(title)` writes the final value.
- `finish(title)` is asynchronous: `capture.stop()` on the capture dispatcher (up to 10 s), row → `PLAIN` with the duration, the hand-off when transcription follows, then `Ended(Saved)`.
- `RecordingPolicy.decide(elapsedMs, usableBytes, bytesPerSecond)` (pure, `:core`): nominal 6000 B/s for Opus 48 kb/s and 4000 B/s for AAC 32 kb/s, measured file growth after 10 s. `RecordingSpace` gives the space-limited and cap-limited remaining time; the hint shows whichever is smaller (features.md section 2). At 15 min of space or less: StorageLow. Below the 200 MiB floor: stop and save. 5 min before `MAX_RECORDING_MS` (2 h): NearTimeLimit. At the cap: stop and save.
- Back, the chevron and the Library button leave the recording running; only Finish stops.
- Pause causes: USER; OTHER_APP on API 29+ from `AudioManager.registerAudioRecordingCallback` matched on our own client session id (`isClientSilenced`); on API 26-28 the transient focus listener, re-requested after each resume. When the silence ends the recording stays paused until the user resumes. No audio focus is requested on 29+, so the user's music keeps playing.
- Recording → processing hand-off: `RecordingService` writes the row QUEUED, calls `startForegroundService(ProcessingService)` inside a `try`/`catch`, waits for `coordinator.state.first { it.serviceForeground }` (10 s timeout), and only then stops its own foreground. The start is legal when Finish came from the visible screen or from the notification action (temporary allow-list). On timeout or failure (for example the 2 h cap reached in the background) the note stays QUEUED and the user gets "Ready to transcribe"; ON_START picks it up.
- After Finish: with consent → enqueue. Without consent and without `models.declinedAt` → the Note screen opens with the Model download screen on top. Otherwise "Audio only". A storage stop or a microphone failure never enqueues automatically.
- `RecordingService` is `START_NOT_STICKY`; crash repair happens through `RecoveryRepository` at the next start.

### 5.9 Playback

`PlaybackController` keeps `MediaPlayer` (Media3 rejected: the behaviour is device-validated and the use case is seeking inside one local file). A 150 ms position flow while playing; the position is persisted every 5 s, and with `final = true` on pause and on leaving (which also refreshes the Library preview). It requests `AUDIOFOCUS_GAIN` (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`) and pauses on focus loss and `ACTION_AUDIO_BECOMING_NOISY`. `playRange(noteId, startMs, endMs)` plays a speaker sample, stops at `endMs` and restores the previous position.

### 5.10 Notifications

Text comes from `ContextCompat.getContextForLanguage(context)`. Each notification is `VISIBILITY_PRIVATE` with a public version without the note title. Channels: `recording` (LOW), `processing` (LOW), `results` (DEFAULT, silent). The small icon is the monochrome logo (`ui_ic_stat_logo`). PendingIntents use `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`. Content and copy are in features.md section 12.

## 6. UI architecture

- One `MainActivity : AppCompatActivity` (AppCompat only for `AppCompatDelegate.setApplicationLocales`; `AppLocalesMetadataHolderService` with `autoStoreLocales` below API 33). Theme `Theme.Cassini` in `app/src/main/res/values{,-night}/themes.xml` with parent `Theme.AppCompat.DayNight.NoActionBar` and `android:windowBackground` = `@color/ui_window_bg` from `:ui`. `enableEdgeToEdge()`, `android:enableOnBackInvokedCallback="true"`, `launchMode="singleTop"`, no `configChanges`. The exported ACTION_VIEW filter stays (content and file schemes; `audio/ogg`, `audio/opus`, `application/ogg`). targetSdk 36 from P10.
- Navigation 3 (`navigation3-runtime`/`navigation3-ui` 1.2.0, `lifecycle-viewmodel-navigation3` 2.11.0). `@Serializable` keys: `LibraryKey`, `NoteKey(noteId, query?)`, `RecordingKey`, `ModelDownloadKey(noteId?)`, `SettingsKey`, `SavedVoicesKey`, `TrashKey`, `LicensesKey`. `NavDisplay` with `rememberSaveableStateHolderNavEntryDecorator()` and `rememberViewModelStoreNavEntryDecorator()`. Sheets and dialogs are ViewModel state (in `SavedStateHandle`), not destinations. "Transcribing" is a mode of the Note screen.
- State holders: one ViewModel per destination, built in `viewModel { }` factories from `AppGraph`. One immutable `UiState` per screen as a `StateFlow` (`combine` + `stateIn(WhileSubscribed(5_000))`). Events are a sealed interface per screen passed to `onEvent`. `XxxRoute(viewModel)` in `:app` collects with `collectAsStateWithLifecycle()` and calls the stateless `XxxScreen(state, onEvent)` in `:ui`.
- ViewModels produce semantic values (enums, millisecond durations, `LocalDate`/`LocalTime`, counts, user text). `:ui` turns them into localized text. This keeps ViewModels free of `Resources` and makes the Italian and font-scale screenshot tests work from the same fixtures.
- The speaker naming state machine (rename, pick a saved person, same-person prompt, rename the saved voice, merge with confirmation, remember, "Not X") is a pure reducer `ui/note/SpeakerNaming.kt` in `:app` with unit tests.
- Back: an open sheet, dialog, search mode or rename consumes back first. On Recording, back minimises (the recording continues). Library is the root; back leaves the app with the system's predictive back-to-home.
- Deep links go through `MainActivity` (`onCreate` with no saved state, and `onNewIntent`); a handled intent is marked so recreation does not replay it. ACTION_VIEW imports on IO and pushes `NoteKey(id)` on top of the existing stack, with Library underneath when the stack was empty. Notification intents carry `open = recording | note` and `noteId`.
- `ModelDownloadKey` with nothing missing: entry points do not open it then; if it is opened anyway (a stale back stack), the route pops itself, enqueuing its note first when it has one.
- After Move to trash the user lands on the Library with the Undo snackbar.
- System bars: dark icons on light screens in the light theme; light icons on Recording always.
- Wide screens: content is limited to 560 dp and centred; backgrounds fill the screen.
- The visual system, components and screen contracts are in `ui.md`.

## 7. Threading rules

| Thread or dispatcher | Runs | Never |
|---|---|---|
| Main | Compose, `StateFlow` collection, `MediaPlayer` control, `startForeground`/`stopSelf`, notification posting | file I/O (StrictMode logs it), sherpa, `capture.stop()` |
| `cassini-inference` (`InferenceDispatcher`, one thread) | every sherpa call, `StreamingOpusEncoder`, decoder callbacks, checkpoint and `names.json` writes from the job, seal | `runBlocking`, suspension inside the native loop |
| IO | Room, DataStore, every repository call, `ModelInstaller` (`runInterruptible(IO)`), adopt, recovery, imports, exports | sherpa |
| `ProgressTracker` (`Default.limitedParallelism(1)`) | `TranscriptionProgress`, `BatteryProjection`, battery reads, building `RunningProgress`/`DraftState` | blocking I/O |
| Coordinator (`appScope`, Default) | queue transitions (each a Room transaction on IO), drain loop, holds | native calls |
| Recording controller (single thread) | capture start/stop, policy loop, recording callback | – |
| `cassini-recording` capture thread (MAX_PRIORITY) | `AudioRecord.read` → MediaCodec → `LiveMuxer` | – |

Locks: `NoteLocks` (a striped `ReentrantLock` per note) and one `ReentrantLock` per file-backed store, shared by suspend and blocking callers and never held across a suspension point; `NativeBudget` (coroutine `Mutex`) around model loading; `ModelInstallOwner`'s `Mutex`; and `JobRepository.claimNext` transactions for the single-RUNNING rule. Draft names have one owner, `DraftNames` (section 3.6): UI and job both change them only through its reducers.

## 8. Native runtime and build

- AAR: `scripts/build-native-aar.sh` builds the pinned fork plus patch with ONNX Runtime 1.27.1 and installs the AAR and a generated POM into `native/m2/org/cassini/native/sherpa-onnx-nemotron/1.13.7-1f23907a/` (git-ignored; `native/m2/README.md` is committed). `scripts/fetch-native-aar.sh` downloads it from the GitHub release `native-sherpa-onnx-1.13.7-nemotron.2` (SHA-256 `d376f7e25707cf6834208db297e5955c8e02117131740df7ff7fd6ca17216068`, pinned in `scripts/native-dependencies.json` with the hash of the patch). The older release `.1` is a pre-patch build (SHA-256 `d03a06f8…`, without `processStreamingChunk`, `saveStreamingState`, `restoreStreamingState`); never use it. `scripts/setup.sh` fetches the libopus source, then takes the AAR from the first source that works: (1) `native/m2` already holds the pinned AAR; (2) the legacy local file `app/libs/sherpa-onnx-1.13.7-nemotron.aar` matches the pin; (3) `fetch-native-aar.sh`; (4) `build-native-aar.sh`. `--from-source` goes straight to (4); `--prebuilt-only` fails instead of falling back. `scripts/verify-native-runtime.sh` compares the installed AAR, the patch and the catalog with the pin.
- `libcassini_opus.so`: AGP `externalNativeBuild` in `:engine` (CMake 3.22.1, NDK 28.2.13676358, `-DOPUS_SOURCE=<root>/.tools/opus/source` from `scripts/fetch-opus-source.sh`, `-DANDROID_STL=c++_static`). `-DCMAKE_BUILD_TYPE=Release` in every variant, so debug builds encode exactly like release builds. `checkOpusSource` fails `preBuild` with "run scripts/setup.sh" when the source is missing and refuses a stale `app/src/main/jniLibs`.
- ABIs: `ndk { abiFilters += listOf("arm64-v8a", "x86_64") }` stays in `:app`'s `defaultConfig` (library `abiFilters` do not filter packaged ABIs). `scripts/apk_compatibility.py` (64-bit ABIs only, 16 KB ELF and ZIP alignment) must pass on the debug APK in every phase.
- Toolchain (verified together with JDK 17): Gradle 9.7.0 (wrapper with `distributionSha256Sum`), AGP 9.3.1 with `android.builtInKotlin=false` and `android.newDsl=false` (deprecated; removed in AGP 10; a temporary opt-out), KGP 2.4.21, KSP 2.3.12, Room 2.8.5, Compose BOM 2026.09.00 (Compose 1.12.1), Navigation 3 1.2.0, compileSdk 37, build-tools 36.0.0, CMake 3.22.1, NDK 28.2.13676358, minSdk 26, Java 17. AGP 9.4.1 + Gradle 9.8.1 with built-in Kotlin is the documented forward path. Always run Gradle with `JAVA_HOME` pointing at JDK 17.
- targetSdk: 34 through P9; 36 in P10 with the Compose UI (edge-to-edge enforced, predictive back). Not 37: Android 17 behaviour changes are not reviewed yet.
- Version catalog `gradle/libs.versions.toml`. Bundles contain only artifacts with an explicit version; BOM-managed artifacts (`compose-ui-test-junit4`, `compose-ui-test-junit4-accessibility`) are declared next to `platform(libs.compose.bom)` in each module. `kotlinx-collections-immutable` 0.5.2. `material-icons-extended` is not used (icons come from the design's SVG paths).
- Screenshot testing: Roborazzi 1.76.0 with Robolectric native graphics, in `:ui` (ui.md section 10). The P2 scaffold test is the proof that it works in an AGP 9 library module with `android.newDsl=false`; the earlier spike ran in an application module only.
- Accessibility checks: `androidx.compose.ui:ui-test-junit4-accessibility` (BOM-managed, pulls in the Accessibility Test Framework). P2 proves `enableAccessibilityChecks()` under Robolectric 4.17/SDK 35; if it does not run there, the checks move to device tests on emulator-5554 (features.md section 14).
- Fonts: unmodified static TTFs of Instrument Sans and IBM Plex Mono, pinned in `third_party/fonts/fonts.json` and fetched by `scripts/fetch-fonts.sh` (ui.md section 3).
- JVM tests: Robolectric 4.17 pinned to SDK 35 by `src/test/resources/robolectric.properties` in every module that uses it, with one test asserting `Build.VERSION.SDK_INT == 35` (SDK 36+ needs a JDK 21 runtime). `--add-opens` jvmArgs and `isIncludeAndroidResources = true`.
- `ciTest` (root task): `:core:test` plus, for every Android module, `verifyRoborazziDebug` where the Roborazzi plugin is applied and `testDebugUnitTest` otherwise. CI and every phase's green check run it.
- Release: R8 (`isMinifyEnabled`, `isShrinkResources`, `proguard-android-optimize.txt`). Keep rules keep members: `com.k2fsa.sherpa.onnx.** { *; }`, `com.github.luben.zstd.** { *; }`, `org.cassini.android.NativeOpus { *; }`, classes with `native` methods, and kotlinx-serialization for the navigation keys. `scripts/package-apk.py` copies the R8 mapping into `dist/`. A `releaseTest` build type (release without R8, same signing) is the tag builds' device-test target; `scripts/launch-check.sh` checks that the minified release APK launches.
- zstd-jni 1.5.7-22: the `@aar` in Android modules, the plain jar in JVM tests.
- Lint: `warningsAsErrors`, `abortOnError`, `checkDependencies`; `MissingTranslation` is an error; `OldTargetApi`, the network-dependent version checks and `UsableSpace` are ignored on purpose. Every Android module has an explicit lint block. Adaptive icons live in `mipmap-anydpi/` (not `-v26`, which raises ObsoleteSdkInt at minSdk 26). After P10 the lint baseline is empty. Attributes newer than minSdk on `<application>` carry `tools:targetApi`.
- Style: detekt 1.23.8 with `detekt-formatting` (autocorrect off, because it would rewrite the verbatim code), `.editorconfig` (4 spaces, 140 columns), ruff for Python, shellcheck, actionlint. detekt parses with a Kotlin 2.0 parser, so the code avoids `when` guards, non-local `break`/`continue`, context parameters and explicit backing fields.
- Signing: the build creates the release signing config only when `CASSINI_BETA_KEYSTORE_FILE` is set and reads `CASSINI_RELEASE_STORE_PASSWORD`, `CASSINI_RELEASE_KEY_PASSWORD` and `CASSINI_RELEASE_KEY_ALIAS` from the environment. No literals in Gradle. CI passes `${{ secrets.X || 'android' }}`-style fallbacks until the owner sets the secrets.
- CI (final shape in P11): `checks` (wrapper validation, detekt, actionlint, shellcheck, ruff, Markdown link check, `fetch-fonts.sh --check`), `core-only` (`:core:test` without `setup.sh`), `build` (prebuilt AAR, `ciTest`, lint, debug assemble, release on tags, Roborazzi diffs uploaded on failure), `emulator` matrix 26/29/34/35 with annotation-selected device tests, `models` (weekly and manual; cached models; `@NeedsModels` on API 34), `native` (weekly from-source build compared with the pin), `release` on tags (notes taken from the version's `CHANGELOG.md` section; `docs/release-notes.md` stays until P11 switches the job). Dependabot watches Gradle, GitHub Actions and the submodule.

## 9. Testing

1. **JVM** (`:core:test`, `:engine:testDebugUnitTest`, `:app:testDebugUnitTest`): the moved engine tests; codec goldens; the P3 pure rules with golden strings; `TranscriptionJobTest` with fakes (window cadence, checkpoint per window, stop → resume equals the uninterrupted transcript, cancel inside a native call, OOM → paused, no network without consent, provenance equal to 0.1.0's for the same inputs, seal never touches `document.opus`); `ProgressTrackerTest`; `ProcessingCoordinatorTest`; `ModelInstallOwnerTest` (one fetch per artifact when a standalone download and a job overlap); `DraftNamesTest` (concurrent UI and job reducers lose nothing); `RecordingControllerTest`; `SpeakerNamingTest`; ViewModel tests with `kotlinx-coroutines-test` and Turbine.
2. **Robolectric** (SDK 35): `:data` repository and DAO tests (FTS prefix and diacritics, snippet markers, cascades, purge order, catalogue rebuild, import classification, adopt crash points, reconcile rows); `ContrastTest`; font and licence asset tests; the SDK 32 test that notification text follows the app locale.
3. **Screenshots** (`:ui`, Roborazzi): every board compared with `design/reference/png`, plus dark, Italian, 1.5x font scale, state variants and components. Goldens are committed; `verifyRoborazziDebug` gates CI.
4. **Device** (`scripts/device-smoke.sh --annotation <name> [--class <c>] [--serial <s>]`): a `CassiniTestRunner` points `AppPaths.root` at `filesDir/androidTest-<run>` and keeps `modelRoot = filesDir` (or an empty scratch model root for download-screen tests), so tests never touch user data and still use the installed models. Annotations: `@CiSmoke` (no models and no microphone; `scripts/ci-emulator-smoke.sh` selects them for the CI matrix), `@NeedsModels` (emulator-5554), `@NeedsMicrophone`, `@Manual`. The expected count comes from a dry run (`-e log true`) of the same selection. The script fails when a selected test fails or is skipped, or when the count changes. `--class` can occur more than one time. `--sample` and `--arg KEY=VALUE` give arguments to the 0.1.0 tests. The argument `-e emptyModelRoot true` gives the run an empty model root. The committed CC-BY-4.0 FLEURS clip is the audio input.
5. **Process-death and persistence checks** (host scripts, not instrumentation, because instrumentation force-stops the app when it ends and `am kill` spares foreground-service processes): `scripts/device-kill-resume.sh` writes `files/debug-root.txt` (debug builds read it; models stay at `filesDir`), copies the long clip into the cache of the scratch root, and starts a job through the debug-only `DebugCommandActivity` (`adb shell am start -n org.cassini.android/.debug.DebugCommandActivity --es cmd import --es file …`). The script kills the process with `adb shell run-as org.cassini.android kill -9 $(pidof org.cassini.android)`. Then it waits for redelivery, or it opens the app again with `--es cmd open`. It reads the status file `<root>/debug-status.json` with `run-as cat`, makes sure that the sealed document passes the format tools, and removes the scratch root and `debug-root.txt`. The entry point is an exported activity, not a receiver: `am` from the shell cannot reach a component that is not exported, and a visible start lets the app start the processing service. The activity does nothing when no debug root is set. `DebugStatusProvider` starts the status writer in each process of a debug-root run, also in a process that Android starts for a redelivered intent. androidx.sqlite keeps the lock file of the database at `cacheDir/<absolute database path>.lck`. Thus the runner and the script also remove that file of their scratch root. `scripts/device-language-check.sh` switches the app language through the UI (uiautomator), kills with `kill -9`, relaunches and checks the language with `uiautomator dump`.
6. **Acceptance** (P12): an instrumented walk through every board on emulator-5554 with device screenshots, compared by a reviewer with the design.
7. **Device safety**: never run Gradle `connected*` or managed-device tasks (they uninstall the app and erase about 770 MB of models on emulator-5554). Install with `adb install -r`, run instrumentation with `adb shell am instrument` or `scripts/device-smoke.sh`. Never `adb uninstall org.cassini.android`, never `pm clear`. Before a test that is not isolated by `CassiniTestRunner` or a debug root, back up the `files/` metadata (not the models) with `run-as` and `tar`.
8. **Pre-release gate** (manual, `docs/development.md`): Pixel 8 (API 37) end-to-end run; music playing while recording does not pause; an incoming call pauses the capture; an API 35/36 AVD for `mediaProcessing` and `onTimeout`; `node scripts/check-portable-document.mjs` and `cassini-extract.py --check` accept a document sealed by the new engine.

## 10. Open-source citizenship

`CONTRIBUTING.md` (fresh clone with submodules, `scripts/setup.sh`, `./gradlew :core:test`, `./gradlew :app:assembleDebug`; when the NDK is needed; test tiers; screenshot goldens; style; DCO sign-off), `CODE_OF_CONDUCT.md` (Contributor Covenant 2.1), `SECURITY.md`, `PRIVACY.md` (data categories, storage paths, the three hosts and the download domains they redirect to, retention, trash, per-note and per-person deletion; shown in the Licenses screen and linked from Model download), `CHANGELOG.md` (Keep a Changelog; 0.0.x and 0.1.0 reconstructed; 0.2 under "Unreleased", including "0.2 does not read 0.1.x app data; exported documents open as before"), issue and PR templates, `CODEOWNERS`, fastlane `metadata/en-US/`. Docs: this file, `docs/design/`, `docs/development.md`, `docs/native-runtime.md`, `docs/data-formats.md` (storage tables, exports, differences from gocassini), `docs/release.md`, `docs/ui-fidelity.md`, `docs/notes/` (dated logs, historical banner). `THIRD_PARTY_NOTICES.md` and the APK copy name the AAR provenance, AndroidX/Room/kotlinx/Compose (Apache-2.0) and the fonts (SIL OFL 1.1). The Nemotron export on dist.gocassini.com gets the OpenMDW-1.1 text, a NOTICE and provenance, prepared under `tools/model-host/` for the owner to upload. F-Droid: no proprietary SDKs; remaining blockers stated honestly (ONNX Runtime prebuilt inside sherpa's scripts, zstd-jni's bundled `.so`).

## 11. Decisions taken and alternatives rejected

- Greenfield storage: one directory per note, Room as a catalogue and cache, the sealed document authoritative for transcript content, Room authoritative for the title. Content-addressed shared `documents/`, `retire` reference counting and `sha256(uri)` checkpoint keys are gone.
- The job seals into `work/sealed.opus` and adopt installs it, instead of sealing over `document.opus`: the old file is the job's audio source and checkpoint signature, so overwriting it before adopt would turn a crash into a false mismatch.
- All voiceprints and matches under `voices/` (including pending ones), so "Forget everyone" is one directory delete and purge is two files per note.
- `ReentrantLock`s shared by suspend and blocking callers instead of coroutine `Mutex`es, because the inference thread must not suspend or `runBlocking`.
- One install owner for models, so a standalone download and a job never write the same `.part`.
- A redirect allow-list in `ArtifactFetcher`, so the privacy promise about hosts is enforced, not just stated.
- Model directories stay at the `filesDir` root with their 0.1.0 names: `ModelCatalog` pins them and the emulator's verified install stays valid.
- Foreground service, not WorkManager, for transcription and downloads: the job is interactive (draft naming, immediate Stop/Resume), and WorkManager restarts after process death start in the background where `setForeground` can throw. Trash purge without WorkManager (section 4.10).
- A persistent FIFO queue in Room with one RUNNING job, instead of 0.1.0's in-process claim set.
- `:engine` behind ports instead of depending on `:data`, so the job is JVM-testable and P4/P5 run in parallel.
- A separate `:ui` module with semantic `UiState` and a `ui_` resource prefix, so the design system and screens are built and screenshot-tested in parallel with the data and service layers.
- Roborazzi over Paparazzi (alpha, layoutlib-bound) and Compose Preview Screenshot Testing (alpha, preview-only). Proven in a spike with this toolchain at 780 x 1688 px, the boards' size.
- Unmodified static TTFs from upstream, not the design's woff2 web subsets: a subset is a Modified Version under the OFL and "Plex" is a Reserved Font Name.
- Icons as `ImageVector`s from the design's SVG paths; `material-icons-extended` removed.
- Manual `AppGraph`, not Hilt. org.json stays in `:core`; kotlinx-serialization only for navigation keys. `MediaPlayer` stays. AppCompat only for locales, and the XML theme lives in `:app` for that reason. Dynamic colour off. StrictMode logs, it does not kill. `singleTop`, not `singleTask`.
- Default speaker labels detected by the `:core` string scan `DefaultLabels.isDefault`, not a stored flag; documents store only label text.
- `OpusEncoder.kt` (MediaCodec, 48 kb/s) deleted: non-Opus sources go through the 24 kb/s libopus path.
- AGP 8.13 rejected (cannot compile against SDK 37); AGP 9.4/Gradle 9.8 deferred. Robolectric at SDK 35 rather than JDK 21. Committing the AAR rejected; a hash-pinned release asset plus a weekly from-source check. Vendoring the conformance vectors rejected in favour of a pinned submodule.

## 12. Known deviations kept on purpose

- The verbatim format code keeps its known deviations from the spec (warnings demote to unverified, pretty-printed bodies, no DATE tag, `null:null` signature for non-file URIs), because the pinned conformance vectors and gocassini agree with the current behaviour. Each is a tracked issue.
- TXT, SRT and VTT exports are word-body derivatives without integrity data; only the sealed `.opus` is a portable Cassini artifact. Android's VTT uses word-timed cues with `<v Label>` spans, while gocassini's `WriteCaptionsVTT` emits one cue per segment as `<Label> text`. `docs/data-formats.md` records both.
- Visual deviations from the boards are listed in ui.md section 11.
- Checkpoint cadence: 0.1.0 saved a checkpoint at the first sample block. The job saves none before the first window is done, so a stop before that leaves no checkpoint ("Paused before any words", features.md TX_PAUSED_EARLY).
- Transcribe again over an UNVERIFIED or UNKNOWN_FORMAT document is refused (`DOCUMENT_<state>`), as 0.1.0 refused every state except `ok` (section 5.4).
- A diarizer failure after the first window keeps the earlier speakers and the diarization record, as 0.1.0 did, though the plan reads "Failure.SPEAKERS degrades to speakersSeparated = false" (section 5.4).
- The model installer checks free space per part, not once for the whole plan (section 5.6).
