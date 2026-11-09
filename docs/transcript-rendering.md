# Reading turns

`ReadingTurns.of` is the pure JVM display projection pinned by D84 to the gocassini viewer at
`1111155cb0017b47c072a8662c54f28ad7c44223`. The shared vector lives in
`core/src/test/resources/interjections/`; its README records the source and regeneration procedure.
The expected JSON is copied without edits, and tests compare every normative row/member field.

The pipeline mirrors `portable.ts` readable grouping and small-block merging, followed by
`meetingExport.ts` display-only timing repair, then `overlap.ts` run assembly, nesting, competing
speech and paragraph layout. Continuation matches `MeetingView.svelte` (same speaker within 60 s).

The canonical word list is never sorted or changed. Every member names canonical word indices;
only a separate display word list may have repaired ends. Grouping uses file order, then blocks
are stably ordered by start. Seeking always uses unchanged word starts. Pass `endsBoundedByAudio`
only for an artifact whose provenance explicitly sets that flag; measured ends then remain intact.
`lowConfidenceWords` can carry canonical confidence flags without changing the portable word schema.

Rows are reading paragraphs, with inline interjection members and competing speaker IDs in `over`.
Runs include the chip speakers as well as the host speakers, for statistics without paragraph-count
inflation. All indices remain usable even when a row owns nonconsecutive canonical words.

This model is introduced separately from its consumers so the shared-vector port can be reviewed
on its own. Subsequent changes replace the existing export/statistics/preview and Note builders.

TXT, SpeakerStats and Library previews consume the shared model. SpeakerStats uses
`ReadingTurns.wholeRuns`: the same grouping, timing repair and run assembly, with no unused row
layout, nesting or competing-speech label work. Statistics count whole runs, including inline speakers, rather than
paragraphs. TXT keeps the reader's continuation and overlap labels. Excerpts follow reading order
and paragraph ends, keep chips inline, and preserve stored punctuation and whole oversized atoms;
ellipses indicate omitted words separately. Subtitle cue segmentation remains time-based.

## Surface audit (D84)

| Surface | Reading path |
| --- | --- |
| Sealed Note / Read & listen | `NoteTranscripts.sealed` -> `ReadingTurns` -> `TurnUi` -> `ReadAndListen` |
| Processing / paused / failed draft | `NoteTranscripts.draft` -> the same rows -> `NoteModes.DraftTurn` |
| Queued or failed job over a document | `NoteModes.DocumentBelow` delegates to the same Read & listen transcript |
| Normal and voice-suggestion headers | Both use `TurnHeaderLine`, including continuation, competing speech and crosstalk; naming controls do not replace the reading metadata |
| In-note search | `TranscriptSearch` indexes `turnText`, including the same inline labels and word offsets |
| Library search excerpts | `DocumentDerivation` and variant selection index `ReadingText.body`; SQLite FTS snippets select from that body |
| Library playback-position preview | `PreviewText` uses reading rows and canonical playback anchors; clipped chip wrappers stay balanced |
| TXT / speaker statistics | `ReadingTurns` / `ReadingTurns.wholeRuns` |

`SubtitleCues` is called only by `SrtWriter` and `VttWriter`. It is a timed caption
projection with cue duration, line-length and speaker-overlap constraints, not a reader turn
assembler. Its canonical words and subtitle timings stay intact; it is not used by any Note
or search surface. The D84 audit explicitly leaves subtitle cue segmentation unchanged.

The browser companion at protocol main `77b2c62` displays capture/admission/transport status
and diagnostics, not transcript words. It has no transcript renderer to port. Its popup CSS
`subtitle` class labels the companion itself and is unrelated to timed captions.

The Note/cache integrations are supplied by the separately reviewed #95/#97 changes. This
follow-up closes the remaining suggestion-header branch in both reading and draft modes.
