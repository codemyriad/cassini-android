# Recorder sync

This document describes how Cassini for Android works with the Cassini voice recorder (open firmware, Bluetooth phone
mode). Cassini copies the recordings from the recorder into new notes, transcribes them on the phone, and sends each
transcript back to the recorder. The recorder shows the transcript during playback.

The user interface is plain for now. Its strings are in English only (`app/src/main/res/values/recorder.xml`,
`TODO(i18n)`).

## Where the code is

| Module | Package or file | Content |
|---|---|---|
| `:core` | `core.recorder` | The XREC protocol: CRC, HMAC authentication, the line codec, the blocking `XrecClient`, `StreamXrecInput` and the CWX1 encoder. |
| `:core` | `src/testFixtures` | `FakeRecorder` (the recorder's XREC state machine behind pipes) and `CwxReader` (the checks of the recorder's CWX reader). The `:core` and `:app` tests use them. |
| `:app-logic` | `recorder/SyncPlanner.kt`, `recorder/RecorderPolicies.kt` | What a sync downloads and uploads, the clock tolerance, the "Auto sync" waits, the 2-minute poll while the recorder records, and the waits of a transcript that goes at once. |
| `:data` | `data.recorder.RecorderStore` | The binding and the sync state, in `recorder/recorder.json` under the app root. |
| `:app` | `recorder/` | `RecorderSync` (the engine), its ports, the Bluetooth, Keystore and library adapters, `RecorderRunner`, `RecorderAutoSync`, `RecorderTranscriptPush`, `RecorderSyncService`, and the Recorder screen. |

`AppGraph.recorder` makes the parts. The app makes it when it is visible for the first time, and "Auto sync" and the
transcript push start then.

## The recorder

- The recorder's radio is on only while the user has it in Bluetooth phone mode (Home, BT, ENTER; on some builds a
  choice screen follows: select Phone). In other modes, a connection fails. The app expects this and stays quiet.
- The recorder name is `Cassini_XX`. The XREC server is the Serial Port Profile service (UUID
  `00001101-0000-1000-8000-00805F9B34FB`, RFCOMM channel 1). It accepts only a secure link with an authenticated
  (MITM) bond.
- ENTER on the Bluetooth screen opens a pairing window of 60 seconds. The recorder then shows a 6-digit PIN.
- The recorder accepts one link at a time. It does not disconnect an idle link.

## Binding

1. The user opens Settings, Recorder, and pushes "Bind recorder". The screen tells the user to press ENTER on the
   recorder.
2. "Find recorder" opens the companion device chooser of Android (`CompanionDeviceManager`, filter `Cassini_.*`). The
   chooser needs no scan permission and no location permission. Without the chooser, the screen lists the bonded
   devices with a `Cassini_` name, and the user can pair the recorder in the Bluetooth settings of Android first.
3. If the phone has no bond with the recorder, the app calls `createBond()`. Android shows its pairing dialog, and the
   user pushes Pair. The recorder accepts inside its pairing window. The app follows `ACTION_BOND_STATE_CHANGED`: a
   refusal ends the wait at once, and a bond that does not start within 5 seconds is a failure. The wait ends after 60
   seconds.
   If the phone had a bond already and the recorder refuses the link, the recorder lost its side of the bond (a reset,
   new firmware). The screen then tells the user to forget the recorder in the Bluetooth settings of Android and to
   bind again, with a button to these settings.
4. The app opens the secure RFCOMM socket and sends `PING`. The reply must have `pair=1` (the window is open).
5. The user types the PIN. The app makes a random 32-byte key (`SecureRandom`) and sends `PAIR,<pin>,<key>`. After a
   wrong PIN, the app asks again. After five wrong PINs, the recorder closes the window.
6. The app encrypts the key with an AES-256-GCM key of the Android Keystore (on the IO dispatcher) and stores only the
   encrypted form. The app never logs a key. A new binding also clears an earlier "firmware too old" result. Then it
   starts a sync.

"Forget recorder" removes the binding and the key, and ends the companion association. The record of the copied
recordings stays, so a recorder that is bound again does not give the same notes again.

## A sync

One sync runs at a time (a `Mutex`). A sync does these steps:

1. Connect, then `CHAL` and `AUTH` with the stored key. If the recorder replies `ERR,AUTH` or `ERR,UNBOUND` (for
   example, a different phone bound it), the app shows "Bind again" and stops all syncs until the user binds again.
2. `STAT`, `LS`, `STAT`. A recording that starts during `LS` is in the second `STAT`, so its growing file is never
   planned. A recording that stops or splits during `LS` (the two `STAT`s differ: it ran at the first and not at the
   second, or with another `rec_idx`) can leave its file in the listing with a size that is not its final one: the app
   lists again (at most 3 times; a recording that still keeps changing is planned as if the setting were off). While the recorder records (media 33), no download starts, unless "Sync while the recorder
   records" is on and the firmware can do it (see "While the recorder records" below). Before each download after the
   first, the app sends `STAT` again, and stops the downloads if a recording started; it does not when the setting is
   on and the firmware names its active file.
3. `RTC`. If the recorder's clock is more than 5 seconds from the phone's local time, the app sets it. While the
   recorder records, the app neither reads nor sets the clock (the firmware would not apply it); the next sync does.
4. `SyncPlanner` decides. The sync identity of a recording is its name and size. A recording with the same
   name and a different size is a new recording (the recorder numbers from 1 again after a card format). The app also
   keeps the SHA-256 of the first 4 KiB of each recording. When the listing has a copied recording but not its
   sidecar, although the app sent one or has one to send, the app downloads the first 4 KiB again and compares them.
   A different file (a new recording with the same name and size after a card format) is a new recording: the app
   copies it, and does not send the old transcript to it.
5. For each new `.wav` file larger than 44 bytes: `GETS` downloads it into the cache (`recorder/<address>/<name>.part`).
   Each `GETS` asks for a 64 KiB window (open_fw takes any total and paces the frames through its TX ring), so the
   link does not wait for a round trip every 3840 bytes. The stock firmware (`pen133` in the `PONG` firmware field)
   keeps the 3840-byte window, and so does a download after two failed windows in a row. The client checks the offset
   and the CRC of each frame, and asks again from the last good offset after an error: first it drains the rest of the
   failed window until the link is quiet (at most `drainMillis` plus the time 64 KiB take at 8 KiB/s), then it skips
   frames of the old window that still arrive after the new `GETS`. A short frame is not the end (the device clips
   frames to its MTU); the end is the listed size, and an empty frame at the expected offset means the file is
   shorter than listed. Each finished download logs its bytes, time, KB/s, retries and `GETS` count at INFO with the
   tag `Recorder`; the Recorder screen shows the speed of the last sync ("Downloaded: 2 (102 KB/s)").
   The `.part` file is written through a 64 KiB buffer, flushed before `fd.sync()` and before a stopped download
   stores its length. A download that stops (a lost link, process death) continues from the length of the `.part`
   file at the next sync. Then the app imports the file (`ImportRepository.import`, title = the file name without `.wav`) and asks
   for a transcription (`ProcessingCoordinator.transcribe`, origin AUTOMATIC). Without model consent, the note waits
   for consent, as a recording does. The app asks for the transcription before it writes the state. If the sync stopped
   between the import and that request, the next import finds the note (same bytes), and the app asks then, when the
   note has no transcript and no job. The app writes the state after each file.
   - A lost link or a timeout ends the sync. Another download failure (a card error, bad frames after all retries)
     skips the file: the other files and the transcripts still go, and the next sync tries the file again.
   - A file that changed or went after `LS` is skipped; the next `LS` decides.
   - An import refused for lack of space or a save error keeps the downloaded file, and the next sync imports it
     again without a download. Another refusal (for example a WAV that is not 16-bit PCM) is final.
   - At the end of a sync, the app deletes the `.part` files that no stopped download continues.
6. For each copied recording that is still on the recorder and whose note has a finished transcript: the app makes the
   CWX1 sidecar from the shown variant (the words, the note title, the audio duration, and the speaker labels and
   colours as the Note screen shows them, in the colours of the dark theme). It uploads the sidecar with
   `PUT ...,REC,<stem>.cwx:` and commits it with `PUTEND`. The first chunk is 64 bytes (the CWX header), the others
   600 bytes. The version of a sidecar is the variant ID and a hash of the bytes. Thus a new variant or a renamed
   speaker sends the sidecar again.
   - After a lost reply or a refused header, the app sends `\r+XREC:PING\r` and drops all replies up to the `PONG`.
     Old firmware reads the payload of a refused header as command lines; the short first chunk keeps that payload
     small.
   - A lost `PUTEND` reply: the app sends `PUTEND` again (the new firmware answers it again). An answer `ERR,NOPUT`
     (older firmware) is checked with `LS`: the sidecar must be there with its size.
   - `ERR,NOENT` (the recording went), `ERR,NAME`, `ERR,SDIO` or `ERR,NOSD` skip this sidecar; the others still go.
     The app does not send a version again that the recorder refused with `ERR,NAME`.
7. If the recorder refuses the `REC` target (`ERR,ARG` or `ERR,UNSUP`), its firmware is too old. The app tells it,
   and asks again after one day, or at once when the firmware name in `PING` changes. The new firmware keeps the name
   `openfw_v5`, so the daily try is what finds it.

A call to the recorder that makes no progress for 60 seconds closes the link (a watchdog): reads time out by
themselves, but a blocked socket write does not. A cancelled sync closes the link too.

The app does not delete recordings from the recorder (`TODO(recorder-sync)` in `RecorderSync`).

## While the recorder records

The recorder firmware of branch `feat/bt-sync-rec-hardening` (open_fw, `docs/XREC_PROTOCOL.md` section 5.5 in the
firmware repository, design `docs/BT_SYNC_SPEED_AND_RECORDING.md` stage S4) can move finished recordings while it
records. The recording always wins: the firmware paces the transfers and refuses what could disturb it. The app's side:

- **Detection.** Such a firmware appends two numeric fields to `STAT`: `rec_idx` (the `NNNN` of the `REC_NNNN.wav` it
  records, 0 = none) and `pace_ms` (0 when it does not record; about 342 ms during a mono recording; 30000 during a
  stereo recording). The app reads the first 8 fields as numbers and fields 9 and 10 when they are numbers
  (`XrecReply.Status.recordingIndex`, `paceMillis`). Without field 9 (older open_fw), nothing changes: every download
  waits while `media` is 33.
- **The setting.** "Sync while the recorder records" on the Recorder screen, **off by default**
  (`RecorderSettings.syncWhileRecording`). Off: the old rule (no download while the recorder records). On: the
  firmware decides whether it can.
- **Downloads.** With the setting on, a recording in progress (`media` 33), `rec_idx` > 0 and `pace_ms` up to 10 s,
  `SyncPlanner` downloads the finished recordings and skips only `REC_<rec_idx>.wav` (`SkipReason.ACTIVE`). Only that
  file: the listing lies between two `STAT`s with the same `rec_idx`, so no other listed file grows, and after
  `REC_9999.wav` the firmware numbers a new recording from the lowest free number, so finished recordings can have
  higher numbers. A stereo recording (`pace_ms` 30000) allows no transfer (mono only, decision 5
  of the design): all downloads wait, as before. While the recorder records, each `GETS` asks for 16 KiB
  (`XrecLimits.RECORDING_GETS_WINDOW`), and `pace_ms` is the shortest interval between the starts of two windows: the
  firmware paces the frames itself (about 48 KB/s), so the client sends the next `GETS` as soon as a window is complete
  when it took that long, and adds no pause after a window (the firmware's burst allowance is only 2 KiB: a pause after
  each window would halve the rate, below the 32 KB/s that a mono recording makes).
- **`ERR,BUSY`.** The firmware refuses the file it records. A download that gets `BUSY` (a recording started after
  `STAT`) is skipped for this sync; its `.part` file goes, and the next sync lists it again.
- **`ERR,LATER,<ms>`** to a `GETS` or in place of a frame means a stereo recording, which allows no transfer until it
  ends: the bytes so far stay in the `.part` file, and the file and the rest of the downloads wait for a later sync
  (`skipped`, not `failed`) at once. The app does not wait on the open link: a connected link polls the radio all the
  time, next to the recording microphone. The stopped download resumes from the `.part` file.
- **Transcripts.** The firmware takes no card write while it records: `PUT` and `PUTEND` answer `ERR,LATER` (the
  payload of a refused chunk is discarded, not read as commands). When `STAT` shows a recording and names its file, the
  app does not try: the transcripts wait (`SyncOutcome.Done.deferredUploads`). A `LATER` during an upload (a recording
  that started meanwhile) ends the uploads of this sync the same way. A waiting transcript is not a failure: its record
  keeps no failure. With "Sync while the recorder records" on, the transcript push tries again after 2 minutes, at most
  60 times in a row (about 2 hours), unless "Auto sync" is on: then its syncs send the transcript, and the push stops,
  so that no second loop calls the recorder. With the setting off, the push ends: no sync calls the recorder while it
  records for a transcript, which goes with the next sync or the next transcript.
- **Poll.** With "Auto sync" on, a sync that ran during a recording with the setting on and ended with files waiting
  for it (the active file, a deferred download or transcript) sets `SyncOutcome.Done.recordingPoll`. The next attempt
  then comes after a fixed 2 minutes (`RecordingPoll`), without the idle doubling, for at most 60 attempts in a row
  (about 2 hours); then the usual waits of `AutoSyncBackoff` apply. A failed attempt keeps the failure waits. The sync
  disconnects when it is done, as always: no link stays open between polls.

Not done here (stage S5 of the design): the recorder's radio still runs only in its Bluetooth phone mode, and the
companion device presence (the recorder paging the phone after a recording) does not start a sync yet.

## What runs when

- "Sync now" (the visible Recorder screen): the app starts `RecorderSyncService` (foreground service type
  `connectedDevice`, notification on the LOW channel `recorder`, `VISIBILITY_PRIVATE`), then the sync runs in the
  application scope. The service starts only when a sync can connect (bound, key accepted, Bluetooth ready). The
  notification changes at most twice a second. The service stops when the sync ends.
- After a successful binding: a sync starts at once.
- "Auto sync": while it is on, a recorder is bound and the process lives, an attempt runs at once and then after a
  wait. The wait is 3 minutes after a sync that copied or sent a file. After a sync with nothing to do, it doubles up
  to 15 minutes. After failed attempts, it is 1 minute and doubles up to 15 minutes. An attempt starts the service
  only when the sync has files to move, so failed attempts and checks that find nothing show no notification. A
  failure of an attempt never stops the loop.
- A new transcript goes to the recorder at once, also when "Auto sync" is off (`RecorderTranscriptPush`). When the
  note of a copied recording gets its finished transcript, an upload-only sync (`SyncMode.UPLOADS`) starts: it
  connects, authenticates, reads `STAT` and `LS`, and sends the sidecars that the recorder does not have. It does not
  download, so a transcript does not wait behind a long download. The next full sync copies the new recordings. The
  same check runs once when the push starts (the first time the app is visible, or after a binding), so a transcript
  that waited before the process ended goes too.
  - A transcript waits when the app has not sent its version, the recorder did not refuse it, its recording was in
    the last `LS` (each sync marks the records whose file is gone as `absent`) and has a sidecar name, and the
    recorder's firmware did not refuse transcripts in the last day (`RecorderSync.pendingUploads`, read without the
    recorder). So a transcript that no sync can send does not start attempts in each new process.
  - If a sync or a binding runs, the push waits for its end and tries again at once, without a failure. A full sync
    reads the transcripts again after its downloads, so a transcript that finished during a long download goes in the
    same session. A full sync ("Sync now", the sync after a binding, "Auto sync") waits for an upload-only sync
    instead of ending as busy.
  - If the recorder does not answer (it is not in Bluetooth phone mode), Bluetooth is off, or the sync stopped, the app
    tries again after 30 seconds. The wait doubles after each failure, up to 5 minutes (`TranscriptPushBackoff`).
  - The tries stop when no transcript waits, when a sync connected (what it did not send cannot go now: the recording
    went, or the recorder refused it), when only the user can make the sync possible (no permission, no Bluetooth,
    a refused key), after 25 failures in a row (about 1 h 50 min), or after 3 failed syncs in a row (a failure that
    comes back each time). A new transcript, a new process or a new binding starts them again; the next sync sends
    what waits. A note that loses its transcript does not stop the tries for the others.
  - The Recorder screen shows "Transcribed. Waiting for the recorder: put it in Bluetooth phone mode." for a transcript
    that waits, "Transcribed. The recording is no longer on the recorder." when its recording is gone, and "Transcript
    sent to the recorder" after the upload.
  - A new variant or a renamed speaker of a note that has a transcript already does not start a push; the next sync
    sends it.
- Android 12 and later refuse a foreground service start from the background. An app with a companion device (and
  `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND`) is exempt. Without the exemption, the sync continues
  without the service while the process lives. The app does not use WorkManager.

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` (Android 12 and later, at run time) | Bond, connect, read the bonded devices. The Recorder screen asks for it. After a denial, the screen shows "App settings". |
| `BLUETOOTH`, `BLUETOOTH_ADMIN` (`maxSdkVersion 30`) | The same on Android 11 and earlier. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | The type of `RecorderSyncService`. |
| `REQUEST_COMPANION_START_FOREGROUND_SERVICES_FROM_BACKGROUND` | "Auto sync" with a companion device. |

The app does not ask for `BLUETOOTH_SCAN` or a location permission: it does not scan. Bluetooth and the companion
device chooser are optional features (`android:required="false"`).

## Tests

- `:core`: the protocol and the CWX encoder, against `FakeRecorder` and the golden files of the reference encoder.
- `:app-logic`: `SyncPlannerTest`, `RecorderPoliciesTest`.
- `:data`: `RecorderStoreTest`.
- `:core`: `FakeRecorder` models the firmware of branch fix/xrec-cwx-sidecar when `recordTarget` is true (the refused
  payload is discarded, `ERR,NOENT` for a sidecar without its recording, a repeated `PUTEND` is answered again, `DEL`
  of a recording deletes its sidecar, names are case-insensitive), and the older firmware when it is false.
- `:core`: `FakeRecorder` with `syncsWhileRecording` models the firmware of branch feat/bt-sync-rec-hardening while it
  records: `STAT` with `rec_idx` and `pace_ms`, `ERR,BUSY` for the newest `.wav` (the active file), `ERR,LATER,30000`
  for `DEL`, `PUT` and `PUTEND`, `OK,RTC,LATER`, the `GETS` rate cap, a stereo recording that answers `GETS` with
  `LATER`, and `laterAtFrame` (a `LATER` in place of a frame).
- `:app`: `RecorderSyncTest` (a new recording to import, transcription and sidecar upload, also after a speaker
  rename; resume after a lost link and after process death; a recording in progress or one that starts during the
  sync; a refused key; old firmware asked again after a day; a sidecar whose recording went; a refused sidecar name;
  an unreadable recording; a recording removed after `LS`; an import refused for lack of space; a sync that stopped
  before the transcription request; a reused name and size after a card format; a stalled link; a store that cannot
  be written; binding with a wrong PIN; a stale bond; a transcript that finishes during the downloads; a full sync
  that waits for an upload-only one; while the recorder records: the finished recordings go in 16 KiB windows and the
  active file waits, with LS between two STATs and no clock; a recording that stops or splits during LS is listed
  again; an older firmware and the setting off keep skipping everything; a `LATER` defers the downloads to the next
  sync at once, which continues from the `.part` file; a `BUSY` file is skipped; a transcript refused with `LATER`
  waits without a failure),
  `RecorderAutoSyncTest` (the 2-minute poll while the recorder records, and the usual waits with the setting off),
  `RecorderTranscriptPushTest` (a transcript held by a recording is tried every 2 minutes with the setting on and goes
  after it, is not polled with the setting off, or is left to "Auto sync"; a new transcript goes at once with "Auto sync" off and without
  a download; a transcript that waits at the start goes; an unreachable recorder is tried again with growing waits
  until it takes the transcript, and at most 25 times; a sync that fails each time is tried 3 times; a recording that
  left the recorder ends the push after one sync; a busy recorder is no failure and a full sync takes the new
  transcript; a note that loses its transcript does not stop the others; no attempt when no transcript waits), `RecorderTranscriptsTest`, `GraphRecorderLibraryTest` and `RecorderViewModelTest`.

## Limits

- Only one recorder is bound at a time.
- The app syncs only while the recorder is in Bluetooth phone mode and the app process lives.
- While the recorder records, only finished recordings of a mono recording move, and only with "Sync while the
  recorder records" on and a recorder firmware that names its active file; transcripts wait for the end of the
  recording.
- The app imports only 16-bit PCM WAV. The importer refuses an IMA-ADPCM WAV; the screen shows "Not imported (WAV)",
  and the app does not download that file again.
- A note that the user deletes stays in the record of the recorder. The app does not copy that recording again.
- If `recorder/recorder.json` cannot be read, the store keeps it as `recorder.json.bad` and starts empty. The user
  must bind again, and the next sync copies the recordings again: a recording whose note is live gives that note, but
  a recording whose note the user deleted comes back as a new note.
- After a 60-second bond wait that timed out, the app does not cancel the bond (the API for it is hidden).
- The Recorder screen is a plain screen in `:app` (an exception to the `:ui` rule, see `architecture.md`).
