# WP5: Transcribing

The Phase 1 brief and decisions L-45/L-55 define the compact status and details sheet. The shared catalog stays pinned to cassini-ux `5e0c34e0f1bb390298dabdf4eedc494e933fd329`.

The Android top bar keeps Back, the meeting title, its date and More actions. The date leaves on draft scroll; the title and divider remain. Search and playback arrive with the finished transcript. The accent spinner, current time-left estimate, chevron, Stop and thin progress track stay above the draft. Both status actions have separate native touch targets.

Tapping the status opens the existing Material 3 modal sheet with the raised surface. It reads the current tracker state for steps, percentage, audio done, words and battery. Download cancellation, low-memory feedback, skipped matching and notification guidance remain available. Stop uses the existing event on both surfaces and is disabled while the checkpoint is being saved. The sheet closes when processing ends and does not reopen on Resume. Its presentation state is saved per note, above the lazy transcript items.

Processing, Paused and Failed share one draft list. Opening the screen does not scroll. New text follows only while the reader remains at the end. Reading older text, opening details and Stop/Resume keep the reader's position. The finished transcript has a separate list state.

The draft heading is Transcript so far. Stable words use ink; only the existing unsettled word flags or legacy boundary use lighter text. No new inference about which words are stable is added. Compact speaker suggestions keep the existing question, naming action and answer events. Shared Yes/No labels have full spoken names and separate 48dp targets; document-mode suggestions retain their existing layout.

Focused tests exercise live figures, sheet dismissal and state transitions, busy Stop, held-away scrolling, large text, naming callbacks and stable/unsettled ink. Linux screenshots cover the rest state, scrolled draft and actual native sheet in light and dark themes. The UI does not change capture, transcription, queue, persistence or file-format behavior.
