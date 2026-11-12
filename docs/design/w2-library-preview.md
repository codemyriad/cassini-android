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

## Shared words

Five visible labels come from the checksum-pinned shared catalog in all six
languages: recording, saving, transcribing without an ETA, needs-attention and
received. The full spoken ETA also uses its existing shared entry.
`scripts/generate-w2-badge-strings.py --check` runs in CI. It refuses catalog
byte drift, missing locale output, edited translations and placeholder drift.

The coordinator allowed canvas text where a short catalog form is missing:

| Canvas preview text | Existing form that cannot supply it |
|---|---|
| Importing… | import.progress requires done and total |
| Interrupted | states.badge.interrupted includes a position |
| Transcribing · {n} min | states.badge.transcribing is the longer spoken phrase |

These three preview-only strings are English and nontranslatable until the
coordinator adds their shared forms. They are listed in the PR body.

## Boundaries

Importing, Saving and Received are demonstrated in the component gallery.
The draft adds no early-visible import rows: ZIP admission remains atomic.
Received has no live mapping while the L-10 transfer contract is open. Existing
queued work uses the neutral work badge while its spoken status remains Queued.
The header/search redesign belongs to a later wave.

Speaker metadata is a read-only Room relation, filtered to the selected variant
and ordered by first speaking. There is no schema change. The listing cache
quantizes the running note's ETA to whole minutes before mapping rows.
