package org.cassini.android.recording

import android.os.Build
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.cassini.android.AppGraph
import org.cassini.android.nav.ModelDownloadKey
import org.cassini.android.nav.NoteKey
import org.cassini.android.notices.Notices
import org.cassini.core.audio.RecordingSpace
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.recording.RecordingEvent
import org.cassini.ui.recording.RecordingHint
import org.cassini.ui.recording.RecordingPhase
import org.cassini.ui.recording.RecordingUiState
import org.cassini.ui.recording.CaptureFormat as CaptureFormatUi

/** What the Recording ViewModel reads and changes (features.md section 2); [GraphRecordingPorts] binds it. */
interface RecordingPorts {
    val state: StateFlow<RecordingState>

    /** The 56 waveform bars; sampled only while collected. */
    val levels: Flow<List<Float>>

    fun start(title: String): Boolean

    fun setTitle(title: String)

    fun pause()

    fun resume()

    fun finish(title: String?)

    fun dismissEnded()

    /** Whether the model plan has missing parts: then Finish without consent opens Model download too. */
    suspend fun modelsMissing(): Boolean

    /** The last capture format, for the format chip before the capture starts; null before the first recording. */
    suspend fun lastFormat(): CaptureFormat?

    /** Whether POST_NOTIFICATIONS was requested once already (`notifications.asked`). */
    suspend fun notificationsAsked(): Boolean

    suspend fun markNotificationsAsked()
}

/** [RecordingPorts] over the app's graph. */
class GraphRecordingPorts(private val graph: AppGraph) : RecordingPorts {
    private val controller get() = graph.recording

    override val state: StateFlow<RecordingState> get() = controller.state
    override val levels: Flow<List<Float>> = flow { emitAll(controller.levels) }

    override fun start(title: String): Boolean = controller.start(title)

    override fun setTitle(title: String) = controller.setTitle(title)

    override fun pause() = controller.pause()

    override fun resume() = controller.resume()

    override fun finish(title: String?) = controller.finish(title)

    override fun dismissEnded() = controller.dismissEnded()

    override suspend fun modelsMissing(): Boolean = !graph.models.plan().isEmpty

    override suspend fun lastFormat(): CaptureFormat? =
        graph.settings.lastRecordingFormat.first()?.let(CaptureFormat::of)

    override suspend fun notificationsAsked(): Boolean = graph.settings.notificationsAsked.first()

    override suspend fun markNotificationsAsked() = graph.settings.markNotificationsAsked()
}

/** What the Recording route does for the ViewModel. */
sealed interface RecordingEffect {
    /** Asks for [permissions] with `RequestMultiplePermissions`. */
    data class RequestPermissions(val permissions: List<String>) : RecordingEffect

    /** The app's page in the system settings (microphone access off). */
    data object OpenAppSettings : RecordingEffect

    /** Minimise: back to the Library; the recording continues. */
    data object Close : RecordingEffect

    /** Replace the Recording entry with [keys] (the note, and Model download on top of it when needed). */
    data class ReplaceWith(val keys: List<NavKey>) : RecordingEffect
}

/**
 * The Recording screen (board 01, features.md section 2; ui.md section 7). The recording itself is
 * [RecordingController]'s; this ViewModel starts it on entry once RECORD_AUDIO is granted, shows its state, levels
 * and banners, and leads away when it ends.
 *
 * - Entry ([onEnter]) with the permission starts the capture; without it the route asks (with POST_NOTIFICATIONS on
 *   API 33+, once) and `pendingStart` waits in [saved]. A denial shows "Microphone access is off"; the capture
 *   starts when the screen resumes with the permission granted ([onResume]).
 * - Entry while a recording is in progress shows it ("return to the recording"). A restored entry with no recording
 *   (process death) closes itself.
 * - Finish: Saved with TRANSCRIBE or AUDIO_ONLY opens the note; ASK_CONSENT opens the note with Model download on
 *   top when the model plan has missing parts. A storage stop, a microphone failure and a failed start stay here with
 *   their banner.
 */
@Suppress("TooManyFunctions") // one handler per Recording event of ui.md section 7
class RecordingViewModel(
    private val saved: SavedStateHandle,
    private val ports: RecordingPorts,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : ViewModel() {
    private val effectChannel = Channel<RecordingEffect>(Channel.BUFFERED)

    val effects: Flow<RecordingEffect> = effectChannel.receiveAsFlow()

    private val micDenied: StateFlow<Boolean> = saved.getStateFlow(KEY_MIC_DENIED, false)
    private val pendingStart: StateFlow<Boolean> = saved.getStateFlow(KEY_PENDING_START, false)

    /** A failed end that the screen still shows, with the note it saved (if any). */
    private val ended = MutableStateFlow<Pair<RecordingOutcome, String?>?>(null)
    private val format = MutableStateFlow<CaptureFormat?>(null)

    /** This ViewModel saw a capture in progress: only then does an Ended state lead away from the screen. */
    private var sawCapture = false
    private var leaving = false

    private val flags = combine(micDenied, pendingStart, ended, format) { denied, pending, ended, format ->
        Flags(denied, pending, ended, format)
    }

    val state: StateFlow<RecordingUiState> = combine(ports.state, ports.levels, flags) { recorder, levels, flags ->
        build(recorder, levels, flags)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial())

    private data class Flags(
        val micDenied: Boolean,
        val pendingStart: Boolean,
        val ended: Pair<RecordingOutcome, String?>?,
        val format: CaptureFormat?,
    )

    init {
        viewModelScope.launch { format.value = guarded { ports.lastFormat() } }
        viewModelScope.launch { ports.state.collect(::follow) }
    }

    private fun initial() = RecordingUiState(
        phase = RecordingPhase.Starting,
        title = "",
        elapsedMs = 0,
        format = CaptureFormatUi.OPUS,
        sampleRateHz = SAMPLE_RATE_HZ,
    )

    private fun build(recorder: RecordingState, levels: List<Float>, flags: Flags): RecordingUiState {
        val active = recorder as? RecordingState.Active
        val format = active?.format ?: flags.format ?: defaultFormat()
        return RecordingUiState(
            phase = phase(recorder, flags),
            title = titleOf(recorder),
            elapsedMs = elapsedOf(recorder),
            levels = levels.toImmutableList(),
            format = if (format == CaptureFormat.OPUS) CaptureFormatUi.OPUS else CaptureFormatUi.M4A,
            sampleRateHz = active?.sampleRateHz ?: format.sampleRateHz,
            hint = active?.space?.let(::hint),
            banner = Notices.recording(
                state = recorder,
                micOff = flags.micDenied && !recorder.inProgress,
                ended = flags.ended?.first,
            ).firstOrNull(),
        )
    }

    /**
     * The screen's phase. Before a capture: NeedsPermission while the microphone is denied or asked for; after a
     * failed end the screen keeps Stopping (the audio so far is saved), or Starting when nothing started.
     */
    private fun phase(recorder: RecordingState, flags: Flags): RecordingPhase {
        val idle = !recorder.inProgress
        return when {
            idle && (flags.micDenied || flags.pendingStart) -> RecordingPhase.NeedsPermission
            idle && flags.ended?.first == RecordingOutcome.CouldNotStart -> RecordingPhase.Starting
            idle && flags.ended != null -> RecordingPhase.Stopping
            recorder is RecordingState.Active ->
                if (recorder.pause != null) RecordingPhase.Paused else RecordingPhase.Recording
            recorder is RecordingState.Finishing || recorder is RecordingState.Ended -> RecordingPhase.Stopping
            else -> RecordingPhase.Starting
        }
    }

    private fun titleOf(recorder: RecordingState): String = when (recorder) {
        is RecordingState.Starting -> recorder.title
        is RecordingState.Active -> recorder.title
        is RecordingState.Finishing -> recorder.title
        else -> ""
    }

    private fun elapsedOf(recorder: RecordingState): Long = when (recorder) {
        is RecordingState.Active -> recorder.elapsedMs
        is RecordingState.Finishing -> recorder.elapsedMs
        else -> 0
    }

    // Entry and permissions. -------------------------------------------------------------------------------------

    /**
     * The screen is shown. [micGranted] and [notificationsGranted] are the current permissions. Runs once per entry:
     * a recreated screen does not start a second recording.
     */
    fun onEnter(micGranted: Boolean, notificationsGranted: Boolean) {
        if (saved.get<Boolean>(KEY_ENTERED) == true) {
            // A restored entry: with no recording (the process died) and nothing to wait for, the entry goes.
            if (!ports.state.value.inProgress && !pendingStart.value && ended.value == null) close()
            return
        }
        saved[KEY_ENTERED] = true
        if (ports.state.value.inProgress) return
        viewModelScope.launch {
            val askNotifications =
                sdkInt >= TIRAMISU && !notificationsGranted && guarded { ports.notificationsAsked() } != true
            val ask = buildList {
                if (!micGranted) add(RECORD_AUDIO)
                if (askNotifications) add(POST_NOTIFICATIONS)
            }
            if (micGranted) startCapture() else saved[KEY_PENDING_START] = true
            if (ask.isNotEmpty()) emit(RecordingEffect.RequestPermissions(ask))
        }
    }

    /** The answer to [RecordingEffect.RequestPermissions]. */
    fun onPermissionResult(micGranted: Boolean, askedNotifications: Boolean) {
        if (askedNotifications) viewModelScope.launch { guarded { ports.markNotificationsAsked() } }
        if (!pendingStart.value) return
        if (micGranted) {
            startCapture()
        } else {
            saved[KEY_MIC_DENIED] = true
        }
    }

    /** ON_RESUME: back from the system settings with the permission granted, the capture starts. */
    fun onResume(micGranted: Boolean) {
        if (micGranted && pendingStart.value && !ports.state.value.inProgress) startCapture()
    }

    private fun startCapture() {
        saved[KEY_PENDING_START] = false
        saved[KEY_MIC_DENIED] = false
        ended.value = null
        ports.dismissEnded()
        ports.start("")
    }

    // Events. ----------------------------------------------------------------------------------------------------

    fun onEvent(event: RecordingEvent) {
        when (event) {
            is RecordingEvent.Title -> ports.setTitle(event.text)
            RecordingEvent.PauseResume -> {
                val active = ports.state.value as? RecordingState.Active ?: return
                if (active.pause != null) ports.resume() else ports.pause()
            }
            RecordingEvent.Finish -> {
                val active = ports.state.value as? RecordingState.Active ?: return
                ports.finish(active.title)
            }
            RecordingEvent.Minimise, RecordingEvent.OpenLibrary -> close()
            RecordingEvent.OpenSettings -> emit(RecordingEffect.OpenAppSettings)
            is RecordingEvent.BannerAction -> bannerAction(event.action)
        }
    }

    private fun bannerAction(action: BannerAction) {
        when (action) {
            BannerAction.RESUME -> ports.resume()
            BannerAction.OPEN_SETTINGS -> emit(RecordingEffect.OpenAppSettings)
            BannerAction.TRY_AGAIN -> startCapture()
            BannerAction.OPEN_NOTE -> ended.value?.second?.let { leave(listOf(NoteKey(it))) } ?: close()
            else -> Log.w(TAG, "No Recording handler for $action")
        }
    }

    // The recorder. ----------------------------------------------------------------------------------------------

    private suspend fun follow(recorder: RecordingState) {
        when (recorder) {
            is RecordingState.Starting, is RecordingState.Finishing -> sawCapture = true
            is RecordingState.Active -> {
                sawCapture = true
                // The title field starts with the default title, as the board shows it; the user may clear it.
                if (recorder.title.isEmpty() && saved.get<String>(KEY_PREFILLED) != recorder.noteId) {
                    saved[KEY_PREFILLED] = recorder.noteId
                    ports.setTitle(recorder.defaultTitle)
                }
            }
            is RecordingState.Ended -> if (sawCapture) onEnded(recorder)
            RecordingState.Idle -> Unit
        }
    }

    private suspend fun onEnded(recorder: RecordingState.Ended) {
        sawCapture = false
        val noteId = recorder.noteId
        when (val outcome = recorder.outcome) {
            is RecordingOutcome.Saved -> {
                ports.dismissEnded()
                if (noteId == null) return close()
                val download = outcome.next == AfterFinish.ASK_CONSENT && guarded { ports.modelsMissing() } == true
                leave(listOfNotNull(NoteKey(noteId), ModelDownloadKey(noteId).takeIf { download }))
            }
            else -> {
                ended.value = outcome to noteId
                ports.dismissEnded()
            }
        }
    }

    private fun leave(keys: List<NavKey>) {
        if (leaving) return
        leaving = true
        emit(RecordingEffect.ReplaceWith(keys))
    }

    private fun close() {
        if (leaving) return
        leaving = true
        emit(RecordingEffect.Close)
    }

    private fun hint(space: RecordingSpace): RecordingHint = when (space.limit) {
        RecordingSpace.Limit.SPACE -> RecordingHint.Space(space.spaceMs)
        RecordingSpace.Limit.CAP -> RecordingHint.Cap(space.capMs)
    }

    /** Opus from API 29 (the platform encoder), else M4A, as the controller picks it. */
    private fun defaultFormat(): CaptureFormat = if (sdkInt >= OPUS_API) CaptureFormat.OPUS else CaptureFormat.M4A

    private fun emit(effect: RecordingEffect) {
        effectChannel.trySend(effect)
    }

    private suspend fun <T> guarded(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        Log.w(TAG, "A Recording read failed", error)
        null
    }

    companion object {
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

        private const val TAG = "CassiniRecordingScreen"
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val SAMPLE_RATE_HZ = 48_000
        private const val TIRAMISU = 33
        private const val OPUS_API = 29
        private const val KEY_ENTERED = "entered"
        private const val KEY_PENDING_START = "pendingStart"
        private const val KEY_MIC_DENIED = "micDenied"
        private const val KEY_PREFILLED = "prefilled"
    }
}
