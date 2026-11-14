# L-10 on Android: Imported provenance (D-1035)

Decision L-10 (cassini-ux `D99`; `PARITY.md`, "Phase 2 parity contract" §1; `contract/STATUS-BADGES.md`) as Phase 2
work `D-997`. It matches the desktop `WP7` rename (`L-57`, GNOME `D-970`, Mac `D-971`).

## Catalog pin

The Phase 1 bundle in `third_party/cassini-ux` moves from `5e0c34e` to cassini-ux
`5e4f0bff95300f2ef795fe71244b22644cad5c25`. Brand tokens, marks and the Library rules are byte-identical; the
generated resources change only in the `states.badge.received` word (`Received` → `Imported`, all six languages)
and the header comment. `wave5/strings.json` and `contract/import-types.json` keep their own pins.

## Which notes are Imported

`Origin.imported` (`:core`) is cassini-ux `origin != own`: `IMPORTED_AUDIO` (imported audio, a ZIP entry) and
`IMPORTED_DOCUMENT` (an opened, shared or picked Cassini file). `RECORDED` and `RECOVERED` (a capture of this
device repaired after a crash) are this device's own recordings. No column, file or migration changes (`D51`).

## Library and Search rows

A finished imported row shows the outline `Imported` badge (`states.badge.received`, incoming glyph, `22/12/8`)
before its speaker-count or `No speech` pill. Any progress or problem badge (`Transcribing`, `Queued`, `Paused`,
`Audio only`, `Couldn't transcribe`, …) takes its place while it applies. Search results use the same row, so they
show it too. TalkBack adds "imported" after the status of such a row.

## Meeting header subtitle

The top bar and the scrolling meeting header append the catalog's provenance words to the existing subtitle:
`Today, 10:02 · 48:13 · Imported` (`format.previewSubtitle.received`), or `… · Imported · Transcribed on another
device` (`format.previewSubtitle.importedOtherDevice`). As on GNOME, only the words after `{duration}` are
generated (`ui_subtitle_imported*` in `phase1.xml`); `generate-phase1-strings.py` fails if either entry stops
extending `format.previewSubtitle`. A "Date unknown" or local recorded-at label keeps the suffix verbatim.

"Transcribed on another device" applies when the transcript on screen came inside an imported Cassini file: the
note is `IMPORTED_DOCUMENT`, the selected variant is readable with at least one word, and its `createdAtUtc` is
missing or earlier than the note's `addedAt`. A variant this app writes (Transcribe again, a merge) always carries a
`createdAtUtc` from after the import, so it shows plain `Imported`, as does imported audio transcribed here and the
empty transcript of an audio-only Cassini file (`D12`).

## Differences from the desktop apps

- The Note actions sheet's meta line is unchanged; the contract names only rows and the header subtitle.
- After a catalogue rebuild (`CatalogueRepository`), a `document.opus` whose Opus vendor is cassini-android is
  catalogued as `RECORDED`. A Cassini file made on another Android phone, or imported audio this phone has since
  transcribed, then loses its Imported mark. Rebuilds happen only when the database is lost.
- `Recorded by me` (L-35) is not part of this change; it will use `Origin.imported`.
