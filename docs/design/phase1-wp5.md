# WP5: Transcribing

The Phase 1 brief and decisions L-45/L-55 define the compact status and details sheet. The shared catalog stays pinned to cassini-ux `5e0c34e0f1bb390298dabdf4eedc494e933fd329`.

The Android top bar keeps Back, the meeting title, its date and More actions. The date leaves on draft scroll; the title and divider remain. Search and playback arrive with the finished transcript. The accent spinner, current time-left estimate, chevron, Stop and thin progress track stay above the draft. Both status actions have separate native touch targets.

Tapping the status opens the existing Material 3 modal sheet with the raised surface. It reads the current tracker state for steps, percentage, audio done, words and battery. Download cancellation, low-memory feedback, skipped matching and notification guidance remain available. Stop uses the existing event on both surfaces and is disabled while the checkpoint is being saved. The sheet closes when processing ends and does not reopen on Resume. Its presentation state is saved per note, above the lazy transcript items.

Processing, Paused and Failed share one draft list. Opening the screen does not scroll. New text follows only while the reader remains at the end. Reading older text, opening details and Stop/Resume keep the reader's position. The finished transcript has a separate list state.

The draft heading is Transcript so far. Stable words use ink; only the existing unsettled word flags or legacy boundary use lighter text. No new inference about which words are stable is added. Compact speaker suggestions keep the existing question, naming action and answer events. Shared Yes/No labels have full spoken names and separate 48dp targets; document-mode suggestions retain their existing layout.

Focused tests exercise live figures, sheet dismissal and state transitions, busy Stop, held-away scrolling, large text, naming callbacks and stable/unsettled ink. Linux screenshots cover the rest state, scrolled draft and actual native sheet in light and dark themes. The UI does not change capture, transcription, queue, persistence or file-format behavior.

Review corrections retain the last live tracker publication inside the transient Stopping phase. Busy Stop keeps the same percentage, figures and draft until the checkpoint is saved. Queued and pre-transcription Resume phases load the saved draft; follow state belongs to the screen beside the draft list state. The integration regression renders the real NoteViewModel through all these coordinator phases and checks the visible sentence and its offset, with a reader explicitly away from both ends.

The scrolled processing divider sits below the status strip, at the draft boundary. Compact ETA and single-minute detail Time left values use the shared catalog plurals in all six locales; ranges, multi-hour spans, unknown, finishing and sub-minute states keep the existing formatters. Compact status, ongoing notification, details sheet and More actions Stop controls all read `common.stop` / `transcribing.stopAndKeepProgress` from the shared catalog and report `Saving…` while disabled during checkpointing. Stage announcements use the visible catalog labels. Detail-only variants open the actual native sheet in both themes, including download, verification, waiting, memory and notification states.
