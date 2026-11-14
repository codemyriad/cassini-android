# WP6: Name a speaker

Phase 1 decisions L-14, L-41 and L-46 retain Android's native modal sheet, text field, switch, keyboard handling and save reducer. The canonical token and string bundle remains pinned to cassini-ux `5e0c34e0f1bb390298dabdf4eedc494e933fd329`.

The heading uses ink without a numbered avatar, while the native sheet window carries the localized `speakers.dialog.a11y` title. Speaking time and sampling (`speakers.talkTime`, `speakers.playSample`) precede a bounded quote from the selected speaker in the displayed transcript, including drafts. The quote excludes other speakers and synthetic overlap labels. Extraction runs on the compute dispatcher and stops at forty words or 240 UTF-16 units without splitting a surrogate pair.

The duplicate-name notice sits immediately below Name and styles the exact `{name}` placeholder span in semibold. Its compact answer pills retain 48dp native targets, existing events and the blocked-save focus and repeated announcement. Saved voices use grouped field surfaces, 56dp rows, neutral Likely labels and avatars drawn from their stored colour slots. Sorting never changes those slots.

Remember is one accessible switch target in a grouped card. Its shared two-line privacy text preserves explicit newlines in Android resources. Existing availability, model and speech guards remain in force. The scrolling body and pinned Merge, Cancel and Save footer retain the existing IME behavior. No storage schema, enrolment, merge or file-writing behavior changes.

Regression tests cover quotes in both transcript forms, bounded Unicode and 100k-word extraction, view-model projection, stored-slot ordering, native switch semantics, disabled behavior, WCAG text contrast on field/notice surfaces and all six app locales (`en-US`, `it-IT`, `fr-FR`, `es-ES`, `pt-BR`, `pt-PT`). Existing tests retain both duplicate answers, save blocking, sampling and enrolment. Linux light/dark screenshots include the actual native modal; device checks retain large-text and open-keyboard save checks.
