package io.codemyriad.cassini.recorder

import android.util.Log
import io.codemyriad.cassini.core.recorder.CwxEncoder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.core.recorder.XrecClient
import io.codemyriad.cassini.core.recorder.XrecDownloadStats
import io.codemyriad.cassini.core.recorder.XrecException
import io.codemyriad.cassini.core.recorder.XrecFailure
import io.codemyriad.cassini.core.recorder.XrecLimits
import io.codemyriad.cassini.core.recorder.XrecNames
import io.codemyriad.cassini.core.recorder.XrecReply
import io.codemyriad.cassini.core.recorder.XrecTimeouts
import io.codemyriad.cassini.data.recorder.LastSync
import io.codemyriad.cassini.data.recorder.RecorderBinding
import io.codemyriad.cassini.data.recorder.RecorderDevice
import io.codemyriad.cassini.data.recorder.RecorderDeviceState
import io.codemyriad.cassini.data.recorder.RecorderStore
import io.codemyriad.cassini.data.recorder.StoredRecording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.Flushable
import java.io.IOException
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/** What the recorder sync does now, for the Recorder screen and the notification. */
sealed interface RecorderActivity {
    data object Idle : RecorderActivity

    /** Opening the Bluetooth link. */
    data object Connecting : RecorderActivity

    /**
     * A step of a sync: [file] and its [done] of [total] bytes when the step has a file. The byte count changes at most
     * once for each percent, so the screen and the notification do not redraw for each frame.
     */
    data class Syncing(val step: SyncStep, val file: String? = null, val done: Long = 0, val total: Long = 0) :
        RecorderActivity

    data class Binding(val step: BindStep) : RecorderActivity
}

/** The steps of a sync. [CHECKING] reads the recorder's state; the others move files. */
enum class SyncStep { CHECKING, DOWNLOADING, IMPORTING, UPLOADING }

enum class BindStep { BONDING, CONNECTING, PIN, PAIRING }

/** What one sync moves. */
enum class SyncMode {
    /** Downloads the new recordings, then sends the transcripts. */
    FULL,

    /**
     * Only sends the transcripts (a new transcript is ready): no download, so a waiting transcript does not wait behind
     * a long download. The new recordings stay for the next full sync.
     */
    UPLOADS,
}

/**
 * The download speed of one sync: [bytes] of recordings moved in [millis] (resumed bytes not counted), with [retries]
 * failed GETS windows. Null in [SyncOutcome.Done] when nothing was downloaded.
 */
data class DownloadSpeed(val bytes: Long, val millis: Long, val retries: Int) {
    /** Kilobytes (1000 bytes) per second; 0 when no time was measured. */
    val kilobytesPerSecond: Long get() = if (millis > 0) bytes / millis else 0

    operator fun plus(stats: XrecDownloadStats) =
        DownloadSpeed(bytes + stats.bytes, millis + stats.millis, retries + stats.retries)
}

/** How one sync ended. */
sealed interface SyncOutcome {
    /**
     * The sync ran. [skipped] counts the new files it did not download (those that wait for a recording included),
     * [failed] the files it could not download or import this time (the next sync tries them again). [speed] measures
     * the downloads that completed. [deferredUploads] counts the transcripts that the recorder takes only after its
     * recording (`ERR,LATER`); [recordingPoll] is true when the recorder records, "Sync while the recorder records" is
     * on and files wait for the end of the recording: "Auto sync" checks again after [RecordingPoll.INTERVAL_MS].
     */
    data class Done(
        val downloaded: Int,
        val uploaded: Int,
        val skipped: Int,
        val deviceRecording: Boolean,
        val failed: Int = 0,
        val speed: DownloadSpeed? = null,
        val deferredUploads: Int = 0,
        val recordingPoll: Boolean = false,
    ) : SyncOutcome {
        /** Nothing moved: "Auto sync" waits longer before the next attempt. */
        val idle: Boolean get() = downloaded == 0 && uploaded == 0
    }

    data object NotBound : SyncOutcome

    /** A sync or a binding runs already. */
    data object Busy : SyncOutcome

    data class Unavailable(val readiness: RadioReadiness) : SyncOutcome

    /** The recorder did not answer: it is not in Bluetooth phone mode, or out of range. */
    data object Unreachable : SyncOutcome

    /** The recorder refused the stored key (another phone bound it, or it lost its key): bind it again. */
    data object RebindNeeded : SyncOutcome

    /**
     * The sync stopped with [failure] (the link closed, no reply, a device error), or with null and the [message] of a
     * failure in the phone (storage, Keystore, library).
     */
    data class Failed(val failure: XrecFailure?, val message: String?) : SyncOutcome
}

/** How a binding ended. */
sealed interface BindOutcome {
    data object Bound : BindOutcome

    data object Busy : BindOutcome

    data class Unavailable(val readiness: RadioReadiness) : BindOutcome

    /** Android did not bond with the recorder (the user refused, or the pairing window closed). */
    data object NotBonded : BindOutcome

    data object Unreachable : BindOutcome

    /**
     * The phone had a bond with the recorder, but the recorder refused the link: it lost its side of the bond (a reset,
     * new firmware, or a bond with another phone). The user removes the recorder in the Bluetooth settings of Android,
     * then binds again.
     */
    data object StaleBond : BindOutcome

    /** The recorder's pairing window is not open (or closed after five wrong PINs): press ENTER on it again. */
    data object PairingClosed : BindOutcome

    data object Cancelled : BindOutcome

    data class Failed(val failure: XrecFailure?, val message: String?) : BindOutcome
}

/**
 * The recorder sync (docs/recorder-sync.md): binds a recorder, and runs one sync at a time with the bound one.
 *
 * A sync connects, authenticates with CHAL/AUTH, reads STAT, sets the recorder's clock when it is more than 5 s off,
 * lists /record, and lets [SyncPlanner] decide. It downloads each new recording into [Parts.downloads] (a `.part`
 * file that a later sync continues, also after process death), imports it, and asks for its transcription. Then it
 * sends each finished transcript that the recorder does not have as the CWX sidecar `REC,<stem>.cwx`. The state is
 * written after each file, so a sync that stops loses at most the file in progress.
 *
 * While the recorder records, a firmware that names the file it records (STAT `rec_idx`) lets the finished recordings
 * go at its pace when "Sync while the recorder records" is on ([SyncPlanner]); it answers `ERR,LATER,<ms>` to what
 * must wait for the end of the recording: a download (a stereo recording) keeps its `.part` file and waits for a later
 * sync at once, without holding the link, and transcripts wait for the end of the recording
 * ([SyncOutcome.Done.deferredUploads]).
 *
 * A link failure or a timeout ends the sync quietly: the recorder's radio is on only while it is in Bluetooth phone
 * mode. A file that fails for another reason is skipped, and the sync goes on. A refused key marks the recorder
 * "re-bind needed", and no sync runs until it is bound again. No failure escapes [sync] or [bind]: they run in the
 * application scope and in the screen's scope, where an exception would end the process.
 */
@Suppress("TooManyFunctions", "LargeClass") // one function per sync step
class RecorderSync(private val parts: Parts) {
    /** The seams of the sync; [AppGraph][io.codemyriad.cassini.AppGraph] passes the app's, tests pass fakes. */
    @Suppress("LongParameterList") // one seam per collaborator
    class Parts(
        val store: RecorderStore,
        val radio: RecorderRadio,
        val vault: KeyVault,
        val library: RecorderLibrary,
        /** The directory of the `.part` downloads (in the cache). */
        val downloads: File,
        val io: CoroutineDispatcher,
        val clock: () -> Long = System::currentTimeMillis,
        val zone: () -> ZoneId = ZoneId::systemDefault,
        val timeouts: XrecTimeouts = XrecTimeouts(),
        val random: SecureRandom = SecureRandom(),
        /**
         * A client call that makes no progress for this long closes the link. Reads time out by themselves; a socket
         * write does not, and ignores interrupts.
         */
        val stallMillis: Long = DEFAULT_STALL_MS,
    )

    private val store = parts.store
    private val lock = Mutex()
    private val stateLock = Any()

    /** Count of running or queued operations ([sync] and [bind]); drops to 0 only after [lock] is unlocked. */
    private val active = MutableStateFlow(0)

    /** Guarded by [stateLock]: the mode of the sync that holds [lock], and queued waiters behind it. */
    private var running: SyncMode? = null
    private var fullQueued = false
    private var bindWaiters = 0

    @Volatile private var movingCallback: (() -> Unit)? = null
    private val current = MutableStateFlow<RecorderActivity>(RecorderActivity.Idle)

    val activity: StateFlow<RecorderActivity> = current.asStateFlow()

    /**
     * Syncs with the bound recorder; [SyncOutcome.Busy] at once when a sync or a binding runs already. At most one full
     * sync waits for an upload-only one (a transcript push, which is short) instead: "Sync now" and the first attempt
     * of "Auto sync" are not lost to it. [onMovingFiles] runs once when this sync first downloads, imports or uploads.
     */
    suspend fun sync(mode: SyncMode = SyncMode.FULL, onMovingFiles: (() -> Unit)? = null): SyncOutcome {
        val acquired = synchronized(stateLock) {
            if (lock.tryLock()) {
                running = mode
                active.update { it + 1 }
                true
            } else if (mode == SyncMode.FULL && running == SyncMode.UPLOADS && !fullQueued && bindWaiters == 0) {
                fullQueued = true
                active.update { it + 1 }
                false
            } else {
                return SyncOutcome.Busy
            }
        }
        if (!acquired) {
            try {
                lock.lock()
            } catch (error: CancellationException) {
                synchronized(stateLock) { fullQueued = false }
                active.update { it - 1 }
                throw error
            }
            synchronized(stateLock) {
                fullQueued = false
                running = mode
            }
        }
        var notified = false
        movingCallback = onMovingFiles?.let { callback ->
            {
                if (!notified) {
                    notified = true
                    callback()
                }
            }
        }
        return try {
            syncLocked(mode)
        } finally {
            movingCallback = null
            current.value = RecorderActivity.Idle
            releaseLock()
        }
    }

    /** Returns when no sync and no binding runs: at once when none does. */
    suspend fun awaitIdle() {
        active.first { it == 0 }
    }

    /** True when a sync can try to connect now: a recorder is bound, its key is accepted, and Bluetooth is ready. */
    suspend fun canSync(): Boolean {
        val settings = store.read()
        return settings.binding != null && settings.current?.rebindNeeded != true &&
            parts.radio.readiness() == RadioReadiness.READY
    }

    /**
     * The notes of the bound recorder's imported recordings whose finished transcript a sync can send: the recorder
     * does not have it yet (not sent in this version, and not refused for good), the recording was on the recorder at
     * the last sync that listed its files and has a sidecar name, and its firmware did not refuse transcripts within
     * [TranscriptUploadProbe.RETRY_MS]. Read without the recorder: the sync itself checks with `LS` that the recording
     * is still there. Empty when no recorder is bound or it needs a new binding.
     */
    suspend fun pendingUploads(): Set<String> {
        val settings = store.read()
        val state = settings.binding?.let { settings.devices[it.device.address] }
            ?.takeIf { !it.rebindNeeded && takesTranscripts(it) } ?: return emptySet()
        val recordings = state.recordings.filter { stored ->
            !stored.absent && XrecNames.sidecarFor(stored.recording.name) != null
        }.map { it.recording }.filter { it.noteId != null }
        val transcripts = parts.library.transcripts(recordings.mapNotNull { it.noteId })
        val versions = encode(transcripts).mapValues { it.value.version }
        return recordings.filter { recording ->
            val version = versions[recording.noteId]
            version != null && version != recording.transcriptUploadedVersion &&
                version != recording.transcriptRefusedVersion
        }.mapNotNull { it.noteId }.toSet()
    }

    /**
     * False while the recorder's firmware refused transcripts less than [TranscriptUploadProbe.RETRY_MS] ago. Its
     * name can change only on the recorder: until a sync sees a new one, the refusal holds for the day.
     */
    private fun takesTranscripts(state: RecorderDeviceState): Boolean {
        val refused = state.transcriptsUnsupportedFirmware ?: return true
        return TranscriptUploadProbe.mayUpload(refused, state.transcriptsUnsupportedAt, refused, parts.clock())
    }

    /**
     * Binds [device]: bonds, connects, checks that the pairing window is open, asks [pin] for the PIN that the
     * recorder shows (its argument is true after a wrong PIN; null cancels), and sends a new random key with PAIR. The
     * key is stored only in its Keystore-wrapped form. Waits for a sync that runs.
     */
    @Suppress("TooGenericExceptionCaught") // nothing may escape into the screen's scope; see below
    suspend fun bind(device: RecorderDevice, pin: suspend (wrong: Boolean) -> String?): BindOutcome {
        synchronized(stateLock) {
            bindWaiters++
            active.update { it + 1 }
        }
        try {
            lock.lock()
        } catch (error: CancellationException) {
            synchronized(stateLock) { bindWaiters-- }
            active.update { it - 1 }
            throw error
        }
        synchronized(stateLock) {
            bindWaiters--
            running = null
        }
        return try {
            bindLocked(device, pin)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A Keystore, storage or Bluetooth stack failure. After a PAIR that the recorder accepted, the recorder has
            // a key that this phone did not keep: the user binds again.
            Log.e(TAG, "The binding failed", error)
            BindOutcome.Failed(null, error.javaClass.simpleName)
        } finally {
            current.value = RecorderActivity.Idle
            releaseLock()
        }
    }

    private fun releaseLock() {
        synchronized(stateLock) {
            running = null
            lock.unlock()
        }
        active.update { it - 1 }
    }

    /**
     * "Forget recorder": removes the binding, its wrapping key, and any unimported `.part` download cache; the record
     * of imported files stays.
     */
    suspend fun forget() {
        store.update { settings ->
            val address = settings.binding?.device?.address
            val devices = if (address != null && address in settings.devices) {
                settings.devices + (address to settings.devices.getValue(address).copy(partial = null))
            } else {
                settings.devices
            }
            settings.copy(binding = null, devices = devices)
        }
        withContext(parts.io) {
            parts.vault.clear()
            parts.downloads.deleteRecursively()
        }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        store.update { it.copy(autoSync = enabled) }
    }

    suspend fun setSyncWhileRecording(enabled: Boolean) {
        store.update { it.copy(syncWhileRecording = enabled) }
    }

    // Sync. ---------------------------------------------------------------------------------------------------------

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // not bound, waiting for a new binding, or a sync
    private suspend fun syncLocked(mode: SyncMode): SyncOutcome {
        val settings = store.read()
        val binding = settings.binding ?: return SyncOutcome.NotBound
        if (settings.current?.rebindNeeded == true) return SyncOutcome.RebindNeeded
        val outcome = try {
            connectAndSync(binding, mode)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // The sync runs in the application scope, where an exception ends the process: a failure of the phone's
            // storage, Keystore or library ends only this sync.
            Log.e(TAG, "The sync failed", error)
            SyncOutcome.Failed(null, error.javaClass.simpleName)
        }
        record(binding.device.address, outcome)
        return outcome
    }

    @Suppress("ReturnCount") // each early end of a sync is its own outcome
    private suspend fun connectAndSync(binding: RecorderBinding, mode: SyncMode): SyncOutcome {
        val readiness = parts.radio.readiness()
        if (readiness != RadioReadiness.READY) return SyncOutcome.Unavailable(readiness)
        val key = try {
            withContext(parts.io) { parts.vault.unwrap(binding.wrappedKey) }
        } catch (error: GeneralSecurityException) {
            Log.w(TAG, "The binding key cannot be read; bind the recorder again", error)
            return SyncOutcome.RebindNeeded
        }
        current.value = RecorderActivity.Connecting
        val link = try {
            parts.radio.open(binding.device.address)
        } catch (error: IOException) {
            Log.i(TAG, "The recorder does not answer: ${error.message}")
            return SyncOutcome.Unreachable
        }
        return try {
            session(link) { syncWith(it, binding.device.address, key, mode) }
        } catch (error: XrecException) {
            when (error.failure) {
                XrecFailure.AUTH, XrecFailure.UNBOUND -> SyncOutcome.RebindNeeded
                else -> SyncOutcome.Failed(error.failure, error.message)
            }
        } catch (error: IOException) {
            Log.w(TAG, "The sync stopped", error)
            SyncOutcome.Failed(null, error.message)
        } finally {
            key.fill(0)
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod") // the steps of one sync, in order
    private suspend fun syncWith(session: Session, address: String, key: ByteArray, mode: SyncMode): SyncOutcome {
        val pong = session.call { ping() }
        session.call { authenticate(key) }
        current.value = RecorderActivity.Syncing(SyncStep.CHECKING)
        val (listing, status, steady) = steadyListing(session)
        // The recorder does not set its clock while it records (open_fw answers OK,RTC,LATER): the next sync sets it.
        if (!status.recording) setClock(session)
        markAbsent(address, listing)
        // A recording that kept changing during LS: its sizes can be stale, so no download runs while it records.
        val syncWhileRecording = store.read().syncWhileRecording && steady
        val noteIds = deviceState(address).recordings.mapNotNull { it.recording.noteId }
        val transcripts = encode(parts.library.transcripts(noteIds))
        val versions = transcripts.mapValues { it.value.version }
        val suspects = SyncPlanner.suspects(listing, deviceState(address).syncState(), versions)
        val unverified = verifySuspects(session, address, suspects)
        val state = deviceState(address).syncState()
        val plan = SyncPlanner.plan(listing, status, state, versions, syncWhileRecording)
        // A firmware that names the file it records refuses every card write while it records: transcripts wait.
        val holdUploads = status.recording && status.namesActiveFile
        if (mode == SyncMode.UPLOADS) {
            val uploads = plan.uploads.filterNot { fileKey(it.recording.name) in unverified }
            val sent = upload(session, address, pong, uploads, transcripts, holdUploads)
            return SyncOutcome.Done(
                0,
                sent.uploaded,
                plan.skipped.size + plan.downloads.size,
                plan.deviceRecording,
                deferredUploads = sent.deferred,
            )
        }
        val pace = Pace(
            window = XrecLimits.RECORDING_GETS_WINDOW.takeIf { status.recording },
            pauseMillis = if (plan.whileRecording) status.paceMillis ?: 0L else 0L,
        )
        // Today's rule (a recording that starts stops the downloads) holds unless the firmware protects its active file.
        val statEachFile = !(syncWhileRecording && status.namesActiveFile)
        var downloaded = 0
        var failed = 0
        var recording = plan.deviceRecording
        var deferred = 0
        var speed: DownloadSpeed? = null
        for ((index, download) in plan.downloads.withIndex()) {
            // A recording can start after STAT: its file grows, so no download starts while one runs.
            val started = index > 0 && statEachFile && session.call { stat() }.recording
            val result = if (started) {
                FileResult.DEFERRED
            } else {
                download(session, address, download, pace) { stats -> speed = (speed ?: NO_SPEED) + stats }
            }
            when (result) {
                FileResult.DONE -> downloaded++
                FileResult.FAILED -> failed++
                FileResult.CHANGED -> Unit
                FileResult.DEFERRED -> {
                    // The recorder takes no transfer until its recording ends: the others wait the same.
                    recording = true
                    deferred = plan.downloads.size - index
                    break
                }
            }
        }
        // A transcript can have finished during the downloads (a long one, or an existing note): it goes now too.
        val checked = (suspects.map { fileKey(it.name) } + plan.downloads.map { fileKey(it.name) }).toSet()
        val (uploads, latest) = latestUploads(session, address, listing, status, checked, unverified)
        val sent = upload(session, address, pong, uploads, latest, holdUploads)
        withContext(parts.io) { deleteStrayParts(address) }
        // TODO(recorder-sync): delete synced recordings from the recorder (DEL) once the user can choose it.
        val waiting = plan.skipped.count { it.reason in WAITING_REASONS } + deferred + sent.deferred
        return SyncOutcome.Done(
            downloaded,
            sent.uploaded,
            plan.skipped.size + deferred,
            recording,
            failed,
            speed,
            deferredUploads = sent.deferred,
            recordingPoll = plan.whileRecording && waiting > 0,
        )
    }

    /** The LS [listing] of a sync, the [status] it is planned with, and whether the recording stayed the same during LS. */
    private data class Listing(val listing: List<XrecReply.Entry>, val status: XrecReply.Status, val steady: Boolean)

    /**
     * STAT, LS, STAT. A recording that starts before the second STAT is in that STAT; one that stops or splits during LS
     * can leave its file in the listing with a size that is no longer its size (the firmware rewrites the header at the
     * end), so LS runs again (at most [MAX_LISTINGS] times) until both STATs show the same recording. Still changing:
     * the recording STAT is used, and the sync plans as if the setting were off (not [Listing.steady]).
     */
    private suspend fun steadyListing(session: Session): Listing {
        var before = session.call { stat() }
        var listings = 0
        while (true) {
            val listing = session.call { list() }
            val after = session.call { stat() }
            val steady = sameRecording(before, after)
            if (steady || ++listings >= MAX_LISTINGS) {
                return Listing(listing, if (steady || after.recording) after else before, steady)
            }
            Log.i(TAG, "The recording changed during LS; listing again")
            before = after
        }
    }

    /** True when no recording ran across [before] and [after], or the same one ran at both and its file is the same. */
    private fun sameRecording(before: XrecReply.Status, after: XrecReply.Status): Boolean =
        !before.recording || (after.recording && before.recordingIndex == after.recordingIndex)

    /** The GETS [window] (null: the session's) and the [pauseMillis] between two windows of the downloads of a sync. */
    private class Pace(val window: Int?, val pauseMillis: Long)

    /** Marks each record whose recording [listing] does not have (deleted, or the card was wiped), and the others not. */
    private suspend fun markAbsent(address: String, listing: List<XrecReply.Entry>) {
        val listed = listing.map { fileKey(it.name) to it.size }.toSet()
        fun StoredRecording.absentNow() = (fileKey(recording.name) to recording.size) !in listed
        if (deviceState(address).recordings.all { it.absent == it.absentNow() }) return
        store.updateDevice(address) { state ->
            state.copy(recordings = state.recordings.map { it.copy(absent = it.absentNow()) })
        }
    }

    /**
     * The uploads and their transcripts as the library has them now, after the downloads. A recording whose transcript
     * finished meanwhile is compared first, unless [checked] (by name) already.
     */
    private suspend fun latestUploads(
        session: Session,
        address: String,
        listing: List<XrecReply.Entry>,
        status: XrecReply.Status,
        checked: Set<String>,
        unverified: Set<String>,
    ): Pair<List<PlannedUpload>, Map<String, EncodedTranscript>> {
        val noteIds = deviceState(address).recordings.mapNotNull { it.recording.noteId }
        val transcripts = encode(parts.library.transcripts(noteIds))
        val versions = transcripts.mapValues { it.value.version }
        val suspects = SyncPlanner.suspects(listing, deviceState(address).syncState(), versions)
        val skipped = unverified + verifySuspects(session, address, suspects.filterNot { fileKey(it.name) in checked })
        val uploads = SyncPlanner.plan(listing, status, deviceState(address).syncState(), versions)
            .uploads
            .filterNot { fileKey(it.recording.name) in skipped }
        return uploads to transcripts
    }

    private suspend fun setClock(session: Session) {
        val clock = session.call { rtc() }
        val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(parts.clock()), parts.zone()).withNano(0)
        if (!RecorderClock.needsSet(clock.time, now)) return
        try {
            session.call { setRtc(now) }
        } catch (error: XrecException) {
            // A refused clock does not stop the sync; the next sync tries again.
            if (error.failure !in CLOCK_FAILURES) throw error
            Log.w(TAG, "The recorder refused the clock", error)
        }
    }

    /**
     * Compares the first 4 KiB of each suspect with the stored hash. A different file with the old name and size (a
     * new recording after a card wipe) is not the old recording: its record goes, so the plan downloads it and the old
     * note's transcript does not go to it. Returns the names of suspects whose header could not be read now: their
     * records stay, and no transcript goes to them until a later check reads their header.
     */
    private suspend fun verifySuspects(
        session: Session,
        address: String,
        suspects: List<SyncedRecording>,
    ): Set<String> {
        val unverified = HashSet<String>()
        val replaced = suspects.filter { suspect ->
            val head = headOnRecorder(session, suspect)
            if (head == null) unverified += fileKey(suspect.name)
            head != null && head != suspect.headSha256
        }
        for (suspect in replaced) {
            Log.i(TAG, "${suspect.name} is a new recording with the name and the size of an imported one")
            store.updateDevice(address) { state ->
                state.copy(recordings = state.recordings.filterNot { it.recording == suspect })
            }
        }
        return unverified
    }

    /** The SHA-256 of the first 4 KiB of [recording] on the recorder; null when the recorder cannot give them now. */
    private suspend fun headOnRecorder(session: Session, recording: SyncedRecording): String? {
        val head = ByteArrayOutputStream()
        val length = minOf(HEAD_BYTES.toLong(), recording.size)
        return try {
            session.call { download(recording.name, length, head) { session.touch() } }
            sha256(head.toByteArray())
        } catch (error: XrecException) {
            if (error.failure in LINK_FAILURES) throw error
            Log.w(TAG, "${recording.name} could not be compared (${error.failure})")
            null
        }
    }

    /** DEFERRED: the recorder answered `ERR,LATER` (a stereo recording runs); the next sync continues. */
    private enum class FileResult { DONE, CHANGED, FAILED, DEFERRED }

    /**
     * Downloads, imports and records one recording; [measured] gets the measurements of a complete download. An
     * `ERR,LATER,<ms>` to a GETS means a stereo recording, which allows no transfer until it ends: the bytes so far
     * stay in the `.part` file and the download waits for a later sync, without a failure. The link is not held open
     * meanwhile: a connected link polls the radio all the time, next to the recording microphone.
     */
    @Suppress("ReturnCount") // a failure, a download that waits for a later sync, or an import
    private suspend fun download(
        session: Session,
        address: String,
        planned: PlannedDownload,
        pace: Pace,
        measured: (XrecDownloadStats) -> Unit,
    ): FileResult {
        val part = partFile(address, planned.name)
        val previousPart = planned.resume?.let { partFile(address, it.name) }
        if (previousPart != null && previousPart != part) {
            withContext(parts.io) { if (previousPart.isFile && !part.exists()) previousPart.renameTo(part) }
        }
        // The file length is the truth: after process death the stored offset can be behind it.
        val resume = if (planned.resume != null && part.isFile) part.length().coerceAtMost(planned.size) else 0L
        withContext(parts.io) { prepare(part, resume) }
        store.updateDevice(address) { it.copy(partial = PartialDownload(planned.name, planned.size, resume)) }
        progress(SyncStep.DOWNLOADING, planned.name, resume, planned.size)
        val stats = try {
            fetch(session, planned, part, pace)
        } catch (error: XrecException) {
            if (error.failure != XrecFailure.LATER) return downloadFailed(address, planned, part, error)
            val bytes = withContext(parts.io) { part.length() }
            store.updateDevice(address) { it.copy(partial = it.partial?.copy(bytes = bytes)) }
            Log.i(TAG, "${planned.name} waits for the end of the recording, at $bytes bytes")
            return FileResult.DEFERRED
        }
        stats?.let {
            measured(it)
            Log.i(
                METRICS_TAG,
                "${planned.name}: ${it.bytes} bytes in ${it.millis} ms, ${it.bytesPerSecond / BYTES_PER_KB} KB/s, " +
                    "${it.retries} retries, ${it.requests} GETS of ${it.window} bytes",
            )
        }
        return import(address, planned, part)
    }

    /**
     * One download into [part] from its length, through a buffer of [PART_BUFFER_BYTES]: the bytes that came stay in
     * the file also when the download fails, and a complete file is synced to the disk. A flush that fails after a
     * failed download does not hide the download's failure (a LATER or a lost link): it is added as suppressed.
     */
    private suspend fun fetch(session: Session, planned: PlannedDownload, part: File, pace: Pace): XrecDownloadStats? {
        val offset = withContext(parts.io) { part.length() }
        return session.call {
            FileOutputStream(part, true).use { out ->
                val buffered = BufferedOutputStream(out, PART_BUFFER_BYTES)
                thenFlushing(buffered) {
                    download(planned.name, planned.size, buffered, offset, pace.window, pace.pauseMillis) { done ->
                        session.touch()
                        progress(SyncStep.DOWNLOADING, planned.name, done, planned.size)
                    }
                }
                out.fd.sync()
            }
            lastDownload
        }
    }

    private suspend fun downloadFailed(
        address: String,
        planned: PlannedDownload,
        part: File,
        error: XrecException,
    ): FileResult {
        if (error.failure in CHANGED_FAILURES) {
            Log.i(TAG, "${planned.name} changed on the recorder (${error.failure}); the next sync lists it again")
            withContext(parts.io) { part.delete() }
            store.updateDevice(address) { it.copy(partial = null) }
            return FileResult.CHANGED
        }
        val bytes = withContext(parts.io) { part.length() }
        store.updateDevice(address) { it.copy(partial = it.partial?.copy(bytes = bytes)) }
        // A lost link ends the sync; the next one continues from the .part file.
        if (error.failure in LINK_FAILURES) throw error
        // A file that the recorder cannot read now (a card error, bad frames after all retries) does not stop the
        // other files; the next sync tries it again.
        Log.w(TAG, "${planned.name} was not downloaded (${error.failure}); the sync goes on", error)
        return FileResult.FAILED
    }

    private suspend fun import(address: String, planned: PlannedDownload, part: File): FileResult {
        current.value = RecorderActivity.Syncing(SyncStep.IMPORTING, planned.name)
        movingCallback?.invoke()
        val head = withContext(parts.io) { headSha256(part) }
        val noteId = when (val imported = parts.library.import(part, planned.name)) {
            is LibraryImport.Rejected -> return rejected(address, planned, part, head, imported.failure)
            is LibraryImport.Created -> imported.noteId.also { parts.library.transcribe(it) }
            // The sync can have stopped between an import and its transcription request: ask now if nothing runs.
            is LibraryImport.Existing -> imported.noteId.also {
                if (parts.library.needsTranscription(it)) parts.library.transcribe(it)
            }
        }
        store.updateDevice(address) { state ->
            state.withRecording(SyncedRecording(planned.name, planned.size, noteId, head), failure = null)
        }
        withContext(parts.io) { part.delete() }
        return FileResult.DONE
    }

    /** A refused import: kept for the next sync when the refusal can pass (space, saving), else recorded for good. */
    private suspend fun rejected(
        address: String,
        planned: PlannedDownload,
        part: File,
        head: String,
        failure: String,
    ): FileResult {
        if (failure in TEMPORARY_IMPORT_FAILURES) {
            Log.w(TAG, "${planned.name} was not imported ($failure); the next sync tries again")
            store.updateDevice(address) { it.copy(partial = PartialDownload(planned.name, planned.size, planned.size)) }
            return FileResult.FAILED
        }
        store.updateDevice(address) { state ->
            state.withRecording(SyncedRecording(planned.name, planned.size, null, head), IMPORT_FAILURE + failure)
        }
        withContext(parts.io) { part.delete() }
        return FileResult.DONE
    }

    /** A recording with the same name and another size is a new one: it replaces the old record. */
    private fun RecorderDeviceState.withRecording(recording: SyncedRecording, failure: String?) = copy(
        recordings = recordings.filterNot { it.recording.name.equals(recording.name, ignoreCase = true) } +
            StoredRecording(recording, failure),
        partial = null,
    )

    /** The transcripts a sync sent, and those that wait for the end of the recording. */
    private data class Uploads(val uploaded: Int, val deferred: Int)

    /**
     * Sends [uploads]. With [hold] (the recorder records and refuses card writes until it stops) none goes: they all
     * wait for the end of the recording. An `ERR,LATER` (a recording started meanwhile) ends the uploads the same way.
     */
    @Suppress("ReturnCount", "LongParameterList") // no upload: nothing planned, held, or this firmware refused before
    private suspend fun upload(
        session: Session,
        address: String,
        pong: XrecReply.Pong,
        uploads: List<PlannedUpload>,
        transcripts: Map<String, EncodedTranscript>,
        hold: Boolean,
    ): Uploads {
        if (uploads.isEmpty()) return NO_UPLOADS
        val firmware = pong.firmware
        val state = deviceState(address)
        val now = parts.clock()
        val refused = state.transcriptsUnsupportedFirmware
        val advertised = pong.recordTarget
        val allowed = TranscriptUploadProbe.mayUpload(
            refused,
            state.transcriptsUnsupportedAt,
            firmware,
            now,
            advertised,
        )
        if (!allowed) return NO_UPLOADS
        if (hold) {
            Log.i(TAG, "${uploads.size} transcripts wait for the end of the recording")
            return Uploads(0, uploads.size)
        }
        var uploaded = 0
        for ((index, upload) in uploads.withIndex()) {
            val transcript = upload.recording.noteId?.let(transcripts::get) ?: continue
            val failure = uploadOne(session, upload, transcript)
            if (failure == XrecFailure.LATER) {
                Log.i(TAG, "The recorder records: ${uploads.size - index} transcripts wait for its end")
                return Uploads(uploaded, uploads.size - index)
            }
            if (failure == XrecFailure.FIRMWARE_TOO_OLD) {
                Log.w(TAG, "The recorder firmware $firmware does not take transcripts")
                store.updateDevice(address) {
                    it.copy(transcriptsUnsupportedFirmware = firmware, transcriptsUnsupportedAt = now)
                }
                return Uploads(uploaded, 0)
            }
            store.updateDevice(address) { it.withUpload(upload, failure) }
            if (failure == null) uploaded++
        }
        return Uploads(uploaded, 0)
    }

    /**
     * Sends one sidecar; returns null when the recorder took it, or why it refused this one file. [XrecFailure.LATER]:
     * the recorder records and takes it after (the payload of a refused chunk is not read as commands).
     */
    private suspend fun uploadOne(
        session: Session,
        upload: PlannedUpload,
        transcript: EncodedTranscript,
    ): XrecFailure? {
        val total = transcript.bytes.size.toLong()
        progress(SyncStep.UPLOADING, upload.sidecarName, 0, total)
        return try {
            session.call {
                uploadSidecar(upload.sidecarName, transcript.bytes) { done ->
                    session.touch()
                    progress(SyncStep.UPLOADING, upload.sidecarName, done.toLong(), total)
                }
            }
            null
        } catch (error: XrecException) {
            if (error.failure == XrecFailure.LATER) return XrecFailure.LATER
            if (error.failure !in UPLOAD_FAILURES) throw error
            Log.w(TAG, "The recorder refused ${upload.sidecarName} (${error.failure})")
            error.failure
        }
    }

    private fun RecorderDeviceState.withUpload(upload: PlannedUpload, failure: XrecFailure?) = copy(
        recordings = recordings.map { stored ->
            val recording = stored.recording
            when {
                recording != upload.recording -> stored
                failure == null -> StoredRecording(
                    recording.copy(
                        transcriptUploadedVersion = upload.transcriptVersion,
                        transcriptRefusedVersion = null,
                    ),
                )
                // The recorder refuses this name for good: do not send this version again.
                failure == XrecFailure.BAD_NAME -> StoredRecording(
                    recording.copy(transcriptRefusedVersion = upload.transcriptVersion),
                    UPLOAD_FAILURE + failure.name,
                )
                else -> stored.copy(failure = UPLOAD_FAILURE + failure.name)
            }
        },
        transcriptsUnsupportedFirmware = null,
        transcriptsUnsupportedAt = null,
    )

    /** Writes how the sync ended; a storage failure here is logged, as the outcome is known already. */
    private suspend fun record(address: String, outcome: SyncOutcome) {
        val now = parts.clock()
        val last = when (outcome) {
            is SyncOutcome.Done -> LastSync(
                now,
                OUTCOME_OK,
                null,
                outcome.downloaded,
                outcome.uploaded,
                downloadedBytes = outcome.speed?.bytes ?: 0,
                downloadMillis = outcome.speed?.millis ?: 0,
            )
            SyncOutcome.Unreachable -> LastSync(now, OUTCOME_UNREACHABLE)
            SyncOutcome.RebindNeeded -> LastSync(now, OUTCOME_REBIND)
            is SyncOutcome.Unavailable -> LastSync(now, outcome.readiness.name)
            is SyncOutcome.Failed -> LastSync(now, OUTCOME_FAILED, outcome.failure?.name ?: outcome.message)
            SyncOutcome.NotBound, SyncOutcome.Busy -> return
        }
        try {
            store.updateDevice(address) { state ->
                state.copy(
                    lastSync = last,
                    lastSuccessAt = if (outcome is SyncOutcome.Done) now else state.lastSuccessAt,
                    rebindNeeded = state.rebindNeeded || outcome == SyncOutcome.RebindNeeded,
                )
            }
        } catch (error: IOException) {
            Log.w(TAG, "The result of the sync was not saved", error)
        }
    }

    /** Sets [RecorderActivity.Syncing] when the step, the file or the percent changes. */
    private fun progress(step: SyncStep, file: String, done: Long, total: Long) {
        val shown = current.value as? RecorderActivity.Syncing
        val same = shown != null && shown.step == step && shown.file == file && shown.total == total &&
            percent(shown.done, total) == percent(done, total)
        if (!same) current.value = RecorderActivity.Syncing(step, file, done, total)
        if (step != SyncStep.CHECKING) movingCallback?.invoke()
    }

    private fun percent(done: Long, total: Long): Long = if (total > 0) done * PERCENT / total else 0

    // Binding. ------------------------------------------------------------------------------------------------------

    @Suppress("ReturnCount") // each early end of a binding is its own outcome
    private suspend fun bindLocked(device: RecorderDevice, pin: suspend (Boolean) -> String?): BindOutcome {
        val readiness = parts.radio.readiness()
        if (readiness != RadioReadiness.READY) return BindOutcome.Unavailable(readiness)
        current.value = RecorderActivity.Binding(BindStep.BONDING)
        val bond = parts.radio.bond(device.address)
        if (bond == BondResult.FAILED) return BindOutcome.NotBonded
        current.value = RecorderActivity.Binding(BindStep.CONNECTING)
        val link = try {
            parts.radio.open(device.address)
        } catch (error: IOException) {
            Log.i(TAG, "The recorder does not answer: ${error.message}")
            // The recorder accepts only a link with a bond it knows: an old bond that it lost fails here every time.
            return if (bond == BondResult.BONDED_BEFORE) BindOutcome.StaleBond else BindOutcome.Unreachable
        }
        return try {
            session(link) { pair(it, device, pin) }
        } catch (error: XrecException) {
            when (error.failure) {
                XrecFailure.PAIRING_CLOSED -> BindOutcome.PairingClosed
                else -> BindOutcome.Failed(error.failure, error.message)
            }
        } catch (error: IOException) {
            Log.w(TAG, "The binding stopped", error)
            BindOutcome.Failed(null, error.message)
        }
    }

    @Suppress("ReturnCount") // the window is closed, the user cancelled, or bound
    private suspend fun pair(session: Session, device: RecorderDevice, pin: suspend (Boolean) -> String?): BindOutcome {
        if (!session.call { ping() }.pairingOpen) return BindOutcome.PairingClosed
        val key = XrecAuth.newKey(parts.random)
        try {
            if (!sendPin(session, key, pin)) return BindOutcome.Cancelled
            val wrapped = withContext(parts.io) { parts.vault.wrap(key) }
            store.update { settings ->
                val address = device.address
                // A new binding asks the recorder for transcripts again, whatever its firmware refused before.
                val state = (settings.devices[address] ?: RecorderDeviceState()).copy(
                    rebindNeeded = false,
                    transcriptsUnsupportedFirmware = null,
                    transcriptsUnsupportedAt = null,
                )
                val devices = settings.devices + (address to state)
                settings.copy(binding = RecorderBinding(device, wrapped), devices = devices)
            }
            return BindOutcome.Bound
        } finally {
            key.fill(0)
        }
    }

    /** PAIR with the PIN that [pin] gives, asking again after a wrong one; false when the user cancelled. */
    private suspend fun sendPin(session: Session, key: ByteArray, pin: suspend (Boolean) -> String?): Boolean {
        var wrong = false
        while (true) {
            current.value = RecorderActivity.Binding(BindStep.PIN)
            val code = pin(wrong) ?: return false
            current.value = RecorderActivity.Binding(BindStep.PAIRING)
            wrong = try {
                session.call { pair(code, key) }
                return true
            } catch (error: XrecException) {
                if (error.failure != XrecFailure.WRONG_PIN) throw error
                true
            }
        }
    }

    // Helpers. ------------------------------------------------------------------------------------------------------

    /**
     * One link and its client. [call] runs a blocking client call on IO, interrupted by cancellation; [touch] marks
     * progress inside a long call (a frame, a chunk), so that [stalled] tells a blocked call from a long one.
     */
    private class Session(val client: XrecClient, private val io: CoroutineDispatcher) {
        @Volatile private var busy = false

        @Volatile private var lastProgress = 0L

        fun touch() {
            lastProgress = System.nanoTime()
        }

        suspend fun <T> call(block: XrecClient.() -> T): T {
            touch()
            busy = true
            try {
                return runInterruptible(io) { client.block() }
            } finally {
                busy = false
            }
        }

        fun stalled(limitMillis: Long): Boolean =
            busy && System.nanoTime() - lastProgress > limitMillis * NANOS_PER_MILLI
    }

    /**
     * Runs [block] on a session over [link], and closes the link at the end. A watchdog closes it earlier when a call
     * stalls or the caller is cancelled: a blocked socket write ignores interrupts, and only the close ends it.
     */
    private suspend fun <T> session(link: RecorderLink, block: suspend (Session) -> T): T = try {
        coroutineScope {
            val client = XrecClient(link.input, link.output, parts.timeouts).apply {
                onFallback = { name, offset, window ->
                    Log.i(METRICS_TAG, "$name: repeated failed windows at $offset; GETS of $window bytes for this file")
                }
            }
            val session = Session(client, parts.io)
            val watchdog = launch(parts.io) {
                try {
                    val tick = (parts.stallMillis / WATCHDOG_TICKS).coerceIn(MIN_TICK_MS, MAX_TICK_MS)
                    while (!session.stalled(parts.stallMillis)) delay(tick)
                    Log.w(TAG, "The recorder link stalled for ${parts.stallMillis} ms; closing it")
                } finally {
                    link.close()
                }
            }
            try {
                block(session)
            } finally {
                watchdog.cancel()
            }
        }
    } finally {
        withContext(NonCancellable + parts.io) { link.close() }
    }

    private suspend fun deviceState(address: String): RecorderDeviceState =
        store.read().devices[address] ?: RecorderDeviceState()

    private class EncodedTranscript(val bytes: ByteArray, val version: String)

    /** The sidecar bytes of each transcript; the version names the variant and the bytes, so any change sends again. */
    private suspend fun encode(transcripts: Map<String, RecorderTranscript>): Map<String, EncodedTranscript> =
        withContext(parts.io) {
            transcripts.mapValues { (_, transcript) ->
                val bytes = CwxEncoder.encode(transcript.cwx)
                EncodedTranscript(bytes, "${transcript.variantId}:${sha256(bytes).take(VERSION_DIGEST_CHARS)}")
            }
        }

    private fun deviceDirectory(address: String): File = File(parts.downloads, address.filter { it.isLetterOrDigit() })

    private fun partFile(address: String, name: String): File = File(deviceDirectory(address), "$name$PART")

    /** Deletes the `.part` files of downloads that the state no longer continues. */
    private suspend fun deleteStrayParts(address: String) {
        val keep = deviceState(address).partial?.let { partFile(address, it.name) }
        deviceDirectory(address).listFiles()?.filter { it.isFile && it.name.endsWith(PART) && it != keep }
            ?.forEach { it.delete() }
    }

    private fun prepare(part: File, keep: Long) {
        part.parentFile?.mkdirs()
        if (keep == 0L) {
            part.delete()
        } else {
            RandomAccessFile(part, "rw").use { it.setLength(keep) }
        }
    }

    private fun headSha256(file: File): String {
        val head = ByteArray(HEAD_BYTES)
        val read = FileInputStream(file).use { input ->
            var total = 0
            while (total < head.size) {
                val n = input.read(head, total, head.size - total)
                if (n < 0) break
                total += n
            }
            total
        }
        return sha256(head.copyOf(read))
    }

    private fun sha256(bytes: ByteArray): String = XrecAuth.toHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun fileKey(name: String): String = name.lowercase(Locale.ROOT)

    companion object {
        private const val TAG = "RecorderSync"

        /** The tag of the per-file download measurements (`adb logcat -s Recorder:I`). */
        private const val METRICS_TAG = "Recorder"
        private const val BYTES_PER_KB = 1_000L
        private val NO_SPEED = DownloadSpeed(0, 0, 0)
        private val NO_UPLOADS = Uploads(0, 0)

        /** The skipped files that wait for the end of a recording. */
        private val WAITING_REASONS = setOf(SkipReason.ACTIVE, SkipReason.RECORDING)

        /** The LS runs of one sync while a recording keeps stopping, starting or splitting during them. */
        private const val MAX_LISTINGS = 3

        /** The buffer of a `.part` file (docs/BT_SYNC_SPEED_AND_RECORDING.md A7). */
        private const val PART_BUFFER_BYTES = 64 * 1024

        private const val HEAD_BYTES = 4096
        private const val VERSION_DIGEST_CHARS = 16
        private const val PERCENT = 100L
        private const val PART = ".part"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val WATCHDOG_TICKS = 4
        private const val MIN_TICK_MS = 10L
        private const val MAX_TICK_MS = 1_000L

        /** A blocked call closes the link after this long without progress. */
        const val DEFAULT_STALL_MS = 60_000L

        const val OUTCOME_OK = "OK"
        const val OUTCOME_UNREACHABLE = "UNREACHABLE"
        const val OUTCOME_REBIND = "REBIND"
        const val OUTCOME_FAILED = "FAILED"

        /** The prefix of [StoredRecording.failure] for a refused import, before the `Failure` name. */
        const val IMPORT_FAILURE = "IMPORT_"

        /** The prefix of [StoredRecording.failure] for a refused upload, before the [XrecFailure] name. */
        const val UPLOAD_FAILURE = "UPLOAD_"

        private val CLOCK_FAILURES = setOf(XrecFailure.RTC_RANGE, XrecFailure.RTC_FAILED, XrecFailure.BAD_ARGUMENTS)

        /** The file changed after LS: skip it now, the next sync lists again. */
        private val CHANGED_FAILURES =
            setOf(XrecFailure.SIZE_CHANGED, XrecFailure.NOT_FOUND, XrecFailure.BUSY, XrecFailure.BAD_NAME)

        /** The link is gone or lost its place: the sync ends, and the next one starts on a new link. */
        private val LINK_FAILURES = setOf(XrecFailure.DISCONNECTED, XrecFailure.TIMEOUT, XrecFailure.PROTOCOL)

        /**
         * The recorder refused this one upload; the others still go. NOT_FOUND: the recording went (the next LS
         * decides). Bad replies after all retries (INTEGRITY) and a lost link are not here: they end the sync.
         */
        private val UPLOAD_FAILURES = setOf(
            XrecFailure.FIRMWARE_TOO_OLD,
            XrecFailure.BAD_NAME,
            XrecFailure.NOT_FOUND,
            XrecFailure.CARD_IO,
            XrecFailure.NO_CARD,
        )

        /** The library could not take the file now (no space, a save error): the next sync imports it again. */
        private val TEMPORARY_IMPORT_FAILURES = setOf("SPACE", "SAVE")
    }
}

/**
 * Runs [block], then flushes [out] also when [block] failed: the bytes that came stay for the next sync. A failed flush
 * after a failed [block] does not replace its failure (a LATER, a lost link), it is added to it as suppressed; after a
 * [block] that succeeded it is thrown.
 */
internal inline fun <T> thenFlushing(out: Flushable, block: () -> T): T {
    val result = runCatching(block)
    val flushed = runCatching { out.flush() }
    result.exceptionOrNull()?.let { error ->
        flushed.exceptionOrNull()?.let(error::addSuppressed)
        throw error
    }
    flushed.getOrThrow()
    return result.getOrThrow()
}
