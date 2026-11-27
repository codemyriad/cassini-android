package io.codemyriad.cassini.recorder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.recorder.LastSync
import io.codemyriad.cassini.data.recorder.RecorderDevice
import io.codemyriad.cassini.data.recorder.RecorderSettings
import io.codemyriad.cassini.data.recorder.StoredRecording
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Recorder screen needs from the app; [GraphRecorderScreenPorts] in the app, fakes in tests. */
interface RecorderScreenPorts {
    val settings: Flow<RecorderSettings>
    val activity: StateFlow<RecorderActivity>

    /** The live notes by id, as the recorder rows show them. */
    val notes: Flow<Map<String, NoteSyncInfo>>

    /** The companion device chooser exists; else the screen lists the bonded recorders. */
    val companionSupported: Boolean

    fun readiness(): RadioReadiness

    fun bondedRecorders(): List<RecorderDevice>

    fun syncNow()

    suspend fun setAutoSync(enabled: Boolean)

    suspend fun setSyncWhileRecording(enabled: Boolean)

    suspend fun bind(device: RecorderDevice, pin: suspend (wrong: Boolean) -> String?): BindOutcome

    suspend fun forget(device: RecorderDevice)
}

/**
 * A note as the recorder rows show it: [transcribed] when it has a finished transcript with words, [noSpeech] when its
 * transcription finished without any word (a silent recording). Both push sidecars back to the recorder.
 */
data class NoteSyncInfo(
    val title: String,
    val jobState: JobState?,
    val transcribed: Boolean,
    val noSpeech: Boolean = false,
)

enum class RecordingStatus {
    IMPORT_FAILED,
    NOTE_GONE,
    WAITING_FOR_CONSENT,
    TRANSCRIBING,
    TRANSCRIPTION_FAILED,
    IMPORTED,
    TRANSCRIBED,

    /** The transcription finished without any word: its "No speech" sidecar waits for the recorder. */
    TRANSCRIBED_NO_SPEECH,

    /** Transcribed, but the recording is no longer on the recorder: the transcript has nowhere to go. */
    TRANSCRIBED_NOT_ON_RECORDER,
    UPLOADED,
    UPLOADED_NO_SPEECH,
    UPLOAD_FAILED,
}

/** Pending round-trip counts for the bound recorder. */
data class RecorderQueueCounts(
    val transcribing: Int = 0,
    val waitingForRecorder: Int = 0,
)

/** One synced recording: its file [name], its note ([noteId], [title]) and where it is now. */
data class RecordingRow(
    val name: String,
    val noteId: String?,
    val title: String?,
    val status: RecordingStatus,
    val failure: String? = null,
)

sealed interface BindFailure {
    data object NotBonded : BindFailure

    data object Unreachable : BindFailure

    /** The recorder refused the link over the phone's old bond: remove the bond in the Bluetooth settings. */
    data object StaleBond : BindFailure

    data object PairingClosed : BindFailure

    data class Chooser(val reason: String?) : BindFailure

    data class Unavailable(val readiness: RadioReadiness) : BindFailure

    data class Other(val reason: String?) : BindFailure
}

/** The steps of "Bind recorder". */
sealed interface BindUi {
    /** What to do on the recorder first, and "Find recorder". */
    data object Instructions : BindUi

    /** The bonded recorders, when Android has no companion device chooser. */
    data class Choose(val devices: List<RecorderDevice>) : BindUi

    /** Android pairs, or the app connects or binds: the activity tells which. */
    data object Working : BindUi

    data class Pin(val wrong: Boolean) : BindUi

    data class Failed(val failure: BindFailure) : BindUi
}

data class RecorderUiState(
    val readiness: RadioReadiness = RadioReadiness.READY,
    val companionSupported: Boolean = true,
    val device: RecorderDevice? = null,
    val rebindNeeded: Boolean = false,
    val autoSync: Boolean = false,
    val syncWhileRecording: Boolean = true,
    val activity: RecorderActivity = RecorderActivity.Idle,
    val lastSync: LastSync? = null,
    val transcriptsUnsupported: Boolean = false,
    val recordings: List<RecordingRow> = emptyList(),
    val queue: RecorderQueueCounts = RecorderQueueCounts(),
    val bind: BindUi? = null,
) {
    val busy: Boolean get() = activity != RecorderActivity.Idle
}

sealed interface RecorderEvent {
    data object StartBind : RecorderEvent

    /** "Find recorder" without the companion chooser: list the bonded recorders. */
    data object ListBonded : RecorderEvent

    data class DevicePicked(val device: RecorderDevice) : RecorderEvent

    data class ChooserFailed(val reason: String?) : RecorderEvent

    data class PinEntered(val pin: String) : RecorderEvent

    data object CancelBind : RecorderEvent

    data object SyncNow : RecorderEvent

    data class AutoSync(val enabled: Boolean) : RecorderEvent

    data class SyncWhileRecording(val enabled: Boolean) : RecorderEvent

    data object Forget : RecorderEvent

    /** The screen resumed or a permission answer came: read the Bluetooth state again. */
    data object Refresh : RecorderEvent

    data class OpenNote(val noteId: String) : RecorderEvent
}

sealed interface RecorderEffect {
    data class OpenNote(val noteId: String) : RecorderEffect
}

/** The Recorder screen (docs/recorder-sync.md): the bound recorder, binding, sync and the synced recordings. */
class RecorderViewModel(private val ports: RecorderScreenPorts) : ViewModel() {
    private val readiness = MutableStateFlow(ports.readiness())
    private val bind = MutableStateFlow<BindUi?>(null)
    private val effectChannel = Channel<RecorderEffect>(Channel.BUFFERED)
    private var pinAnswer: CompletableDeferred<String?>? = null
    private var binding: Job? = null

    val effects: Flow<RecorderEffect> = effectChannel.receiveAsFlow()

    val state: StateFlow<RecorderUiState> = combine(
        ports.settings,
        ports.activity,
        ports.notes,
        readiness,
        bind,
    ) { settings, activity, notes, readiness, bind ->
        val current = settings.current
        val rows = RecorderRows.rows(current?.recordings.orEmpty(), notes)
        RecorderUiState(
            readiness = readiness,
            companionSupported = ports.companionSupported,
            device = settings.binding?.device,
            rebindNeeded = current?.rebindNeeded == true,
            autoSync = settings.autoSync,
            syncWhileRecording = settings.syncWhileRecording,
            activity = activity,
            lastSync = current?.lastSync,
            transcriptsUnsupported = current?.transcriptsUnsupportedFirmware != null,
            recordings = rows,
            queue = RecorderRows.queue(rows),
            bind = bind,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), RecorderUiState())

    @Suppress("CyclomaticComplexMethod") // one branch per event
    fun onEvent(event: RecorderEvent) {
        when (event) {
            RecorderEvent.StartBind -> bind.value = BindUi.Instructions
            RecorderEvent.ListBonded -> bind.value = BindUi.Choose(ports.bondedRecorders())
            is RecorderEvent.DevicePicked -> startBinding(event.device)
            is RecorderEvent.ChooserFailed -> bind.value = BindUi.Failed(BindFailure.Chooser(event.reason))
            is RecorderEvent.PinEntered -> if (PIN.matches(event.pin)) pinAnswer?.complete(event.pin)
            RecorderEvent.CancelBind -> cancelBinding()
            RecorderEvent.SyncNow -> ports.syncNow()
            is RecorderEvent.AutoSync -> viewModelScope.launch { ports.setAutoSync(event.enabled) }
            is RecorderEvent.SyncWhileRecording -> viewModelScope.launch { ports.setSyncWhileRecording(event.enabled) }
            RecorderEvent.Forget -> state.value.device?.let { device -> viewModelScope.launch { ports.forget(device) } }
            RecorderEvent.Refresh -> readiness.value = ports.readiness()
            is RecorderEvent.OpenNote -> effectChannel.trySend(RecorderEffect.OpenNote(event.noteId))
        }
    }

    private fun startBinding(device: RecorderDevice) {
        binding?.cancel()
        bind.value = BindUi.Working
        binding = viewModelScope.launch {
            val outcome = ports.bind(device) { wrong ->
                val answer = CompletableDeferred<String?>()
                pinAnswer = answer
                bind.value = BindUi.Pin(wrong)
                answer.await().also { bind.value = BindUi.Working }
            }
            pinAnswer = null
            bind.value = failureOf(outcome)?.let(BindUi::Failed)
            if (outcome == BindOutcome.Bound) ports.syncNow()
        }
    }

    private fun cancelBinding() {
        pinAnswer?.complete(null)
        pinAnswer = null
        binding?.cancel()
        binding = null
        bind.value = null
    }

    private fun failureOf(outcome: BindOutcome): BindFailure? = when (outcome) {
        BindOutcome.Bound, BindOutcome.Cancelled -> null
        BindOutcome.Busy -> BindFailure.Other(null)
        is BindOutcome.Unavailable -> BindFailure.Unavailable(outcome.readiness)
        BindOutcome.NotBonded -> BindFailure.NotBonded
        BindOutcome.Unreachable -> BindFailure.Unreachable
        BindOutcome.StaleBond -> BindFailure.StaleBond
        BindOutcome.PairingClosed -> BindFailure.PairingClosed
        is BindOutcome.Failed -> BindFailure.Other(outcome.failure?.name ?: outcome.message)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        val PIN = Regex("[0-9]{6}")
    }
}

/** The rows of the synced recordings, newest first, with the state of their notes. */
object RecorderRows {
    fun rows(recordings: List<StoredRecording>, notes: Map<String, NoteSyncInfo>): List<RecordingRow> =
        recordings.asReversed().map { stored ->
            val recording = stored.recording
            val note = recording.noteId?.let(notes::get)
            RecordingRow(
                name = recording.name,
                noteId = recording.noteId.takeIf { note != null },
                title = note?.title,
                status = status(stored, note),
                failure = stored.failure?.substringAfter('_'),
            )
        }

    fun queue(rows: List<RecordingRow>): RecorderQueueCounts = RecorderQueueCounts(
        transcribing = rows.count { it.status == RecordingStatus.TRANSCRIBING || it.status == RecordingStatus.IMPORTED },
        waitingForRecorder = rows.count {
            it.status == RecordingStatus.TRANSCRIBED || it.status == RecordingStatus.TRANSCRIBED_NO_SPEECH
        },
    )

    private fun status(stored: StoredRecording, note: NoteSyncInfo?): RecordingStatus = when {
        stored.recording.noteId == null -> RecordingStatus.IMPORT_FAILED
        note == null -> RecordingStatus.NOTE_GONE
        stored.failure?.startsWith(RecorderSync.UPLOAD_FAILURE) == true -> RecordingStatus.UPLOAD_FAILED
        else -> noteStatus(stored, note)
    }

    private fun noteStatus(stored: StoredRecording, note: NoteSyncInfo): RecordingStatus {
        val done = note.transcribed || note.noSpeech
        return when {
            done && stored.recording.transcriptUploadedVersion != null ->
                if (note.noSpeech) RecordingStatus.UPLOADED_NO_SPEECH else RecordingStatus.UPLOADED
            done && stored.absent -> RecordingStatus.TRANSCRIBED_NOT_ON_RECORDER
            note.transcribed -> RecordingStatus.TRANSCRIBED
            note.noSpeech -> RecordingStatus.TRANSCRIBED_NO_SPEECH
            note.jobState == JobState.NEEDS_CONSENT -> RecordingStatus.WAITING_FOR_CONSENT
            note.jobState == JobState.FAILED -> RecordingStatus.TRANSCRIPTION_FAILED
            note.jobState != null -> RecordingStatus.TRANSCRIBING
            else -> RecordingStatus.IMPORTED
        }
    }
}
