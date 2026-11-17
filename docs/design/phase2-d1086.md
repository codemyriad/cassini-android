# Voices question, model download sheet and Saved voices footnote on Android (D-1086 / D103: L-60, L-61, L-63)

Silvio approved the Phase 2 second pass on 16 November 2026 (D-1044), recorded as D103 in cassini-ux. This is the
Android part of L-60 (first run and the one voices question), L-61 (model download sheet) and L-63 (Saved voices),
with the Trash intro of L-64 and the L-67 leftovers on the first-run Library.

## Catalog pin

`third_party/cassini-ux/phase2/strings.json` is re-pinned to cassini-ux `c28b72461c7317ea0a4684183cb9103626aabf17`
(D-1074 reviewed translations and the D-1087 French colon fix; it descends from `1ffebfa`, the commit in the
ticket). `scripts/generate-phase2-strings.py` generates the new ids in all six languages into `values*/phase2.xml`;
the hand-written copies they replace are deleted from `strings.xml`.

## Voices question (L-60)

- `NoteViewModel.askVoicesQuestion` opens `NoteSheet.VoicesQuestion` the first time a visible meeting shows more
  than one speaker (`NoteTranscript.meetingSpeakers`, sealed or draft) while no other sheet or dialog is open. It is
  not asked while saved voices are read-only (`people.json` newer or unreadable), and it is not restored after
  process death: the next open asks again until it is answered.
- "Yes, remember voices" / "Not now" store `voices.questionAsked` and `voices.rememberDefault` (DataStore,
  `SettingsRepository.answerVoicesQuestion`). Closing the sheet (swipe, scrim, back) marks the question asked and
  leaves the default as it was (on), so it is asked once without a swipe turning remembering off. The sheet stays
  open and both answers are disabled until the write succeeds. A failed write shows a retryable error.
- The privacy line is `voices.setup.privacy.phone` (D98), not the drawn "It is never shared." (PARITY §3). The board's
  lock glyph is drawn with the app's privacy Shield, as on every other Android privacy line.
- `NamingInput.rememberDefault` feeds Name a speaker: "Remember this voice" starts on only when the default is on, or
  when the speaker is already bound to a saved voice (a CONFIRMED voice stays remembered). The checkbox still turns
  remembering on or off for one speaker. A saved draft choice takes precedence over the global default, so a name
  correction preserves an explicit enrollment or exclusion. With the default off, a new name alone does not enroll a voiceprint.
- Settings › Speakers › Saved voices gains "Remember people I name" (`voices.rememberDefault.*`) before the Saved
  voices row. Changing it there also counts as answering the question.

## Model download (L-61)

One body for any set of missing parts (`models.offer.body.phone`, the total in bold); the single-part wording is
gone. `models.offer.spaceNeeded` replaces the footnote, the link is "Where the models come from" only (Privacy is
dropped from the sheet), and "Not now" (`common.notNow`) has `models.offer.recordingKept` under it instead of
"Not now — keep the audio". The part names come from `models.offer.part.*`.

## Saved voices (L-63) and Trash (L-64)

The footnote is `savedVoices.howToAdd.short`, the row menu uses `savedVoices.row.forgetEllipsis` and the options
button `savedVoices.row.options.a11y`. The privacy line stays `savedVoices.privacy.phone` (D98). The Trash intro is
`trash.phone.intro`.

## First run and L-67 leftovers

The how-to line under the wave is `firstRun.howTo`; the hero and the D98 privacy line are unchanged. The Library
record button's accessible label and the empty and unavailable Library banner titles now say meeting in all six
languages. They have no catalog id yet, so their translations are hand-written with the glossary's feminine meeting
nouns. New recording titles (`ui_voice_note_title` / `rec_default_title`) and processing notifications also say
meeting in all six languages. Existing user titles stay unchanged.
