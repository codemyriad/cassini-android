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
| `:app-logic` | `recorder/SyncPlanner.kt`, `recorder/RecorderPolicies.kt` | What a sync downloads and uploads, the clock tolerance and the "Auto sync" waits. |
| `:data` | `data.recorder.RecorderStore` | The binding and the sync state, in `recorder/recorder.json` under the app root. |
| `:app` | `recorder/` | `RecorderSync` (the engine), its ports, the Bluetooth, Keystore and library adapters, `RecorderRunner`, `RecorderAutoSync`, `RecorderSyncService`, and the Recorder screen. |

`AppGraph.recorder` makes the parts. The app makes it when it is visible for the first time, and "Auto sync" starts
then.

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
   user pushes Pair. The recorder accepts inside its pairing window.
4. The app opens the secure RFCOMM socket and sends `PING`. The reply must have `pair=1` (the window is open).
5. The user types the PIN. The app makes a random 32-byte key (`SecureRandom`) and sends `PAIR,<pin>,<key>`. After a
   wrong PIN, the app asks again. After five wrong PINs, the recorder closes the window.
6. The app encrypts the key with an AES-256-GCM key of the Android Keystore and stores only the encrypted form. The
   app never logs a key. Then it starts a sync.

"Forget recorder" removes the binding and the key, and ends the companion association. The record of the copied
recordings stays, so a recorder that is bound again does not give the same notes again.

## A sync

One sync runs at a time (a `Mutex`). A sync does these steps:

1. Connect, then `CHAL` and `AUTH` with the stored key. If the recorder replies `ERR,AUTH` or `ERR,UNBOUND` (for
   example, a different phone bound it), the app shows "Bind again" and stops all syncs until the user binds again.
2. `STAT`. While the recorder records (media 33), no download starts.
3. `RTC`. If the recorder's clock is more than 5 seconds from the phone's local time, the app sets it.
4. `LS`, then `SyncPlanner` decides. The sync identity of a recording is its name and size. A recording with the same
   name and a different size is a new recording (the recorder numbers from 1 again after a card format).
5. For each new `.wav` file larger than 44 bytes: `GETS` downloads it into the cache (`recorder/<address>/<name>.part`).
   The client checks the offset and the CRC of each frame, and asks again from the last good offset after an error.
   A download that stops (a lost link, process death) continues from the length of the `.part` file at the next
   sync. Then the app imports the file (`ImportRepository.import`, title = the file name without `.wav`) and asks
   for a transcription (`ProcessingCoordinator.transcribe`, origin AUTOMATIC). Without model consent, the note waits
   for consent, as a recording does. The app writes the state after each file.
6. For each copied recording that is still on the recorder and whose note has a finished transcript: the app makes the
   CWX1 sidecar from the shown variant (the words, the note title, the audio duration, and the speaker labels and
   colours as the Note screen shows them, in the colours of the dark theme). It uploads the sidecar with
   `PUT ...,REC,<stem>.cwx:` in chunks of 600 bytes and commits it with `PUTEND`. The version of a sidecar is the
   variant ID and a hash of the bytes. Thus a new variant or a renamed speaker sends the sidecar again.
7. If the recorder refuses the `REC` target (`ERR,ARG` or `ERR,UNSUP`), its firmware is too old. The app tells it
   once and does not try again until the firmware version (from `PING`) changes.

The app does not delete recordings from the recorder (`TODO(recorder-sync)` in `RecorderSync`).

## What runs when

- "Sync now" (the visible Recorder screen): the app starts `RecorderSyncService` (foreground service type
  `connectedDevice`, notification on the LOW channel `recorder`, `VISIBILITY_PRIVATE`), then the sync runs in the
  application scope. The service stops when the sync ends.
- "Auto sync": while it is on, a recorder is bound and the process lives, an attempt runs at once and then after a
  wait. The wait is 3 minutes after a sync that connected. After failed attempts, it is 1 minute and doubles up to 15
  minutes. An attempt starts the service only when the link is up, so failed attempts show no notification. A new
  finished transcript of a copied recording starts an attempt at once.
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
- `:app`: `RecorderSyncTest` (a new recording to import, transcription and sidecar upload; resume after a lost link and
  after process death; a recording in progress; a refused key; old firmware; binding with a wrong PIN),
  `RecorderAutoSyncTest`, `RecorderTranscriptsTest`, `GraphRecorderLibraryTest` and `RecorderViewModelTest`.

## Limits

- Only one recorder is bound at a time.
- The app syncs only while the recorder is in Bluetooth phone mode and the app process lives.
- The app imports only 16-bit PCM WAV. The importer refuses an IMA-ADPCM WAV; the screen shows "Not imported (WAV)",
  and the app does not download that file again.
- A note that the user deletes stays in the record of the recorder. The app does not copy that recording again.
