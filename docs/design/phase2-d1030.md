# L-25 on Android: shared import types and import cards (D-1030)

Decision L-25 (cassini-ux `COMPONENTS.md` section 7; `ALIGNMENT-PLAN.md` W0.7, W4.5 and W4.6) as Phase 2 work
`D-1009`.

## One accepted-types source

`third_party/cassini-ux/contract/import-types.json` (`cassini-ux/import-types@1`) is vendored unchanged from cassini-ux
`d591497797749830aca33c14e4279c39d89162cb` (version `2026-11-14.3`, native FLAC decided: D-938, D102; D-1049). The contract landed after the Phase 1 bundle, so its `UPSTREAM.json`
entry carries its own `commit`; `scripts/check-ux-pin.py` allows that only for this file and checks its bytes like
the others. To update it, copy the new upstream file, record its commit and SHA-256 and update `ImportTypes`.

`ImportTypes` (`:core`) holds the decided entries: Opus/Ogg (`.opus`, `.ogg`), MP3, M4A, WAV and native FLAC
(`.flac`, `audio/flac`, `audio/x-flac`). `ImportTypesTest` fails when they or the FLAC `headerValidation` parameters
differ from the vendored file. Ogg FLAC (`audio/x-flac+ogg`, `audio/x-oggflac`) stays unsupported.
`scripts/check-import-types.py` checks that the manifest's Open with (`ACTION_VIEW`) and Share (`ACTION_SEND`) filters
list exactly the decided `androidMimeTypes`. The system picker uses the same list plus Cassini's ZIP archives (D96).
Android has no subclass-matching picker, so `compatibilityMimeTypes` are never declared.

A file the person picks, opens with or shares to Cassini is checked in `ImportRepository` (`checkType`):

1. Before the copy, `ImportTypes.verdict` rejects video MIME types, the Ogg FLAC MIME types, the contract's rejected
   Ogg extensions (`.ogv`, `.oga`, `.ogx`, `.spx`) and every other extension. Opus/Ogg requires `.opus` or `.ogg`;
   native FLAC requires `.flac`, and a `.flac` under `audio/ogg` or `application/ogg` is rejected, as is a FLAC MIME
   type on any other extension. A file without an extension is accepted only under an MP3, M4A, WAV or FLAC MIME
   type (FLAC then needs the header check). Without a display name the extension comes from the Uri's last path
   segment (never used as the title).
2. After the copy, an Opus/Ogg file and any file reported under a compatibility MIME type (`audio/ogg`,
   `application/ogg`) must pass the contract's stream check on its first 1024 bytes: page 0 an OggS BOS page starting
   with `OpusHead`, page 1 a non-BOS page of the same stream starting with `OpusTags`. Ogg video (Theora, OGM, Dirac,
   Daala, Skeleton), Vorbis, Speex and FLAC-in-Ogg fail. Android applies the check to `.opus` too, not only to the
   ambiguous `.ogg`: a valid Opus file always passes, and an MP3 named `.opus` is rejected instead of being probed.
3. Before the copy, a native FLAC input must pass the contract's `headerValidation` (D102) on its first 42 bytes
   (`ImportTypes.headerValidated`): `fLaC`, a first metadata block of type 0 (STREAMINFO) with length 34, and a
   non-zero 20-bit sample rate. A shorter file, Ogg FLAC (`OggS`) renamed to `.flac`, an ID3v2-prefixed stream and a
   zero sample rate are rejected after reading at most those 42 bytes, before the size and space checks. A valid
   FLAC becomes `source.flac` after `MediaExtractor` finds its audio track and, like MP3, M4A and WAV, is decoded
   (`AudioDecoder`, `MediaCodec`) into the Opus portable meeting.

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
contract instead of `audio/*`. Formats it took before and the contract does not decide (AAC, AMR, 3GP, WebM, Ogg
FLAC) are no longer offered or imported from the picker, Open with or Share. Desktop drag-and-drop states do not apply.
