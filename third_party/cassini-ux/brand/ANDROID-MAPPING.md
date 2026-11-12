# Android canvas mapping

This preview reads cassini-ux e244ad6a830e4ef77d87fb452143f7100c767139. Generated values come
from the byte-identical pinned tokens.json. Color.kt owns Android aliases; the sync test checks every
approved token/derived role, every packed color field and the additional named getters in both modes.
No source role is deferred to a later preview. Native shadow geometry stays; shadow is unused as an
explicit paint role. The desktop card-rest role is imported but unused by W1 Android rows.

Android secondary aliases: surfaceWash/boardBg → sunken; text3 → ink-2; line2 → line;
line3 → ink-3; accentTrack → accent-tint; amberWash → warn-tint; redLine → error;
inkFill/onInkFill → ink/ground. The Material outlineVariant reads line-soft.

SpeakerTone maps One through Six to speaker-1..6/avatar-1..6, and Unnamed to speaker-0/avatar-0.
Old speaker palettes are removed. Named speakers retain the existing first-turn, named-only ordering
helper, now with six slots; unnamed speakers take no named slot. Saved-voice avatars cycle the six
named pairs in list order. File colourIndex is not used or changed: that proposed mapping is not approved.
Names/initials use the speaker ink; transcript words keep ink. Question washes use avatar-0.

Filled destructive actions read errorFill/onRecord (white) in both modes. The live Record control uses
recordTint/recordInk; Record at rest uses record/onRecord. Filled success uses successFill/onRecord;
Android currently has success badges but no filled success action. All seven speaker/avatar pairs,
fill pairs and the corrected ink-3/sunken pair pass text AA. No contrast exceptions remain.

The Recording screen's forced-dark palette remains unchanged while L-08 is open. Launcher artwork
remains unchanged. The generator/literal enforcement is being completed in the included plumbing work.

The temporary #195 old-palette snapshot and compatibility generator path are deleted. Recording now
uses canonical dark ground/ink/ink-2/ink-3/line/record-ink and Record fill roles.
