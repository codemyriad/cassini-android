# Queued work

Requested on 2026-10-08. Start after the current streaming pipeline, speaker merging and progress display changes are verified on the phone.

- [x] Stop an ongoing transcription while keeping completed results; restart from the saved position instead of processing the recording from the beginning. Preserve speaker identities, assigned names and merges, and prepared audio.
- [x] Improve ETA presentation, including what remains and how confident the estimate is.
- [x] Show the expected battery percentage at completion. Distinguish a measured projection from insufficient discharge data or charging, and account for the remaining processing time.

Use Parakeet for this work.

- [x] Investigate named voices returning to “Voice 1” on later recognition. Check persistence of names and voice profiles, and how identities are reused.

Completed and checked on the connected Pixel on 2026-10-08. See [device results](pixel8-results.md) for timings, verification and battery-estimate limits.
