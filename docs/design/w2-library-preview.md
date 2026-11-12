# Wave 2 Library look preview

This draft applies L-26 and L-30 from the canvas. It covers ANDROID-COM-03, -04
and -05, and includes the canonical palette and type from the W1 look preview.
It stays draft until Silvio and Alex approve the pictures.

## Badge and row

- Every badge is a pill: at least 22 dp high, horizontal padding 8, gap 4,
  12 sp / 600 with tabular figures, and one glyph before its label.
- Recording uses record ink/tint and a dot. Work uses neutral ink/tint and a
  static spinner mark. Problems use warning ink/tint and an alert glyph.
  Received has only an outline, with a tray arrow.
- Each meeting is a surface card with a 1 dp line, radius 14, padding 12/16
  and a 4 dp gap between its title and metadata. List padding is 16 and gap 12.
  The title is one line at 17/600; duration and metadata are 13 sp.
- Metadata contains time, at most one badge and speaker names. Stored labels
  stay verbatim; missing labels use the current locale's Speaker N. Names
  ellipsize on the badge line, with their full text in the spoken description.
  The 22 dp metadata minimum grows when large fonts need it.
- Ready notes have no badge. Transcription appears in its own meeting row;
  the Library progress card and privacy line are removed. Rest rows omit the
  transcript excerpt; search results keep their matched excerpt.
- Row opening, long press and recovery events stay intact. The compact visual
  labels retain detailed TalkBack status, including pause position, failure
  reason, queue state, speaker count, no-speech state and the ETA when known.

## Shared words and fixtures

All seven labels use the checksum-pinned shared catalog at merged cassini-ux
`793209cb3425bece7143178458fddd94b691e12d` (PR 11). This replaces the three
English-only preview strings. The state vectors v3, format vectors v5 and five
changed Library expectations use the same pin. The canonical contract verifier
checks both the meeting and transient import-entry arrays, ETA forms, all five
translation languages and 41 Library rows. Android renders the shared badge
presentations in light and dark with tests of text, glyph and actual text colour.
These are presentation tests; they do not invent a shared persisted lifecycle
or new applicability for Android-only states.

`scripts/generate-w2-badge-strings.py --check` runs in CI. It refuses catalog
byte drift, missing locale output, edited translations and placeholder drift.
The fixture-family test also checks all vendored hashes and refuses half-pins.

The dogfood progress/remaining-time UI is on hold for a shared decision. Existing
detailed spoken ETA stays byte-for-byte in the generated resources, using the
one retained catalog entry from the prior merged pin `26a37501`. Its separate
source SHA, selection and receipt are in UPSTREAM.json. It is detail copy, not
a badge label. The visible progress display and its placement are not extended
by this contract adoption. The current detailed interrupted position also stays
available to TalkBack and the existing detail controls.

## Boundaries

Importing, Saving and Received are demonstrated in the component gallery.
The draft adds no early-visible import rows: ZIP admission remains atomic.
Received has no live mapping while the L-10 transfer contract is open. Existing
queued work uses the neutral work badge while its spoken status remains Queued.
The header/search redesign belongs to a later wave.

Speaker metadata is a read-only Room relation, filtered to the selected variant
and ordered by first speaking. There is no schema change. The listing cache
quantizes the running note's ETA to whole minutes before mapping rows.
