# UI fidelity report

This report compares the screenshot goldens of `:ui` with the design boards in `design/reference/png`. It records
the P8 review of the ten phone boards and the five columns of the States board. All boards and columns meet the
advisory targets. It also records the P12 review of the device screenshots (see "Device review"). Each difference
that a reader can see is an accepted deviation or a new deviation. This report gives the reason for each one.

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
- Advisory target: at most 8 % of pixels above 32 for each board, and no band with more than 25 %. CI does not
  use this target. CI compares the goldens only with the committed goldens (`:ui:verifyRoborazziDebug`).
- Accepted deviation N: item N of the list "Accepted deviations from the boards" in ui.md section 11.
- New deviation N: item N of the list "New deviations from the boards" in this report.

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

## Device review

In P12 the reviewer compared each board with the screenshots of the acceptance walk on emulator-5554 (API 34,
1080 x 2340 px, 440 dpi), in light, dark and Italian. The walk of `scripts/acceptance-walk.sh` made the
screenshots on 11 Oct 2026 (`build/acceptance/20261011-031058/`, not committed). After the corrections of this
review, the `realModels` part ran again in light (`build/acceptance/20261011-034439/light/`) and in dark
(`build/acceptance/20261011-034904/dark/`). After the change to the walk in commit `57cc96d`, the full walk passed
again in light, dark and Italian (`20261011-045343`, `20261011-045602` and `20261011-045814`). After the fixes of
phase F1, the full walk passed again in light, dark and Italian (`20261011-065635`). The README and store
screenshots come from this last run. "Acceptance walk runs" in `development.md` lists the runs.

For each board, the reviewer read the design PNG, the golden and the device screenshots with the Read tool, side
by side at the same dp scale. The "Device" part of each board section gives the verdict.

These differences apply to all boards. They are not deviations:

- The emulator screen is 411 dp wide. The boards are 390 dp wide. Thus some lines wrap one word later than on the
  board.
- The device shows the status bar and the gesture bar. The boards and the goldens have no system bars.
- The content comes from the walk: FLEURS sentences in Italian and the fixture names (Anna Ricci, Paolo Ferri,
  "Weekly product sync"). The board content is different.
- The device language is English (United States). Thus the times show in the 12-hour form ("3:11 AM"). The boards
  and the Italian walk use the 24-hour form. The app uses the format of the locale.
- Text rows on the device are about 1.5 dp taller for each line than in the goldens. For example, a Settings
  row is 70 dp high on the device and 67 dp high in the golden. The cause is not known (follow-up 1).

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

### Device

Verdict: the device matches the board. The rows, the chips, the progress banner and the bottom bar show as on the
board. The excerpt preview ended with the ellipsis of `TextOverflow.Ellipsis` after a space ("il …"). The board
excerpt has no space before its ellipsis. F1 corrected this (follow-up 6). At font scale 2.0, the suffix "· No
speech" was cut to "· No". P12 corrected this: the line with the time, the chip and the suffix is now a flow row, so
the suffix goes to the next line. The goldens did not change.

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

### Device

Verdict: the device matches the board. The emulator microphone gives silence, so the waveform shows a row of dots.
At the time of this review (P12), the caption showed the 2-hour form ("Stops at 2 hours, in about 1 h 55 min."),
because the space for the recording was more than the 2-hour limit. In English, "min." went alone to the third line.
F1 corrected this (follow-up 2): a duration does not break at a line end. On 2026-10-11 we removed the 2-hour limit: the
caption always shows the space form ("Space for about 31 h more."). The dark and the Italian screens are the same design.
No text is cut.

## 02 Transcribing

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 7.21 | 5.66 % | 13.18 % (468-520 dp) | every row within 0 to +1 dp |

- The highest band holds the first draft paragraph. The words are a fraction of a pixel narrower than on the
  board. "get the" wraps one word later (accepted deviation 4).
- The arrow in "54% → about 41%" is larger and heavier than on the board. The board draws it with a fallback font.
  This difference is for the owner to review.
- The progress card stays fixed above a scrolling list with the banners, the "Draft transcript" header and the
  turns, as on the board. If the card leaves less than 160 dp for the draft below the top bar (a very short screen
  or a large font scale), the card is the first item of the list. Then the card and the draft scroll together, and
  the draft keeps some space (new deviation 7).
- The first speaker header extends 15.5 dp up into the "Draft transcript" row. As a result, the first turn is in
  the position of the board. Each plain turn after the first is about 7 dp lower than a board turn, because its
  header is 48 dp high (accepted deviation 2). Board 02 shows no such turn.
- The paddings in the suggestion card come from board 02: 7.5 dp above the question, 3 dp below it, and 4 dp at the
  bottom of the card.

### Device

Verdict: the device matches the board and the contract change of new deviation 12. The progress card, the stepper,
the suggestion card ("Is this Paolo Ferri?") and the draft show as on the board. The draft words that can still
change use the unsettled color. In the light screenshot, new draft text came after the screen opened. Thus the list
scrolled to the end, and the progress card went above the top of the list. Then the user did not see the progress.
F1 corrected this (follow-up 3): the card stays fixed, and only the draft scrolls. The dark screenshot shows the
card at the top. In Italian, the tag "Le parole possono ancora cambiare" goes below the header, as in the golden.

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

### Device

Verdict: the device matches the board after one correction. The active word, the speaker header, the chips and
the player show as on the board. With one speaker, "1 speaker" shows next to the Verified chip. The active word
can include its punctuation ("settentrionale,"), because the recognizer gives the punctuation with the word.

The review found two problems. P12 corrected them:

- The player surface stopped above the gesture bar, and the bar showed the `bg` color below it. The navigation-bar
  inset is now padding inside the surface (`PlaybackBar`). The goldens use zero insets, so they did not change.
- At font scale 2.0, the "10" in the skip buttons covered the arrow of the icon. The "10" is now part of the icon
  and does not follow the font scale. The golden `fontscale/03-read-and-listen.png` changed.

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

### Device

Verdict: the device matches the board. The walk opens the sheet on the paused draft, with one speaker. Thus the
list heading is "Saved voices" (no ranking), no row is "Likely", and the footer has no "Merge into…". The
same-person question, Remember and the footer show as on the board. In Italian, all text fits.

The review found one problem in dark. P12 corrected it: the white Check on the dark `accent` (#8FA6FF) had a
contrast of 2.3:1. The Check now uses `surface`, which is white in light (no change) and #181C20 in dark (7.4:1).
The goldens `dark/04-name-a-speaker.png` and `components/forms-dark.png` changed.

With the keyboard open, the sheet went up under the status bar. F1 corrected this (follow-up 4): the top of the
sheet stays below the status bar, and the content of the sheet scrolls.

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

### Device

Verdict: the device matches the board and accepted deviation 7. The copy, the part sizes and the buttons are the board
copy. The dark and the Italian screens fit without cut text.

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

### Device

Verdict: the device matches the board. The sheet shows "Adds version 2", because the note of the walk has one
version. The rows are a little taller than in the golden (see "Device review").

## 07 Saved voices

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 4.83 | 3.61 % | 13.27 % (52-104 dp) | every row within -1.0 to +1.5 dp |

- The highest band holds the privacy line. Its pixels are text anti-aliasing.
- The padding of a voice row is 10/6/9.5/14 dp. The rows with a button measure 67.5 dp on the board.
- The text column has 2 dp and 2.5 dp of vertical padding. As a result, a row is 12 dp taller than its text when
  the text, not the 48 dp button, sets the row height (the row of Luca, with the stale hint).
- The meta line uses a 19.5 dp line box. On the board, it is an inline 13 px span in a 16 px line.

### Device

Verdict: the device matches the board. The walk has two saved voices. Paolo Ferri shows "0 notes", because the walk
saves the same voiceprint two times and the second voice (Anna Ricci) keeps the link to the note. This is a state
of the walk, not a defect.

## 08 Settings

| Mean | Pixels above 32 | Highest band | Rows |
|---|---|---|---|
| 5.47 | 3.89 % | 11.96 % (676-728 dp) | every row within -1.0 to +1.5 dp |

- The highest band holds the About card. "timed" wraps to line 2 (accepted deviation 4).
- The subtitle of a settings row uses the 19.5 dp line box of the 16 px parent on the board.
- The helper below the language control uses the normal caption line height, because the board sets no line height
  there. The helper below the models keeps a line height of 1.4.

### Device

Verdict: the device matches the board. All three models show Ready, so no row shows "Downloads with next
transcription". In Italian, the language helper wraps to two lines. At font scale 2.0, all text fits (see
"Acceptance 0.2" in `development.md`).

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

### Device

Verdict: the device matches the board and accepted deviation 11. The walk has one note in the trash, so the screen shows
one card and no snackbar.

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

### Device

The walk shows nine states on the screens where the app shows them:

| State | Screenshot | Verdict |
|---|---|---|
| Paused | `10-states-paused.png` | Copy as the board. In the first walk the screenshot came before the draft loaded. The walk now waits for the draft. |
| Not verified | `10-states-not-verified.png` | Copy and action as the board |
| Transcript can't be read | `10-states-unreadable.png` | Copy and action as the board |
| Storage almost full | `10-states-storage-low.png` | Copy as the board, on Recording |
| Downloading | `10-states-downloading.png` | Copy as the board and accepted deviation 8. The card shows "Starting…" and 0 %, as the golden `variants/transcribing-downloading.png`. |
| Checking files | `10-states-checking.png` | Copy as the board |
| No connection | `10-states-no-connection.png` | Copy and action as the board |
| Not enough storage | `10-states-no-space.png` | Copy and action as the board |
| File check failed | `10-states-file-check.png` | Copy and action as the board |

The download screens show "294 of 640 MB", because the script of the walk gives 294 MB. All banners keep their
tone in dark. In Italian, no text is cut.

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
7. On Transcribing, the progress card stays fixed above the draft list while it leaves 160 dp or more for the
   draft. If the card is taller, it is the first item of the list. The first draft header extends 15.5 dp up into
   the "Draft transcript" row.
8. The suggestion card paddings come from board 02 (7.5 / 3 / 4 dp).
9. A zero-width space comes before each mono file extension, so that a line can break before ".opus".
10. The Read pane has a top padding of 0.5 dp, and the paragraph is 1.5 dp below the speaker header.
11. Large text: the search placeholder can use two lines, the Library Import button becomes an icon when the two
    buttons do not fit, the Record label can wrap, the Name sheet footer and the draft header are flow rows, and
    stage labels become smaller until their longest word fits. At large font scales the stage tracker uses more
    rows. The Library list and its snackbar use the measured height of the bottom bar, because the bar becomes
    taller when the Record label wraps.
12. The draft of a running transcription does not scroll when the screen opens. When new text comes, the draft
    list scrolls to the end. Only the list below the fixed progress card scrolls, so the card stays in view (new
    deviation 7). A scroll that stops above the end pauses this, and a scroll back to the end starts it again
    (features.md section 3.1).
13. The Check of `CassiniCheckbox` uses `surface`, not white (ui.md section 6). In light, `surface` is white, so
    nothing changes. In dark, a white Check on `accent` (#8FA6FF) has a contrast of 2.3:1, and `surface` gives
    7.4:1 (P12, board 04).
14. The `PlaybackBar` surface extends below the navigation bar. The inset is padding inside the surface.
15. The "10" in the skip buttons does not follow the font scale, because it is a part of the 28 dp icon.
16. In a Library row, the line with the time, the chip and the suffix is a flow row. At a large font scale, the
    suffix goes to the next line.
17. A prose duration ("1 h 55 min", "under 1 min") has no-break spaces (U+00A0) in place of its spaces, in each
    language (`Durations`). The time-left texts keep the number and its unit together ("6–9 min", "Under 1 min
    left"). Thus a line does not break inside a duration. The golden `variants/recording-cap-hint.png` changed:
    "1 h 35 min." is now on one line. (On 2026-10-11 we removed that golden with the 2-hour limit.
    `variants/recording-long-hint.png` shows a long space hint.) The text of the processing notification does not use these spaces. Now in
    the spec: features.md section 0.
18. The title and the excerpt of a Library row do not show a space before the ellipsis (`EllipsisText`). When the
    ellipsis of `TextOverflow.Ellipsis` comes after a space, the row shows the text before the space and "…".
19. The top of a sheet (`CassiniSheet`) stays below the status bar and the display cutout. When the keyboard is
    open and the content is taller than the space, the content scrolls.
20. A document with no words shows a plain card in the transcript pane: "No speech" with "No words were found in
    this recording." The text comes from the TXT export (`export_no_words`). The golden `variants/read-no-words.png`
    shows it. features.md has no copy for this state (see "Items for the owner to review"). Now in the spec:
    features.md section 3.3, ui.md section 8 (Read & listen) and the table of ui.md section 12.
21. When Android stops a standalone model download (`onTimeout`), DL_STOPPED has the body "Android stopped the
    download. Try again to continue where it stopped." ("Android ha interrotto il download. Riprova per continuare
    da dove si è fermato."), not "The connection dropped." The error is `STOPPED_BY_SYSTEM`. features.md has no
    copy for this case (see "Items for the owner to review"). Now in the spec: features.md section 13.4 and the
    table of ui.md section 12.
22. In Settings, a model row with "Download failed" opens Model download, as a "Not downloaded" row does.
    features.md section 11 names only the "Not downloaded" row. architecture.md section 5.7 step 6 sends the user
    to this row after Android stops a download. The text stays "Download failed — tries again next time", as on the
    board. But nothing tries again in the background: the next transcription that needs the model, or a tap on
    the row, starts the download again (see "Items for the owner to review"). Now in the spec: features.md
    section 11.
23. A recording has no time limit (2026-10-11, architecture.md section 3.7). We removed REC_NEAR_LIMIT and the "Stops
    at 2 hours" hint. REC_STOPPED_FILE_LIMIT is new (not on the board): it has the tone, the layout and the action of
    REC_STOPPED_STORAGE. The goldens `variants/recording-stopped-file-limit.png` and `components/banners-it-1.png`
    show it. TX_PAUSED and TX_PAUSED_EARLY have a second body for a pause by Android (`BannerArgs.bySystem`). The
    States board column 5 shows "Choose a file under 4 GB." (accepted deviation 15 of ui.md section 11).

## Changes to the screen contracts

These changes are in addition to ui.md sections 1 and 7. P10 used them when it wrote the ViewModels and the
navigation. Each one has its reason in the KDoc. This list names the changes that a reader of ui.md can miss. The
full contract is the code: read the KDoc of each `UiState`, `Event`, `Sheet` and `Dialog` type in `:ui` before you
write or change its ViewModel.

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
19. Each screen limits its content to 560 dp and centres it on a wide window. The background of the screen fills
    the window (architecture.md section 6). `:app` must not add a second limit.
20. `NoteUiState` has `noWords`. The ViewModel sets it when the sealed version has loaded and has 0 words. The
    transcript pane shows the "No speech" card only when `noWords` is true. Thus the pane does not show the card
    while the transcript loads, and not for a transcript that cannot be read (new deviation 20).

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
- The empty state of a document with no words (new deviation 20). features.md and ui.md have no copy for this
  state, and F1 did not change them, because the two files are read-only in the worktree. The owner must accept
  the copy "No speech" — "No words were found in this recording." ("Nessun parlato" — "Non sono state trovate
  parole in questa registrazione.") and add it to features.md section 3.3 and to the table of ui.md section 12.
  Now in the spec: features.md section 3.3 and the table of ui.md section 12 have this copy.
- The DL_STOPPED body after a stop by Android (new deviation 21). features.md section 13.4 gives DL_STOPPED only
  for network failures. The owner must accept the copy and add it to features.md. Now in the spec: features.md
  section 13.4 has this copy.
- The "Download failed" model row in Settings (new deviation 22). It opens Model download, but features.md
  section 11 does not say so. The text "tries again next time" does not tell the user to tap the row. The owner
  must decide if the row stays a button, and if the text changes, for example to "Download failed — tap to try
  again". Then features.md section 11 must agree. Now in the spec: features.md section 11 says that the row opens
  Model download, with the text of the board. The question about the text stays open.
- The no-break spaces of new deviation 17 are not in features.md section 0. The owner must decide if section 0
  names them, and if the processing notification also uses them. Now in the spec: features.md section 0 names
  them, and says that the processing notification does not use them. The question about the notification stays
  open.

## Device follow-ups

The device review of P12 found these items. F1 examined each item again. "Resolved" items have a correction and a
test. "Open" items need more work.

1. Resolved, no defect. Text rows on the device seemed about 1.5 dp taller for each line than in the goldens. The
   cause is the scale of the measurement, not the line height. emulator-5554 has 440 dpi (2.75 px for each dp),
   not 420 dpi (2.625 px). At 420 dpi, each row seems 4.8 % taller: about 1.5 dp for a 31 dp row. F1 scaled the
   Settings screenshot of `20261011-045343/light` to 2 px for each dp at 440 dpi and compared it with `rows.py`.
   From "Transcription runs on this phone" to the end of the screen (11 rows), the offset changes by 1 dp in
   total. At 420 dpi, the same rows change by 16.5 dp. The small remaining drift comes from Android: it rounds
   each line up to a full pixel, and 19.5 sp is 53.6 px at 2.75 px for each dp. `includeFontPadding` is false
   (the default of Compose), and F1 changed no line height.
2. Resolved. A duration does not wrap between the number and its unit (new deviation 17). `FormatTest` and
   `FormatItalianTest` cover the no-break spaces.
3. Resolved. On Transcribing, the progress card stays fixed above the draft, and new text scrolls only the draft
   list (new deviations 7 and 12). `ScreenBehaviourTest.newDraftTextKeepsTheProgressCardInView` covers this at
   390x844 dp, 411x731 dp (the pixel_2 profile of the CI emulators) and 360x640 dp. Without the fixed card, these
   tests fail. Before round 1 of F1, the card was fixed only while it used half of the space or less, and the
   tests at 411x731 dp and 360x640 dp failed. On emulator-5554,
   `SheetAndDraftDeviceTest.newDraftTextKeepsTheProgressCardInView` showed the full card and the newest turn after
   new text came. The same test passed with the display set to 1080x1920 px at 420 dpi (731 dp high, as pixel_2).
   The goldens of board 02 did not change.
4. Resolved. On Name a speaker, the top of the sheet stays below the status bar when the keyboard is open (new
   deviation 19). `ModalBottomSheet` of Material 3 1.4 already moves the sheet above the keyboard. Thus
   `CassiniSheet` no longer adds its own IME padding. On emulator-5554,
   `SheetAndDraftDeviceTest.theNameSheetStaysBelowTheStatusBarWithTheKeyboardOpen` opened the keyboard. The top of
   the sheet was at 136 px, the bottom of the status bar. Without the correction, the same test found the top at
   0 px. The goldens did not change, because they draw the sheets inline. The test also checks that the name
   field stays above the keyboard. It reads the keyboard inset from the sheet's own dialog window, and it waits
   until that inset is above 0. Three runs on emulator-5554 (API 34) passed: keyboard 833 px, field bottom 595 px,
   window 2340 px. The CI emulators at API 26 and 29 did not run this check yet. Below API 30 the dialog window
   resizes for the keyboard, so the inset is available there too.
5. Resolved, with an owner question. A document with no words shows "No speech" on the Note screen (new deviation
   20, contract change 20). `ScreenBehaviourTest.aDocumentWithNoWordsSaysSo` and
   `NoteViewModelTest.aDocumentWithNoWordsSaysSoOnlyWhenItsTranscriptHasLoaded` cover this. The new golden is
   `variants/read-no-words.png`. The copy is not in features.md (see "Items for the owner to review"). Now in the
   spec: features.md section 3.3.
6. Resolved. The Library title and excerpt do not show a space before the ellipsis (new deviation 18).
   `EllipsisTextTest` covers this at 101 widths. The Library goldens did not change, because no fixture puts the
   ellipsis after a space.
7. Open. The emulator microphone gives silence. Thus the review did not see the waveform bars move, or stay static
   with the animator duration scale at 0. Do this check on a phone, together with the pre-release gate.
