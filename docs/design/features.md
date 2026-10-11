# Cassini Android 0.2: features and behavior

Status: this document is a binding contract for the implementers of `:data`, `:engine`, `:app` and `:ui`. `docs/architecture.md` gives the storage, the services and the threads. `docs/design/ui.md` gives the visuals, the components and the screen contracts. This document tells what each screen does, where each value comes from, and how to examine it. If the copy is different from a design board, the difference is intentional, and ui.md section 11 lists it.

The tags have these meanings:

- **[NEW]**: not in 0.1.0.
- **[CHANGED]**: in 0.1.0, with a different behavior or UI.
- **[KEPT]**: the behavior of 0.1.0 stays.

Each section ends with the **Acceptance** criteria. In these criteria, "JVM" is a unit test and "Robo" is a Robolectric test. "Shot" is a Roborazzi screenshot that the review compares with the board. "Device" is an instrumentation test or a host script on emulator-5554.

## 0. Conventions

- Durations (clock): `mm:ss` (minutes with a leading zero, `02:41`), and `h:mm:ss` from 1 h (`1:12:05`). The spoken form is "48 minutes 13 seconds".
- Durations (prose): "3 min 12 s" (speaking time, and processing time "in 4 min 12 s"). "22 min", and "N h M min" from 60 min. "under 1 min" for less than 60 s (confirmed speech). Section 3.1 gives the forms of the time left. All forms use plurals where the language needs them.
- No-break spaces in durations: `Durations` in `:ui` puts a no-break space (U+00A0) in place of each space of a prose duration, in each language. Thus a line never breaks between a number and its unit. This applies to `ui_duration_s`, `ui_duration_min`, `ui_duration_min_s`, `ui_duration_h`, `ui_duration_h_min` and `ui_duration_under_minute` ("1 h 55 min", "under 1 min"). It also applies to the time-left range `ui_time_left_minutes_range` ("6–9 min"). In "Under 1 min" and "Under 1 min left", only the space after the number changes. The translations can keep plain spaces in the string resources. The processing notification (section 12) does not use these spaces.
- Times of day: `HH:mm` or the 12-hour form, as the system setting tells.
- Day labels: "Today", "Yesterday", then `EEEE d MMM` in the current year ("Monday 5 Oct"), else `d MMM yyyy`. They use the app locale.
- Numbers: the grouping of the locale ("4,812" / "4.812").
- Sizes: `ModelCatalog.mebibytes`, with the label "MB" (as on the boards and in 0.1.0). A total next to its parts is the sum of the rounded parts.
- Plurals: each count has plurals. This applies to speakers, notes, people, days left, minutes, "N more in queue", "N recordings recovered", "N ahead of this note", "Delete N notes" and "N waiting".
- Placeholder speaker labels ("Speaker N" / "Voce N"): `DefaultLabels.isDefault` finds them. The UI shows them localized, from `placeholder_number`. The stored label does not change.
- Speaker tint: a placeholder speaker is always amber ("not named yet"). The named speakers get blue, teal and violet, in this sequence, by their first turn in the variant on the screen. Placeholder speakers do not take a position in this sequence (`SpeakerTint` in `:core`). The label text always shows.
- Saved-voice avatars: blue, teal, amber and violet, in sequence by the position in the list. The initial has the ink color of the tone.
- Each string is a resource in English and Italian. Composite lines are format resources.

## 1. Library (board 00) [CHANGED]

### Layout and data

The Library has these parts, from the top to the bottom:

1. Header: the logo, "Notes", the Trash button (a11y "Trash") and the Settings button (a11y "Settings").
2. Search field [CHANGED]: the placeholder "Search titles and what was said", the IME action Search, and a clear button when the field is not empty.
3. Privacy line [NEW]: a shield and "Transcribed on this phone. Audio never leaves it." The app always shows it.
4. Activity area: the recording card, the transcription banner and the notices. "Activity area" gives the details.
5. List of notes. "List of notes" gives the details.
6. Bottom bar: "Import audio" and the Record button. "Import audio" opens the system picker (`ACTION_OPEN_DOCUMENT`, MIME `audio/*` and `application/ogg`). While the app records, the Record button shows "Recording · 24:07" with the live dot, and it opens Recording again. Only one capture can exist at a time.
7. Snackbar: "“<title>” moved to trash" with Undo (section 8). The Library shows it after Move to trash on the Note screen or in the actions sheet.

### Activity area

The activity area shows these items, in this sequence:

- Recording card [NEW]: the app shows it while `RecordingController.state` is `Active`. It is a dark card with "Recording · <title>" and the elapsed time, or with "Recording paused · <title>". The caption is "Tap to return". A tap opens Recording.
- Transcription banner [NEW]: the app shows it while a job is RUNNING or a standalone download runs. The data comes from `ProcessingCoordinator.state` and `ModelInstallOwner`. The banner has the title "Transcribing <title>" and the percent, a 4 dp bar, and line 2 "About 6–9 min left · 1 more in queue".
- The other forms of line 2 are "Calculating time left…", "Finishing the previous note…" (`WaitingForPrevious`), "Downloading speech recognition — 46%" and "Checking files — 80%". During a download, the title is "Getting ready to transcribe <title>", or "Downloading models" for a standalone download. At 0, the banner does not show the queue suffix. A tap opens the running note, or Settings for a standalone download.
- Notices from `NoticeCenter` (section 13): queue held, recording recovered, record now transcribe later, notifications off, no Opus encoder, import errors, and "Importing <name>…".

### List of notes

- Day groups: by the local day of `created_at`, the newest first, with overline headers.
- Row: the title (up to 2 lines) and the duration, an optional preview (up to 2 lines), and a meta line with the time, the status chip and the suffix.
- Preview without a query (`PreviewText` in `:core`, stored in `notes.preview`): if the saved playback position is after the first word, the preview is about 160 characters of the selected variant. It starts at the word that plays at that position. It has a "…" at the start, and a "…" at the end when the text is cut. Board 00 shows "…quindi la consegna slitta a giovedì, but the price stays as agreed in the March order…".
- Else the preview is the start of the transcript (`note_text.body`): the first 160 characters, cut at a word, with a "…" at the end when the text is cut. The app calculates the preview again when the playback pauses, when the user leaves the Note screen, and when the selected variant changes. A note without a transcript has no preview.
- Preview during a search: the FTS snippet, with the matches in weight 600 on the accent tint (not only by color). A match in the title only highlights the title and shows no snippet.
- The row of the RUNNING note is on `surface`. The other rows are transparent.
- A tap opens the Note screen (with the query during a search). A long press, and the TalkBack custom actions (Rename, Move to trash, and Resume when the note is paused), open the Note actions sheet.

### Status chip (`NoteStatus.of` in `:core`, the first match applies)

| Status | Condition | Chip (tone) | Suffix |
|---|---|---|---|
| RECORDING | `LIVE_CAPTURE`, and it is the active capture | "Recording" (error tone, dot) | – |
| TRANSCRIBING | Job RUNNING (also during the download and the preparation) | "Transcribing" (info) | – |
| QUEUED | Job QUEUED | "Queued" (outline) | – |
| PAUSED | Job PAUSED | "Paused at 12:40" (warn, from `done_ms`) | The text button "· Resume". It looks as on the board. Its touch area extends to 48 dp, and the layout does not move (ui.md section 5). The a11y text is "Resume transcribing <title>". |
| FAILED | Job FAILED | "Couldn't transcribe" (error) | "· Try again" (the same touch rule) |
| UNPLAYABLE | `audio_kind = UNPLAYABLE` | "Can't play" (error) | – |
| UNREADABLE | Document `UNREADABLE` or `INVALID_METADATA` | "Can't read transcript" (error) | – |
| NEWER_FORMAT | `UNKNOWN_FORMAT` | "Newer format" (outline) | – |
| TRANSCRIBED | Document `OK`, `UNVERIFIED` or `STALE_AUDIO` | "Transcribed" (ok) | "· 3 speakers" with 2 or more speakers. "· No speech" at 0 words. |
| AUDIO_ONLY | All other notes (PLAIN, NEEDS_CONSENT) | "Audio only" (outline) | – |

### Search [CHANGED: FTS4 with highlights]

- `SearchQuery.fts(input)` splits the input at white space. It removes the characters `" * ^ : ( ) -` from each term, and it removes the terms that become empty. It writes each term as a quoted prefix phrase, with the star inside the quotes (`"consegn*"`, the FTS3/4 syntax). It joins the terms with spaces (implicit AND). The golden is `consegna giov` → `"consegna*" "giov*"`. The function quotes `AND`, `OR`, `NOT` and `NEAR` from the user, so that they are words.
- The search ignores case and diacritics (unicode61, `remove_diacritics=1`).
- The results are a flat list, the newest first, with the date in the meta line. The debounce is 150 ms. `SavedStateHandle` keeps the query. The results do not include the notes in the trash.
- `SearchQuery.highlights` changes the snippet markers `char(2)`/`char(3)` into ranges. The markers never go to the UI.

### Empty and error states

- No notes: the plain banner "No notes yet" — "Record something, or import an audio file." [Record] [Import].
- No results: the plain banner "Nothing matches “<query>”" — "Try another word or title."
- Import errors: section 13.5. They show at the top until the user dismisses them or until the next import.

### Acceptance

- JVM: the `NoteStatus` table, each row. `DayGroups` (today, yesterday, this year, other year, zone change at midnight). `SearchQuery.fts` (special characters, operators, empty input, the golden of this section) and `highlights`. `PreviewText` (position 0, in the middle of the transcript, at the end of the transcript, cut at a word, ellipses).
- Robo: the DAO search finds "giovedì" from "giovedi", and "consegna" from "CONSEGNA" and from "consegn". The results do not include the notes in the trash. The code parses the snippet markers.
- Shot: `00-library` is equal to board 00 within the tolerances of ui.md. Variants: empty, no results, recording card, search with highlights.
- Device: two imports of the FLEURS clip give one note (`Existing`). A long press opens the actions sheet. Record during a recording opens the Recording screen again.

## 2. Recording (board 01)

The dark surface is the same in the two app themes.

- Header: a chevron down (a11y "Minimise to library (recording continues)") opens the Library. At the center: "Recording" with the orange dot, or "Paused" with a hollow ring. The word gives the state, and the ring is decorative. The format chip shows "Opus 48 kHz" / "M4A 48 kHz" from the capture.
- Title [KEPT, live save NEW]: the label "Title" and an editable field. The default is "Voice note 8 Oct, 08:14" (Italian "Nota vocale 8 ott, 08:14"). The maximum is 200 characters. If the title is blank at Finish, the app uses the default.
- Timer: `mm:ss` / `h:mm:ss`. It is not a live region. The content description is "24 minutes 7 seconds".
- Waveform [CHANGED]: 56 bars for the last 7 s (125 ms each). The 7 newest bars have the fresh color. The waveform stops while the recording is paused. It is one image node, "Microphone level, last 7 seconds". With reduced motion, it has no animation.
- Hint [NEW]: "Keeps recording with the screen off or in another app." and then the smaller of two limits (`RecordingSpace`). The space limit is "Space for about 31 h more.". When the 2 h limit comes first, the hint is "Stops at 2 hours, in about 1 h 35 min.". Rounding: whole hours from 2 h, else minutes rounded down to 5, with a minimum of 5. Board 01 shows the space form, and the fixture uses it.
- Controls: "Library" (the recording continues), Pause/Resume (a11y "Pause recording" / "Resume recording") and "Finish" (stop and save).
- Banners (section 13.2): paused by another app [Resume], storage almost full, near the 2 h limit ("Stops automatically in 5 minutes. The recording so far is saved."), microphone access off [Open settings], microphone stopped working [Open note], and couldn't start the microphone [Try again].
- Finish flow: `finish(title)` makes the row PLAIN. With consent, the app adds the note to the queue and opens the Note screen (Transcribing or Queued). Without consent and without `models.declinedAt`, it opens the Note screen and then Model download on top. Else it opens the Note screen (Audio only).
- Back minimises the screen [CHANGED: in 0.1.0, back finished the recording].
- Permissions [KEPT]: the screen asks for RECORD_AUDIO and, on API 33+, POST_NOTIFICATIONS when it opens. After a permanent denial, it shows "Microphone access is off" with Open settings. When the user comes back with the permission, the capture starts automatically (`pendingStart` in `SavedStateHandle`, examined at ON_RESUME).

**Acceptance**: JVM `RecordingPolicyTest` (StorageLow at 15 min, stop at the floor, warning at 1:55, stop at 2:00). `RecordingSpace` rounding and the smaller of the space and the time limit. `RecordingControllerTest` (finish never on the main thread, the title stays, silenced → paused, and it stays paused). Shot: `01-recording`. Variants: paused, permission off, storage low, near the limit, a hint with the time limit. Device `@NeedsMicrophone`: record 5 s, push back, the Library shows the recording card, push Finish in the notification, and the note shows as PLAIN or QUEUED.

## 3. Note screen

The Note screen is one destination with modes. The mode follows the job and the document:

| Mode | When | Shows |
|---|---|---|
| Audio only | PLAIN note and no job, or NEEDS_CONSENT | The plain banner "Plain audio — No transcript yet." [Transcribe] (no consent → Model download, else the note goes into the queue), and the player |
| Queued | Job QUEUED | The plain card "Queued" — "N ahead of this note." or "Starts after the current transcription." [Remove from queue]. For a document (Transcribe again), the card is above the Read & listen content. The player. |
| Transcribing | Job RUNNING | The progress card and the draft (3.1). No player. |
| Paused | Job PAUSED | The warn banner (3.2), the read-only draft and the player |
| Failed | Job FAILED | The error banner (section 13.3) [Try again], and Start over when a checkpoint stays. For a document, the Read & listen content follows. The player. |
| Read & listen | A document and no job | The chips, the trust banners, the transcript and the player (3.3) |
| Unplayable | `UNPLAYABLE` | The red banner "This recording can't be played" — "Cassini closed before the recording was saved. You can still save the file." No player. |

Header: back (a11y "Back to library") and the title (one line). In Read & listen, there is no subtitle, because Note actions and Document info give the date and the duration. Then come Search (a11y "Search in transcript") and More (a11y "Share and more"), as on board 03. In all other modes, the header has the subtitle "Today, 10:02 · 48:13" and More (a11y "More actions"), as on board 02. More opens the Note actions sheet.

While a job row exists for a document note (Queued, Transcribing, Paused, or Failed with a checkpoint), the sealed transcript is read-only. A speaker rename, a merge or a change of version on the sealed document shows "Wait until transcription finishes". The draft naming in Transcribing and Paused operates as 3.1 describes. The rename of the note title always operates (architecture section 4.1).

### 3.1 Transcribing (board 02) [CHANGED]

The progress card is a polite live region. It updates at most at 1 Hz, and TalkBack announces it at a change of stage or at each 5 %. It has these parts:

- Headline (`ProgressHeadline`): STARTING "Starting…", WAITING_FOR_PREVIOUS "Finishing the previous note…", READING_AUDIO "Reading the audio", PREPARING "Preparing", FINDING_WORDS_AND_SPEAKERS "Finding words and speakers", FINDING_WORDS "Finding words" (no diarizer installed), MATCHING_VOICES "Matching saved voices" and SAVING "Saving". The range is "29:00–29:45", and it is hidden when it is 0–0.
- Percent: at most 99 until Done. A 6 dp bar.
- Stepper: Reading, Preparing, Words & voices, Matching, Saving. The a11y text is "Step 3 of 5: Words and voices".
- Stats: Time left ("6–9 min", "Under 1 min", "About 6 min", "1 h 10 min–1 h 30 min", "Calculating…"). Processed "29:52 / 48:13". Battery at finish ("54% → about 41%", "Plugged in", "Measuring…", or hidden without a reading). Words so far.
- "Stop and keep progress" gives PAUSED(USER).
- Footnote: "Keeps going if you leave the app. A notification shows progress and lets you stop." If the user denied notifications: "Notifications are off, so progress shows only here."
- Low memory hint (amber text) when the memory check before the start found a problem.
- In the download phase, the download banner replaces the stats (section 13.4).

The draft transcript [KEPT, CHANGED visuals] has these parts and rules:

- The header "Draft transcript" and the dashed tag "Words may still change".
- Turns with the speaker label and the start time of the turn. The words that end after `tentativeFromMs` have the unsettled color.
- The list follows the newest text automatically. It stops when the user scrolls up and starts again at the bottom.
- The words are not interactive. A tap on a speaker label opens the Name a speaker sheet in draft mode.
- From step 6 (attribute), naming is closed. The cards and the rename show "Saving — you can rename when it's done".

The inline suggestion card [NEW] shows for each speaker with a `live` SUGGESTED match that is not rejected and whose label is a placeholder. It is at the most recent turn of that speaker. It has a header (the speaker label and the time, as a 48 dp button that opens the Name sheet, as each speaker label does), then "Is this **Luca**?" [Yes, Luca] [Not Luca]. These rules apply:

- Yes calls `coordinator.draft(noteId).confirm(speakerId, voiceId)`. This sets `labels`, `remembered`, `generation` and `live.state = CONFIRMED`. Adopt enrols the voice if the generation did not change.
- Not calls `reject(speakerId, voiceId)`. The voice goes into `rejected`, and the app removes `live` and a matching `automatic` entry. The app does not suggest this voice again for that speaker.
- An AUTO match applies the name directly, with the "auto" badge.

**Acceptance**: JVM `ProgressTrackerTest` (the stepper only increases, the headline mapping with FINDING_WORDS, Calculating until 3 windows, Finishing, the maximum of 99, battery Measuring → Projected after 120 s, PluggedIn, the finalization is included). `DraftActionsTest` (the writes of Yes and Not, no new suggestion, enrolment only with an unchanged generation, no unenrolment, a job `setLive` at the same time as a UI `confirm` loses neither). Shot: `02-transcribing`. Variants: Queued, Paused with a draft, Downloading, Checking, Failed, WaitingForPrevious. Device `@NeedsModels`: transcribe a 3-minute clip. The percent only increases. Stop gives Paused. Resume gives Done with a verified document.

### 3.2 Paused

The warn banner is "Paused at 12:40 of 34:12" — "Pick up where you left off. The draft so far is kept." [Resume] [Start over]. Start over asks "Start over? The draft so far is discarded. Your recording is kept." The draft under the banner is read-only (`CheckpointRepository.loadDraft`), and naming stays open.

A pause without a checkpoint (a stop during the download or the first window) shows "Paused before any words were found" — "Resume to start transcribing." [Resume]. All pause reasons use this banner, except MEMORY, which uses the red "Not enough memory" banner. A queue with a hold adds "Transcription paused · N waiting" [Continue].

### 3.3 Read & listen (board 03)

The chips row has these chips:

- Trust chip: OK "Verified" (teal, check icon), UNVERIFIED "Not verified" (amber), STALE_AUDIO "Audio changed" (amber), UNREADABLE/INVALID_METADATA "Can't read transcript" (red) and UNKNOWN_FORMAT "Newer format" (red). Each chip has an icon. A tap shows or hides the matching trust banner (13.1).
- Version chip, only with 2 or more readable variants: "Version 2 of 2 · speakers merged". The suffix is "· speakers merged" for MERGED, "· transcribed again" for a TRANSCRIBED variant with an ordinal more than 0, and none for ordinal 0. A tap opens the Versions sheet.
- "3 speakers" (hidden at 0).

The other parts of the screen are:

- Banners: a trust banner when the state is not OK (UNVERIFIED until "Play anyway"). "All words shown as one voice" when the selected variant has words, `speakers_told_apart == false` and `speaker_count == 1`. The inline suggestion card at the first turn of a placeholder speaker when its link is SUGGESTED (Yes → relabel, CONFIRMED and enrolment. Not → REJECTED).
- Transcript: one item for each turn. The speaker label is a button (a11y "Giulia, named automatically, at 11 minutes 41 seconds", onClick label "Name this speaker"). The "auto" badge shows when the link is AUTO and the label is equal to the name of the voice. The paragraph is in ink for named speakers and in `text3` for placeholders.
- Each word is a link: a tap seeks to `startMs` and plays (onClick label "Play from here"). The active word has an inverted background, and the app makes only the changed turn again. Follow mode keeps the active word in view. A scroll stops it, and a "Follow playback" chip starts it again.
- Player [KEPT, new style]: a slider (a11y "Position", in steps of 10 s), the times, −10 s, play/pause and +10 s. The app saves the position each 5 s, at pause and when the user leaves the screen. The last save also updates the Library preview.
- In-note search [KEPT]: the search button changes the header into a field with "3 of 12" and up/down buttons. The matches are on the amber tint, and the current match is in weight 600. The search ignores diacritics (NFD). With no result, it shows "No matches". Back closes the search. When the user opens the note from a Library search, the field has the query, and the screen goes to the first match.
- No words: a sealed version with 0 words has no turns. For this document, the transcript pane shows a plain card, 16 dp below the top of the pane: "No speech" — "No words were found in this recording." The Italian copy is "Nessun parlato" — "Non sono state trovate parole in questa registrazione." The body is the copy of the TXT export (section 9). The card does not show for a draft, while the transcript loads, or for a transcript that cannot be read. The Library row of this note shows "· No speech" (section 1).
- Speaker rename is disabled for trust states other than OK ("Fix the transcript first: Transcribe again").
- `refreshIfStale` runs when the screen opens.

**Acceptance**: Shot `03-read-and-listen` (active word "quindi", auto badge on Paolo Ferri, no subtitle). Variants: Not verified, Audio changed, Unreadable, Newer format, one voice, search mode, Queued over a document, no words (`variants/read-no-words.png`). Robo/Compose: a word tap seeks, a speaker tap opens the sheet, and back closes the search before it leaves the screen. A document with no words shows "No speech" only after its transcript loads. Device: play, seek with a word tap, and the position stays after the user leaves and comes back.

### 3.4 Versions sheet [CHANGED: replaces "Choose transcript"]

The title is "Versions". The sheet has one row for each variant, in the sequence of the manifest: "Version N", the detail "Transcribed 8 Oct, 15:40 · 4,812 words" or "Speakers merged · from version 1 · 8 Oct, 16:02", and the language when it is not empty. A radio button marks the selection. A selection calls `selectVariant` and closes the sheet. The unreadable variants are disabled ("Can't be read").

## 4. Name a speaker sheet (board 04) [CHANGED: one sheet replaces 3-4 chained dialogs]

The sheet has two modes: SEALED (document OK, no job row) and DRAFT (job RUNNING or PAUSED, naming open). The pure reducer `SpeakerNaming` controls it: events go in, and the state of the sheet and the effects come out.

1. Header: an avatar in the speaker tint (the placeholder number, or the first grapheme of the label). The title is the display label. "Speaks for 3 min 12 s in this note" (`speaking_ms`. For a draft, the app calculates it from the draft words, with the merges applied). Play sample (a11y "Play a sample of this voice") calls `playRange(sample_start_ms, sample_end_ms)`. It is disabled without a sample window.
2. Name field: at most 80 characters, with capital letters at the start of words. It contains the label, unless the label is a placeholder. IME Done = Save.
3. "Saved voices, closest first": the voices of the current model, ranked by `cosine(print, voice.mean)` descending, then by name. The top row gets "Likely" only if its score is `Thresholds.suggest` (0.50) or more. Stale voices are last, by name, without a score. Without a print, the header is "Saved voices", and the sort is by name. The rows are 52 dp. A tap on a row puts the name in the field and binds `chosenVoiceId`. If the user edits the name, so that it is not the name of that voice, the binding goes. The list is hidden when there are no saved voices. It shows at most 3 rows, with a "Show all" row (52 dp, `body16` accentInk) when more exist.
4. Same-person prompt (amber card): it shows when Remember is on, no voice is bound, a print exists, and the typed name matches a saved voice (`NameMatch`). The comparison uses the trimmed name, ignores case, and removes the accents after NFD. The name matches when it is equal to the full name of the voice or to its first word. The prompt is "“Anna Ricci” is already a saved voice. Is this the same person?" [Same person] [Someone else]. With more than one match, the prompt uses the highest score (alphabetical for equal scores). Same person binds the voice and puts its full name in the field. Someone else keeps the typed name, and Save then makes a new voice.
5. While the question has no answer, Save keeps its enabled look (as on board 04). A push on Save does not save. It moves the accessibility focus to the question, announces "Answer the question above first", and briefly pulses the border of the card (no animation with reduced motion).
6. Rename-saved-voice prompt: when the match is CONFIRMED to voice V and the typed name is not the name of V: "This voice is saved as “Anna Ricci”. Also rename the saved voice?" [Rename saved voice] [Someone else]. Other documents keep their labels. While the question has no answer, Save operates as in item 5.
7. "Not <voice name>?" text button, when the link is AUTO or CONFIRMED: the app first unenrols a CONFIRMED voice. The match becomes REJECTED. A label that is equal to that name goes back to the placeholder.
8. Remember checkbox (on by default when a print exists): "Remember this voice on this phone" — "Future notes will name <name> automatically. The voiceprint never leaves the phone." It is disabled with "Too little speech to remember this voice." (less than 4 s) or "Recognising saved voices isn't installed yet."
9. Footer: "Merge into…", "Cancel" and "Save name". "Merge into…" gives the list of the other speakers (label, tint, speaking time) and then the confirmation "Merge <A> into <B>? Everything <A> said will be shown as <B>." It adds "This adds version N+1; version N stays available." (sealed) or "This changes the draft." (draft). Placeholder speakers are valid targets.
10. A sealed merge makes a MERGED variant. The prints move as in `applySpeakerMerge` of 0.1.0: the app removes the source print, and CONFIRMED copies become AUTO.
11. Save is disabled (38 % alpha) only when there is nothing to save: the name is blank, or the label, the binding and the remember state did not change and the match is already CONFIRMED.
12. Sealed save: if the label changed, the app relabels the document. Then it calls `rememberVoice` as in 0.1.0. After a change, it unenrols a previous CONFIRMED voice. It clears a stale AUTO. It enrols into the bound voice or a new voice, and sets CONFIRMED with the score.
13. Draft save: `DraftNames.rename` / `remember` with the generation.

**Acceptance**: JVM `SpeakerNamingTest` covers each prompt path, the Save rule (enabled but blocked while a question is open, disabled when nothing changed), "Not X", the merge confirmation, and the effects in draft and sealed mode. `NameMatch` (accents, case, first word, equal scores). Shot: `04-name-a-speaker` (Speaker 3, "Anna", conflict shown, Remember on, Save enabled). Variants: rename-saved-voice, no saved voices, no print, Show all. Device `@NeedsModels`: name a draft speaker with "Yes, Luca". After Done, the document has the label, and Saved voices lists the person with 1 note.

## 5. Model download (board 05) [CHANGED: full screen, and the size of each model NEW]

- Close (a11y "Close"). The logo. "Transcribe on this phone". "Cassini needs a one-time download of about **730 MB**. After that, recognising words and telling voices apart happen on this phone, even offline. Your audio stays here." The total is the sum of the rounded sizes of the missing parts.
- The list of the missing parts with their sizes: Speech recognition 640 MB, Telling speakers apart 62 MB, Recognising saved voices 28 MB.
- The footnote, exactly as on the board: "Needs about 860 MB free while installing. Each file is checked before it is used." (`ModelPlan.requiredFreeBytes`). Under the footnote, one text-button row "Where the models come from · Privacy" opens Licenses. This row is an addition to the board, and ui.md section 11 lists it.
- Primary button: with a note, "Download and transcribe" (it records the consent, adds the note to the queue and goes back to the note). Without a note, "Download" (it records the consent, starts a standalone download through `ModelInstallOwner` and goes back). Before the start, the screen examines the free space. If the space is not sufficient, it shows "Not enough storage — Free about 860 MB and try again." [Retry] and does not record the consent.
- "Not now — keep the audio" calls `recordDeclined()` and goes back.
- The screen asks for POST_NOTIFICATIONS one time, before the first download (API 33+).
- No missing model: the entry points (Transcribe, Settings rows, banners) do not open this screen. An old entry removes itself. With a note, it first adds the note to the queue.
- The screen does not use the "Keep Cassini open." of board 10 (in the download banner). Downloads run in the foreground service, and they continue when the user leaves the app.

**Acceptance**: JVM `ModelPlan` (a new installation needs about 861 MiB, and the screen shows 730 MB, partial installations). Coordinator test: no `ArtifactFetcher` call before consent. Shot: `05-model-download` and the no-space variant. Device (P12): the walk opens it on an empty scratch model root.

## 6. Note actions and export (board 06)

- Header: the title and "Yesterday, 15:10 · 23:50 · 3 speakers". Plain notes do not show the speaker part.
- "Save a copy": three export rows. The list "The "Save a copy" rows" after this list gives them.
- Text and subtitles are disabled for notes without a document ("Available after transcription") and for UNREADABLE/UNKNOWN_FORMAT.
- The exports use the selected variant and the display labels. The writes run on IO, with the progress in the row. The snackbar is "Saved “<file>”" or "Couldn't save the file".
- **Rename** [NEW]: a dialog (at most 200 characters, not blank) that calls `NoteRepository.rename`. The Library and all screens show the new title immediately. The app writes the title of a document again when the document is `OK` and no transcription waits. Else it writes it after the transcription. For a damaged document, it does not write it, and a copy of that document keeps the old title inside.
- **Transcribe again** [KEPT]: "Adds version 3. Speaker names carry over." The second sentence shows only when the voice model is ready. Plain notes show **Transcribe**. RUNNING shows **Stop and keep progress**. QUEUED shows **Remove from queue**. PAUSED shows **Resume** and **Start over**. For a document that is not OK (STALE_AUDIO, UNREADABLE, INVALID_METADATA), the app asks for the confirmation "Earlier versions can't be kept because the audio changed." Then it seals a new document.
- **Document info** [KEPT]: the trust line, the title, the recording date and time, the duration, and "N versions · M speakers". For the selected version: the language, "Transcribed on this phone in 4 min 12 s · 11× realtime", "Parakeet TDT 0.6B v3, INT8", "Speakers told apart on this phone" or "All words shown as one voice", "2 names from saved voices" and "Resumed from 12:40". Then the file size and the meeting ID (selectable mono text).
- **Move to trash** [NEW] (destructive color): "Kept for 30 days, then deleted." The rules of section 8 apply. Then the app goes back to the Library and shows the Undo snackbar there. The row is hidden during a recording. For a running job, the app asks "Stop transcription and move to trash?".

The "Save a copy" rows are:

1. **Cassini document** `.opus` [KEPT]: "Audio with the transcript inside. Plays anywhere." For DOCUMENT notes. It uses SAF `CreateDocument("audio/ogg")`, the name `ExportNames.file(title, "opus")`, and a byte-for-byte copy. Plain notes show **Audio file** `.opus`/`.m4a`/`.<ext>` — "The recording as it is. No transcript yet."
2. **Transcript as text** `.txt` [NEW] (`text/plain`).
3. **Subtitles** `.srt · .vtt` [NEW]: it opens a chooser with "SubRip (.srt) — works in most video players" and "WebVTT (.vtt) — for web players" (`application/x-subrip`, `text/vtt`).

**Acceptance**: JVM export goldens (section 9). Device `@NeedsModels`: the Cassini copy is the same, byte for byte (`run-as sha256sum`). The TXT, SRT and VTT exports of the FLEURS clip are equal to the goldens that we recorded one time on the emulator. Shot: `06-note-actions-and-export`, the plain-note variant and the subtitle chooser.

## 7. (reserved)

## 8. Trash (board 09) [NEW]

- Header: back (a11y "Back to library" or "Back to settings", for the screen that it goes back to), "Trash" and "Empty" (destructive, hidden when the trash is empty).
- Intro: "Notes here are deleted for good after 30 days, with their audio, transcripts and voiceprints from those notes."
- Cards, the oldest deletion first (the note that expires first is at the top, as on board 09): the title and the duration, "Deleted 2 Oct · 23 days left" ("Deleted today", "Deleted yesterday", with plurals), [Restore] and [Delete now].
- Dialogs: "Delete “Test recording” for good? Its audio, transcripts and voiceprints from this note are deleted. This can't be undone." [Delete] [Cancel]. "Delete N notes for good?" with the same body.
- Undo snackbar: on the screen where the user moved the note to the trash (in practice, the Library). The duration is `AccessibilityManager.getRecommendedTimeoutMillis(10_000, FLAG_CONTENT_TEXT | FLAG_CONTENT_CONTROLS)`. Undo restores the note.
- Restore brings the job state back (`notes.trashed_job`). A note that was in the queue goes again to the end of the queue. A paused note stays paused.
- The "moved to trash" snackbar of board 09 over the Trash screen is a state of the fixture only (ui.md section 11). After Move to trash, the app never shows Trash.
- Empty: "Trash is empty" — "Notes you delete stay here for 30 days."
- Purge rules: architecture section 4.10.

**Acceptance**: JVM `TrashPolicy` ("Deleted 2 Oct · 23 days left" on 9 Oct, "today · 30 days left", DST changes). Robo `PurgeOrderTest` (a crash after the file deletion, and the next run finishes), and a restore of a queued note puts it again into the queue. Shot `09-trash` and the empty variant. Device: trash → Undo → trash → Delete now removes `notes/<id>` and `voices/notes/<id>.json`.

## 9. Export writers (`:core` `org.cassini.core.export`) [NEW]

The input is `ExportTranscript(title, durationMs, words, labels)`. It has the words of the selected variant in canonical order (the writers never sort them again) and the display label of each speaker ID. Named labels are verbatim, and placeholders are the localized "Speaker N". If a label is missing, the writer uses the ID. The output is UTF-8 without BOM, with `\n` line ends and exactly one `\n` at the end. One space separates the words.

- Turns (`TranscriptTurns.of`): the longest runs of consecutive words with the same speaker. The start is the start of the first word, and the end is the end of the last word. The UI and `SpeakerStats` also use this function.
- Clock (`ExportClock`): `mm:ss` for a duration of less than 1 h, else `h:mm:ss`. The seconds are rounded down. SRT uses `HH:MM:SS,mmm`, and VTT uses `HH:MM:SS.mmm`.
- TXT (`TextExport.write(t, subtitle, emptyText)`): a title line, a subtitle line ("8 Oct 2026, 15:10 · 23:50 · 3 speakers") and a blank line. Then, for each turn, `[<clock>] <label>` and the paragraph. One blank line separates the turns. With only one speaker, the writer does not write the label. With 0 words, the file has the title, the subtitle, a blank line and `emptyText`.
- Cues (`SubtitleCues.build`): the writer reads the words in canonical order. The list "Cue breaks" after this list gives the conditions for a new cue.
- SRT: the indices start at 1, with a blank line between the cues. The prefix `"<label>: "` is only on the first cue and at a speaker change in the output sequence, and only with 2 or more speakers. SRT has no markup.
- VTT: a `WEBVTT` header, no cue IDs, and no STYLE or REGION. With 2 or more speakers, each cue is `<v Label>…</v>`. The writer escapes `&`, `<` and `>` in the text and in the labels. It removes the control characters from the labels and writes `-->` as `--&gt;`.
- File names (`ExportNames.file`): the function replaces `/ \ : * ? " < > |` and the control characters with `_`. It joins each run of white space into one space. It removes the dots and the spaces at the ends. It cuts the name at 100 UTF-16 units, at a code-point boundary. If the result is empty, the name is "Cassini note". Then it adds the extension.

Cue breaks: the writer starts a new cue before the word `w` in these conditions:

1. The speaker changes.
2. `w.startMs − prev.endMs ≥ 1500`.
3. `w.endMs − cue.startMs > 6000`.
4. The length of the cue text + 1 + `w.text.length` > 84. The first cue of a speaker includes the SRT label prefix.
5. `prev.text` ends with `. ? ! … 。 ？ ！`, and the cue already lasts 1500 ms or more.

The end of a cue is max(end, start + 1000), but never after the next cue of the same speaker. Cues of the same speaker never overlap. Cues of different speakers can overlap. A stable sort by the start puts the cues in sequence, and the text stays in canonical order.

If a cue has more than 42 characters, the writer breaks the line. It uses the space that gives the best balance of two lines of 42 characters or less. If there is no such space, it breaks at the last space at position 42 or less. It never removes words and never makes 3 lines.

**Acceptance**: JVM golden strings for each writer: Italian accents, one speaker and many speakers, an empty transcript, speakers that overlap, each of the 5 break rules, the minimum duration, the line balance, the escapes, the clock for 1 h or more, and the canonical order inside a cue while the cues are sorted.

## 10. Saved voices (board 07) [CHANGED: was People]

- Header: back (a11y "Back to settings") and "Saved voices".
- Privacy note: "Voiceprints stay on this phone. They are never uploaded or written into shared files — only the names you choose are."
- Rows: the avatar initial (the tint by index, the initial in the ink color of the tone), the name, and "14 notes · 22 min confirmed speech" ("under 1 min", "N h M min" from 60 min, with plurals).
- The options button of a row (a11y "Options for Giulia") gives Rename… (at most 80 characters) and Forget… ("Forget Giulia? Cassini stops recognising this voice. Names already in your notes stay.").
- The sort is by notes, then by confirmed seconds, then by name.
- Stale hint: "Name them again in a note to keep recognising them". It shows exactly when `voice.model != ModelCatalog.voiceprintModel.sha256 || voice.mean.size != 512`. If the user names that speaker again with Remember on, the app enrols the voice again with the same ID.
- Footnote: "To add someone, name a speaker in any note and keep “Remember this voice” on."
- "Forget everyone…" asks "Forget everyone? Cassini stops recognising all N saved voices and deletes the voiceprints from every note. Names already in your notes stay." Then it calls `forgetEveryone()`. The new generation makes invalid the Remember choices of the drafts and the pending prints of the running jobs.
- Empty: "No saved voices yet" and the footnote. "Forget everyone…" is hidden.
- Error: for a newer or damaged `people.json`, the screen shows "Saved voices can't be read" — "They were made by a newer Cassini or are damaged. Nothing was changed." The edits are disabled.

**Acceptance**: Robo `VoiceRepositoryTest`: forget removes the matches in the sealed files, in the pending files and in the drafts. forgetEveryone increases the generation and clears the links and the pending files. An adopt after forgetEveryone writes no prints. A newer version is read-only. Shot `07-saved-voices`, and the empty, stale and error variants.

## 11. Settings (board 08) and Licenses

- **App language**: a segmented control Phone / English / Italiano. It calls `AppCompatDelegate.setApplicationLocales` (an empty list = Phone). For a11y, it is a radio group. The footnote is "Speech is recognised in any language, automatically."
- **On-device models**: "Speech recognition — INT8 · 640 MB", "Telling speakers apart — 62 MB" and "Recognising saved voices — 28 MB", with a status text. The status texts are Ready (teal), "Downloading 46%" / "Checking files" (blue), "Downloads with next transcription" (consent given and the model is missing, amber), "Not downloaded" (no consent, and the row opens Model download) and "Download failed — tries again next time" (red). A "Download failed" row also opens Model download, without a note. There, Download starts the download again (section 5). For example, the row shows "Download failed" after Android stops a standalone download (architecture.md section 5.7, step 6). Nothing tries again in the background. The next transcription that needs the model, or a tap on the row, starts the download again. The other rows are not buttons. The footnote is "Transcription runs on this phone. Audio never leaves it." There is no delete action.
- **Privacy**: "Saved voices — 4 people" ("None yet") opens Saved voices. "Trash — 2 notes" ("Empty") opens Trash.
- **About**: "**Cassini <versionName>** — turns speech into searchable, timed text on your phone." "Free software under GPLv3. Source code and notices" opens Licenses.
- Notifications-off banner, when the user denied POST_NOTIFICATIONS: "Notifications are off — Progress of long transcriptions shows only in the app." [Turn on] opens the notification settings of the app.
- **Licenses** (no board, ui.md section 8): Cassini (GPLv3), the third-party notices, the native dependencies, Instrument Sans (OFL), IBM Plex Mono (OFL), the model notices (OpenMDW for the diarizer), "Where the models come from" (the hosts and the download domains of architecture section 5.6), Privacy (`PRIVACY.md`), and "Source code on GitHub", which opens `https://github.com/codemyriad/cassini-android`.

**Acceptance**: Shot `08-settings`, and the notifications-off and downloading variants. Device: the selection of Italiano stays after a process kill (`scripts/device-language-check.sh`). Robo `LicenseAssetsTest` covers the copies of the notices and of the privacy file. `FontAssetsTest` of `:ui` covers the font licenses.

## 12. Notifications

| Notification | Channel | Content | Actions |
|---|---|---|---|
| Recording (ongoing, id 1) | recording | "Recording" / "Recording paused". The text is the title or the active warning. A chronometer during the recording, and "Paused at 24:07" during a pause. | Pause/Resume and Finish. The content opens Recording. |
| Processing (ongoing, id 2, ≤ 1 Hz) | processing | "Transcribing “<title>”" and "62% · 6–9 min left" or "Calculating time left…". Also "Starting…" and "Finishing the previous note…". For a download: "Downloading speech recognition — 46%" / "296 of 640 MB". "Checking files — 80%". "Matching voices…" and "Saving…". The sub-text is "1 more in queue". | Stop (stop all and hold) and Open. Cancel during a download. |
| Transcribed | results | "Transcribed: <title>" / "3 speakers · 48:13" | The content opens the note. |
| Couldn't transcribe | results | "Couldn't transcribe <title>" / the failure text | The content opens the note. |
| Ready to transcribe | results | "<title> is waiting. Open Cassini to start." | The content opens the note. |
| Paused by Android | results | "Paused at 12:40 of 34:12. Open to resume." | The content and Resume open the note. |
| Transcription stopped | results | "Cassini closed while transcribing <title>." | Resume (foreground service) |
| Recording stopped / Microphone stopped working | results | The copy for storage or for the microphone | It opens the note. |

The app posts the results notifications only while the app is not visible. The text follows the app language. The public versions do not show the title.

**Acceptance**: Robo at SDK 32: the notification text follows the app locale. Device: `dumpsys activity services` shows `dataSync` during the processing on API 34.

## 13. Banner catalogue (board 10)

Each banner has a tone, a title that tells the condition (never only a color), an optional body and up to two actions. The first action has an ink fill, and the second has an outline. The banners that show after an event are polite live regions.

A banner that the user can dismiss has a 48 dp close button at the end (a 20 dp Close glyph, a11y "Dismiss"). The board does not show this button (ui.md section 11). `NoticeCenter` sorts the banners in this sequence: failed, attention, info, fine/plain. In one tone, the newest banner is first. The copy in the tables is the copy of the board, unless a note in the table tells otherwise.

### 13.1 Document trust (Note screen, and the Library chip of section 1)

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| TRUST_VERIFIED | ok | "Verified" — "Audio matches the transcript exactly. Everything works." | OK, when the user taps the chip | – |
| TRUST_NOT_VERIFIED | warn | "Not verified" — "The audio could not be checked, so word timing may be off." | UNVERIFIED and not acknowledged | Play anyway (`acknowledgeTrust` and play) |
| TRUST_AUDIO_CHANGED | warn | "Audio changed since transcription" — "Word timing may not match what you hear." | STALE_AUDIO | Transcribe again (a new document) |
| TRUST_UNREADABLE | error | "Transcript can't be read" — "The file's Cassini data is damaged. The audio still plays." | UNREADABLE, INVALID_METADATA (Document info gives the read error) | Transcribe again |
| TRUST_NEWER_FORMAT | error | "Newer Cassini format" — "This version can't be read here yet. The audio still plays." | UNKNOWN_FORMAT | – |
| PLAIN_AUDIO | plain | "Plain audio" — "No transcript yet." | PLAIN note | Transcribe |
| UNPLAYABLE | error | "This recording can't be played" — "Cassini closed before the recording was saved. You can still save the file." (not on the board) | UNPLAYABLE | – |

### 13.2 Recording (the Recording screen in light tones, and the Library for the recovery)

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| REC_PAUSED_OTHER_APP | warn | "Paused — another app is using audio" — "Resume when your call ends. Nothing was lost." | `pause == OTHER_APP` | Resume |
| REC_STORAGE_LOW | warn | "Storage almost full — about 10 min left" — "Free some space to keep going. If it runs out, the recording saves automatically." | StorageLow | – |
| REC_NEAR_LIMIT | warn | "Stops automatically in 5 minutes" — "The recording so far is saved." (not on the board) | NearTimeLimit | – |
| REC_STOPPED_STORAGE | warn | "Stopped — storage almost full" — "Less than 200 MB free. The recording so far is saved." | `Ended(StoppedForSpace)` (also a notification in the background) | Open note |
| REC_MIC_OFF | error | "Microphone access is off" — "Turn it on in system settings. Recording starts when you come back." | RECORD_AUDIO permanently denied | Open settings |
| REC_MIC_FAILED | error | "Microphone stopped working" — "Recording ended. The audio so far is in your library." | Capture error | Open note |
| REC_COULD_NOT_START | error | "Couldn't start the microphone" — "Another app may be using it. Try again." (not on the board) | `Ended(CouldNotStart)` | Try again |
| RECOVERED | info | "1 recording recovered" (plural) — "Cassini closed unexpectedly. We saved what was captured (05:12)." | `recover` returned IDs that are newer than `notices.recoveredAck` | Open (the most recent). The user can dismiss it. |

### 13.3 Transcription

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| TX_PAUSED | warn | "Paused at 12:40 of 34:12" — "Pick up where you left off. The draft so far is kept." | PAUSED (USER, SYSTEM, INTERRUPTED) with a checkpoint | Resume, Start over |
| TX_PAUSED_EARLY | warn | "Paused before any words were found" — "Resume to start transcribing." (not on the board) | PAUSED without a checkpoint | Resume |
| TX_QUEUE_HELD | warn | "Transcription paused · N waiting" — "Continue when you're ready." (not on the board) | `queue.hold` is set, and QUEUED rows exist | Continue |
| TX_CALCULATING | info | "Calculating time left…" — "An estimate appears after the first few minutes of audio." | Catalogue only (StatesBoardTest). In the app, this state is the "Calculating…" stat cell. | – |
| TX_BATTERY | info | "Measuring battery use…" — "Plugged in — no battery estimate needed." | Catalogue only (StatesBoardTest). In the app, these states are the "Measuring…" and "Plugged in" stat cells. | – |
| TX_ONE_VOICE | warn | "All words shown as one voice" — "Speakers couldn't be told apart in this recording." | The rule of section 3.3 | Transcribe again |
| TX_CHECKPOINT_MISMATCH | error | "Saved progress no longer matches" — "The recording or models changed. Your recording is kept." | FAILED(CHECKPOINT_MISMATCH) | Start over |
| TX_MEMORY | error | "Not enough memory" — "Close other apps or try a shorter clip." | PAUSED(MEMORY) or FAILED(MEMORY) | Try again |
| TX_FAILED | error | "Couldn't transcribe" — "<FailureMessages text> Your recording is kept." (not on the board) | FAILED, all other failures | Try again (and Start over with a checkpoint) |

`TX_CALCULATING` and `TX_BATTERY` keep their strings (with translations), so that the catalogue looks as the board. The ViewModels never give them.

### 13.4 Model download (the Note card, the Library banner and the Model download screen)

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| DL_DOWNLOADING | info | "Downloading speech recognition — 46%" — "296 of 640 MB." The app does not use "Keep Cassini open." of the board. No bar, as on the board. | Downloading(part) | Cancel (the `.part` file stays, and the note goes back to its earlier state) |
| DL_CHECKING | info | "Checking files — 80%" — "Each file is verified before it is used." | Check of the files | – |
| DL_NO_CONNECTION | error | "No connection" — "Connect to the internet and try again. Recording still works." | A DOWNLOAD failure without a validated network, or a timeout | Retry |
| DL_STOPPED | error | "Download stopped" — "The connection dropped. Try again to continue where it stopped." (not on the board). When Android stopped the download: "Android stopped the download. Try again to continue where it stopped." (not on the board) | A DOWNLOAD failure with a network (HTTP status, an early end, a redirect outside the allow-list). Also `STOPPED_BY_SYSTEM`: Android stopped a standalone download (`onTimeout`, architecture.md section 5.7, step 6). | Retry |
| DL_NO_SPACE | error | "Not enough storage" — "Free about 860 MB and try again." | SPACE (the figure is the remaining `requiredFreeBytes`) | Retry |
| DL_FILE_CHECK | error | "File check failed" — "The download was damaged and has been discarded." | VERIFY | Download again |
| DL_INSTALL_FAILED | error | "Couldn't finish installing" — "Try again. Your recordings are safe." (not on the board) | INSTALL | Try again |
| DL_RECORD_NOW | info | "Record now, transcribe later" — "Transcription needs a one-time download. Audio is safe meanwhile." | No consent, and Audio-only notes exist | Download |

A failure of a best-effort part never shows a red banner. It shows in Settings. After Done, it shows as TX_ONE_VOICE when the diarizer was missing.

### 13.5 Library and import

| Kind | Tone | Copy | Trigger | Actions |
|---|---|---|---|---|
| LIB_EMPTY | plain | "No notes yet" — "Record something, or import an audio file." | No live notes and no query | Record, Import |
| LIB_NO_MATCH | plain | "Nothing matches “fattura”" — "Try another word or title." | The search found nothing | – |
| IMPORTING | info | "Importing <name>…" (not on the board) | An import operates | – |
| IMPORT_TOO_LARGE | error | "File too large" — "Choose a file under 2 GB." | Rejected(LARGE) | Choose another |
| IMPORT_NO_SPACE | error | "Not enough space to import" — "This file needs about 1.4 GB free. Free some space and try again." | Rejected(SPACE) | Try again |
| IMPORT_WAV | error | "Unsupported WAV" — "Use 16-bit PCM WAV, or MP3, M4A or Opus." | Rejected(WAV) | Choose another |
| IMPORT_OPEN | error | "Can't open this file" — "It doesn't look like audio Cassini can read." (not on the board) | Rejected(OPEN) | Choose another |
| NO_ENCODER | warn | "This phone can't create Cassini files" — "It has no Opus encoder. You can still record (M4A) and open documents." when the capture format is M4A (the copy of the board). "It has no Opus encoder. You can still record and open documents." for the other formats. | `libcassini_opus` did not load (the seal needs it) | The user can dismiss it. |
| NOTIFICATIONS_OFF | info | "Notifications are off" — "Progress shows only in the app." (not on the board) | POST_NOTIFICATIONS denied while a job runs (Library), or always (Settings) | Turn on |
| VOICES_UNREADABLE | error | "Saved voices can't be read" — "They were made by a newer Cassini or are damaged. Nothing was changed." (not on the board) | A voice file is newer or damaged | – |

**Acceptance (all of the catalogue)**: JVM `NoticeCenterTest` makes each row that the app gives from a constructed state. Shot: the five columns of the States board are equal to the crops of the board (ui.md section 10.2). Each kind is in `StateVariantsTest` or in the component strip, in light and dark, and in English and Italian.

## 14. Accessibility (acceptance for each screen)

- Each icon button has a content description (the aria-labels of the boards). Decorative icons have none.
- Headings: the day headers, the section headers, "Draft transcript", and the titles of the sheets and screens.
- Live regions: the progress card and the Library banner (only at stage changes and at 5 % steps), the event banners, the recording status and the snackbar.
- State descriptions: recording/paused, the steps of the stepper, play/pause, the slider ("11:44 of 23:50").
- The app never shows a state by color alone. The chips have text, the trust chips have icons, the active word is inverted, and the search hits and the selected segments are in weight 600.
- Each standalone control has a touch area of at least 48 x 48 dp. The inline word links of the transcript are an exception (the inline exception of WCAG 2.5.8). The Library row suffixes ("· Resume", "· Try again") keep their text size. They get a 48 dp touch area through extended hit testing (ui.md section 5) and a custom action of the row.
- TalkBack gets to each transcript word as a link.
- No text is cut at a font scale of 200 %. The screenshots use 1.5x.
- Reduced motion (animator scale 0): no animation of the waveform, the progress or the pulse.
- The contrast pairs of ui.md section 11. `ContrastTest` enforces them in the two themes.
- Compose accessibility checks (`enableAccessibilityChecks()`, from `ui-test-junit4-accessibility`). P2 found that these checks do not operate under Robolectric. Thus they run in the device flow tests of P10 on an emulator (`flows/ScreenAccessibilityTest`, `flows/AppAccessibilityTest`). A finding fails the test, except for the inline word links, the row suffixes with an extended hit area, and the low contrast of `rec.barIdle` (ui.md section 11).
