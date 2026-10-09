# Test fixtures

Two kinds of input live here:

- `audio/`: a short Italian speech clip that the device tests read.
- `filesdir/`: copies of the app's private `files/` directory in the states that earlier versions left on phones. The data migration of 0.2 is tested against them.

Nothing here comes from a real user. There are no real voiceprints and no private recordings.

## audio/

`fleurs-it-short.{wav,m4a,mp3,opus}` is one recording from the Italian dev split of Google FLEURS (file `10017639081645832204.wav`, dataset revision `70bb2e84b976b7e960aa89f1c648e09c59f894dd`), licensed [CC-BY-4.0](https://creativecommons.org/licenses/by/4.0/). Attribution: Google FLEURS contributors. `fleurs-it-short.json` records the source, the reference text, the licence, the conversion and the SHA-256 of each file. The WAV is 16 kHz mono PCM; the other three are ffmpeg re-encodings of it. Nothing was cut or edited.

`app/build.gradle.kts` adds this directory to the androidTest assets, so tests open `fleurs-it-short.wav` and the others by name. `scripts/fetch-smoke-audio.py` rebuilds the files from the pinned revision (it needs ffmpeg). ffmpeg output can change between versions, so review the diff before you commit a rebuild.

Two other clips stay out of the repository: the YouTube excerpts made by `tools/fetch-youtube-audio.sh` (written to `app/src/androidTest/assets/`, which is ignored) and the private meeting clips for `VoiceprintCalibrationTest` (put them in `tools/private-fixtures/meetings/`, which is ignored and packaged when present).

## filesdir/

Each directory is the content of one `files/` directory, the app's `context.filesDir`.

### Conventions

- **Device root.** Uris and paths inside the trees start with the emulator's files root, `/data/user/0/org.cassini.android/files`. In JSON it appears escaped, as Android's org.json writes it: `file:\/\/\/data\/user\/0\/...`.
- **`.mtimes`.** Git does not keep modification times, but a checkpoint's `signature` is `<length>:<mtime>` of its source file. Every tree has a `.mtimes` manifest with one line per file: `<relative path><TAB><mtime in milliseconds>`, sorted by path.
- **Model stand-ins.** Model files (`parakeet-v3/`, `parakeet-v3-fp32/`, `vad/`, `speaker-models/`) are zero-length files with the real names. The `verified` and `verified-voiceprint` markers keep their real content. So a model directory looks installed by name, but `ready()` is false because the lengths do not match.
- **Synthetic voiceprints.** In `voices.json` and `voiceprints/*.json`, every embedding is a random unit vector (fixed seed). A person's `mean` and the matching note print share one vector. Every other field is as the app wrote it.
- **Materialising.** To use a tree, copy it into a scratch files root and run the steps that `FixtureTree.materialise` performs (`app/src/test/java/org/cassini/android/FixtureTree.kt`; it moves to the `:data` module's tests in P2):
  1. Replace the device root, in both the plain and the escaped form, in `library.json`, `library.json.new`, `session.json` and each `checkpoint.json`.
  2. Rename each `processing-checkpoints/<key>` to the SHA-256 of its rewritten `source` string. This is the key that `ProcessingCheckpoint` derives from the stored uri string.
  3. Set each file's modification time from `.mtimes`.

  Every other byte is copied unchanged. The scratch root must contain only `[A-Za-z0-9/._-]`, so that `Uri.fromFile` produces the same string.

### The trees

| Tree | What it holds |
| --- | --- |
| `v0.1.0-basic` | Three notes in `library.json`, with `library-migrated`. (1) `Recording.m4a`: a 0.0.x AAC capture, `documents/<uuid>.m4a`, with its transcript inline (speaker `spk_1`, language `it`). (2) `fleurs-it-short.opus`: an imported clip, transcribed to the end. Its speaker was then named "Ada Example" and remembered, so the document was relabelled, `voices.json` holds the person and `voiceprints/mtg_*.json` holds the confirmed match. The imported `<sha256>.input` stays in `documents/` without a note, as 0.1.0 leaves it. (3) `fleurs-it-padded.m4a`: a 60-second import (the clip, then silence), stopped right after its first 28-second window. It is paused, with an inline draft transcript. Its `processing-checkpoints/<key>/` holds `checkpoint.json`, `names.json` (draft name "Ada Example"), `audio.opus.part` and `clips.pcm`. `session.json` points at note 2. `latest.words.json` and `latest.processing.json` come from note 2's transcription. The model directories hold stand-ins. |
| `v0.1.0-corrupt-library` | `v0.1.0-basic` with `library.json` cut to its first half, so it does not parse, and no `.bak`. Expected result: the file stays untouched, the failure is journaled and a rescan rebuilds the rows. |
| `v0.1.0-corrupt-library-new` | `v0.1.0-basic` with its intact `library.json`, plus `library.json.new` holding the first half of it: a write that never finished. Expected result: the `.new` file is left alone. |
| `v0.1.0-pre-library` | `documents/` only: the sealed document of note 2 before and after the relabel (two versions of one meeting). No catalogue, session or marker. |
| `v0.1.0-rec-crash` | Note 1 alone in `library.json` and `session.json`, plus what an interrupted capture leaves in `documents/`: a `.rec.opus` cut 37 bytes into its last Ogg page; a complete orphan `.rec.m4a` with no note; an orphan `.rec.m4a` whose index box was turned into a `free` box and which was cut at 60 %, as a recorder stopped before writing its index leaves it; and a stale `<uuid>.part` import copy that is two days old. Repair truncates by design, so this tree is excluded from any "documents unchanged" check. |
| `v0.0.6-beta` | The 0.0.6 shapes: a WAV capture `documents/<uuid>.wav` with an inline FP32 transcript, and an opened import `documents/<sha256>.input` without one. `library.json` uses the 0.0.6 note fields (no `processingPaused`). `session.json` has `fp32` and `modelChoice`. There are stand-ins for `parakeet-v3-fp32/` and `parakeet-v3/`, and the retired pyannote and ERes2Net files with their two-hash `verified` marker in `speaker-models/`. No voices and no checkpoints. |
| `v0.0.4-style` | An AAC capture `documents/<uuid>.m4a` with its transcript inline in `session.json` (0.0.4 shape), no `library.json`, and `parakeet-v3-fp32/` stand-ins. |
| `empty` | A fresh install: nothing but `.keep`. |

`app/src/test/java/org/cassini/android/CheckpointGoldenTest.kt` reads the `v0.1.0-basic` checkpoint through the 0.1.0 `ProcessingCheckpoint` JSON shape and checks that every `.mtimes` manifest lists exactly the files of its tree.

### How the trees were produced

On 2026-10-09, `app/src/androidTest/java/org/cassini/android/FixtureCaptureTest.kt` ran on emulator-5554 (API 34, x86_64) with the speech, speaker and voiceprint models installed and no saved people:

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r -e class org.cassini.android.FixtureCaptureTest -e captureFixtures true \
    org.cassini.android.test/androidx.test.runner.AndroidJUnitRunner
for tree in v0.0.4-style v0.0.6-beta v0.1.0-basic v0.1.0-corrupt-library v0.1.0-corrupt-library-new \
        v0.1.0-pre-library v0.1.0-rec-crash; do
    scripts/pull-filesdir-fixture.sh --source cache/fixture-capture/$tree fixtures/filesdir/$tree
done
adb shell run-as org.cassini.android rm -r cache/fixture-capture
```

Never use Gradle's `connected*` tasks for this: they can uninstall the app and erase the downloaded models. The test leaves the app's own data as it found it. `PreserveLibraryRule` restores the catalogue, documents, checkpoints and voices, and the test also restores `latest.*.json` and the finalization estimate.

What ran, tree by tree:

- **0.1.0 trees.** The real 0.1.0 code drove the screens: import by `ACTION_VIEW`, Transcribe, Stop, the naming dialog. `LibraryStore` wrote note 1. The test copied the results out of `files/`, then synthesised the voiceprints and made the stand-ins. The corrupt, pre-library and rec-crash trees are derived from `v0.1.0-basic` in the same run. The `.rec.opus` comes from the platform Opus encoder and `OggOpus.LiveMuxer`, with the same settings as `OpusRecording` but fed with the FLEURS clip instead of the microphone, so no room audio can reach the repository. The AAC captures are `MediaCodec` output in MP4 at 32 kb/s.
- **0.0.x trees.** These are written by `LegacySession` in the capture test: verbatim copies of `SessionStore.save` and `LibraryNote.json` from v0.0.4-beta and v0.0.6-beta (the two are identical), run through Android's org.json. The word lists are the ones that note 2 recognised.

`scripts/pull-filesdir-fixture.sh` can also copy a whole `files/` directory (`--sanitise` replaces model blobs with stand-ins and drops `voices.json` and `voiceprints/`). Into `fixtures/filesdir/` it copies only from `cache/fixture-capture/` or with `--sanitise`, and `.gitignore` keeps every tree that it does not name ignored. To add a tree, review it and then add its name to `.gitignore`. Do not commit a tree pulled from a phone that has real recordings.

### Known differences from real installs

- **Language tag.** The 0.1.0 trees come from this repository's code at 0.1.0 plus two later fixes (`ed73e73`, `0ebea43`), not from the `v0.1.0` tag. So the transcripts in notes 2 and 3, the checkpoint words and `latest.*.json` have no `"language":"it"`, while the tag wrote one. Note 1 and the 0.0.x trees do carry `"language":"it"`, as the old versions wrote it. `CheckpointGoldenTest.theReleasedTagsLanguageMemberReads` puts the member back in each of those places, as the tag wrote it, and reads the tree again, so the shape released installs hold is covered too.
- **No 0.0.x sealed document.** The 0.0.x trees have no sealed document: 0.0.6's document writer differs from 0.1.0's. Running the old APKs on the shared emulator would have needed an uninstall, which would erase its models.
- **Not real versions.** The 0.0.x trees are written by copies of the old writers, not by the old APKs.
- **Synthetic crash files.** The crash files are synthesised, as described above, not left by a killed recording.
- **No preferences.** SharedPreferences (`shared_prefs/`: the interface language and the finalization estimate) are not part of these trees.
