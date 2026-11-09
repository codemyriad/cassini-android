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
