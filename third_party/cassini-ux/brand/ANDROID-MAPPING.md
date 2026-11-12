# Android canvas mapping

This preview reads byte-identical cassini-ux b4dec4160a04a5e9995ce0a74d0215aa24a3da9c,
version 2026-11-12.3, SHA-256 9d3d10d88db5d86d18a1cd099df36ca1c00234344fa383d28283992b734c6358.
All 53 authored colours and derived card-rest are decided. The sync test checks every role and
packed colour alias in both modes. No speaker or avatar rendering is frozen.

Android imports but does not use these desktop roles: `sidebar`, `sidebar-mac`, `field`, `field-line`.
`shadow` is not an explicit paint role (native shadow geometry is retained); W1 rows do not use `card-rest`.
These exemptions are named individually in the sync test.

Secondary aliases: surfaceWash/boardBg → sunken; text3 → ink-2; line2 → line;
line3 → ink-3; accentTrack → accent-tint; amberWash → warn-tint; redLine → error;
inkFill/onInkFill → ink/ground. Material outlineVariant reads line-soft.
Neutral pills and secondary controls use ink at 10% over their actual backdrop (L-46/L-54).

L-41 / HARMONY 10.11: colourIndex is fixed at first speech, including unnamed speakers,
and stored in the meeting's speaker record. Unnamed shows speaker-0/avatar-0; naming reveals
speaker-((colourIndex mod 6)+1) and the corresponding avatar, without shifting other speakers.
Draft metadata keeps the original pre-merge speaking order. Existing indices survive merges,
new transcript variants and re-diarization. Old files without an index use first speech in their first readable variant (manifest order if no body can be read);
the next explicit document edit persists that index. Merely importing or viewing never rewrites a file.
The Room cache carries this file fact separately from variant-specific display ordinals.

L-41 / HARMONY 10.12: saved voices store their save-order slot in people.json; display sort,
rename, deleting another voice and reload retain it. Older voice files derive their initial
slots from createdAt (file order breaks ties) before edits, then persist them on the next write.
Names/initials use speaker ink; transcript words keep ink. Question washes use avatar-0.

Destructive actions use error-fill/on-record; successful filled actions use success-fill/on-record.
Record uses record/on-record at rest and record-tint/record-ink while live. Recording's forced-dark
palette uses canonical dark roles. All text contrast thresholds remain 4.5:1, without exceptions.
The old #195 palette snapshot is deleted. The preview stays draft pending visual approval.
