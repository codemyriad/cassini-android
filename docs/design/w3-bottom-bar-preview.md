# W3.10 Library bottom-bar preview

This draft applies L-20/L-25 to ANDROID-SCR-06 and ANDROID-COM-07/08. It
includes the W1 palette/type and W2 rows/badges so the pictures show the same
Library. Its PR base is main; it stays draft with hold for look approval.

The bottom bar uses the canvas's 28/16/24 dp padding and ground fade, with a
40 dp secondary Import audio button and a 56 dp Record pill. Record has
24 dp horizontal padding, a 12 dp white dot, gap 10, and 17 sp semibold system
sans. Its only shadow uses the record token at 30%, radius 18, offset 6.
Pressed feedback darkens the token by 16% on pointer down. Keyboard focus
uses a 2 dp accent ring with a 2 dp gap. These drawn metrics are named
constants; none introduces another authored colour.

The list and snackbar use the same fallback metrics and measured bar height.
Controls keep at least 48 dp touch targets. At large fonts or when translated
labels cannot fit, Import keeps its existing labelled icon fallback and Record
can grow to two lines. This is a deliberate accessibility adaptation to the
single-line drawing. While recording, the existing return-to-recording action
and label remain; no new Starting, Saving or Unavailable behaviour is inferred
from the open canvas states.

Screenshot proof includes the Library in both modes, existing localized and
large-font boards, a pressed Record pair using an actual held pointer, and a
keyboard-focused pair. Native pixel tests check the accent ring outside the
pill in both themes and require zero recording starts on focus. Suppressing
the ring makes both focused-state tests fail.
The new pressed and focused captures are added coverage; earlier sources do not have it.
Existing rest-state Library captures provide the before/after comparison.

W3.7–W3.9 need the approved W0.2 Saturn asset source. They are separate from
this package. Progress/remaining-time UI is on hold; this draft does not change
it. The ongoing recording-feedback fix ships independently as a normal PR.
