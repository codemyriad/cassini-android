# Android QA walkthrough (D-1057)

This is an end-to-end user walkthrough of `main` at 561f498a, done on 15 November 2026.
The screenshots and the full bug reports are on the Linear card D-1057. This file keeps the
findings next to the code. It is a record of what was observed and changes no behaviour.

## Setup

- APK: the CI artifact `cassini-android-0.4.2-beta.3-x86_64.apk` (debug) from Android run 43647405270,
  whose head SHA is 561f498a.
- Emulator: API 35 `google_apis` x86_64, run headless in Docker with `/dev/kvm`. The phone (Pixel 7
  profile) was tested in portrait and landscape, in light and dark theme. The tablet (Pixel Tablet
  profile) was a fresh install.
- Driving: `adb shell input`, `uiautomator dump` and `screencap`. Every step was inspected visually.
- Inputs: the committed fixtures in `fixtures/audio/` (FLEURS in five containers, the JFK WAV), plus
  three corrupt files made from them: random bytes as `.mp3`, a 3 kB `.m4a` prefix and a 20 kB `.opus`
  prefix.
- Limits: the emulator microphone records silence, so recordings end as `No speech`. Transcription,
  speakers and search were tested on imports. Permissions were pre-granted with `install -g`, so the
  permission prompts were not tested.

## Results

| Area | Result |
| --- | --- |
| First launch and Settings | OK. The empty hero and the backup notice show. `Export All as ZIP…` is disabled on an empty Library. Model downloads work. Whistle shows `Unavailable` on x86_64. |
| Recording | OK. Live timer, level meter, the active card and the `Recording` row badge, Pause and Resume, Stop. **Back to the Library shows the post-recording badge at once** (`Transcribing…`, then `No speech`); no stale `Recording` badge appeared. A second recording works. With the model consent declined, the row is `Audio only` and the Library shows the "Record now, transcribe later" card. |
| Imports | OK. A batch of `.m4a`, `.mp3`, `.wav`, `.flac` and `.opus` gives "Imported 5". After transcription a row shows the outline `Imported` badge and the speaker pill, and the subtitle ends in `· Imported`. Corrupt or truncated files give "3 files could not be imported" and `Needs attention` cards. A Cassini file re-imports. |
| Transcription, playback, speakers | OK. Word highlighting, ±10 s, speed and word-tap seek work. `Transcribe again` adds version 2. Name a speaker plus Remember this voice gives `auto` naming in the next meeting, and the voice is listed in Saved voices. |
| Star, filters, search | OK. Star and Unstar from the row menu and the meeting menu, the `Starred` filter and its empty state. Search highlights transcript excerpts. `Recorded by me` leaves out Imported meetings. Closing search restores the day sections. |
| Export | OK. The order is Cassini file, Markdown, More formats (TXT, SRT, VTT). A saved `.opus` carries a valid portable-meeting manifest and words payload. An audio-only import offers only `Original audio` and the note (PARITY §2). The ZIP dialog works. |
| Trash | OK apart from bug 1. Move to trash, Undo, the Trash list, Restore, Delete now and Empty all work, and both deletes ask for confirmation. |

## Bugs

Linear refused new issues (the workspace's free issue limit), so both reports are comments on D-1057
and still need their own issues.

1. **Stale Undo after a permanent delete (medium).** Move a meeting to trash, open Trash while the
   snackbar shows, choose Delete now, then go back. The Library shows ““X” moved to trash · Undo” again
   with a new timer, and Undo does nothing, without any message. `CassiniSnackbarHost` runs its timeout
   in a `LaunchedEffect`, so leaving the Library cancels the timer and returning starts it again. The
   `MOVED_TO_TRASH` `SnackbarSpec` in `LibraryViewModel` is not cleared when the meeting is purged.
2. **Translucent Import audio pill (low).** The secondary button in `LibraryBottomBar` sits over the top
   of the bar's gradient, where the backdrop is still mostly transparent, and its fill is translucent.
   List text shows through it in both themes. It is easiest to see in phone landscape, where the
   "DATE UNKNOWN" header sits under the bar.

## Notes (not filed)

- Search: "0 meetings" sits below the "Nothing matches" card. "Try another word or title." also shows
  when a filter, not the words, empties the results.
- The ZIP selection list shows titles only, so imports with the same title look identical.
- "Delete 1 meeting for good? Their audio, transcripts and voiceprints are deleted." uses a plural
  pronoun for one meeting.
- After its download, Parakeet Ultra's row still reads "Optional download · 440 MB".
