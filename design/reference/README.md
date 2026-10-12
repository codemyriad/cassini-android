# Design reference

This directory holds the visual reference for the Cassini 0.2 UI. The binding specification is
[`docs/design/ui.md`](../../docs/design/ui.md) for visuals and
[`docs/design/features.md`](../../docs/design/features.md) for behaviour and copy. These files show what
those documents describe. When a board and the documents disagree, the documents win: ui.md section 11
lists every accepted deviation.

## Contents

- `png/`: renders of the 11 boards. Each phone board is 390 x 844 dp, rendered at 2x (780 x 1688 px).
  The States board (`10-states-and-errors.png`) is 2270 x 1180 dp (4540 x 2360 px).
- `markup/`: the source markup of each board, with the exact inline styles (sizes, colours, spacing).
  Use it to read a value that is hard to measure in the PNG.

| Slug | PNG | Markup |
|---|---|---|
| 00 Library | `png/00-library.png` | `markup/00-Library.html` |
| 01 Recording | `png/01-recording.png` | `markup/01-Recording.html` |
| 02 Transcribing (draft) | `png/02-transcribing.png` | `markup/02-Transcribing.html` |
| 03 Read & listen | `png/03-read-and-listen.png` | `markup/03-Read_and_listen.html` |
| 04 Name a speaker | `png/04-name-a-speaker.png` | `markup/04-Name_a_speaker.html` |
| 05 Model download | `png/05-model-download.png` | `markup/05-Model_download.html` |
| 06 Note actions & export | `png/06-note-actions-and-export.png` | `markup/06-Note_actions_and_export.html` |
| 07 Saved voices | `png/07-saved-voices.png` | `markup/07-Saved_voices.html` |
| 08 Settings | `png/08-settings.png` | `markup/08-Settings.html` |
| 09 Trash | `png/09-trash.png` | `markup/09-Trash.html` |
| 10 States, banners & errors | `png/10-states-and-errors.png` | `markup/10-States_and_errors.html` |

The board goldens in `ui/src/test/screenshots/light/` have these PNG slugs as their names. Thus
`tools/ui-fidelity/compare.py` can pair a board with its golden by file name.

## Fonts

The markup names the fonts by family only (Instrument Sans, IBM Plex Mono). We do not commit the web
font files of the design. They are Google Fonts web-font subsets, and a subset is a Modified Version
under the SIL Open Font License. The app ships the unmodified upstream TTFs (ui.md section 3). To see
the markup as the designer made it, install the two families on your computer.

## Status

- These files are a reference for development. The APK does not contain them, because no Gradle
  source set or resource directory includes `design/`.
- The owner must approve the publication of these files in the public repository.
