package io.codemyriad.cassini.recorder

import android.util.Log
import io.codemyriad.cassini.core.recorder.CwxEncoder
import io.codemyriad.cassini.core.recorder.XrecAuth
import io.codemyriad.cassini.core.recorder.XrecClient
import io.codemyriad.cassini.core.recorder.XrecException
import io.codemyriad.cassini.core.recorder.XrecFailure
import io.codemyriad.cassini.core.recorder.XrecTimeouts
import io.codemyriad.cassini.data.recorder.LastSync
import io.codemyriad.cassini.data.recorder.RecorderBinding
import io.codemyriad.cassini.data.recorder.RecorderDevice
import io.codemyriad.cassini.data.recorder.RecorderDeviceState
import io.codemyriad.cassini.data.recorder.RecorderStore
import io.codemyriad.cassini.data.recorder.StoredRecording
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** What the recorder sync does now, for the Recorder screen and the notification. */
sealed interface RecorderActivity {
    data object Idle : RecorderActivity

    /** Opening the Bluetooth link. */
    data object Connecting : RecorderActivity

    /** A step of a sync: [file] and its [done] of [total] bytes when the step has a file. */
    data class Syncing(val step: SyncStep, val file: String? = null, val done: Long = 0, val total: Long = 0) :
        RecorderActivity

    data class Binding(val step: BindStep) : RecorderActivity
}

enum class SyncStep { CHECKING, DOWNLOADING, IMPORTING, UPLOADING }

enum class BindStep { BONDING, CONNECTING, PIN, PAIRING }

/** How one sync ended. */
sealed interface SyncOutcome {
    /** The sync ran. [skipped] counts the new files it did not download (none while [deviceRecording]). */
    data class Done(
        val downloaded: Int,
        val uploaded: Int,
        val skipped: Int,
        val deviceRecording: Boolean,
    ) : SyncOutcome

    data object NotBound : SyncOutcome

    /** A sync or a binding runs already. */
    data object Busy : SyncOutcome

    data class Unavailable(val readiness: RadioReadiness) : SyncOutcome

    /** The recorder did not answer: it is not in Bluetooth phone mode, or out of range. */
    data object Unreachable : SyncOutcome

    /** The recorder refused the stored key (another phone bound it, or it lost its key): bind it again. */
    data object RebindNeeded : SyncOutcome

    /** The sync stopped with [failure] (the link closed, no reply, a device error, or a storage error: null). */
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
 * A link failure or a timeout ends the sync quietly: the recorder's radio is on only while it is in Bluetooth phone
 * mode. A refused key marks the recorder "re-bind needed", and no sync runs until it is bound again.
 */
@Suppress("TooManyFunctions") // one function per sync step
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
    )

    private val store = parts.store
    private val lock = Mutex()
    private val current = MutableStateFlow<RecorderActivity>(RecorderActivity.Idle)

    val activity: StateFlow<RecorderActivity> = current.asStateFlow()

    /** Syncs with the bound recorder; [SyncOutcome.Busy] at once when a sync or a binding runs already. */
    suspend fun sync(): SyncOutcome {
        if (!lock.tryLock()) return SyncOutcome.Busy
        return try {
            syncLocked()
        } finally {
            current.value = RecorderActivity.Idle
            lock.unlock()
        }
    }

    /**
     * Binds [device]: bonds, connects, checks that the pairing window is open, asks [pin] for the PIN that the
     * recorder shows (its argument is true after a wrong PIN; null cancels), and sends a new random key with PAIR. The
     * key is stored only in its Keystore-wrapped form. Waits for a sync that runs.
     */
    suspend fun bind(device: RecorderDevice, pin: suspend (wrong: Boolean) -> String?): BindOutcome {
        lock.lock()
        return try {
            bindLocked(device, pin)
        } finally {
            current.value = RecorderActivity.Idle
            lock.unlock()
        }
    }

    /** "Forget recorder": removes the binding and its key; the record of imported files stays. */
    suspend fun forget() {
        store.update { it.copy(binding = null) }
    }

    suspend fun setAutoSync(enabled: Boolean) {
        store.update { it.copy(autoSync = enabled) }
    }

    // Sync. ---------------------------------------------------------------------------------------------------------

    @Suppress("ReturnCount") // not bound, waiting for a new binding, or a sync
    private suspend fun syncLocked(): SyncOutcome {
        val settings = store.read()
        val binding = settings.binding ?: return SyncOutcome.NotBound
        if (settings.current?.rebindNeeded == true) return SyncOutcome.RebindNeeded
        val outcome = connectAndSync(binding)
        record(binding.device.address, outcome)
        return outcome
    }

    @Suppress("ReturnCount") // each early end of a sync is its own outcome
    private suspend fun connectAndSync(binding: RecorderBinding): SyncOutcome {
        val readiness = parts.radio.readiness()
        if (readiness != RadioReadiness.READY) return SyncOutcome.Unavailable(readiness)
        val key = try {
            parts.vault.unwrap(binding.wrappedKey)
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
            session(link) { syncWith(it, binding.device.address, key) }
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

    private suspend fun syncWith(session: Session, address: String, key: ByteArray): SyncOutcome {
        val pong = session.call { ping() }
        session.call { authenticate(key) }
        current.value = RecorderActivity.Syncing(SyncStep.CHECKING)
        val status = session.call { stat() }
        setClock(session)
        val listing = session.call { list() }
        val device = deviceState(address)
        val transcripts = encode(parts.library.transcripts(device.recordings.mapNotNull { it.recording.noteId }))
        val plan = SyncPlanner.plan(listing, status, device.syncState(), transcripts.mapValues { it.value.version })
        var downloaded = 0
        for (download in plan.downloads) {
            if (download(session, address, download)) downloaded++
        }
        val uploaded = upload(session, address, pong.firmware, plan.uploads, transcripts)
        // TODO(recorder-sync): delete synced recordings from the recorder (DEL) once the user can choose it.
        return SyncOutcome.Done(downloaded, uploaded, plan.skipped.size, plan.deviceRecording)
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

    /** Downloads, imports and records one recording; false when the file changed on the recorder meanwhile. */
    private suspend fun download(session: Session, address: String, planned: PlannedDownload): Boolean {
        val part = partFile(address, planned.name)
        val partial = deviceState(address).partial
        // The file length is the truth: after process death the stored offset can be behind it.
        val resume = if (partial?.name == planned.name && partial.size == planned.size && part.isFile) {
            part.length().coerceAtMost(planned.size)
        } else {
            0L
        }
        withContext(parts.io) { prepare(part, resume) }
        store.updateDevice(address) { it.copy(partial = PartialDownload(planned.name, planned.size, resume)) }
        current.value = RecorderActivity.Syncing(SyncStep.DOWNLOADING, planned.name, resume, planned.size)
        try {
            session.call {
                FileOutputStream(part, true).use { out ->
                    download(planned.name, planned.size, out, resume) { done ->
                        current.value = RecorderActivity.Syncing(SyncStep.DOWNLOADING, planned.name, done, planned.size)
                    }
                    out.fd.sync()
                }
            }
        } catch (error: XrecException) {
            if (error.failure !in CHANGED_FAILURES) {
                store.updateDevice(address) { it.copy(partial = it.partial?.copy(bytes = part.length())) }
                throw error
            }
            Log.i(TAG, "${planned.name} changed on the recorder (${error.failure}); the next sync lists it again")
            part.delete()
            store.updateDevice(address) { it.copy(partial = null) }
            return false
        }
        import(address, planned, part)
        return true
    }

    private suspend fun import(address: String, planned: PlannedDownload, part: File) {
        current.value = RecorderActivity.Syncing(SyncStep.IMPORTING, planned.name)
        val head = withContext(parts.io) { headSha256(part) }
        val imported = parts.library.import(part, planned.name)
        val noteId = when (imported) {
            is LibraryImport.Created -> imported.noteId
            is LibraryImport.Existing -> imported.noteId
            is LibraryImport.Rejected -> null
        }
        val failure = (imported as? LibraryImport.Rejected)?.let { IMPORT_FAILURE + it.failure }
        val stored = StoredRecording(SyncedRecording(planned.name, planned.size, noteId, head), failure)
        store.updateDevice(address) { state ->
            // A recording with the same name and another size is a new one: it replaces the old record.
            val others = state.recordings.filterNot { it.recording.name == planned.name }
            state.copy(recordings = others + stored, partial = null)
        }
        withContext(parts.io) { part.delete() }
        if (imported is LibraryImport.Created) parts.library.transcribe(imported.noteId)
    }

    @Suppress("ReturnCount") // no upload: nothing planned, or this firmware refused before
    private suspend fun upload(
        session: Session,
        address: String,
        firmware: String,
        uploads: List<PlannedUpload>,
        transcripts: Map<String, EncodedTranscript>,
    ): Int {
        if (uploads.isEmpty() || deviceState(address).transcriptsUnsupportedFirmware == firmware) return 0
        var uploaded = 0
        for (upload in uploads) {
            val transcript = upload.recording.noteId?.let(transcripts::get) ?: continue
            val total = transcript.bytes.size.toLong()
            current.value = RecorderActivity.Syncing(SyncStep.UPLOADING, upload.sidecarName, 0, total)
            val failure = try {
                session.call {
                    uploadSidecar(upload.sidecarName, transcript.bytes) { done ->
                        current.value =
                            RecorderActivity.Syncing(SyncStep.UPLOADING, upload.sidecarName, done.toLong(), total)
                    }
                }
                null
            } catch (error: XrecException) {
                if (error.failure !in UPLOAD_FAILURES) throw error
                error.failure
            }
            if (failure == XrecFailure.FIRMWARE_TOO_OLD) {
                Log.w(TAG, "The recorder firmware $firmware does not take transcripts")
                store.updateDevice(address) { it.copy(transcriptsUnsupportedFirmware = firmware) }
                return uploaded
            }
            store.updateDevice(address) { state -> state.withUpload(upload, failure) }
            if (failure == null) uploaded++
        }
        return uploaded
    }

    private fun RecorderDeviceState.withUpload(upload: PlannedUpload, failure: XrecFailure?) = copy(
        recordings = recordings.map { stored ->
            when {
                stored.recording != upload.recording -> stored
                failure != null -> stored.copy(failure = UPLOAD_FAILURE + failure.name)
                else -> StoredRecording(stored.recording.copy(transcriptUploadedVersion = upload.transcriptVersion))
            }
        },
        transcriptsUnsupportedFirmware = null,
    )

    private suspend fun record(address: String, outcome: SyncOutcome) {
        val now = parts.clock()
        val last = when (outcome) {
            is SyncOutcome.Done -> LastSync(now, OUTCOME_OK, null, outcome.downloaded, outcome.uploaded)
            SyncOutcome.Unreachable -> LastSync(now, OUTCOME_UNREACHABLE)
            SyncOutcome.RebindNeeded -> LastSync(now, OUTCOME_REBIND)
            is SyncOutcome.Unavailable -> LastSync(now, outcome.readiness.name)
            is SyncOutcome.Failed -> LastSync(now, OUTCOME_FAILED, outcome.failure?.name ?: outcome.message)
            SyncOutcome.NotBound, SyncOutcome.Busy -> return
        }
        store.updateDevice(address) { state ->
            state.copy(
                lastSync = last,
                lastSuccessAt = if (outcome is SyncOutcome.Done) now else state.lastSuccessAt,
                rebindNeeded = state.rebindNeeded || outcome == SyncOutcome.RebindNeeded,
            )
        }
    }

    // Binding. ------------------------------------------------------------------------------------------------------

    @Suppress("ReturnCount") // each early end of a binding is its own outcome
    private suspend fun bindLocked(device: RecorderDevice, pin: suspend (Boolean) -> String?): BindOutcome {
        val readiness = parts.radio.readiness()
        if (readiness != RadioReadiness.READY) return BindOutcome.Unavailable(readiness)
        current.value = RecorderActivity.Binding(BindStep.BONDING)
        if (!parts.radio.bond(device.address)) return BindOutcome.NotBonded
        current.value = RecorderActivity.Binding(BindStep.CONNECTING)
        val link = try {
            parts.radio.open(device.address)
        } catch (error: IOException) {
            Log.i(TAG, "The recorder does not answer: ${error.message}")
            return BindOutcome.Unreachable
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
            val wrapped = parts.vault.wrap(key)
            store.update { settings ->
                val address = device.address
                val state = (settings.devices[address] ?: RecorderDeviceState()).copy(rebindNeeded = false)
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

    /** One link and its client; [call] runs a blocking client call on IO, interrupted by cancellation. */
    private class Session(val client: XrecClient, private val io: CoroutineDispatcher) {
        suspend fun <T> call(block: XrecClient.() -> T): T = runInterruptible(io) { client.block() }
    }

    private suspend fun <T> session(link: RecorderLink, block: suspend (Session) -> T): T = try {
        block(Session(XrecClient(link.input, link.output, parts.timeouts), parts.io))
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
                val digest = XrecAuth.toHex(MessageDigest.getInstance("SHA-256").digest(bytes))
                EncodedTranscript(bytes, "${transcript.variantId}:${digest.take(VERSION_DIGEST_CHARS)}")
            }
        }

    private fun partFile(address: String, name: String): File =
        File(File(parts.downloads, address.filter { it.isLetterOrDigit() }), "$name.part")

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
        val digest = MessageDigest.getInstance("SHA-256").apply { update(head, 0, read) }.digest()
        return XrecAuth.toHex(digest)
    }

    companion object {
        private const val TAG = "RecorderSync"
        private const val HEAD_BYTES = 4096
        private const val VERSION_DIGEST_CHARS = 16

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

        /** The recorder refused this one upload; the others still go. */
        private val UPLOAD_FAILURES = setOf(
            XrecFailure.FIRMWARE_TOO_OLD,
            XrecFailure.BAD_NAME,
            XrecFailure.CARD_IO,
            XrecFailure.NO_CARD,
            XrecFailure.INTEGRITY,
        )
    }
}
