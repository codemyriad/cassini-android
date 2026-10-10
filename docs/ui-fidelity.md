# UI fidelity report

This report compares the screenshot goldens of `:ui` with the design boards in `design/reference/png`. It records
the P8 review of the ten phone boards and the five columns of the States board. All boards and columns meet the
advisory targets. The differences that a reader can see are accepted deviations or new deviations, and this report
gives the reason for each one.

The rules for the review are in `docs/design/ui.md` sections 10.3 and 11, and in `tools/ui-fidelity/README.md`.

## How to make the figures again

The figures use only the committed goldens in `ui/src/test/screenshots/light` and `ui/src/test/screenshots/states`.
Do not record the goldens again to make the figures. If you must record them, do it only on Linux x86_64 (see the
root `README.md` and "Screenshot tests" in `docs/development.md`).

1. Compare the phone boards: `python3 -I tools/ui-fidelity/compare.py`.
2. Cut the States references: `python3 -I tools/ui-fidelity/crop_states.py --top-margin 3`.
3. Compare the States columns:
   `python3 -I tools/ui-fidelity/compare.py --design build/ui-fidelity/states --golden ui/src/test/screenshots/states`.
4. For vertical offsets, use `rows.py`, for example:
   `python3 -I tools/ui-fidelity/rows.py design/reference/png/00-library.png ui/src/test/screenshots/light/00-library.png --x0 32 --x1 700`.

The golden of a States column starts at the top of the line box of the heading. The glyphs of the heading start
3 dp (6 px) lower. `--top-margin 3` moves the top of the crop up by the same distance, so that the two images align.

## Terms

- Mean: the mean difference of all pixels, in levels from 0 to 255. The difference of a pixel is the largest
  difference of its three channels.
- Pixels above 32: the percentage of pixels with a difference of more than 32 levels.
- Band: a horizontal strip of the image, 52 dp high. "Highest band" is the band with the most pixels above 32.
- Rows: the vertical offset of each text row of the golden against the board, from `rows.py`. A positive offset
  is lower than the board.
- Advisory target: at most 8 % of pixels above 32 for each board, and no band above 25 %. CI does not use this
  target. CI compares the goldens only with the committed goldens (`:ui:verifyRoborazziDebug`).
- Accepted deviation N: item N of the list "Accepted deviations from the boards" in ui.md section 11.

## Summary

| Board | Mean | Pixels above 32 | Highest band | Rows | Verdict |
|---|---|---|---|---|---|
| 00 Library | 5.26 | 4.19 % | 9.79 % (104-156 dp) | -1.0 to +1.0 dp | Meets the target |
| 01 Recording | 2.38 | 1.98 % | 7.05 % (520-572 dp) | 0 to +1.0 dp | Meets the target |
| 02 Transcribing | 7.21 | 5.66 % | 13.18 % (468-520 dp) | 0 to +1.0 dp | Meets the target |
| 03 Read and listen | 7.70 | 5.14 % | 16.62 % (364-416 dp) | -1.0 to +2.0 dp | Meets the target |
| 04 Name a speaker | 4.83 | 3.51 % | 9.58 % (572-624 dp) | -0.5 to +1.5 dp | Meets the target |
| 05 Model download | 4.81 | 3.70 % | 11.67 % (208-260 dp) | -1.0 to +1.5 dp | Meets the target |
| 06 Note actions and export | 3.82 | 2.76 % | 9.06 % (416-468 dp) | -0.5 to +0.5 dp | Meets the target |
| 07 Saved voices | 4.83 | 3.61 % | 13.27 % (52-104 dp) | -1.0 to +1.5 dp | Meets the target |
| 08 Settings | 5.47 | 3.89 % | 11.96 % (676-728 dp) | -1.0 to +1.5 dp | Meets the target |
| 09 Trash | 3.54 | 2.70 % | 15.74 % (52-104 dp) | -1.0 to +1.5 dp | Meets the target |
| 10 States, column 1 | 8.48 | 5.70 % | 12.05 % (416-468 dp) | -0.5 to +2.0 dp | Meets the target |
| 10 States, column 2 | 10.45 | 7.02 % | 16.33 % (728-780 dp) | -0.5 to +2.0 dp | Meets the target |
| 10 States, column 3 | 8.04 | 5.70 % | 14.33 % (520-572 dp) | -0.5 to +2.0 dp | Meets the target |
| 10 States, column 4 | 7.45 | 5.37 % | 13.10 % (676-728 dp) | -0.5 to +1.5 dp | Meets the target |
| 10 States, column 5 | 10.00 | 6.46 % | 14.57 % (676-728 dp) | 0 to +2.5 dp | Meets the target |

Each phone golden is 780 x 1688 px, the same size as its board. Most of the pixels above 32 are text
anti-aliasing and text that is a fraction of a pixel wider or narrower than on the board (accepted deviation 4).

For each board, the reviewer read the design PNG, the golden and the 50/50 blend with the Read tool. The colors
are the tokens of ui.md section 4.1. The type is Instrument Sans and IBM Plex Mono in the sizes and weights of
ui.md section 4.3. The icons are the icons of ui.md section 4.5. The copy is the board copy, word for word, except
where a deviation in this report says otherwise.

## 00 Library

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 5.26 | 4.19 % | 9.79 % (104-156 dp) | every row within ±1 dp |

- The highest band holds the search field and the privacy line. Its pixels are text anti-aliasing.
- The excerpt preview of "Call with supplier" wraps "stays" one word earlier than the board (accepted deviation 4).
- Line heights snap to the 2x pixel grid of the boards (`Type.kt`). Without this, the rows drift down by 5.5 dp
  at the last row.
- The search field is 54 dp high, not the 52 dp of ui.md section 6. The board has a CSS content-box border, so the
  board draws the field 54 dp high.
- The status chips have 0.5 dp less top padding, and the day headers have a bottom padding of 5.5 dp. Android sets
  one 13 sp line in 16 dp. The browser uses 15.5 dp.

## 01 Recording

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 2.38 | 1.98 % | 7.05 % (520-572 dp) | every row within 0 to +1 dp |

- The highest band holds the waveform caption. Its pixels are text anti-aliasing.
- The "Title" label is 3 dp lower than a plain 13 sp line. On the board, the label sits inline in a 16 px line box.
- The status ("Recording") sits between the chevron and the format badge, at about 168 dp, as on the board. It is
  not at the center of the screen (195 dp), which ui.md section 8 gives.
- The status shows only in the Recording and Paused phases. The board does not show the other phases. This is a
  decision of P8 (accepted deviation 14).

## 02 Transcribing

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 7.21 | 5.66 % | 13.18 % (468-520 dp) | every row within 0 to +1 dp |

- The highest band holds the first draft paragraph. The words are a fraction of a pixel narrower than on the
  board. "get the" wraps one word later (accepted deviation 4).
- The arrow in "54% → about 41%" is larger and heavier than on the board. The board draws it with a fallback font.
  This difference is for the owner to review.
- The card, the banners, the "Draft transcript" header and the turns are in one scrolling list. The board shows a
  fixed card above a scrolling draft. With a fixed card, a large font scale or a short screen gives no space to the
  draft.
- The first speaker header extends 15.5 dp up into the "Draft transcript" row. As a result, the first turn is in
  the position of the board. Each plain turn after the first is about 7 dp lower than a board turn, because its
  header is 48 dp high (accepted deviation 2). Board 02 shows no such turn.
- The paddings in the suggestion card come from board 02: 7.5 dp above the question, 3 dp below it, and 4 dp at the
  bottom of the card.

## 03 Read and listen

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 7.70 | 5.14 % | 16.62 % (364-416 dp) | turn 1 +2.0 dp, other rows -1.0 to +1.5 dp |

- The highest band holds the active word "quindi". The app draws the background of the active word and does not
  pad the word. As a result, "giovedì," fits on line 2 and the text after "quindi" does not move (accepted
  deviation 3).
- Turn 1 is 2 dp lower than on the board (accepted deviation 6).
- The paragraph is 1.5 dp below the speaker header, and the transcript pane has a top padding of 0.5 dp. These
  values come from board 03 (ui.md gives 0).
- The chips row extends 4 dp into the layout of the top bar, and its rows have no gap. As a result, the 48 dp
  chips keep the pixels of the board.

## 04 Name a speaker

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 4.83 | 3.51 % | 9.58 % (572-624 dp) | every row within -0.5 to +1.5 dp |

- The highest band holds the same-person question. Its pixels are text anti-aliasing.
- Save name keeps its enabled look while the same-person question is open. A press does not save. The app tells
  the user to answer the question first (accepted deviation 12).
- The Name field has 3 dp of top padding. On the board, the 13 px label sits in a 16 px line box.
- The bottom padding of the sheet is 25 dp, not 24 dp. Cancel has a content-box border on the board, so the board
  draws it 50 dp high, and Save name is at the center of that row.
- The checkmark of "Remember this voice on this phone" is thinner than on the board. This difference is for the
  owner to review.

## 05 Model download

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 4.81 | 3.70 % | 11.67 % (208-260 dp) | every row within -1.0 to +1.5 dp, except the added row |

- The highest band holds the title and the body. Its pixels are text anti-aliasing.
- The app adds the row "Where the models come from · Privacy" below the footnote (accepted deviation 7). No other
  pixel moves.
- The title gives back 1 dp above and 1 dp below. Android makes a 28 sp line about 2 dp taller than the line box of
  the board (32.2 dp). Without this change, the body and the card are 2 dp low, and the rows drift to +7 dp.
- The rows of the model parts have a vertical padding of 13.5 dp. Android sets the 15 sp Plex Mono line 0.5 dp
  taller than the browser.

## 06 Note actions and export

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 3.82 | 2.76 % | 9.06 % (416-468 dp) | every row within ±0.5 dp |

- The highest band holds the featured row "Cassini document". Its pixels are text anti-aliasing.
- The top padding of the content is 10.5 dp and the gap below the title is 3.5 dp. The board gives 12 dp and
  2 dp. Android sets the glyphs of an 18 sp line 1.5 dp lower than the browser.
- The caption of the featured row uses a line box of 19.5 sp, not 18.2 sp. On the board, the 13 px lines sit inline
  in the 16 px font. This caption also uses `LineBreak.Simple`.
- The app puts a zero-width space before each mono file extension. Unicode line breaking (UAX 14, rule LB13) never
  breaks a line before ".". Without the space, "anywhere." moves to the next line with ".opus".

## 07 Saved voices

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 4.83 | 3.61 % | 13.27 % (52-104 dp) | every row within -1.0 to +1.5 dp |

- The highest band holds the privacy line. Its pixels are text anti-aliasing.
- The padding of a voice row is 10/6/9.5/14 dp. The rows with a button measure 67.5 dp on the board.
- The text column has 2 dp and 2.5 dp of vertical padding. As a result, a row is 12 dp taller than its text when
  the text, not the 48 dp button, sets the row height (the row of Luca, with the stale hint).
- The meta line uses a 19.5 dp line box. On the board, it is an inline 13 px span in a 16 px line.

## 08 Settings

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 5.47 | 3.89 % | 11.96 % (676-728 dp) | every row within -1.0 to +1.5 dp |

- The highest band holds the About card. "timed" wraps to line 2 (accepted deviation 4).
- The subtitle of a settings row uses the 19.5 dp line box of the 16 px parent on the board.
- The helper below the language control uses the normal caption line height, because the board sets no line height
  there. The helper below the models keeps a line height of 1.4.

## 09 Trash

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 3.54 | 2.70 % | 15.74 % (52-104 dp) | every row within -1.0 to +1.5 dp |

- The highest band holds the intro text. Its pixels are text anti-aliasing.
- The snackbar "moved to trash" over Trash is a state of the fixture only. The app shows this snackbar on the
  Library (accepted deviation 11).
- The bottom padding of the intro is 5 dp. Android makes three 20.5 dp lines, and the board makes three 20.3 dp
  lines.
- The top padding of a card is 14 dp, not 15 dp, and the top margin of the meta line is 2.5 dp. Android sets the
  16 sp title about 1 dp lower in its line.
- "Empty" has 2 dp more end padding. The 6 dp compensation of the top bar is for 44 dp icon buttons.

## 10 States, banners and errors

`StatesBoardTest` makes one golden for each column: `states/10-states-col1.png` to `col5.png`. Each golden is
358 dp wide, on `boardBg`, with the column heading (`overline`, text3, uppercase, 4 dp bottom margin) and the
banners 12 dp apart. The test captures the column node, so the height is the content height.

| Column | Mean | Pixels above 32 | Highest band | Height against the crop | Rows |
|---|---|---|---|---|---|
| 1 Document trust | 8.48 | 5.70 % | 12.05 % (416-468 dp) | +2.0 dp | -0.5 to +2.0 dp |
| 2 Recording | 10.45 | 7.02 % | 16.33 % (728-780 dp) | +1.5 dp | -0.5 to +2.0 dp |
| 3 Transcription | 8.04 | 5.70 % | 14.33 % (520-572 dp) | +1.5 dp | -0.5 to +2.0 dp |
| 4 Model download | 7.45 | 5.37 % | 13.10 % (676-728 dp) | +1.5 dp | -0.5 to +1.5 dp |
| 5 Library and import | 10.00 | 6.46 % | 14.57 % (676-728 dp) | +2.0 dp | 0 to +2.5 dp |

- The banner bodies are about 1 % wider than on the board. Thus "timing" in "Not verified" wraps to line 2 one
  word earlier (accepted deviation 4).
- The rows drift down by up to 2.5 dp at the end of column 5. This is a new deviation. The line height of the
  banner body is 14 sp x 1.45 = 20.3 dp on the board. `Type.kt` snaps it to the pixel grid as 20.5 dp. Each body
  line thus adds 0.2 dp. The drift is more than 2 dp only in the last banner of column 5.
- The download banner does not say "Keep Cassini open." (accepted deviation 8). The NO_ENCODER banner keeps "(M4A)"
  in the fixture.
- TX_CALCULATING and TX_BATTERY are cards of the catalogue only (accepted deviation 9).
- RECOVERED and NO_ENCODER show no close button, as on the board (accepted deviation 10).

## Goldens without a board

These goldens have no design to compare with. The reviewer read each one with the Read tool. They wait for the
review of the owner (accepted deviation 14).

### Dark theme (`dark/`)

`DarkScreenshotTest` records the ten phone boards and the five States columns in the dark theme. The reviewer found
no clipped text and no part that overlaps another part. The Recording screen uses its dark palette in both themes,
so `dark/01-recording.png` is the same design as the light golden. The snackbar on Trash is light on the dark
screen.

### Italian (`it/`)

`LocaleScreenshotTest` records Library, Transcribing, Read and listen, Name a speaker, Note actions and Settings
with the `+it` qualifier and `Locale.ITALIAN`. The strings come from `values-it`. The content of the fixtures
(titles, transcript words, names) stays in the language of the boards.

The first review found two problems. P8 step 6 corrected them:

- The labels of the stage tracker broke inside a word ("Preparazion/e", "Abbinamen/to"). A label now becomes
  smaller until its longest word fits on one line of its column (`fitLongestWord` in `StageTracker.kt`).
- The tag "Le parole possono ancora cambiare" was cut at the end. The draft header is now a flow row, so the tag
  goes below "Bozza della trascrizione" when the two do not fit on one line. The tag can also wrap.

After these corrections, the reviewer found no clipped text in the six Italian goldens. In Settings, the row title
"Riconoscimento delle voci salvate" wraps to three lines and the status wraps to two lines.

`BannerItalianScreenshotTest` records every banner kind in Italian, in three strips
(`components/banners-it-{1,2,3}.png`). The reviewer read the three strips and found no clipped text.

### Font scale 1.5 (`fontscale/`)

`FontScaleScreenshotTest` records Library, Read and listen, Name a speaker and Settings at font scale 1.5. Name a
speaker and Settings go below the bottom of the screen, so each one has a second golden (`-end`) that the test
scrolls to the last part.

The first review found four problems. P8 step 6 corrected them:

- The placeholder of the search field was cut ("Search titles and what was"). The placeholder can now use two
  lines, and the field becomes taller. The field has 8 dp of padding above and below the text. At font scale 1
  the field stays 54 dp high.
- The Record button showed "Rec…". When "Import audio" and "Record" do not fit side by side at full width, Import
  becomes the 52 dp round icon button. This is the same button that the bar shows while the app records. The
  label of the Record button can now wrap to two lines.
- In the footer of Name a speaker, "Save name" became a column of letters. The footer is now a flow row: Cancel and
  Save name go below "Merge into…", and they can also go below each other.
- The labels of the stage tracker can break inside a word at large font scales too. Above font scale 1.15 a label
  becomes smaller only to 85 % of its size. If the labels then do not fit in five columns, the steps go on more
  rows (3 + 2, then pairs). Thus the labels keep the font scale of the user.

After these corrections, the reviewer found no clipped text. The rows become taller and the screens scroll. These
items end with an ellipsis, as the design specifies for long user content: the title in the progress banner
("Transcribing Weekly prod…") and the second line of the excerpt preview in the Library.

### Wide window (`wide/`)

`WideScreenTest` records Library, Recording and Read and listen in a window of 900 x 700 dp. Each screen limits
its content to 560 dp and centres it (architecture.md section 6). The screen background (bg, or rec.bg on
Recording) fills the window. The transcript pane and the player of Read and listen are surface parts of the
content, so they also stop at 560 dp. The test also checks, on eight board screens, that every text is inside the
centred 560 dp band. The sheets of boards 04 and 06 are not part of this check.

## New deviations from the boards

These deviations are in addition to the list in ui.md section 11. Each one has its reason in the section of its
board, and in KDoc or a code comment.

1. Line heights snap to the 2x pixel grid (`Type.kt`). For example, 16 sp normal is 19.5 sp, not 19.52 sp. The
   banner body is 20.5 sp, not 20.3 sp, so the States columns drift by up to 2.5 dp.
2. Content-box borders on the boards: the search field is 54 dp high, and the Name sheet has a bottom padding of
   25 dp.
3. Android sets one 13 sp line in 16 dp, and the browser uses 15.5 dp. Some paddings are 0.5 dp less to compensate:
   status chips, day headers.
4. Inline 13 px spans in 16 px lines on the boards: the Recording "Title" label is 3 dp lower, the Name field has
   3 dp of top padding, and settings subtitles, voice meta lines and the featured caption use 19.5 dp line boxes.
5. Android sets large lines a little taller or lower than the browser: the Model download title gives back 1 dp
   above and below, the model rows have 13.5 dp padding, the Note actions sheet has 10.5 dp top padding and a
   3.5 dp title gap, and the Trash cards have 14 dp top padding.
6. The Recording status sits between the chevron and the badge, as on board 01, not at the center of the screen.
7. Transcribing is one scrolling list. The first draft header extends 15.5 dp up into the "Draft transcript" row.
8. The suggestion card paddings come from board 02 (7.5 / 3 / 4 dp).
9. A zero-width space comes before each mono file extension, so that a line can break before ".opus".
10. The Read pane has a top padding of 0.5 dp, and the paragraph is 1.5 dp below the speaker header.
11. Large text: the search placeholder can use two lines, the Library Import button becomes an icon when the two
    buttons do not fit, the Record label can wrap, the Name sheet footer and the draft header are flow rows, and
    stage labels become smaller until their longest word fits. At large font scales the stage tracker uses more
    rows. The Library list and its snackbar use the measured height of the bottom bar, because the bar becomes
    taller when the Record label wraps.
12. The draft of a running transcription does not scroll when the screen opens, so the progress card stays in view.
    When new text comes, the list scrolls to the end. A scroll that stops above the end pauses this, and a scroll
    back to the end starts it again (features.md section 3.1).

## Changes to the screen contracts

These changes are in addition to ui.md sections 1 and 7. P10 must use them when it writes the ViewModels and the
navigation. Each one has its reason in the KDoc. This list names the changes that P10 can miss. The full contract
is the code: read the KDoc of each `UiState`, `Event`, `Sheet` and `Dialog` type in `:ui` before you write its
ViewModel.

1. `:ui` has `activity-compose` as a main dependency, not only for previews (ui.md section 1). It supplies
   `BackHandler`. Three screens use it: Library (back clears the search), Note (back closes the in-note search) and
   Licenses (back closes the text pane). Each handler is enabled only while its search or pane is open.
2. The Library shows the full Note actions sheet. Thus `LibraryUiState.sheet` is a `LibrarySheet`: `Actions`,
   `SubtitleFormat` or `Info` (Info shows as a dialog). `LibraryDialog` has `TranscribeFresh`, and `LibraryEvent`
   has `Export(id, kind)`. ui.md section 7 gives only the actions sheet and Rename.
3. The Recording screen does not handle system back. The navigation in `:app` must pop `RecordingKey` with the
   predictive pop of ui.md section 9, and that pop minimises the recording. The chevron and the Library button
   send `Minimise` and `OpenLibrary`.
4. The text fields (Library search, in-note search, Recording title, the Name field) keep their own copy of the
   text and send each change as an event (`SyncedText` in `:ui`). A state text that the field sent and that the
   state has not passed yet does not change the field. Any other state text replaces the field text, for example
   Clear. Thus the ViewModels can use the `StateFlow` of architecture.md section 6. The fields apply the length
   limits themselves (title 200, name 80) and never send a longer text. A ViewModel must not cut the text: when
   the field is full, the cut text is equal to the state, so the state does not change and the field does not
   follow it.
5. `LicensesUiState.backTo` (Settings or Model download) selects the label of the back button.
6. `MergeUi.sealed` holds the version on screen and the version that the merge adds. On version 1 of 2, the merge
   adds version 3.
7. `VersionRowUi.withYear` adds the year to the date of a version from an earlier year.
8. `NoteEvent` has no `Resume`, `StartOver`, `Rename`, `Transcribe`, `TranscribeAgain` or `Trash`. These actions
   come as `SheetAction` or `BannerAction`.
9. `BannerSpec.id` must be unique in one banner list, because the `BannerAction` and `DismissBanner` events carry
   only the id. The id defaults to the kind. When two banners of one kind can show together, the ViewModel sets a
   per-notice id. The list key also adds the position, so a duplicate does not crash the list.
10. `LibraryDialog` also has `StartOver(id)` and `StopAndTrash(id)`. `LibraryEvent` also has `Rename(id)`,
    `Trash(id)`, `SnackbarTimeout`, `ConfirmDialog` and `DismissDialog`.
11. `NoteRowUi` has `day: DayLabel?` in place of `date: LocalDate?`.
12. `NoteUiState` has `noteId` and `snackbar`. `ReadOnlyReason` also has `SAVING`. `TranscriptSearchUi` has
    `matches`. `NoteDialog` (not in ui.md) is `Rename`, `StartOver`, `TranscribeFresh`, `StopAndTrash` or
    `ReadOnly(reason)`. `NoteEvent` also has `PlayTurn`, `SeekFinished`, `PauseFollowing`, `TrustChip`,
    `RenameConfirmed`, `ConfirmDialog`, `Merge(event)`, `DismissBanner` and `SnackbarTimeout`.
13. `NoteActionsUi` has `noteId`, and `recordedDay: DayLabel` with `recordedAt: LocalTime` in place of one
    `recordedAt`.
14. `MergeUi` has `sourceId` and `source` (speaker A) and `sealed` in place of `sealedVersion` (change 6).
    `MergeEvent` is `PickTarget(speakerId)`, `Confirm` or `CancelConfirm`.
15. `NameSheetEvent` has `SaveBlocked`: a Save press while a question is open.
16. `ModelDownloadUiState` has no total. The screen shows the sum of the rounded part sizes (features.md
    section 0).
17. The Library list and the Note screen with its search open add the IME inset (ui.md section 9). `:app` must
    set `android:windowSoftInputMode="adjustResize"` on the activity, so that the keyboard insets arrive.
18. The Note screen does not read `NoteUiState.readOnlyReason`. The speaker headers, the suggestion card buttons
    and the version chip stay active while the transcript is read-only, and send their events. The ViewModel
    answers a blocked event with `NoteDialog.ReadOnly(reason)`, which shows "Wait until transcription finishes",
    "Fix the transcript first: Transcribe again" or "Saving — you can rename when it's done". Thus
    `TurnUi.renamable` must stay true while a rename is only blocked (features.md sections 3.1 and 3.3). When it
    is false, the header is a plain label and the user gets no message.
19. Each screen limits its content to 560 dp and centres it on a wide window; the screen's background fills the
    window (architecture.md section 6). `:app` must not add a second limit.

## Items for the owner to review

- The arrow in the battery cell of board 02 is larger and heavier than on the board.
- The checkmark of the checkbox on board 04 is thinner than on the board.
- All goldens without a board: `dark/`, `it/`, `fontscale/`, `wide/` and `variants/`.
- The notifications banner in Settings. features.md section 11 gives the body "Progress of long transcriptions
  shows only in the app." features.md section 13.5 gives "Progress shows only in the app." for the same kind. The
  screen uses the catalogue kind NOTIFICATIONS_OFF, with the short body, on the Library and in Settings.
  The owner must select one text.
- The Recording screen, the Library and the Note screen depend on back handling in `:app` (see the contract
  changes 1 and 3). The owner of ui.md must decide if section 1 changes to name `BackHandler`.
