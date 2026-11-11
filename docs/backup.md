# Transfer and backup (D97)

Implementation status: draft. The manifest still has `allowBackup=false`. Runtime controls,
shared translations and the real local-transport checks must be complete before activation.

The device-local choices are in `noBackupFilesDir/choices.json`, outside both transport datasets.
Transfer starts enabled and cloud backup starts disabled. The Library explains transfer once;
dismissal or changing its Settings switch acknowledges the notice. Enabling cloud backup does
not claim Android has completed a backup. Turning a switch off does not delete an earlier OS snapshot.
A damaged or unreadable choices file disables both routes until the user explicitly saves a choice.

From Android 9, the agent identifies direct device transfer through Android's transport flags.
Unknown transports require the cloud choice. Android 8 does not expose those flags: the independent
transfer switch is disabled and its limitation is explained. The cloud choice gates all transports
on those versions. Restore is separately authorized by Android; it does not opt the new device into
cloud backup, and it can restore an earlier valid dataset after the source choice was turned off.

## Dataset and restore

The static XML rules are a ceiling. The full-backup agent also emits only its positive file allowlist:
the closed, checkpointed `cassini.db`, canonical note audio/documents and committed saved-voice state.
Models, receipts, download consent, app settings, caches, unfinished inference work and temporary
voiceprint updates do not travel. Existing models on the target are retained. Saved voiceprints
remain private app data; exporting a Cassini document does not embed them.

The agent refuses to run once graph construction starts. It never creates the graph or starts a
service or recognizer. Full backup is not allowed in the foreground. A denied transport fails the
operation rather than publishing an empty replacement for an earlier OS snapshot. Android's actual
quota-exceeded callback records the failure for Settings; the app never silently substitutes a
partial library or claims a toggle proves a successful backup.

An inventory records every admitted file's path, size and SHA-256. Incoming entries stream into
private staging; missing, changed, duplicate, linked, unexpected or oversized data is rejected before
live data is replaced. The restored catalogue is opened and repaired in staging: document-derived
caches are refreshed and transient jobs are removed. Notes, catalogue and saved voices are swapped
with a checksum-protected durable journal. Cold startup recovers this journal before Room opens.
An interrupted replacement rolls back; a committed replacement is retained. Cleanup failures cannot
undo a committed restore, and failed recovery retains the journal and original library roots.

## Validation before ready

Run the policy/inventory, restore transaction, real Room/voice, agent, Settings and notice unit tests.
Exercise Android's local transport with a synthetic library on an owned muted emulator: disabled
cloud refusal, enabled cloud success, direct-transfer on/off, restored saved voice matching, absence
of models and a consistent catalogue. Local transport checks do not prove an OEM's cable transfer
implementation. Retain the exact commands/results in the PR and check the bug classes before ready.

## Privacy page and Play data-safety draft — not published

Proposed privacy-page copy, for coordinator review:

> Transcription runs on your phone. During Android setup, Android can transfer your notes, library
> and saved voices to a new phone. Transfer is on by default where Android supports a separate
> control; you can turn it off in Settings. Android cloud backup is off until you turn it on.
> If enabled, Android can back up your notes, library and saved voices to your backup account.
> Downloaded models are excluded from both. Shared Cassini files do not contain voiceprints.
> Turning a choice off does not remove a backup Android already holds.

Play data-safety review notes (not a submitted declaration): distinguish OS-managed transfer and
backup from Cassini-operated network collection; account for note audio, transcript/name metadata
and saved voiceprints in the optional backup dataset. Do not declare all data incapable of leaving
the device. Confirm the current Play form's treatment of OS backups and the chosen backup provider,
retention and deletion controls before publishing. No app-operated upload endpoint or new account
selection is introduced by this change. Existing model-download networking remains separate.

Platform references: [BackupAgent](https://developer.android.com/reference/android/app/backup/BackupAgent)
and [Android backup rules](https://developer.android.com/identity/data/autobackup).
