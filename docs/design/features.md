# Cassini Android 0.2: features and behaviour

Status: binding contract for the `:data`, `:engine`, `:app` and `:ui` implementers. Storage, services and threading are in `docs/architecture.md`; visuals, components and screen contracts are in `docs/design/ui.md`. This document says what each screen does, where each value comes from, and how to check it. Where the copy differs from a design board, the difference is deliberate and listed in ui.md section 11.

Tag legend: **[NEW]** not in 0.1.0; **[CHANGED]** existed with different behaviour or UI; **[KEPT]** 0.1.0 behaviour kept.

Each section ends with **Acceptance** criteria. "JVM" means a unit test, "Robo" a Robolectric test, "Shot" a Roborazzi screenshot compared with the board, "Device" an instrumentation test or host script on emulator-5554.

## 0. Conventions

- **Durations (clock)** `mm:ss` (zero-padded minutes, `02:41`), `h:mm:ss` from 1 h (`1:12:05`). Spoken form "48 minutes 13 seconds".
- **Durations (prose)**: "3 min 12 s" (speaking time, processing time "in 4 min 12 s"); "22 min" and "N h M min" from 60 min, "under 1 min" below 60 s (confirmed speech); time-left forms in section 3.1. All with plurals where the language needs them.
- **Times of day**: `HH:mm` or the 12-hour form, following the system setting.
- **Day labels**: "Today", "Yesterday", then `EEEE d MMM` in the current year ("Monday 5 Oct"), else `d MMM yyyy`. Localized with the app locale.
- **Numbers**: locale grouping ("4,812" / "4.812").
- **Sizes**: `ModelCatalog.mebibytes`, labelled "MB" (as the boards and 0.1.0). A total shown next to its parts is the sum of the rounded parts.
- **Plurals** for every count: speakers, notes, people, days left, minutes, "N more in queue", "N recordings recovered", "N ahead of this note", "Delete N notes", "N waiting".
- **Placeholder speaker labels** ("Speaker N" / "Voce N") are detected with `DefaultLabels.isDefault`. They are displayed localized from `placeholder_number`; the stored label is not changed.
- **Speaker tint**: a placeholder speaker is always amber ("not named yet"). Named speakers cycle blue, teal, violet in order of their first turn in the shown variant; placeholder speakers do not take a slot (`SpeakerTint` in `:core`). The label text always shows.
- **Saved-voice avatars** cycle blue, teal, amber, violet by list position; the initial is drawn in the tone's ink colour.
- Every string is a resource in English and Italian; composite lines are format resources.

## 1. Library (board 00) [CHANGED]

### Layout and data

1. Header: logo, "Notes", Trash button (a11y "Trash"), Settings button (a11y "Settings").
2. Search field [CHANGED]: placeholder "Search titles and what was said", IME action Search, clear button when not empty.
3. Privacy line [NEW]: shield + "Transcribed on this phone. Audio never leaves it." Always shown.
4. Activity area, in this order:
   - **Recording card** [NEW] while `RecordingController.state` is `Active`: dark card "Recording · <title>" with the elapsed time, or "Recording paused · <title>"; caption "Tap to return"; tap → Recording.
   - **Transcription banner** [NEW] while a job is RUNNING or a standalone download runs (data: `ProcessingCoordinator.state`, `ModelInstallOwner`). Title "Transcribing <title>" and the percent; a 4 dp bar; line 2 "About 6–9 min left · 1 more in queue". Other line-2 forms: "Calculating time left…", "Finishing the previous note…" (`WaitingForPrevious`), "Downloading speech recognition — 46%" (title "Getting ready to transcribe <title>", or "Downloading models" for a standalone download), "Checking files — 80%". The queue suffix is omitted at 0. Tap → the running note (or Settings for a standalone download).
   - **Notices** from `NoticeCenter` (section 13): queue held, recording recovered, record now transcribe later, notifications off, no Opus encoder, import errors, "Importing <name>…".
5. List:
   - Day groups by local day of `created_at`, newest first; overline headers.
   - Row: title (up to 2 lines) and duration; optional preview (up to 2 lines); meta line with time, status chip and suffix.
   - Preview without a query (`PreviewText` in `:core`, stored in `notes.preview`): when the saved playback position is after the first word, about 160 characters of the selected variant starting at the word playing at that position, with a leading "…" and, when cut, a trailing "…" (board 00: "…quindi la consegna slitta a giovedì, but the price stays as agreed in the March order…"); otherwise the start of the transcript (`note_text.body`, first 160 characters cut at a word, trailing "…" when cut). Recomputed when playback pauses or the Note screen is left, and when the selected variant changes. No preview without a transcript.
   - Preview while searching: the FTS snippet with matches in weight 600 on the accent tint (not colour alone). A title-only match highlights the title and shows no snippet.
   - The row of the RUNNING note sits on `surface`; the others are transparent.
   - Tap → Note (with the query when searching). Long-press, and the TalkBack custom actions (Rename, Move to trash, Resume when paused) → the Note actions sheet.
6. Bottom bar: "Import audio" (system picker `ACTION_OPEN_DOCUMENT`, MIME `audio/*` and `application/ogg`) and the Record button. While recording, the Record button reads "Recording · 24:07" with the live dot and returns to Recording; only one capture exists at a time.
7. Snackbar "“<title>” moved to trash" with Undo (section 8), shown here after Move to trash from the Note screen or the actions sheet.

### Status chip (`NoteStatus.of` in `:core`; first match wins)

| Status | Condition | Chip (tone) | Suffix |
|---|---|---|---|
| RECORDING | `LIVE_CAPTURE` and it is the active capture | "Recording" (error tone, dot) | – |
| TRANSCRIBING | job RUNNING (includes downloading and preparing) | "Transcribing" (info) | – |
| QUEUED | job QUEUED | "Queued" (outline) | – |
| PAUSED | job PAUSED | "Paused at 12:40" (warn; `done_ms`) | "· Resume" text button (visual as the board; touch area extended to 48 dp without moving the layout, ui.md section 5; a11y "Resume transcribing <title>") |
| FAILED | job FAILED | "Couldn't transcribe" (error) | "· Try again" (same touch rule) |
| UNPLAYABLE | `audio_kind = UNPLAYABLE` | "Can't play" (error) | – |
| UNREADABLE | document `UNREADABLE` or `INVALID_METADATA` | "Can't read transcript" (error) | – |
| NEWER_FORMAT | `UNKNOWN_FORMAT` | "Newer format" (outline) | – |
| TRANSCRIBED | document `OK`, `UNVERIFIED` or `STALE_AUDIO` | "Transcribed" (ok) | "· 3 speakers" when 2 or more; "· No speech" at 0 words |
| AUDIO_ONLY | otherwise (PLAIN, NEEDS_CONSENT) | "Audio only" (outline) | – |

### Search [CHANGED: FTS4 with highlighting]

- `SearchQuery.fts(input)`: split on whitespace; drop the characters `" * ^ : ( ) -` from each term and drop terms that become empty; write each term as a quoted prefix phrase with the star inside the quotes (`"consegn*"`, the FTS3/4 syntax); join with spaces (implicit AND). Golden: `consegna giov` → `"consegna*" "giov*"`. `AND`, `OR`, `NOT` and `NEAR` typed by the user are quoted, so they are words.
- Case- and diacritic-insensitive (unicode61, `remove_diacritics=1`).
- Results are a flat list, newest first, with the date in the meta line. Debounced 150 ms; the query is kept in `SavedStateHandle`. Trashed notes are excluded.
- Snippet markers `char(2)`/`char(3)` are turned into ranges by `SearchQuery.highlights`; the markers never reach the UI.

### Empty and error states

- No notes: plain banner "No notes yet" — "Record something, or import an audio file." [Record] [Import].
- No results: plain banner "Nothing matches “<query>”" — "Try another word or title."
- Import errors: section 13.5; shown at the top until dismissed or the next import.

### Acceptance

- JVM: `NoteStatus` table, every row; `DayGroups` (today, yesterday, this year, other year, zone change at midnight); `SearchQuery.fts` (special characters, operators, empty, the golden above) and `highlights`; `PreviewText` (position 0, mid-transcript, end of transcript, cut at a word, ellipses).
- Robo: DAO search finds "giovedì" from "giovedi", "consegna" from "CONSEGNA" and from "consegn", trashed notes are excluded, snippet markers are parsed.
- Shot: `00-library` equals board 00 within the ui.md tolerances; variants: empty, no results, recording card, search with highlights.
- Device: import the FLEURS clip twice → one note (`Existing`); long-press opens the actions sheet; Record while recording returns to the Recording screen.

## 2. Recording (board 01)

The dark surface is used in both app themes.

- Header: chevron down (a11y "Minimise to library (recording continues)") → Library. Centre: "Recording" with the orange dot, or "Paused" with a hollow ring (the word carries the state; the ring is decorative). Format chip "Opus 48 kHz" / "M4A 48 kHz" from the capture.
- Title [KEPT, live save NEW]: label "Title", editable field; default "Voice note 8 Oct, 08:14" (Italian "Nota vocale 8 ott, 08:14"); max 200; blank at finish → the default.
- Timer: `mm:ss` / `h:mm:ss`; not a live region; content description "24 minutes 7 seconds".
- Waveform [CHANGED]: 56 bars for the last 7 s (125 ms each); the 7 newest bars in the fresh colour; frozen while paused; one image node "Microphone level, last 7 seconds"; no animation with reduced motion.
- Hint [NEW]: "Keeps recording with the screen off or in another app." followed by the smaller of two limits (`RecordingSpace`): space-limited "Space for about 31 h more." or, when the 2 h cap comes first, "Stops at 2 hours, in about 1 h 35 min." Rounding: whole hours from 2 h, else minutes rounded down to 5, minimum 5. (Board 01 shows the space form; the fixture uses it.)
- Controls: "Library" (leaves the recording running), Pause/Resume (a11y "Pause recording" / "Resume recording"), "Finish" (stop and save).
- Banners (section 13.2): paused by another app [Resume]; storage almost full; near the 2 h limit ("Stops automatically in 5 minutes. The recording so far is saved."); microphone access off [Open settings]; microphone stopped working [Open note]; couldn't start the microphone [Try again].
- Finish flow: `finish(title)` → row PLAIN. With consent: enqueue, open Note (Transcribing or Queued). Without consent and without `models.declinedAt`: open Note, then Model download on top. Otherwise: Note (Audio only).
- Back = minimise [CHANGED: 0.1.0 back finished the recording].
- Permissions [KEPT]: RECORD_AUDIO and, on API 33+, POST_NOTIFICATIONS on entry. Permanent denial → "Microphone access is off" with Open settings; the capture starts automatically when the user returns with the permission granted (`pendingStart` in `SavedStateHandle`, checked at ON_RESUME).

**Acceptance**: JVM `RecordingPolicyTest` (StorageLow at 15 min, stop at the floor, warning at 1:55, stop at 2:00), `RecordingSpace` rounding and the min of space and cap, `RecordingControllerTest` (finish never on main, title kept, silenced → paused and stays paused). Shot: `01-recording`; variants paused, permission off, storage low, near limit, cap-limited hint. Device `@NeedsMicrophone`: record 5 s, back, the Library shows the recording card, Finish from the notification → the note appears, PLAIN or QUEUED.

## 3. Note screen

One destination with modes. The mode follows the job and the document:

| Mode | When | Shows |
|---|---|---|
| Audio only | PLAIN note, no job; or NEEDS_CONSENT | plain banner "Plain audio — No transcript yet." [Transcribe] (no consent → Model download, else enqueue); player |
| Queued | job QUEUED | plain card "Queued" — "N ahead of this note." or "Starts after the current transcription." [Remove from queue]; for a document (Transcribe again) the card sits above the Read & listen content; player |
| Transcribing | job RUNNING | progress card and draft (3.1); no player |
| Paused | job PAUSED | warn banner (3.2) and the read-only draft; player |
| Failed | job FAILED | error banner (section 13.3) [Try again] (and Start over when a checkpoint is kept); for a document the Read & listen content follows; player |
| Read & listen | a document, no job | chips, trust banners, transcript, player (3.3) |
| Unplayable | `UNPLAYABLE` | red banner "This recording can't be played" — "Cassini closed before the recording was saved. You can still save the file." No player |

Header: back (a11y "Back to library"); title (one line). Read & listen: no subtitle (date and duration are in Note actions and Document info), then Search (a11y "Search in transcript") and More (a11y "Share and more"), as board 03. Every other mode: subtitle "Today, 10:02 · 48:13" and More (a11y "More actions"), as board 02. More → Note actions sheet.

While a job row exists for a document note (Queued, Transcribing, Paused, Failed with a checkpoint), the sealed transcript is read-only: speaker renaming, merging and Versions changes on the sealed document show "Wait until transcription finishes" (draft naming in Transcribing and Paused works as in 3.1). Rename of the note title always works (architecture section 4.1).

### 3.1 Transcribing (board 02) [CHANGED]

- Progress card (live region polite, updates at most 1 Hz, announced on stage change or every 5 %):
  - Headline (`ProgressHeadline`): STARTING "Starting…"; WAITING_FOR_PREVIOUS "Finishing the previous note…"; READING_AUDIO "Reading the audio"; PREPARING "Preparing"; FINDING_WORDS_AND_SPEAKERS "Finding words and speakers"; FINDING_WORDS "Finding words" (no diarizer installed); MATCHING_VOICES "Matching saved voices"; SAVING "Saving". Range "29:00–29:45" (hidden when 0–0).
  - Percent (capped at 99 until Done); 6 dp bar.
  - Stepper: Reading, Preparing, Words & voices, Matching, Saving; a11y "Step 3 of 5: Words and voices".
  - Stats: Time left ("6–9 min", "Under 1 min", "About 6 min", "1 h 10 min–1 h 30 min", "Calculating…"); Processed "29:52 / 48:13"; Battery at finish ("54% → about 41%", "Plugged in", "Measuring…", hidden without a reading); Words so far.
  - "Stop and keep progress" → PAUSED(USER).
  - Footnote "Keeps going if you leave the app. A notification shows progress and lets you stop." With notifications denied: "Notifications are off, so progress shows only here."
  - Low memory hint (amber text) when the preflight flagged it.
  - Download phase replaces the stats with the download banner (section 13.4).
- Draft transcript [KEPT, CHANGED visuals]: header "Draft transcript" and the dashed tag "Words may still change". Turns with speaker label and turn start time. Words that end after `tentativeFromMs` use the unsettled colour. Auto-follow the newest text; it pauses when the user scrolls up and resumes at the bottom. Words are not interactive. Tapping a speaker label opens the Name a speaker sheet in draft mode.
- Inline suggestion card [NEW]: for each speaker with a `live` SUGGESTED match, not rejected, whose label is a placeholder, at that speaker's most recent turn: header (speaker label and time; a 48 dp button that opens the Name sheet, like any speaker label), then "Is this **Luca**?" [Yes, Luca] [Not Luca].
  - Yes → `coordinator.draft(noteId).confirm(speakerId, voiceId)`: `labels`, `remembered`, `generation`, `live.state = CONFIRMED`; enrolment at adopt if the generation is unchanged.
  - Not → `reject(speakerId, voiceId)`: added to `rejected`, `live` and a matching `automatic` entry removed; the voice is not suggested again for that speaker.
  - AUTO matches apply the name directly with the "auto" badge.
- From step 6 (attribute) naming is closed: cards and renaming show "Saving — you can rename when it's done".

**Acceptance**: JVM `ProgressTrackerTest` (stepper monotonic, headline mapping including FINDING_WORDS, Calculating until 3 windows, Finishing, cap 99, battery Measuring → Projected after 120 s, PluggedIn, finalization included), `DraftActionsTest` (Yes/Not writes, no re-suggestion, enrol only with unchanged generation, no unenrol, a job `setLive` racing a UI `confirm` loses neither). Shot: `02-transcribing`; variants Queued, Paused with draft, Downloading, Checking, Failed, WaitingForPrevious. Device `@NeedsModels`: transcribe a 3-minute clip; the percent increases monotonically; Stop → Paused; Resume → Done with a verified document.

### 3.2 Paused

Warn banner "Paused at 12:40 of 34:12" — "Pick up where you left off. The draft so far is kept." [Resume] [Start over]. Start over asks "Start over? The draft so far is discarded. Your recording is kept." The draft below is read-only (`CheckpointRepository.loadDraft`), and naming stays open. A pause without a checkpoint (stopped during the download or the first window) reads "Paused before any words were found" — "Resume to start transcribing." [Resume]. Pause reasons share this banner; MEMORY uses the red "Not enough memory" banner; a held queue adds "Transcription paused · N waiting" [Continue].

### 3.3 Read & listen (board 03)

- Chips row:
  - Trust chip: OK "Verified" (teal, check icon); UNVERIFIED "Not verified" (amber); STALE_AUDIO "Audio changed" (amber); UNREADABLE/INVALID_METADATA "Can't read transcript" (red); UNKNOWN_FORMAT "Newer format" (red). Each has an icon. Tap toggles the matching trust banner (13.1).
  - Version chip, only with 2 or more readable variants: "Version 2 of 2 · speakers merged" (suffix "· speakers merged" for MERGED, "· transcribed again" for a TRANSCRIBED variant with ordinal > 0, none for ordinal 0) → Versions sheet.
  - "3 speakers" (hidden at 0).
- Banners: non-OK trust (UNVERIFIED until "Play anyway"); "All words shown as one voice" when the selected variant has words, `speakers_told_apart == false` and `speaker_count == 1`; the inline suggestion card at a placeholder speaker's first turn when its link is SUGGESTED (Yes → relabel + CONFIRMED + enrol; Not → REJECTED).
- Transcript: one item per turn. Speaker label (button; a11y "Giulia, named automatically, at 11 minutes 41 seconds"; onClick label "Name this speaker"); "auto" badge when the link is AUTO and the label equals the voice's name; paragraph in ink for named speakers, `text3` for placeholders. Each word is a link (tap → seek to `startMs` and play; onClick label "Play from here"). The active word has an inverted background; only the affected turn is rebuilt. Follow mode keeps the active word in view; scrolling pauses it and a "Follow playback" chip resumes it.
- Player [KEPT, restyled]: slider (a11y "Position", stepped by 10 s), times, −10 s, play/pause, +10 s. Position persisted every 5 s, and on pause and on leaving (which refreshes the Library preview).
- In-note search [KEPT]: the search button turns the header into a field with "3 of 12" and up/down; matches on the amber tint, the current one in weight 600; diacritic-insensitive (NFD); "No matches"; back closes search. Opening from a Library search pre-fills it and jumps to the first match.
- Renaming is disabled for trust states other than OK ("Fix the transcript first: Transcribe again").
- `refreshIfStale` runs when the screen opens.

**Acceptance**: Shot `03-read-and-listen` (active word "quindi", auto badge on Paolo Ferri, no subtitle); variants Not verified, Audio changed, Unreadable, Newer format, one voice, search mode, Queued over a document. Robo/Compose: a word tap seeks; a speaker tap opens the sheet; back closes search before leaving. Device: play, seek by word tap, the position survives leaving and returning.

### 3.4 Versions sheet [CHANGED: replaces "Choose transcript"]

Title "Versions". One row per variant in manifest order: "Version N", detail "Transcribed 8 Oct, 15:40 · 4,812 words" or "Speakers merged · from version 1 · 8 Oct, 16:02", and the language when not empty. A radio marks the selection; selecting calls `selectVariant` and closes. Unreadable variants are disabled ("Can't be read").

## 4. Name a speaker sheet (board 04) [CHANGED: one sheet replaces 3-4 chained dialogs]

Modes: SEALED (document OK, no job row) and DRAFT (job RUNNING or PAUSED, naming open). Driven by the pure reducer `SpeakerNaming` (events in; sheet state and effects out).

1. Header: avatar in the speaker tint (placeholder number, or the first grapheme of the label); title = display label; "Speaks for 3 min 12 s in this note" (`speaking_ms`; drafts compute it from draft words with merges applied); Play sample (a11y "Play a sample of this voice") → `playRange(sample_start_ms, sample_end_ms)`; disabled without a sample window.
2. Name field: max 80; capitalise words; prefilled with the label unless it is a placeholder; IME Done = Save.
3. "Saved voices, closest first": current-model voices ranked by `cosine(print, voice.mean)` descending, then name. The top row gets "Likely" only if its score ≥ `Thresholds.suggest` (0.50). Stale voices last, by name, without a score. Without a print the header is "Saved voices", sorted by name. Rows are 52 dp; tapping one fills the field and binds `chosenVoiceId`; editing the name away from that voice's name clears the binding. Hidden when there are no saved voices. At most 3 rows, with a "Show all" row (52 dp, `body16` accentInk) when more exist.
4. Same-person prompt (amber card) when Remember is on, no voice is bound, a print exists, and the trimmed typed name, compared case-insensitively after NFD accent stripping, equals a saved voice's full name or its first word (`NameMatch`): "“Anna Ricci” is already a saved voice. Is this the same person?" [Same person] [Someone else]. With several matches, the highest score (ties alphabetical). Same person binds the voice and fills its full name. Someone else keeps the typed name; Save then creates a new voice. While unanswered, Save stays visually enabled (as board 04); pressing it does not save: it moves accessibility focus to the question, announces "Answer the question above first", and briefly pulses the card's border (no animation with reduced motion).
5. Rename-saved-voice prompt: when the match is CONFIRMED to voice V and the typed name differs from V's name: "This voice is saved as “Anna Ricci”. Also rename the saved voice?" [Rename saved voice] [Someone else]. Other documents keep their labels. Save while it is unanswered behaves as in item 4.
6. "Not <voice name>?" text button when the link is AUTO or CONFIRMED: CONFIRMED is unenrolled first; the match becomes REJECTED; a label equal to that name returns to the placeholder.
7. Remember checkbox (default on when a print exists): "Remember this voice on this phone" — "Future notes will name <name> automatically. The voiceprint never leaves the phone." Disabled with "Too little speech to remember this voice." (< 4 s) or "Recognising saved voices isn't installed yet."
8. Footer: "Merge into…" → list of the other speakers (label, tint, speaking time) → confirm "Merge <A> into <B>? Everything <A> said will be shown as <B>." plus "This adds version N+1; version N stays available." (sealed) or "This changes the draft." (draft). Placeholder speakers are valid targets. Sealed merges create a MERGED variant; prints are carried as 0.1.0 `applySpeakerMerge` (source print dropped, CONFIRMED copies become AUTO). Then "Cancel" and "Save name". Save is disabled (38 % alpha) only when there is nothing to save: the name is blank, or the label, binding and remember state are all unchanged and the match is already CONFIRMED.
   - Sealed save: relabel when the label changed, then `rememberVoice` as 0.1.0 (unenrol a previous CONFIRMED voice on change; clear a stale AUTO; enrol into the bound or a new voice; CONFIRMED with the score).
   - Draft save: `DraftNames.rename` / `remember` with the generation.

**Acceptance**: JVM `SpeakerNamingTest` covers every prompt path, the Save rule (enabled-but-blocked while a question is open, disabled when nothing changed), "Not X", merge confirmation, draft vs sealed effects; `NameMatch` (accents, case, first word, ties). Shot: `04-name-a-speaker` (Speaker 3, "Anna", conflict shown, Remember on, Save enabled); variants rename-saved-voice, no saved voices, no print, Show all. Device `@NeedsModels`: name a draft speaker via "Yes, Luca"; after Done the document carries the label and Saved voices lists the person with 1 note.

## 5. Model download (board 05) [CHANGED: full screen; per-model sizes NEW]

- Close (a11y "Close"). Logo; "Transcribe on this phone"; "Cassini needs a one-time download of about **730 MB**. After that, recognising words and telling voices apart happen on this phone, even offline. Your audio stays here." The total is the sum of the missing parts' rounded sizes.
- List of missing parts with sizes: Speech recognition 640 MB; Telling speakers apart 62 MB; Recognising saved voices 28 MB.
- Footnote exactly as the board: "Needs about 860 MB free while installing. Each file is checked before it is used." (`ModelPlan.requiredFreeBytes`). Below the footnote, one text-button row "Where the models come from · Privacy" → Licenses (an addition to the board, listed in ui.md section 11).
- Primary: with a note "Download and transcribe" (record consent, enqueue, back to the note); without "Download" (record consent, standalone download through `ModelInstallOwner`, back). Before starting, check free space; if short, show "Not enough storage — Free about 860 MB and try again." [Retry] and do not record consent.
- "Not now — keep the audio" → `recordDeclined()`, back.
- POST_NOTIFICATIONS is requested here once before the first download (API 33+).
- Nothing missing: entry points (Transcribe, Settings rows, banners) do not open this screen. A stale entry pops itself; with a note it enqueues the note first.
- Board 10's "Keep Cassini open." (in the download banner) is not used: downloads run in the foreground service and survive leaving the app.

**Acceptance**: JVM `ModelPlan` (fresh install ≈ 861 MiB needed, 730 MB shown; partial installs). Coordinator test: no `ArtifactFetcher` call before consent. Shot: `05-model-download` and the no-space variant. Device (P12): the walk opens it on an empty scratch model root.

## 6. Note actions and export (board 06)

- Header: title + "Yesterday, 15:10 · 23:50 · 3 speakers" (speaker part omitted for plain notes).
- "Save a copy":
  - **Cassini document** `.opus` [KEPT]: "Audio with the transcript inside. Plays anywhere." For DOCUMENT notes; SAF `CreateDocument("audio/ogg")`, name `ExportNames.file(title, "opus")`, byte-for-byte copy. Plain notes show **Audio file** `.opus`/`.m4a`/`.<ext>` — "The recording as it is. No transcript yet."
  - **Transcript as text** `.txt` [NEW] (`text/plain`).
  - **Subtitles** `.srt · .vtt` [NEW] → chooser: "SubRip (.srt) — works in most video players", "WebVTT (.vtt) — for web players" (`application/x-subrip`, `text/vtt`).
  - Text and subtitles are disabled for non-documents ("Available after transcription") and for UNREADABLE/UNKNOWN_FORMAT.
  - Exports use the selected variant and display labels; writes run on IO with progress in the row; snackbar "Saved “<file>”" or "Couldn't save the file".
- **Rename** [NEW]: dialog (max 200, not blank) → `NoteRepository.rename`. The Library and every screen show the new title at once. A document's own title is rewritten when it is `OK` and no transcription is pending; otherwise after the transcription finishes, or not at all for a damaged document (its copy then keeps the old title inside).
- **Transcribe again** [KEPT]: "Adds version 3. Speaker names carry over." (second sentence only when the voice model is ready). Plain notes: **Transcribe**. RUNNING: **Stop and keep progress**. QUEUED: **Remove from queue**. PAUSED: **Resume** and **Start over**. Non-OK documents (STALE_AUDIO, UNREADABLE, INVALID_METADATA) confirm "Earlier versions can't be kept because the audio changed." and seal a fresh document.
- **Document info** [KEPT]: trust line; title; recorded date and time; duration; "N versions · M speakers"; for the selected version: language, "Transcribed on this phone in 4 min 12 s · 11× realtime", "Parakeet TDT 0.6B v3, INT8", "Speakers told apart on this phone" or "All words shown as one voice", "2 names from saved voices", "Resumed from 12:40"; file size; meeting id (selectable mono).
- **Move to trash** [NEW] (destructive colour): "Kept for 30 days, then deleted." → section 8 rules → back to the Library → Undo snackbar there. Hidden while recording. A running job asks "Stop transcription and move to trash?".

**Acceptance**: JVM export goldens (section 9). Device `@NeedsModels`: Save Cassini copy is byte-identical (`run-as sha256sum`); TXT/SRT/VTT of the FLEURS clip equal goldens recorded once on the emulator. Shot: `06-note-actions-and-export`; plain-note variant; subtitle chooser.

## 7. (reserved)

## 8. Trash (board 09) [NEW]

- Header: back (a11y "Back to library" or "Back to settings", after the screen it returns to), "Trash", "Empty" (destructive; hidden when empty).
- Intro: "Notes here are deleted for good after 30 days, with their audio, transcripts and voiceprints from those notes."
- Cards, oldest deletion first (the soonest to expire on top, as board 09): title + duration; "Deleted 2 Oct · 23 days left" ("Deleted today", "Deleted yesterday"; plurals); [Restore] and [Delete now].
- Dialogs: "Delete “Test recording” for good? Its audio, transcripts and voiceprints from this note are deleted. This can't be undone." [Delete] [Cancel]; "Delete N notes for good?" with the same body.
- Undo snackbar wherever a note was trashed (the Library, in practice); duration `AccessibilityManager.getRecommendedTimeoutMillis(10_000, FLAG_CONTENT_TEXT | FLAG_CONTENT_CONTROLS)`; Undo = restore. Restore brings the job state back (`notes.trashed_job`): a queued note is queued again at the back; a paused note stays paused. Board 09's "moved to trash" snackbar over the Trash screen is a fixture-only state (ui.md section 11); the app never lands on Trash after trashing.
- Empty: "Trash is empty" — "Notes you delete stay here for 30 days."
- Purge rules: architecture section 4.10.

**Acceptance**: JVM `TrashPolicy` ("Deleted 2 Oct · 23 days left" on 9 Oct; "today · 30 days left"; DST changes). Robo `PurgeOrderTest` (crash after file deletion; the next run finishes), restore of a queued note re-queues it. Shot `09-trash`; empty variant. Device: trash → Undo → trash → Delete now removes `notes/<id>` and `voices/notes/<id>.json`.

## 9. Export writers (`:core` `org.cassini.core.export`) [NEW]

Input `ExportTranscript(title, durationMs, words, labels)`: the selected variant's words in canonical order (never re-sorted) and the display label per speaker id (named labels verbatim, placeholders as the localized "Speaker N"; a missing label uses the id). Output UTF-8 without BOM, `\n` line endings, exactly one trailing `\n`. Words are joined with one space.

- **Turns** (`TranscriptTurns.of`): maximal runs of consecutive words with the same speaker; start = first word start, end = last word end. Also used by the UI and `SpeakerStats`.
- **Clock** (`ExportClock`): `mm:ss` below 1 h duration, else `h:mm:ss`; seconds floored. SRT `HH:MM:SS,mmm`; VTT `HH:MM:SS.mmm`.
- **TXT** (`TextExport.write(t, subtitle, emptyText)`): title line, subtitle line ("8 Oct 2026, 15:10 · 23:50 · 3 speakers"), blank line, then per turn `[<clock>] <label>` and the paragraph, turns separated by one blank line. With one distinct speaker the label is omitted. With 0 words: title, subtitle, blank line, `emptyText`.
- **Cues** (`SubtitleCues.build`): iterate words in canonical order; start a new cue before word `w` when (1) the speaker changes, (2) `w.startMs − prev.endMs ≥ 1500`, (3) `w.endMs − cue.startMs > 6000`, (4) cue text length + 1 + `w.text.length` > 84 (the first cue of a speaker includes the SRT label prefix), or (5) `prev.text` ends with `. ? ! … 。 ？ ！` and the cue already lasts ≥ 1500 ms. End = max(end, start + 1000), never beyond the next cue of the same speaker; same-speaker cues never overlap; different speakers may overlap. Cues are stably sorted by start; text stays in canonical order. Line break when > 42 chars at the space that best balances two lines of ≤ 42; if none, at the last space ≤ 42 (never drop words, never 3 lines).
- **SRT**: indices from 1, blank line between cues; `"<label>: "` prefix only on a speaker change in output order (and the first cue) and only with 2 or more speakers; no markup.
- **VTT**: `WEBVTT` header; no cue ids, no STYLE or REGION; with 2 or more speakers every cue is `<v Label>…</v>`; escape `&`, `<`, `>` in text and labels; labels have control characters stripped and `-->` written as `--&gt;`.
- **File names** (`ExportNames.file`): replace `/ \ : * ? " < > |` and control characters with `_`, collapse whitespace, trim dots and spaces, cut at 100 UTF-16 units on a code-point boundary, fall back to "Cassini note", append the extension.

**Acceptance**: JVM golden strings per writer: Italian accents, one vs many speakers, empty transcript, overlapping speakers, each of the 5 break rules, the minimum-duration clamp, line balancing, escaping, ≥ 1 h clock, canonical order kept inside a cue while cues are sorted.

## 10. Saved voices (board 07) [CHANGED: was People]

- Header: back (a11y "Back to settings"), "Saved voices".
- Privacy note: "Voiceprints stay on this phone. They are never uploaded or written into shared files — only the names you choose are."
- Rows: avatar initial (tint by index, initial in the tone's ink colour), name, "14 notes · 22 min confirmed speech" ("under 1 min"; "N h M min" from 60 min; plurals), options (a11y "Options for Giulia") → Rename… (max 80) and Forget… ("Forget Giulia? Cassini stops recognising this voice. Names already in your notes stay."). Sorted by notes, then confirmed seconds, then name.
- Stale hint "Name them again in a note to keep recognising them" exactly when `voice.model != ModelCatalog.voiceprintModel.sha256 || voice.mean.size != 512`. Naming that speaker again with Remember on re-enrols under the same id.
- Footnote "To add someone, name a speaker in any note and keep “Remember this voice” on."
- "Forget everyone…" → "Forget everyone? Cassini stops recognising all N saved voices and deletes the voiceprints from every note. Names already in your notes stay." → `forgetEveryone()` (the generation bump invalidates pending draft remembers and pending prints of running jobs).
- Empty: "No saved voices yet" + footnote; "Forget everyone…" hidden.
- Error: newer or corrupt `people.json` → "Saved voices can't be read" — "They were made by a newer Cassini or are damaged. Nothing was changed." Edits disabled.

**Acceptance**: Robo `VoiceRepositoryTest` (forget scrubs matches in sealed and pending files and in drafts; forgetEveryone bumps generation, clears links and pending files; an adopt after forgetEveryone writes no prints; newer version read-only). Shot `07-saved-voices`; empty, stale and error variants.

## 11. Settings (board 08) and Licenses

- **App language**: segmented Phone / English / Italiano → `AppCompatDelegate.setApplicationLocales` (empty list = Phone); a11y radio group; footnote "Speech is recognised in any language, automatically."
- **On-device models**: "Speech recognition — INT8 · 640 MB", "Telling speakers apart — 62 MB", "Recognising saved voices — 28 MB" with status text: Ready (teal), "Downloading 46%" / "Checking files" (blue), "Downloads with next transcription" (consented and missing; amber), "Not downloaded" (no consent; row opens Model download), "Download failed — tries again next time" (red). Footnote "Transcription runs on this phone. Audio never leaves it." No delete action.
- **Privacy**: "Saved voices — 4 people" ("None yet") → Saved voices; "Trash — 2 notes" ("Empty") → Trash.
- **About**: "**Cassini <versionName>** — turns speech into searchable, timed text on your phone." "Free software under GPLv3. Source code and notices" → Licenses.
- Notifications-off banner when POST_NOTIFICATIONS is denied: "Notifications are off — Progress of long transcriptions shows only in the app." [Turn on] → app notification settings.
- **Licenses** (no board; ui.md section 8): Cassini (GPLv3), Third-party notices, native dependencies, Instrument Sans (OFL), IBM Plex Mono (OFL), model notices (OpenMDW for the diarizer), "Where the models come from" (the hosts and download domains of architecture section 5.6), Privacy (`PRIVACY.md`), and "Source code on GitHub" → `https://github.com/codemyriad/cassini-android`.

**Acceptance**: Shot `08-settings`; notifications-off and downloading variants. Device: switching to Italiano persists across a process kill (`scripts/device-language-check.sh`). Robo `LicenseAssetsTest` covers the notices and privacy copies; `:ui` `FontAssetsTest` covers the font licences.

## 12. Notifications

| Notification | Channel | Content | Actions |
|---|---|---|---|
| Recording (ongoing, id 1) | recording | "Recording" / "Recording paused"; text = title or the active warning; chronometer while recording, "Paused at 24:07" when paused | Pause/Resume, Finish; content → Recording |
| Processing (ongoing, id 2, ≤ 1 Hz) | processing | "Transcribing “<title>”" + "62% · 6–9 min left" or "Calculating time left…"; "Starting…", "Finishing the previous note…"; download "Downloading speech recognition — 46%" / "296 of 640 MB"; "Checking files — 80%"; "Matching voices…", "Saving…"; sub-text "1 more in queue" | Stop (= stop all and hold), Open; Cancel while downloading |
| Transcribed | results | "Transcribed: <title>" / "3 speakers · 48:13" | content → note |
| Couldn't transcribe | results | "Couldn't transcribe <title>" / failure text | content → note |
| Ready to transcribe | results | "<title> is waiting. Open Cassini to start." | content → note |
| Paused by Android | results | "Paused at 12:40 of 34:12. Open to resume." | content and Resume → note |
| Transcription stopped | results | "Cassini closed while transcribing <title>." | Resume (foreground service) |
| Recording stopped / Microphone stopped working | results | storage or microphone copy | → note |

Results notifications are posted only while the app is not visible. Text follows the app language. Public versions omit the title.

**Acceptance**: Robo at SDK 32: notification text follows the app locale. Device: `dumpsys activity services` shows `dataSync` while processing on API 34.

## 13. Banner catalogue (board 10)

Every banner has a tone, a title that states the condition (never colour alone), an optional body and up to two actions (the first ink-filled, the second outlined). Banners that appear after an event are polite live regions. Dismissible banners have a trailing 48 dp close button (20 dp Close glyph, a11y "Dismiss"), which the board does not show (ui.md section 11). `NoticeCenter` orders them failed, attention, info, fine/plain, newest first within a tone. The copy below is the board's unless a note says otherwise.

### 13.1 Document trust (Note screen; library chip per section 1)

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| TRUST_VERIFIED | ok | "Verified" — "Audio matches the transcript exactly. Everything works." | OK, shown when the chip is tapped | – |
| TRUST_NOT_VERIFIED | warn | "Not verified" — "The audio could not be checked, so word timing may be off." | UNVERIFIED and not acknowledged | Play anyway (`acknowledgeTrust` + play) |
| TRUST_AUDIO_CHANGED | warn | "Audio changed since transcription" — "Word timing may not match what you hear." | STALE_AUDIO | Transcribe again (fresh document) |
| TRUST_UNREADABLE | error | "Transcript can't be read" — "The file's Cassini data is damaged. The audio still plays." | UNREADABLE, INVALID_METADATA (the read error is in Document info) | Transcribe again |
| TRUST_NEWER_FORMAT | error | "Newer Cassini format" — "This version can't be read here yet. The audio still plays." | UNKNOWN_FORMAT | – |
| PLAIN_AUDIO | plain | "Plain audio" — "No transcript yet." | PLAIN note | Transcribe |
| UNPLAYABLE | error | "This recording can't be played" — "Cassini closed before the recording was saved. You can still save the file." (not on the board) | UNPLAYABLE | – |

### 13.2 Recording (Recording screen in light tones; Library for recovery)

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| REC_PAUSED_OTHER_APP | warn | "Paused — another app is using audio" — "Resume when your call ends. Nothing was lost." | `pause == OTHER_APP` | Resume |
| REC_STORAGE_LOW | warn | "Storage almost full — about 10 min left" — "Free some space to keep going. If it runs out, the recording saves automatically." | StorageLow | – |
| REC_NEAR_LIMIT | warn | "Stops automatically in 5 minutes" — "The recording so far is saved." (not on the board) | NearTimeLimit | – |
| REC_STOPPED_STORAGE | warn | "Stopped — storage almost full" — "Less than 200 MB free. The recording so far is saved." | `Ended(StoppedForSpace)` (also a notification in background) | Open note |
| REC_MIC_OFF | error | "Microphone access is off" — "Turn it on in system settings. Recording starts when you come back." | RECORD_AUDIO permanently denied | Open settings |
| REC_MIC_FAILED | error | "Microphone stopped working" — "Recording ended. The audio so far is in your library." | capture error | Open note |
| REC_COULD_NOT_START | error | "Couldn't start the microphone" — "Another app may be using it. Try again." (not on the board) | `Ended(CouldNotStart)` | Try again |
| RECOVERED | info | "1 recording recovered" (plural) — "Cassini closed unexpectedly. We saved what was captured (05:12)." | `recover` returned ids newer than `notices.recoveredAck` | Open (most recent); dismissible |

### 13.3 Transcription

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| TX_PAUSED | warn | "Paused at 12:40 of 34:12" — "Pick up where you left off. The draft so far is kept." | PAUSED (USER, SYSTEM, INTERRUPTED) with a checkpoint | Resume, Start over |
| TX_PAUSED_EARLY | warn | "Paused before any words were found" — "Resume to start transcribing." (not on the board) | PAUSED without a checkpoint | Resume |
| TX_QUEUE_HELD | warn | "Transcription paused · N waiting" — "Continue when you're ready." (not on the board) | `queue.hold` set and QUEUED rows exist | Continue |
| TX_CALCULATING | info | "Calculating time left…" — "An estimate appears after the first few minutes of audio." | catalogue only (StatesBoardTest); in the app this state is the "Calculating…" stat cell | – |
| TX_BATTERY | info | "Measuring battery use…" — "Plugged in — no battery estimate needed." | catalogue only (StatesBoardTest); in the app these are the "Measuring…" and "Plugged in" stat cells | – |
| TX_ONE_VOICE | warn | "All words shown as one voice" — "Speakers couldn't be told apart in this recording." | section 3.3 rule | Transcribe again |
| TX_CHECKPOINT_MISMATCH | error | "Saved progress no longer matches" — "The recording or models changed. Your recording is kept." | FAILED(CHECKPOINT_MISMATCH) | Start over |
| TX_MEMORY | error | "Not enough memory" — "Close other apps or try a shorter clip." | PAUSED(MEMORY) or FAILED(MEMORY) | Try again |
| TX_FAILED | error | "Couldn't transcribe" — "<FailureMessages text> Your recording is kept." (not on the board) | FAILED, any other failure | Try again (+ Start over with a checkpoint) |

`TX_CALCULATING` and `TX_BATTERY` keep their strings (translated) so the catalogue renders as the board; ViewModels never emit them.

### 13.4 Model download (Note card, Library banner, Model download screen)

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| DL_DOWNLOADING | info | "Downloading speech recognition — 46%" — "296 of 640 MB." (the board's "Keep Cassini open." is dropped; no bar, as the board) | Downloading(part) | Cancel (`.part` kept; note back to its previous state) |
| DL_CHECKING | info | "Checking files — 80%" — "Each file is verified before it is used." | verifying | – |
| DL_NO_CONNECTION | error | "No connection" — "Connect to the internet and try again. Recording still works." | DOWNLOAD failure without a validated network, or a timeout | Retry |
| DL_STOPPED | error | "Download stopped" — "The connection dropped. Try again to continue where it stopped." (not on the board) | DOWNLOAD failure with a network (HTTP status, early end, a redirect outside the allow-list) | Retry |
| DL_NO_SPACE | error | "Not enough storage" — "Free about 860 MB and try again." | SPACE (figure = remaining `requiredFreeBytes`) | Retry |
| DL_FILE_CHECK | error | "File check failed" — "The download was damaged and has been discarded." | VERIFY | Download again |
| DL_INSTALL_FAILED | error | "Couldn't finish installing" — "Try again. Your recordings are safe." (not on the board) | INSTALL | Try again |
| DL_RECORD_NOW | info | "Record now, transcribe later" — "Transcription needs a one-time download. Audio is safe meanwhile." | no consent and Audio-only notes exist | Download |

A failure of the best-effort parts never shows a red banner; it shows in Settings and, after Done, as TX_ONE_VOICE when the diarizer was missing.

### 13.5 Library and import

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| LIB_EMPTY | plain | "No notes yet" — "Record something, or import an audio file." | no live notes, no query | Record, Import |
| LIB_NO_MATCH | plain | "Nothing matches “fattura”" — "Try another word or title." | search empty | – |
| IMPORTING | info | "Importing <name>…" (not on the board) | an import in progress | – |
| IMPORT_TOO_LARGE | error | "File too large" — "Choose a file under 2 GB." | Rejected(LARGE) | Choose another |
| IMPORT_NO_SPACE | error | "Not enough space to import" — "This file needs about 1.4 GB free. Free some space and try again." | Rejected(SPACE) | Try again |
| IMPORT_WAV | error | "Unsupported WAV" — "Use 16-bit PCM WAV, or MP3, M4A or Opus." | Rejected(WAV) | Choose another |
| IMPORT_OPEN | error | "Can't open this file" — "It doesn't look like audio Cassini can read." (not on the board) | Rejected(OPEN) | Choose another |
| NO_ENCODER | warn | "This phone can't create Cassini files" — "It has no Opus encoder. You can still record (M4A) and open documents." when the capture format is M4A (the board's copy); "It has no Opus encoder. You can still record and open documents." otherwise | `libcassini_opus` failed to load (sealing needs it) | dismissible |
| NOTIFICATIONS_OFF | info | "Notifications are off" — "Progress shows only in the app." (not on the board) | POST_NOTIFICATIONS denied while a job runs (Library) or always (Settings) | Turn on |
| VOICES_UNREADABLE | error | "Saved voices can't be read" — "They were made by a newer Cassini or are damaged. Nothing was changed." (not on the board) | voice file newer or corrupt | – |

**Acceptance (whole catalogue)**: JVM `NoticeCenterTest` produces every app-emitted row from a constructed state. Shot: the five States board columns equal the board crops (ui.md section 10.2); every kind appears in `StateVariantsTest` or the component strip, light and dark, English and Italian.

## 14. Accessibility (acceptance for every screen)

- Content descriptions on every icon button (board aria-labels); decorative icons have none.
- Headings: day headers, section headers, "Draft transcript", sheet and screen titles.
- Live regions: progress card and Library banner (throttled to stage changes and 5 % steps), event banners, recording status, snackbar.
- State descriptions: recording/paused, stepper steps, play/pause, slider "11:44 of 23:50".
- State never by colour alone (chips have text, trust chips have icons, active word inverted, search hits in weight 600, segments in weight 600).
- Every standalone control has a touch area of at least 48 x 48 dp. Inline transcript word links are exempt (WCAG 2.5.8 inline exception). The Library row suffixes ("· Resume", "· Try again") keep their text size and get a 48 dp touch area through extended hit testing (ui.md section 5) plus a row custom action.
- TalkBack reaches each transcript word as a link.
- No clipping at 200 % font scale; screenshots at 1.5x.
- Reduced motion (animator scale 0): no waveform, progress or pulse animation.
- Contrast pairs per ui.md section 11; `ContrastTest` enforces them in both themes.
- Compose accessibility checks (`enableAccessibilityChecks()`, from `ui-test-junit4-accessibility`) run in the Robolectric screen tests; findings other than inline word links and the extended-hit-area row suffixes fail the test. If P2 shows the checks cannot run under Robolectric, they run instead in the P10 device flow tests on emulator-5554 with the same rule.
