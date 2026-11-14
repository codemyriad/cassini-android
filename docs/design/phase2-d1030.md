# L-25 on Android: shared import types and import cards (D-1030)

Decision L-25 (cassini-ux `COMPONENTS.md` section 7; `ALIGNMENT-PLAN.md` W0.7, W4.5 and W4.6) as Phase 2 work
`D-1009`.

## One accepted-types source

`third_party/cassini-ux/contract/import-types.json` (`cassini-ux/import-types@1`) is vendored unchanged from cassini-ux
`03fd3bb7dc806c015c1079807eaf5bc548a9fd42`. The contract landed after the Phase 1 bundle, so its `UPSTREAM.json`
entry carries its own `commit`; `scripts/check-ux-pin.py` allows that only for this file and checks its bytes like
the others. To update it, copy the new upstream file, record its commit and SHA-256 and update `ImportTypes`.

`ImportTypes` (`:core`) holds the decided entries: Opus/Ogg (`.opus`, `.ogg`), MP3, M4A and WAV. `ImportTypesTest`
fails when they differ from the vendored file; FLAC (`status: "open"`, L-09 / D-938) is tracked and rejected.
`scripts/check-import-types.py` checks that the manifest's Open with (`ACTION_VIEW`) and Share (`ACTION_SEND`) filters
list exactly the decided `androidMimeTypes`. The system picker uses the same list plus Cassini's ZIP archives (D96).
Android has no subclass-matching picker, so `compatibilityMimeTypes` are never declared.

A file the person picks, opens with or shares to Cassini is checked in `ImportRepository` (`checkType`):

1. Before the copy, `ImportTypes.verdict` rejects video MIME types, the open FLAC types, the contract's rejected Ogg
   extensions (`.ogv`, `.oga`, `.ogx`, `.spx`, `.flac`) and every other extension. Opus/Ogg requires `.opus` or
   `.ogg`; a file without an extension is accepted only under an MP3, M4A or WAV MIME type. Without a display
   name the extension comes from the Uri's last path segment (never used as the title).
2. After the copy, an Opus/Ogg file and any file reported under a compatibility MIME type (`audio/ogg`,
   `application/ogg`) must pass the contract's stream check on its first 1024 bytes: page 0 an OggS BOS page starting
   with `OpusHead`, page 1 a non-BOS page of the same stream starting with `OpusTags`. Ogg video (Theora, OGM, Dirac,
   Daala, Skeleton), Vorbis, Speex and FLAC-in-Ogg fail. Android applies the check to `.opus` too, not only to the
   ambiguous `.ogg`: a valid Opus file always passes, and an MP3 named `.opus` is rejected instead of being probed.

A rejection is `Failure.OPEN` ("Can't open this file"). Entries of Cassini's own ZIP archives skip the contract, so a
backup that holds an older note in another format still restores.

## Import cards

Each import has a card above the notes (`ImportRow`, `:ui`): the file's name and the neutral "Importing…" badge with
its spinner while it runs (W4.5). A failed import keeps its card with the amber "Needs attention" badge and a
Dismiss button (W4.6). The badges are the catalog's `states.badge.importing` and `states.badge.needsAttention`. The
card is a polite live region, so TalkBack announces both. It replaces the "Importing <name>…" banner, which the app
no longer emits; the import error banner still explains a failed Open with or Share.

The cards live in memory only (`ImportTracker`, W4.6): a failed card goes when it is dismissed, when the import error
banner is dismissed, or when the next import (Open with, Share, or a new picker selection) starts, and none survives
the process. A picked file gets a failed card when it added nothing (no note and no duplicate); the picker's own
summary still counts it.

Screenshots: `ImportCardsScreenshotTest` (`20-library-importing`, `21-library-import-failed`, light and dark).

## Differences from the desktop apps

Android already decoded MP3, M4A and WAV through `MediaExtractor`; this change narrows what it accepts to the
contract instead of `audio/*`. Formats it took before and the contract does not decide (AAC, AMR, 3GP, WebM, FLAC)
are no longer offered or imported from the picker, Open with or Share. Desktop drag-and-drop states do not apply.
