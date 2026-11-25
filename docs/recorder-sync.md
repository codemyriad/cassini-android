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
| `:app-logic` | `recorder/SyncPlanner.kt`, `recorder/RecorderPolicies.kt` | What a sync downloads and uploads, the clock tolerance, the "Auto sync" waits and the waits of a transcript that goes at once. |
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
2. `STAT`. While the recorder records (media 33), no download starts. Before each download after the first, the app
   sends `STAT` again, and stops the downloads if a recording started.
3. `RTC`. If the recorder's clock is more than 5 seconds from the phone's local time, the app sets it.
4. `LS`, then `SyncPlanner` decides. The sync identity of a recording is its name and size. A recording with the same
   name and a different size is a new recording (the recorder numbers from 1 again after a card format). The app also
   keeps the SHA-256 of the first 4 KiB of each recording. When the listing has a copied recording but not its
   sidecar, although the app sent one or has one to send, the app downloads the first 4 KiB again and compares them.
   A different file (a new recording with the same name and size after a card format) is a new recording: the app
   copies it, and does not send the old transcript to it.
5. For each new `.wav` file larger than 44 bytes: `GETS` downloads it into the cache (`recorder/<address>/<name>.part`).
   The client checks the offset and the CRC of each frame, and asks again from the last good offset after an error.
   A download that stops (a lost link, process death) continues from the length of the `.part` file at the next
   sync. Then the app imports the file (`ImportRepository.import`, title = the file name without `.wav`) and asks
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
  - A transcript waits when the app has not sent its version and the recorder did not refuse it
    (`RecorderSync.pendingUploads`, read without the recorder).
  - If the recorder does not answer (it is not in Bluetooth phone mode), a sync runs, Bluetooth is off, or the sync
    stopped, the app tries again after 30 seconds. The wait doubles after each failure, up to 5 minutes
    (`TranscriptPushBackoff`). The tries continue while a transcript waits and the process lives.
  - The tries stop when no transcript waits, when a sync connected (what it did not send cannot go now: the recording
    went, or the recorder refused it), or when only the user can make the sync possible (no permission, no Bluetooth,
    a refused key).
  - The Recorder screen shows "Transcribed. Waiting for the recorder: put it in Bluetooth phone mode." for a transcript
    that waits, and "Transcript sent to the recorder" after the upload.
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
- `:app`: `RecorderSyncTest` (a new recording to import, transcription and sidecar upload, also after a speaker
  rename; resume after a lost link and after process death; a recording in progress or one that starts during the
  sync; a refused key; old firmware asked again after a day; a sidecar whose recording went; a refused sidecar name;
  an unreadable recording; a recording removed after `LS`; an import refused for lack of space; a sync that stopped
  before the transcription request; a reused name and size after a card format; a stalled link; a store that cannot
  be written; binding with a wrong PIN; a stale bond),
  `RecorderAutoSyncTest`, `RecorderTranscriptPushTest` (a new transcript goes at once with "Auto sync" off and without
  a download; an unreachable recorder is tried again with growing waits until it takes the transcript; no attempt when
  no transcript waits), `RecorderTranscriptsTest`, `GraphRecorderLibraryTest` and `RecorderViewModelTest`.

## Limits

- Only one recorder is bound at a time.
- The app syncs only while the recorder is in Bluetooth phone mode and the app process lives.
- The app imports only 16-bit PCM WAV. The importer refuses an IMA-ADPCM WAV; the screen shows "Not imported (WAV)",
  and the app does not download that file again.
- A note that the user deletes stays in the record of the recorder. The app does not copy that recording again.
- If `recorder/recorder.json` cannot be read, the store keeps it as `recorder.json.bad` and starts empty. The user
  must bind again, and the next sync copies the recordings again: a recording whose note is live gives that note, but
  a recording whose note the user deleted comes back as a new note.
- After a 60-second bond wait that timed out, the app does not cancel the bond (the API for it is hidden).
- The Recorder screen is a plain screen in `:app` (an exception to the `:ui` rule, see `architecture.md`).
