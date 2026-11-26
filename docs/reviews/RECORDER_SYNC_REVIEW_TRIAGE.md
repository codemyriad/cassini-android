# Recorder sync review: triage

An independent reviewer (agy-2) reviewed `origin/main..2dc20c59` of the recorder sync in a separate worktree and
committed a fix with a test for each finding. Its report is copied in
[RECORDER_SYNC_REVIEW.md](RECORDER_SYNC_REVIEW.md). Meanwhile `feat/recorder-sync` went on with S4 (sync while the
recorder records: `c1104d47`, `91d18275`), which changed `XrecClient`, `RecorderSync`, `RecorderRunner` and
`SyncPlanner`. This page records, for each reviewer commit, whether its bug is still present on top of S4 and what
happened to the fix.

- **Accept**: the bug is present after S4; the commit was cherry-picked with `-x` as it is (Git merged it cleanly).
- **Port**: the bug is present after S4; the commit conflicted with S4 (or did not work with it) and was applied by
  hand on the S4 code, with the reviewer's test kept (adapted where noted).
- **Reject**: not taken (none).

| Reviewer commit | Finding | Decision | Commit on `feat/recorder-sync` |
| --- | --- | --- | --- |
| `5320e56e` | F5 `StreamXrecInput.close()` leaves the pump parked on a full ring and readers waiting | Accept | `70306c22` |
| `d33d7924` | F1 an open_fw `GETS` window keeps streaming after a recovered or failed download | Accept | `41c02a7c` |
| `a0388159` | F2 an unreadable suspect header lets the old transcript go to an unverified recording | Port | `2a650caf`, `773266ef` |
| `a977eac7` | F6 case-sensitive `.wav` names; `isSidecarName` differs from `is_rec_sidecar_name` | Port | `8c325bba` |
| `67390dd2` | F3 a dropped word cache sends a 0-word sidecar over a good one | Accept | `1809328b` |
| `836eadf1` | F7 `awaitIdle()` wakes before `lock.unlock()`; unbounded queued full syncs | Accept | `80c23f4b` |
| `ab8583de` | F4 no `startForeground` on a repeat start; 10 s notification; service start on `Busy` | Accept | `a5c2fc85` (\*) |
| `fc5de494` | F8 no `Cassini_XX` name on API 33+; `createBond()` after the device bonded | Accept | `e6f0d22f` |
| `0e78281d` | F9 an invalidated Keystore key breaks binding for good; forget keeps `.part` audio | Accept | `e7b2aaaf` |
| `7104e182` | detekt fixes across the fixes, and the report | Port | `876e739b`, `b2ebcd1c` |

(\*) The cherry-pick merged without conflicts but removed the `flow.first` import that S4's "Auto sync" code still
uses; the import was restored in the same commit.

## Per commit

**`5320e56e` (F5), accept.** `XrecInput.kt` and `BluetoothRecorderRadio.SocketLink` are unchanged by S4, so the leak
is there as reported: `close()` only closed the stream, and a pump parked in `put()` on a full ring never woke. The fix
(a `closed` flag under the lock, `signalAll()` before closing the stream, `SocketLink.close()` closing the input first)
is small and its two tests fail without it.

**`d33d7924` (F1), accept.** Checked against the firmware: `handle_ping` does not touch `gets_active`, which only `LS`,
`PUT` and a new `GETS` clear (`open_fw/src/xrec_proto.c`). S4 kept the recovered-download `resync()` and the failure
path that throws without ending the window, so both holes remain. The fix sends a 1-byte `GETS` at the end of the file
(it replaces the window and gives at most one empty frame) before the `PING`. With the S4 firmware that frame can also
be `ERR,BUSY` or `ERR,LATER`; `resync()` drops any reply up to its `PONG`, so that is fine. A wedged link now ends in
`TIMEOUT` from the resync instead of `INTEGRITY`, which ends the sync as a lost link should. Merged cleanly with the S4
pacing in `download()`.

**`a0388159` (F2), port.** `headOnRecorder` still returns null on a non-link failure and `verifySuspects` still keeps
the record, so the old transcript could go to a new recording after a card wipe. S4 adds more ways to get null there (a
`GETS` refused with `ERR,BUSY`/`ERR,LATER` while the recorder records), which makes the fix more useful. The conflict
was S4's `syncWhileRecording` plan and `holdUploads`: the filter on `unverified` now sits in front of the S4
`upload(..., holdUploads)` call in both modes.

On S4 the port broke two S4 tests (`RecorderTranscriptPushTest`, the transcript that waits for a recording): while the
recorder records, the head read of the suspect fails (`ERR,BUSY`), the upload was dropped and no longer counted as
deferred, so the push saw nothing waiting and stopped. A follow-up commit (`773266ef`) passes the unverified names to
`upload()`, which sends the others and counts those as waiting (`deferredUploads`), like a held transcript. The
reviewer's test now expects `deferredUploads = 1` for the sync with the unreadable head; the rest of it is unchanged.

**`a977eac7` (F6), port.** Checked against the firmware: `is_rec_sidecar_name` refuses names that start with `.` and
needs a stem before `.cwx` in any case, and the exFAT lookups compare names without case. S4's planner already reads
`REC_<n>.wav` without case (`recordingNumber`), but `known`, `has`, `partial`, `markAbsent` and `withRecording` still
compared with `==`. The conflict was S4's `skipReason(entry, status, whileRecording)`; the port keeps the S4 active-file
rule and uses the reviewer's `fileKey` for `known`. The `.part` rename for a resumed download whose name changed case
comes as reviewed.

**`67390dd2` (F3), accept.** `GraphRecorderLibrary` is unchanged by S4 and still read `words` without
`refreshIfStale`. The variant choice now matches `NoteRepository.selectedVariant` (selected, else default, else the
first readable one) and no transcript is sent while the word list is empty.

**`836eadf1` (F7), accept.** `sync()` and `bind()` are unchanged by S4: `held` turned false before `lock.unlock()`, so a
`RecorderTranscriptPush` woken by `awaitIdle()` could get a false `Busy`. This matters more with S4, whose push retries
a held transcript every 2 minutes during a recording. The counter that falls to 0 only after the unlock, and at most one
queued full sync, merged cleanly.

**`ab8583de` (F4), accept.** `RecorderSyncService` and `RecorderRunner.attempt()` had the reported behaviour after S4
(S4 changed only the "Auto sync" loop in `RecorderRunner.kt`). With S4 an "Auto sync" attempt runs every 2 minutes
while a recording holds files, so a second `startForegroundService` during a running service is more likely, and the
missing `startForeground` would crash the process. The service-start callback now fires from the sync that owns the
lock, so an attempt that gets `Busy` starts no service. Merged cleanly, except for the S4 import noted above.

**`fc5de494` (F8), accept.** `RecorderCompanion` and the bond flow are outside S4. The name fallback (display name,
then the associated device on API 34+, then `EXTRA_DEVICE`, then the adapter) and `createBond()` only from `BOND_NONE`
are taken as they are.

**`0e78281d` (F9), accept.** Outside S4. `wrap()` now replaces a Keystore key that cannot encrypt, and "Forget recorder"
also deletes the Keystore key, the stopped download's state and the `.part` cache. `PRIVACY.md` (and its APK copy) and
[recorder-sync.md](../recorder-sync.md) now say so.

**`7104e182` (detekt and report), port.** The detekt refactors (`recoverDownload`, `SuspectChecks`, `canQueueFull`,
`canWait`, the service follow loop, test wraps) were applied; the `latestUploads` call conflicted with S4's
`holdUploads` and was resolved like F2. The report is kept here as [RECORDER_SYNC_REVIEW.md](RECORDER_SYNC_REVIEW.md)
instead of `REVIEW_RECORDER_SYNC.md` at the repository root. With the review's tests, `RecorderSyncTest` went over
detekt's `LargeClass` limit; the S4 tests of a sync while the recorder records moved, unchanged, to
`RecorderSyncWhileRecordingTest`.

## Left as they are

- `RecorderSync.forget()` deletes the `.part` cache without taking the sync lock; a sync that downloads at that moment
  loses its partial file and the next sync starts that file again. "Forget recorder" during a sync is rare and the
  outcome is safe, so this stays.
- `RecorderRunner.updateServiceRuns` sets `serviceActive` inside `MutableStateFlow.update`, whose lambda can run more
  than once under contention; the last run writes the right value, so it is correct, only unusual.

## Not fixed by the reviewer (N1 to N6)

N1 to N3 are documented design choices and N4 to N6 were verified safe; none was changed here. S4 does not change them.

## Checks

`scripts/heavy-lock.sh ./gradlew :app:assembleDebug detekt ciTest :app:lintDebug :data:lintDebug :ui:lintDebug` on
JDK 17: green (649 `:app` unit tests, no failures; detekt and the three lint tasks clean). No device or connected test
was run.
