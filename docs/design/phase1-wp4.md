# Phase 1 WP4: Android recording

L-08 keeps recording on the canonical night ground in both app themes. L-48 removes the visible Title caption while retaining the editable field's spoken label, pencil, title-length limit and delayed-state acknowledgement. The format chip, waveform, space hint and permission/error states stay available.

L-49 places Stop in the centre, All meetings on the left and labelled Pause/Resume on the right. Section 4.D of the overnight dispatch specifies a 56sp system-font tabular timer and a 48dp red Stop pill. Native Material controls grow for larger text; side controls have 52dp outline targets. The meter area scrolls in a short window so it cannot cover the fixed capture controls.

Stop sends the existing Finish event. The recording notification uses the same catalog Stop label in active and paused states, retaining its existing action intent and handoff. Starting, permission and saving states still disable capture actions while All meetings stays available, and saving keeps its immediate feedback. No capture, handoff, file or schema changes. Shared catalog text comes from common.stop, player.pause and phone.row.resume; existing full spoken pause/resume actions remain.

Tests cover action order/callbacks, disabled capture states, paused/resume state, editable title semantics, format retention, short windows, larger text and always-dark pixels. Existing title-limit, semantics, mode and recording lifecycle tests remain in use. Goldens are recorded and inspected on Linux only.
