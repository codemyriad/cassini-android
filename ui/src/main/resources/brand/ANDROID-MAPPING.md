The W1 draft look preview activates canvasColors in both modes.
Color.kt owns CanvasRoleTokens and canvasColors; the look-preview PR remains draft for visual approval.
Values come from tokens.json through the Android build's GenerateBrandTokens task, not copied hex constants.
The sync test checks the vendored SHA and every decided/placeholder role in light and dark.

Android secondary roles reuse published roles: surfaceWash/boardBg → sunken; text3 → ink-2;
line2 → line; line3 → ink-3; accentTrack → accent-tint; amberWash → warn-tint;
redLine → error; inkFill/onInkFill → ink/ground. These aliases introduce no new mixed colours.
Speaker/avatar families, their unnamed-question wash and the legacy violet family stay frozen (L-14/L-17).
The forced-dark RecordingColors remain app-local until L-08 closes. Proposed and pending tokens are not generated.

Every decided/placeholder source role must appear in CanvasRoleTokens or one of these explicit sets
in BrandTokenSyncTest (reverse coverage, in addition to checking every Android field):

- Additional named getters activated by this preview: raised (dialog/menu surfaces), highlight/highlight-ink
  (played words/search), neutral-ink/neutral-tint (neutral tones). The test checks each getter against its
  source role. These were explicit deferrals in the no-pixel-change plumbing PR.
- Unused by the current Android components: line-soft (all hairlines use line), record-ink/record-tint
  (no separate record-text/wash component; RecordingColors stays frozen), shadow (native elevation
  and the existing Record shadow treatment stay in place).

The test validates statuses in both tokens and derived. Only decided, placeholder, proposed and pending
are recognized. Promoting a speaker/avatar, approving a derived role such as card-rest, or adding any
active role fails until Android imports/maps it or documents an explicit exemption. The generator
currently imports only tokens; no derived role is approved or exempted.
