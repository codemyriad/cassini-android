# Meeting nouns on Android phones (D-1048 / L-67)

Silvio accepted L-67 on 14 November 2026 (D-1044): Phase 2 phone screens call Library items meetings.
The glossary uses feminine meeting nouns in Italian, French, Spanish and both Portuguese locales.

The contract-first catalog change is [cassini-ux PR 40](https://github.com/codemyriad/cassini-ux/pull/40).
`third_party/cassini-ux/phase2/strings.json` preserves the complete catalog at the independent commit and SHA-256
recorded in `UPSTREAM.json`. `scripts/generate-phase2-strings.py` generates `values*/phase2.xml` for all six
languages. Existing resource ids, types, files and storage names stay unchanged.

The changed copy covers Settings transfer explanations and size refusal, Saved voices counts, stale status,
help and both forget confirmations, and the shared legacy speaker explanation. Trash is on the Settings path,
so its retention, empty and deletion copy changes too. Queued/waiting transcription and the shared accessible
open action also say meeting. Transcription details, Export, About and the newer encrypted-backup explanations
already use meeting nouns. The Markdown Notes heading still means annotations, so it stays Notes.

The shared meeting-count resource also appears outside Settings and Saved voices. The shared open action and
transcription state copy appear on Library and recording paths. Those surfaces inherit the corrected wording.
The change does not alter deletion, recording, transfer or backup behaviour.

## Phase 1 follow-up

The remaining legacy meeting nouns are outside these Phase 2 paths:

- Recording default titles: `ui_voice_note_title` and `rec_default_title` (Voice note).
- Library empty and unavailable banners: `ui_banner_lib_empty_title`, `ui_banner_lib_unavailable_title`.
- Processing notifications: `notif_processing_waiting`, `notif_processing_public`, `notif_transcribed_public`,
  `notif_failed_public`, `notif_ready_public`, `notif_stopped_public`.
- Record button accessibility label: `ui_library_record_description`.

These resources retain their Phase 1 wording in this ticket. Internal identifiers and comments using note are
not user-facing strings. The retired transfer notice catalog entry is not displayed by the app.

## Verification

`tests/ci/test_phase2_strings.py` checks all six generated resources against the pinned catalog, checks the
absence of legacy meeting nouns, and refuses old overrides in `strings.xml`. Android translation tests also read
the generated Phase 2 XML files so these resources receive the same placeholder, locale and plural checks as
legacy resources. Format and transfer UI tests cover the corrected count, queue and size-refusal text.

Screenshot goldens are recorded and verified on Linux x86_64 in GitHub Actions. Light/dark before-and-after
boards accompany the Android PR and Linear issue.
