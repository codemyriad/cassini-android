W1.1 plumbing keeps LegacyLightColors/LegacyDarkColors active. No rendered value changes.
Color.kt owns CanvasRoleTokens and canvasColors; the separate W1 look-preview PR activates them.
scripts/generate-brand-colors.py emits canonical values and the temporary compatibility snapshot into
checked-in BrandTokenValues.kt and colors.xml; CI checks every output and rejects authored literals.
The sync test checks the vendored SHA and every decided/placeholder role in light and dark.

Android secondary roles reuse published roles: surfaceWash/boardBg → sunken; text3 → ink-2;
line2 → line; line3 → ink-3; accentTrack → accent-tint; amberWash → warn-tint;
redLine → error; inkFill/onInkFill → ink/ground. These aliases introduce no new mixed colours.
Speaker/avatar rendering, their unnamed-question wash and the legacy violet family stay frozen in plumbing only.
The old forced-dark Recording values are part of the same checksum-frozen temporary snapshot. Proposed and pending tokens are not generated.

Every decided/placeholder source role must appear in CanvasRoleTokens or one of these explicit sets
in BrandTokenSyncTest (reverse coverage, in addition to checking every Android field):

- Deferred to the separate visual preview: raised (dialog/menu surfaces), highlight/highlight-ink
  (played words/search), neutral-ink/neutral-tint (neutral tones). Plumbing does not add their runtime
  getters or swatches, which would change its golden images.
- Unused by the current Android components: line-soft (all hairlines use line), record-ink/record-tint
  (no separate record-text/wash component; RecordingColors stays frozen), shadow (native elevation
  and the existing Record shadow treatment stay in place).

The test validates statuses in both tokens and derived. Only decided, placeholder, proposed and pending
are recognized. Promoting a speaker/avatar, approving a derived role such as card-rest, or adding any
active role fails until Android imports/maps it or documents an explicit exemption. The generator
imports approved tokens and derived roles; card-rest is an explicit Android-unused role (desktop L-19).

Alex's 12 November decisions are pinned at e244ad6a830e4ef77d87fb452143f7100c767139.
The class now also exposes errorFill/successFill and speaker0..6/avatar0..6; each maps to its named
source token and the sync test checks its packed value in both modes. Existing components and
swatches do not read these sixteen staged fields in PR A, preserving every pixel. TokenScreenshotTest
accounts for exactly those fields explicitly, while still refusing any unaccounted new getter.
The visual preview applies them and deletes both temporary-old-palette snapshot files. The source's card-rest role is Android-unused: L-19's mixed resting
card is for desktop; Android uses surface. Source roles are never exempted by a prefix or wildcard.
Speaker assignment by colourIndex remains unapproved; it is not introduced by this plumbing PR.
