The W1 draft look preview activates canvasColors in both modes.
Color.kt owns CanvasRoleTokens and canvasColors; the look-preview PR remains draft for visual approval.
Values come from tokens.json through the Android build's GenerateBrandTokens task, not copied hex constants.
The sync test checks the vendored SHA and every decided/placeholder role in light and dark.

Android secondary roles reuse published roles: surfaceWash/boardBg → sunken; text3 → ink-2;
line2 → line; line3 → ink-3; accentTrack → accent-tint; amberWash → warn-tint;
redLine → error; inkFill/onInkFill → ink/ground. These aliases introduce no new mixed colours.
Speaker/avatar families, their unnamed-question wash and the legacy violet family stay frozen (L-14/L-17).
The forced-dark RecordingColors remain app-local until L-08 closes. Proposed and pending tokens are not generated.
