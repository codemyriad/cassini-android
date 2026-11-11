# Moving to a new phone (D97)

Device transfer is enabled by the device-local choice in Settings.
`BuildConfig.CLOUD_BACKUP_ENABLED` is fixed to `false` in every build type. There is no cloud switch
or cloud-backup offer. The agent refuses cloud and unknown transports even if a stored preference
contains an old opt-in. Android cloud Auto Backup has a 25 MB per-app limit; this is unsuitable for
a library of recordings. Enabling cloud backup needs a separate product decision and UI copy.

## Choices and capability

The device-local choices are in `noBackupFilesDir/choices.json`, outside the transport dataset.
Transfer starts enabled. The Library explains it once; dismissal or changing the Settings switch
acknowledges the notice. Turning it off does not request or confirm deletion of an earlier OS
snapshot. A damaged or unreadable choices file disables transfer until the user saves a choice.

From Android 9, the agent identifies direct device transfer through Android's transport flags.
Unknown transports are refused while the cloud build flag is off. Android 8 does not expose those
flags: transfer is unavailable and Settings explains the limitation. Restore is separately
authorized by Android; it can restore an earlier valid dataset after the source choice was turned
off. It does not restore the source device's consent or notice state.

The six app languages use the coordinator's canonical catalog entries from cassini-ux commit
febfcc0 (see `ui/src/test/resources/transfer/UPSTREAM.json` for the full pin and hashes).

## Quota and complete datasets

The agent reads `FullBackupDataOutput.getQuota()` for the actual operation. If the file payload and
inventory alone exceed that quota, it records the quota failure and refuses before emitting data.
Android also measures transport framing; `onQuotaExceeded` records a later platform refusal.
Settings shows `settings.transfer.tooLarge` after a quota failure and points to ZIP export. The
normal explanation also recommends Export All as ZIP. No smaller subset is silently substituted.
Downloaded models are never transferred. A permitted retry clears the previous size warning before
preparation; a new quota refusal sets it again. A refused transport does not clear that warning.

Android's production D2D limit can differ from the local test transport. We do not hard-code the
cloud limit as the transfer limit. The test transport retains its 25 MiB quota even when its D2D
flag is set, which makes a small, deterministic oversized-library test possible.

## Dataset and restore

The static XML rules are a ceiling. The full-backup agent also emits only its positive file allowlist:
the closed, checkpointed `cassini.db`, canonical note audio/documents and committed saved-voice state.
Models, receipts, download consent, app settings, cache directories, unfinished work files and temporary
voiceprint updates do not travel. The catalogue can contain derived cache rows and transient jobs;
restore re-derives the former and removes the latter before publishing the library. Existing models
on the target are retained. Exported Cassini documents never contain saved voiceprints.

The agent refuses to run once graph construction starts. It never creates the graph or starts a
service or recognizer. Full backup is not allowed in the foreground. A denied transport fails the
operation rather than publishing a successful empty archive. Backup failure does not promise that
every transport will retain its previous snapshot; retention belongs to Android and the provider.

Before creating an inventory, preparation recovers any interrupted restore transaction. An unresolved
or damaged journal refuses the snapshot. It then opens admitted voice files through `AtomicFile`,
recovering committed `.bak` data on Android 9–10 even when the base is missing or truncated. Links
and unresolved atomic recovery refuse the snapshot; `.bak` files are not copied as separate payloads.

An inventory records every admitted file's path, size and SHA-256. Incoming entries stream into
private staging and are hashed as they arrive. Each completely received file gets a durable hash
receipt. The deadline-bound `onRestoreFinished` callback only commits a small, checksum-protected
received-transaction pointer. It does not scan payloads, read the inventory, open Room, rebuild
transcripts, replace library roots or delete staging trees. Error cleanup is deferred too.

Cold startup finishes the transaction before opening the live Room database or publishing the Library.
It compares received hash receipts with the inventory and revalidates the staged files. Missing,
changed, duplicate, linked, unexpected or oversized data refuses startup before live data is replaced.
Startup reads the received notes in place while it opens and repairs a separate catalogue copy.
The original received catalogue stays unchanged, so interrupted migration or cache rebuilding can
restart from verified input. Audio and saved voices are only moved during journaled promotion;
no hard-link permission or duplicate audio storage is required.

Notes, the repaired catalogue and saved voices are swapped with a durable journal. An interrupted
replacement first rolls back, retaining the received transaction for a fresh preparation and retry;
a committed replacement is retained. All validation, catalogue work and recursive cleanup happen on
the startup worker. Cleanup failures cannot undo a committed restore. Failed recovery retains its
journal and old roots. A backup requested before a received restore has completed startup is refused,
so an incomplete or older library cannot become the next successful snapshot.

## Package and app-version binding

Android restores a package dataset to the same installed package identity and checks signing
compatibility. This is not migration between package names: `io.codemyriad.cassini.talk` does not
receive the old `io.codemyriad.cassini` dataset, and the debug `.talk.debug` package is separate.
This conclusion comes from Android's package-keyed restore implementation, rather than a physical
cross-package phone-transfer test. Use exported files to move notes across those app identities.

The manifest explicitly sets `android:restoreAnyVersion="false"`, preserving Android's default:
a dataset from an app with a newer versionCode is not restored into an older app. This attribute
does not relax package or signing checks. The app separately rejects unsupported inventory and
catalogue schemas before replacing the live library.

## Validation

The focused tests cover transport consent, complete inventories, quota errors and retry reset,
atomic choices, journaled root replacement, cache repair and saved-voice preservation. API 28 and
29 tests interrupt actual `AtomicFile` writes, then verify the inventory and restored committed
voices. Preparation is tested immediately after each of nine interrupted restore moves, after a
committed interruption, and with a damaged journal. Deliberately omitting voice recovery, journal
recovery or quota reset fails the corresponding regression; restored source passes.

Deferred-restore tests hide both the incoming directory and receipt file during finalization, verify
that no prepared catalogue or live library is created, and restore access before startup. A received
unsupported catalogue is accepted into durable staging without being opened by finalization, then
refused at startup without replacing the live library. Tampered payloads/receipts, truncated or
duplicate receipts and oversized metadata are refused. Every interrupted promotion move is retried
from unchanged received bytes. AppGraph tests verify that its catalogue cannot expose restored
jobs or unvalidated rows, and invalid input prevents the live database from opening. These assertions
use state and injected events, not elapsed-time budgets.

The real API 35 arm64 local-transport matrix checks cloud refusal despite a stored opt-in, transfer
off/on, a 26 MiB library exceeding the local D2D quota, a smaller successful retry with the warning
cleared, and verified restores before and after that retry. Restored notes retain UTC, words and
speaker labels; synthetic saved-voice matching works; models and unfinished jobs are excluded.
Current commands, APK hashes and reports are retained with the PR evidence.

To repeat on a task-owned disposable emulator, build the debug app and instrumentation APKs, then
run `python3 scripts/check-backup-transport.py --help` for the SDK, APK and external output paths.
The harness creates its own AVD and isolated adb server, uses `-no-audio`, and stops both on completion.
It refuses personal devices. Local transport checks do not prove an OEM's cable-transfer implementation.

## Privacy page and Play data-safety draft — not published

Proposed privacy-page copy for review:

> Transcription runs on your phone. On Android 9 or newer, Android can copy your notes, library and
> saved voices directly to a new phone during setup. This choice starts on; Cassini explains it once
> and you can turn it off in Settings. Downloaded models are not copied. Saved voiceprints move with
> enabled phone transfer but are never written into shared Cassini documents. Cassini does not offer
> Android cloud backup. On Android 8, this transfer choice is unavailable.
>
> Turning the choice on does not prove that a transfer has completed. Android can refuse a library
> that exceeds the transfer limit; Cassini reports that failure. Save important notes as exported files.
> Turning the choice off does not confirm deletion of any earlier OS snapshot.

Proposed Play explanation for review: on-device transcription; optional direct phone-setup transfer
of note audio, transcripts, library metadata, speaker names and saved voiceprints; no downloaded
models; no cloud-backup offer. The purpose is migration to the user's new phone, not analytics or
advertising. Review the current Play categories and OS-transfer treatment before submission; do not
infer an exemption or retain an absolute claim that data cannot leave the phone. No store form or
privacy page has been published by this PR.

## Platform references

- [Auto Backup and its 25 MB cloud limit](https://developer.android.com/identity/data/autobackup)
- [Testing backup and device transfer](https://developer.android.com/identity/data/testingbackup)
- [Restore finalization deadline](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/services/backup/java/com/android/server/backup/BackupAgentTimeoutParameters.java)
- [Operation quota](https://developer.android.com/reference/android/app/backup/FullBackupDataOutput#getQuota())
- [Manifest restoreAnyVersion](https://developer.android.com/guide/topics/manifest/application-element#restoreAnyVersion)
- [Android package-keyed restore and version checks](https://android.googlesource.com/platform/frameworks/base/+/e0e69f8704cb96f12e8fb1530524443136daaf7e/services/backup/java/com/android/server/backup/restore/PerformUnifiedRestoreTask.java)
- [Local test transport quota](https://android.googlesource.com/platform/frameworks/base/+/master/packages/LocalTransport/src/com/android/localtransport/LocalTransport.java)
