# L-34/L-35 on Android: starring and the unified filters (D-1043)

Decisions L-34 and L-35 (cassini-ux `D99`; `PARITY.md`, "Phase 2 parity contract" §3) as the Android slice of
`D-999`. The catalog strings come from the cassini-ux bundle already pinned at `5e4f0bf` (milestone
`Phase 2 filters (L-34/L-35)`), generated into `phase1.xml` by `scripts/generate-phase1-strings.py`.

## Stars

- `meeting.star` / `meeting.unstar` are a row of the Note actions sheet (after Rename), so both the Library long press
  and the Note screen's More menu offer them, and a TalkBack custom action on every Library row.
- A starred row leads its meta line with a filled star (accent, 14 dp); its spoken description adds
  `library.row.starred.a11y` ("starred").
- Stars are per-device Library state, never written into the Cassini file, an export or a ZIP archive (`D53.1`).
  They live in the Room table `note_stars` (schema 4, `MIGRATION_3_4`), not in a `notes` column: the document
  derivation and other writers replace whole `notes` rows from a snapshot (`CacheDao.replaceCache`,
  `NoteDao.update`), and a column there could silently undo a star set in between. The row cascades from its
  note. A catalogue rebuilt from the files (only when the database is lost) starts with no stars. A device-to-device
  transfer or cloud backup carries the database, so it carries the stars with it.

## Library at rest (L-34)

- Material filter chips `All` / `Starred` above the list, in a group named `library.filters.a11y`, single choice and
  `All` by default. The choice is remembered on this device (`library.starredOnly` in the settings DataStore). The
  row is hidden while searching and before the first note (the first-run hero). Placement is PROVISIONAL, pending
  Alex in `D-1019`.
- `Starred` with nothing starred shows `library.starred.empty` under the row. The empty-Library hero and banner still
  count every live note.

## Search (L-35)

- One horizontally scrolling chip row under the field: `Starred` · `Speaker` · `Any date` · `Recorded by me`.
  `Any date` gains `This year` (`search.filter.date.year`): from local midnight on 1 January of the current local year
  to the end of today, computed on the meeting's recording day (`D95`) like the other ranges.
- `D14` semantics are unchanged: AND, narrowing the typed hits, reset when Search closes, not persisted. `Starred`
  starts as the Library's choice each time Search opens (also when an open Search comes back after process death);
  toggling it in Search does not change the Library's choice.
- `Recorded by me` keeps `origin = own` (`Origin.imported` false). This now includes a recovered capture of this
  device (`RECOVERED`), which it used to leave out; Imported notes never match (L-10).
- `Has summary` stays hidden.

## Differences from the other apps

- Android places Star / Unstar in its existing actions sheet rather than a separate context menu or swipe action.
