# L-11 on Android: one export set (D-1039)

Decision L-11 (cassini-ux `D99`; `PARITY.md`, "Phase 2 parity contract" §2) as Phase 2 work `D-998`. Same export
set on all four apps: Cassini file and Markdown first, then Plain text, Subtitles (SRT) and Subtitles (WebVTT) under
"More formats".

## Catalog pin

No bump: the Phase 1 bundle in `third_party/cassini-ux` is already at cassini-ux
`5e4f0bff95300f2ef795fe71244b22644cad5c25` (D-1035), which carries the `Phase 2 export (L-11)` entries.
`generate-phase1-strings.py` now also generates `export.cassiniFile.*`, `export.moreFormats`, `export.plainText.*`,
`export.srt.*`, `export.vtt.*`, `export.originalAudio.*` and `export.audioOnly.note` (`ui_export_*` in `phase1.xml`,
six languages). Markdown keeps its earlier resources (`ui_actions_markdown`, `ui_markdown_description`, the same
catalog text). The retired Android-only strings (`Cassini document`, `Transcript as text`, `Subtitles`, `SubRip
(.srt)`, `WebVTT (.vtt)` and their captions, `Available after transcription`) are removed.

## The Note actions sheet

"Save a copy" keeps its place in the shared Note actions sheet (Library long press and Note screen More):

1. The featured row: **Cassini file** `.opus` for a document; **Original audio** `.<ext>` for a plain imported note
   (`Origin.imported`: the imported bytes, unchanged, as in the D96 ZIP); **Audio file** for an own recording that
   has no Cassini file yet.
2. **Markdown** `.md`.
3. **More formats**, a disclosure row with a chevron, closed when the sheet opens: **Plain text** `.txt`,
   **Subtitles (SRT)** `.srt`, **Subtitles (WebVTT)** `.vtt`. It stays open while one of its rows writes. It exposes
   the expand / collapse accessibility actions.

Every export row keeps its own Share button (D53). The separate SubRip / WebVTT chooser sheet is gone: SRT and
WebVTT are rows of their own (`NoteAction.SAVE_SRT` / `SAVE_VTT` / `SHARE_SRT` / `SHARE_VTT`, `NoteStart` likewise).
The file contents and the `:core` writers are unchanged, so the export goldens (features.md section 9 and the
device goldens) still hold.

## Audio-only meetings

A meeting without a completed, readable transcript is audio-only: a plain note, including one whose first
transcription is queued, running, paused or failed (D12: never partial words). Its text formats are **hidden**, not
disabled, and the catalog line "No transcript yet, so only the audio can be exported." shows under the audio row. A
document Cassini cannot read (UNREADABLE, INVALID_METADATA, UNKNOWN_FORMAT, or no readable variant) shows the
existing "Not available for this transcript" instead, because it does have a transcript. The Library sheet and the
Note screen use one rule, `NoteExports.textExportEnabled`.

## Several meetings

`Export All as ZIP…` / `Export Selected as ZIP…` (D96) are unchanged and stay the multi-meeting export. No text
format goes into the archive.

## Differences from the contract and the desktop apps

- **Own recordings not yet transcribed** have no Cassini file on Android: a recording stays plain Opus audio until
  its first transcription seals the document. The contract offers a Cassini file with an empty word-timed transcript
  there (D12); Android offers the recording itself as "Audio file" (the Android string it had before), because
  sealing an empty document would touch the storage pipeline. The ZIP export does the same (D96).
- **An imported audio-only Cassini file** (a document whose selected variant is readable but has no words) still
  shows the text formats, which then write a transcript with no words (the `:core` writers' empty text). The contract
  counts it as audio-only.
- Phones show one sheet, not a dialog: there is no `Export “{title}”` title, no `Save to {folder}` and no speaker-names
  line; the system picker or share sheet follows the row, as before.
- Text-format golden vectors in cassini-ux `vectors/` (PARITY §2: "Android's current output is the starting
  reference") are not part of this change; Android's writers and goldens are as they were.
