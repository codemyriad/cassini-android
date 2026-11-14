# Moving and backup (D97, D98)

Two automatic channels can copy the library off the phone: encrypted Android cloud backup (Auto Backup through
Google or Seedvault) and direct device-to-device transfer during a new phone's setup. D98 (D-980) turns cloud backup
on by default; transfer was already on (D97). `BuildConfig.CLOUD_BACKUP_ENABLED` is `true` in every build type and
remains the kill switch for the cloud channel. The Settings section and its copy follow cassini-ux `PARITY.md`,
Wave 6 contract §3 (Android table); the design note is `docs/design/phase2-d980.md`.

## Choices and capability

The device-local choices are in `noBackupFilesDir/choices.json`, outside the transport dataset, so a restored or
transferred library never brings the source phone's choices. Version 2 of the file holds:

| Field | Default | Meaning |
|---|---|---|
| `cloud` | on | Encrypted cloud backup. |
| `transfer` | on | Device-to-device transfer. |
| `voices` | on | "Include saved voices in backups": off leaves everything under `voices/` out of both channels. |
| `noticeAcknowledged` | off | The once-only "Keep them on this phone only" card was answered, or a choice changed in Settings. |
| `quotaExceeded` / `cloudQuotaExceeded` | off | The last transfer / cloud attempt was refused for its size. |

A version 1 file (D97 builds) was written only after a choice in Settings or a refused transfer, while the copy said
that nothing is sent to a cloud account. It keeps its stored `cloud = false`, so an update never starts uploading for
someone who had already chosen; they can turn cloud backup on in Settings, and the card does not show while cloud is
off. A missing file means the D98 defaults, so the card shows once. A damaged or unreadable file turns every channel
off until the user saves a choice.

The agent (`CassiniBackupAgent`) decides per run with `BackupChoices.permits`:

- Before API 28 Android exposes no transport flags, so cloud and transfer cannot be told apart: both are refused and
  `full_backup_content.xml` excludes every domain. Settings explains the limitation on each switch.
- A transport with the device-to-device flag (2, or API 36.1's cross-platform 8) follows `transfer`.
- Any other transport is cloud. It needs `cloud`, the build flag and `FLAG_CLIENT_SIDE_ENCRYPTION_ENABLED` (1), so the
  "Encrypted cloud backup" title is true whenever bytes leave. An unencrypted or unknown transport is refused. On API
  31+ `data_extraction_rules.xml` also sets `disableIfNoEncryptionCapabilities` on the cloud section.
- With `voices` off, `LibraryBackup.prepare(includeVoices = false)` lists nothing under `voices/` (saved people,
  generation and every note's voiceprints), so the dataset is complete without them and restore accepts it.

The XML rules on API 28+ are the same library ceiling for both channels (`cassini.db`, `notes/`, `voices/`, the
inventory); `requireFlags` would AND its flags, so the per-channel decision is the agent's. Seedvault can present
itself as a device-to-device transport; such a backup then follows the transfer choice, and the phone-only state
(both off) still emits nothing.

"Keep on this phone only" on the card turns off cloud and transfer and answers the card; "Keep backup on" and the
close button only answer it. Afterwards the switches are independent. Turning a channel off does not request or
confirm deletion of an earlier OS snapshot (`backup.cloud.body.off` says so for cloud).

From Android 9, the agent identifies direct device transfer through Android's transport flags. Restore is separately
authorized by Android; it can restore an earlier valid dataset after the source choice was turned off. It does not
restore the source device's choices or card state.

The six app languages use the coordinator's canonical catalog entries: the D97 transfer copy from cassini-ux commit
febfcc0 (see `ui/src/test/resources/transfer/UPSTREAM.json`) and the D98 Moving and backup copy from 31973ff
(`third_party/cassini-ux/wave5/strings.json`, generated into `ui/src/main/res/values*/wave6.xml`).

## Quota and complete datasets

The agent lets `fullBackupFile` measure every tar-framed entry during Android's preflight, even when
the payload exceeds the reported quota. Android can then report the actual `Quota exceeded` result
and invoke `onQuotaExceeded` before streaming. Throwing an exception before measurement would report
zero bytes through the one-way Binder callback, so it is not used for quota detection.
Settings shows `settings.transfer.tooLarge` after a transfer quota failure and `backup.info.android.overQuota` after a
cloud one (Android's 25 MB Auto Backup limit); both point to ZIP export. The agent remembers which channel it measured,
so `onQuotaExceeded` records the warning for that channel only. The
normal explanation also recommends Export All as ZIP. No smaller subset is silently substituted.
Downloaded models are never transferred. A permitted retry clears that channel's size warning only
after snapshot preparation succeeds; a new quota refusal sets it again. Refused transports and failed
preparation leave the warning intact. Unchanged choices do not create or replace an atomic file.

Android's production D2D limit can differ from the local test transport. We do not hard-code the
cloud limit as the transfer limit. The test transport retains its 25 MiB quota even when its D2D
flag is set, which makes a small, deterministic oversized-library test possible.

## Dataset and restore

The XML itself excludes every credential/device-protected domain and external files from cloud.
API26–27 exclude all backup domains; API28–30 include library files only with the D2D requirement.
Modern device-transfer rules include the library ceiling separately from cloud exclusions. These
privacy restrictions do not depend on exceptions from the one-way agent Binder being propagated.
The full-backup agent also emits only its positive file allowlist:
the closed, checkpointed `cassini.db`, canonical note audio/documents and committed saved-voice state.
Models, receipts, download consent, app settings, cache directories, unfinished work files and temporary
voiceprint updates do not travel. The catalogue can contain derived cache rows and transient jobs;
restore re-derives the former and removes the latter before publishing the library. Existing models
on the target are retained. Exported Cassini documents never contain saved voiceprints.

The agent refuses to run once graph construction starts. It never creates the graph or starts a
service or recognizer. Full backup is not allowed in the foreground. A denied transport emits no library data. Android's one-way agent Binder may still acknowledge an empty
archive; an exception alone cannot guarantee a transport failure or preservation of an earlier snapshot.
The declarative cloud exclusions remain independent of that acknowledgement. Snapshot retention
belongs to Android and the provider.

Before creating an inventory, preparation recovers any interrupted restore transaction. An unresolved
or damaged journal refuses the snapshot. It then opens admitted voice files through `AtomicFile`,
recovering committed `.bak` data on Android 9–10 even when the base is missing or truncated. Links
and unresolved atomic recovery refuse the snapshot; `.bak` files are not copied as separate payloads.

An inventory records every admitted file's path, size and SHA-256. Incoming entries stream into
private staging and are hashed as they arrive. Tar modification times are epoch seconds and are
restored in that unit, with negative values clamped to the epoch. Each completely received file gets
a durable hash receipt. The inventory is emitted last and its expected entry count is parsed while
receiving; finalization checks completeness using only that count and the received set size.
A refused entry latches failure for all later file/finish callbacks on that receiver. The deadline-bound `onRestoreFinished` callback only commits a small, checksum-protected
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
the startup worker. Cleanup failures cannot undo a committed restore. A received stage proven incomplete or corrupt is
retired on startup, leaving the intact live library accessible and recording a rejection diagnostic.
Transient storage failures and unsupported newer catalogue schemas retain the received transaction.
A later restore may supersede an unapplied received transaction without deleting its data inside the
OS callback; startup cleans superseded stages. Interrupted applying transactions must recover before
they can be replaced. A backup requested before a received restore has completed startup is refused,
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

The real API 35 arm64 local-transport matrix (`scripts/check-backup-transport.py`) starts from a fresh install
with the D98 defaults (cloud backup, transfer and saved voices on, no choices file). It checks that an unencrypted
cloud transport is refused despite cloud backup being on, that encrypted cloud is refused with cloud backup off and
accepted with it on, transfer off/on, a 26 MiB library exceeding the local D2D quota, a smaller successful retry
with the warning cleared, and verified restores before and after that retry. Each restored phone has no choices
file and reads the D98 defaults: the source phone's choices never travel. Restored notes retain UTC, words and
speaker labels; synthetic saved-voice matching works; models and unfinished jobs are excluded. The D97 run's
commands, APK hashes and reports are retained with that PR's evidence; the D98 matrix needs an arm64 host and has
not yet been rerun on this branch.

To repeat on a task-owned disposable emulator, build the debug app and instrumentation APKs, then
run `python3 scripts/check-backup-transport.py --help` for the SDK, APK and external output paths.
The harness creates its own AVD and isolated adb server, uses `-no-audio`, and stops both on completion.
It refuses personal devices. Local transport checks do not prove an OEM's cable-transfer implementation.

## Privacy page and Play data-safety draft — not published

`PRIVACY.md` describes both channels as shipped by D98. Proposed Play explanation for review: on-device
transcription; optional, default-on encrypted Android cloud backup (Google or Seedvault, only with client-side
encryption) and direct phone-setup transfer of note audio, transcripts, library metadata, speaker names and, unless
"Include saved voices in backups" is off, saved voiceprints; no downloaded models. Cassini itself sends none of it
to its servers; Android's backup service does. The purpose is backup and migration to the user's new phone, not
analytics or advertising. Review the current Play categories and OS-backup treatment before submission; do not
infer an exemption or retain an absolute claim that data cannot leave the phone. No store form has been
published by this PR.

## Platform references

- [Auto Backup and its 25 MB cloud limit](https://developer.android.com/identity/data/autobackup)
- [Testing backup and device transfer](https://developer.android.com/identity/data/testingbackup)
- [Restore finalization deadline](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/services/backup/java/com/android/server/backup/BackupAgentTimeoutParameters.java)
- [Operation quota](https://developer.android.com/reference/android/app/backup/FullBackupDataOutput#getQuota())
- [Manifest restoreAnyVersion](https://developer.android.com/guide/topics/manifest/application-element#restoreAnyVersion)
- [Android package-keyed restore and version checks](https://android.googlesource.com/platform/frameworks/base/+/e0e69f8704cb96f12e8fb1530524443136daaf7e/services/backup/java/com/android/server/backup/restore/PerformUnifiedRestoreTask.java)
- [Local test transport quota](https://android.googlesource.com/platform/frameworks/base/+/master/packages/LocalTransport/src/com/android/localtransport/LocalTransport.java)
