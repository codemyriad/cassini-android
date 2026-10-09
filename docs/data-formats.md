# Data formats

This file describes the data that Cassini for Android 0.2 keeps and writes. It has two parts:

- The internal data: the files in the private storage of the app, the Room catalogue and the settings. These formats are not a contract. A later version can change them.
- The output: the portable `.opus` document and the exports. The portable document is a contract with other Cassini software. The exports are copies for people.

Only the sealed `.opus` document is a complete portable Cassini artifact. It holds the audio, the timed words, the speakers, the provenance and the integrity data. TXT, SRT and VTT exports and the `*.words.json` files of the device tests are word-body derivatives. They hold the words without audio and without integrity data, and Cassini cannot read them back.

0.2 starts with a new storage layout. It does not read the app data of 0.1.x. Exported `.opus` documents of 0.1.x open as before.

[`architecture.md`](architecture.md), sections 3 and 4, gives the full rules. This file gives the contracts that a contributor must know.

## Storage layout

`filesDir` is the private files directory of the app. The app excludes it from backup and from device transfer (`data_extraction_rules.xml`, `fullBackupContent=false`).

```
cassini.db, cassini.db-wal, cassini.db-shm   Room catalogue
settings.preferences_pb                      DataStore settings
notes/<noteId>/                              one directory for each note; <noteId> is a UUID v4
    capture.opus | capture.m4a               microphone capture
    source.<ext>                             imported audio that is not a Cassini document
    document.opus                            the sealed Cassini document; when it exists, it is the audio of the note
    document.opus.tmp, source.tmp            temporary writes
    work/                                    transcription state, only while a job is not finished
        checkpoint.json (+.bak)              the checkpoint, version 1
        names.json (+.bak)                   the draft speaker names
        audio.opus.part                      libopus output for a source that is not Opus
        clips.pcm                            voice clips, raw 16 kHz f32le audio
        sealed.opus                          the finished document
        sealed                               the marker: sealed.opus is complete and correct
voices/                                      all voiceprints and voice matches, and nothing else
    people.json                              saved people (VoiceCodec version 1)
    generation                               an integer; "Forget everyone" increases it
    notes/<noteId>.json                      voiceprints and matches of a sealed note (NoteVoiceCodec version 2)
    pending/<noteId>.json                    voiceprints and matches of a sealed job before adopt
parakeet-v3/  vad/  speaker-models/          the models, with the names and markers that ModelCatalog pins
```

`cacheDir/exports/` holds temporary export files. The app deletes them after each write and at each start.

These rules apply to a note directory:

- A note has one current audio file. The column `notes.audio_file` names it.
- An import keeps an extension of 1 to 5 characters `[a-z0-9]`. If the extension is not known, the file name is `source.bin`.
- For a source that is not Opus, the document holds a 24 kb/s libopus encode. After adopt, the app deletes its copy of the original file.
- An edit outside a job (rename of a speaker, title, merge) writes `document.opus.tmp`, syncs it to the disk and renames it over `document.opus`.
- The lock of the note in `NoteLocks` serializes all writes to one note.

## What is authoritative where

| Data | Authoritative source | Copy |
|---|---|---|
| Audio of a note | The file in `notes/<id>/` | `notes.audio_file`, `notes.duration_ms` |
| Transcript versions, speaker labels, meeting ID, provenance | The sealed `document.opus` | The tables `variants`, `speakers`, `note_text` |
| Title | Room `notes.title` | `meeting.title` and the `TITLE` tag of the document |
| Trash state, playback position, selected version, preview | Room `notes` | None |
| Transcription queue | Room `jobs` | None |
| Unfinished transcription | `notes/<id>/work/` | `jobs.done_ms` |
| Saved people | `voices/people.json` | None |
| Voiceprints and matches of a note | `voices/notes/<id>.json`, `voices/pending/<id>.json` | `note_voice_links`, without embeddings |
| Consent, estimates, notices, queue hold | DataStore | None |

If `cassini.db` is lost, the app makes the catalogue again from the note directories. It gets the transcripts and the titles back from the documents. It loses these data:

- The trash state. Notes in the trash come back as live notes.
- The playback positions and the queue.
- A rename that the app did not write into the document yet.
- The titles of the notes that have no document. The title of such a note becomes "Recovered recording" and the date.
 A note with a valid checkpoint comes back paused.

## Room catalogue

The database is `org.cassini.data.db.CassiniDatabase`, version 1. The schemas are in `data/schemas/`. Room stores the enums as `TEXT` with stable names, never as ordinals.

| Table | Key | Content |
|---|---|---|
| `notes` | `id` | Title, `title_dirty`, dates, origin, audio file and kind, duration, SHA-256 of an import, document state and signature, meeting ID, selected version, position, preview, trust acknowledgement, trash time and the job state at trash time |
| `variants` | `note_id`, `variant_id` | One row for each transcript version: order, default, language (`""` if not identified), kind (`TRANSCRIBED` or `MERGED`), word count, speaker count, inference time, errors |
| `speakers` | `note_id`, `variant_id`, `speaker_id` | Label, placeholder flag and number, speaking time, word and turn counts, the sample window |
| `note_text` | `rowid` | The title and the words of the selected version, for search |
| `note_fts` | | An FTS4 table over `note_text` with `tokenizer = unicode61` and `remove_diacritics=1` |
| `jobs` | `note_id` | The queue: order, kind, state, pause reason, failure, progress, attempts |
| `note_voice_links` | `note_id`, `variant_id`, `speaker_id` | A copy of the matches in `voices/notes/<id>.json`: voice ID, state, score, seconds. No embeddings. |

The tables `variants`, `speakers`, `note_text`, `jobs` and `note_voice_links` cascade from `notes`. Each later schema version needs a written `Migration` and a `MigrationTestHelper` test. The code does not use `fallbackToDestructiveMigration`. If the database does not open, the app renames it `cassini.db.corrupt-<epoch>` and makes the catalogue again.

## Settings

DataStore keeps the settings in `settings.preferences_pb`:

| Key | Type | Meaning |
|---|---|---|
| `models.consentAt` | Long | The user agreed to the model download |
| `models.declinedAt` | Long | The user pushed "Not now — keep the audio" |
| `processing.finalizationMs` | Long | The estimate of the matching and saving time |
| `queue.hold` | String | `USER`, `SYSTEM` or no value |
| `recording.lastFormat` | String | The last capture format |
| `notices.recoveredAck` | Long | The latest recovered recording that the user saw |
| `notices.opusNativeAck` | Boolean | The user dismissed "This phone can't create Cassini files" |
| `notifications.asked` | Boolean | The app asked for `POST_NOTIFICATIONS` one time |
| `trash.lastPurgeAt` | Long | The last trash purge |

The app language is not in DataStore. AppCompat keeps it as a per-app locale. On Android 13 (API 33) and later, Android keeps it. On earlier versions, AppCompat keeps it in a file in `filesDir` (`autoStoreLocales`).

## The work directory

`notes/<id>/work/` holds the state of an unfinished transcription. The app makes it after the job row, and it deletes it at the end of adopt, at "Start over" and at purge. At start, the app deletes a work directory that has no job row and no marker.

### checkpoint.json

`checkpoint.json` version 1 has the keys of 0.1.0. They are `version`, `source`, `signature`, `model`, `speakers`, `recognition`, `encoder`, `diarizer`, `speakerPrefix`, `speakerFailure`, `clipSamples`, `voiceClips`, `positionSamples`, `doneMs`, `durationMs` and `elapsedMs`.

- `source` is the name of the audio file of the note, for example `capture.opus` or `document.opus`.
- `signature` is `length:mtime` of that file.
- `model` is the Parakeet revision, and `speakers` is the SHA-256 of the diarizer model.
- `diarizer.cache` holds at most 200000 floats.

`CheckpointState.validate` compares these values with the note row. If a value is different, the job fails with `CHECKPOINT_MISMATCH` ("Saved progress no longer matches"). A golden `checkpoint.json` from a 0.1.0 device run is in `core/src/test/resources/checkpoint/`.

### names.json

`names.json` holds the draft speaker state of a running or paused job. The members of 0.1.0 are `labels`, `merges`, `remembered`, `automatic` and `generation`. 0.2 adds two optional members:

```json
"live":     { "<speakerId>": { "voice": "<voiceId>", "score": 0.61, "state": "SUGGESTED" } },
"rejected": { "<speakerId>": ["<voiceId>", "..."] }
```

`live` is the latest live match for each speaker. `rejected` lists the voices that the user answered "Not X" to. The codec reads a missing member as empty. It writes a member only when it is not empty. The file is internal, and the app never exports it.

### sealed.opus and the marker

The job never writes `document.opus`. At the end, it seals the document into `work/sealed.opus`, reads it again to make sure that it is correct, and then writes the marker `work/sealed`.

Adopt installs the sealed document in this order:

1. It renames `work/sealed.opus` over `document.opus` and syncs the directory.
2. It updates the note row and the cache rows in one Room transaction.
3. It deletes the previous `capture.*` or `source.*` file.
4. It deletes `work/`.

If the process stops before the marker exists, the old audio stays as it was. If the marker exists, the next start finishes adopt. If `sealed.opus` does not read correctly, the next start deletes the marker and the file.

## Voices

`voices/` holds all voiceprints and voice matches. A voiceprint is biometric data. It never goes into a document, an export or a backup.

- `people.json` holds the saved people: their names, embeddings and counts.
- `generation` holds an integer. "Forget everyone" first increases it, then deletes all other files under `voices/`.
- `notes/<id>.json` holds the voiceprints and matches of a sealed note. Its keys are `<variantId>/<speakerId>`.
- `pending/<id>.json` holds the final voiceprints and matches of a sealed job before adopt. It has the same codec and the generation of the time when the job sealed.

At adopt, the app compares the generation in the pending file with the current value. If they are equal, it merges the file into `notes/<id>.json` and drops the matches to voices that no longer exist. If they are different, it deletes the pending file and does not merge it. Thus a job that ends after "Forget" or "Forget everyone" cannot bring a voice back.

A voice file can have a `version` that is newer than the codec. The app then reads the file but does not change it. Only a file that is not valid JSON gets the name `.corrupt-<epoch>`.

## The portable document

The specification of the format is `SPEC.md` and the `spec/` directory in the `third_party/cassini-format` submodule. The submodule is pinned to the revision that this app conforms to. The [cassini-format repository](https://github.com/codemyriad/cassini-format) and a sibling `../cassini-format` checkout can hold a newer revision.

A Cassini document is an Ogg Opus file at 48 kHz. Any audio player plays it. The OpusTags header holds two layers of metadata:

- Plain tags, for example `TITLE`, `CASSINI_SPEAKER_COUNT` and `CASSINI_MEETING_ID`.
- A JSON manifest (`application/vnd.cassini.portable-meeting+json`) and the transcript bodies (`application/vnd.cassini.transcript-words+json`, format `cassini.words.v1`). They are compressed and split across numbered tags.

Each transcription adds a transcript version (a variant). The document keeps the earlier versions. "Save a copy" copies the document byte for byte, together with the versions, extensions and attachments that Android does not use.

Parakeet does not identify the language, so the Android producer writes an empty `language` (`""`). The Android producer also writes these extension keys in the provenance of a version: `x-inferenceMs`, `x-segmentation`, `x-resumedFromMs`, `x-speakerDiarization`, `x-incrementalDiarization`, `x-speakerMerges`, `x-speakerAssignmentEditedBy` (the value `"user"`, written together with `x-speakerMerges`), `x-speakerIdentification`, `x-derivedFromTranscript` and `x-sourceAttribution`.

The format code keeps four known differences from the specification, because the pinned vectors and gocassini agree with the current behavior:

- A warning makes a document unverified.
- The transcript bodies are JSON with indentation.
- The document has no `DATE` tag.
- The signature of a source that is not a file is `null:null`.

## Exports

The Note actions sheet has three export rows. Each export uses the selected version and the display labels. A placeholder speaker gets the localized "Speaker N". A text file is UTF-8 without a BOM, with `\n` line ends and one `\n` at the end.

| Export | Extension and type | Content |
|---|---|---|
| Cassini document | `.opus`, `audio/ogg` | The sealed document, byte for byte |
| Audio file | `.opus`, `.m4a` or the import extension | The audio of a note without a transcript, byte for byte |
| Transcript as text | `.txt`, `text/plain` | The title, a subtitle and the turns |
| Subtitles | `.srt` (`application/x-subrip`) or `.vtt` (`text/vtt`) | Timed cues |

The `:core` package `org.cassini.core.export` writes the text exports. [`design/features.md`](design/features.md), section 9, gives all the rules.

### TXT

```
Title of the note
8 Oct 2026, 15:10 · 23:50 · 3 speakers

[00:00] Anna
Words of the first turn.

[00:12] Luca
Words of the second turn.
```

A turn is a run of words of one speaker. The clock is `mm:ss` for a note of less than 1 hour, else `h:mm:ss`. If the version has only one speaker, the turn line has only the clock. If the version has no words, the file has the title, the subtitle and a blank line. Then a line tells that there are no words.

### SRT and VTT

The app makes the cues from the words. It starts a new cue at these points:

- The speaker changes.
- The gap after the previous word is 1500 ms or more.
- The cue becomes longer than 6000 ms.
- The cue text becomes longer than 84 characters.
- The previous word ends a sentence and the cue is 1500 ms or longer.

A cue lasts at least 1000 ms, but not into the next cue of the same speaker. A line has at most 42 characters, and a cue has at most two lines.

- SRT: the cues are numbered from 1. With two or more speakers, the first cue and each cue after a speaker change start with `Label: `. SRT has no markup.
- VTT: the file starts with `WEBVTT`. With two or more speakers, each cue is `<v Label>text</v>`. The writer escapes `&`, `<` and `>`.

### File names

`ExportNames.file` makes the file name from the title. It replaces `/ \ : * ? " < > |` and control characters with `_`, and it joins runs of white space. Then it removes dots and spaces at the ends and cuts the name at 100 UTF-16 units. If the result is empty, the name is "Cassini note".

## Differences from gocassini

| Subject | gocassini | Android |
|---|---|---|
| Speakers | The names come from the call signaling. There is no voice analysis. | Sortformer finds the speakers in the audio. CAM++ voiceprints on the phone give the names. |
| Speaker IDs | Made from the name | `diar_<uuid>_N` |
| Rename of a speaker | No annotation operation | The app seals the document again with the new `label` |
| VTT cues | `WriteCaptionsVTT` writes one cue for each segment, as `<Label> text` | One cue for a short run of words of one speaker, as `<v Label>text</v>` |
| VTT timing | Segment times | Word times, with the break rules of this file |
| Text export | The desktop writes no TXT file. The web viewer gives Markdown: `# Title`, then `**Label** [clock]` and the paragraph for each row. | Plain text with `[clock] Label` lines (see [TXT](#txt)) |
| SRT | No SRT writer | SRT with `Label: ` at a speaker change |

Both programs read and write the same portable document. A word-body export of either program cannot replace the document.
