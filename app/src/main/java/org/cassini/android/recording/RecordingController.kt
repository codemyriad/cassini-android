package org.cassini.android.recording

import android.util.Log
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.cassini.core.audio.RecordingPolicy
import org.cassini.core.audio.RecordingSpace
import org.cassini.data.notes.NoteTitles
import org.cassini.engine.capture.MicrophoneCapture
import java.io.File

/**
 * The one recording of the process (architecture 5.8): the [MicrophoneCapture], [state], [levels] and the 1 s policy
 * loop. Every change runs on [Parts.dispatcher], a single thread, so `capture.stop()` and the row writes never run
 * on the main thread; the public calls only post work there and return at once.
 *
 * - [start] is called from the visible Recording screen (the Android 14 rule for a microphone service): it creates
 *   the LIVE_CAPTURE row, starts `RecordingService` and waits until it is in the foreground, then starts the capture,
 *   the wake lock (2 h 10 min) and the policy loop. A failed start removes the row and its directory (CouldNotStart).
 * - Title edits are written to the row 500 ms after the last change; [finish] writes the final value.
 * - [finish] stops the capture, makes the row PLAIN, hands the note to processing when transcription follows (with
 *   consent; never after a storage stop or a microphone failure), then ends with [RecordingState.Ended].
 * - Another app that silences the capture pauses it (OTHER_APP); it stays paused until the user resumes.
 */
@Suppress("TooManyFunctions") // the recording API of architecture 5.8 plus one helper per step of start and finish
class RecordingController(private val parts: Parts) {
    /** What the controller is built from; `AppGraph` passes the app's implementations, tests pass fakes. */
    @Suppress("LongParameterList") // one seam per collaborator of architecture 5.8
    class Parts(
        val scope: CoroutineScope,
        /** A single thread: every change of the recording runs on it. */
        val dispatcher: CoroutineDispatcher,
        /** Waits for the startup work (`AppGraph.ready`). */
        val ready: suspend () -> Unit,
        val notes: suspend () -> RecordingNotes,
        val capture: CaptureSource,
        val platform: RecordingPlatform,
        val handoff: TranscriptionHandoff,
        val consent: RecordingConsent,
        /** The default title for a recording created at the given wall-clock time ("Voice note 8 Oct, 08:14"). */
        val defaultTitle: (Long) -> String,
        val clock: () -> Long = System::currentTimeMillis,
        val timing: Timing = Timing(),
    )

    /** The intervals of architecture 5.8; tests shorten [tickMs]. */
    data class Timing(
        val tickMs: Long = 1_000,
        val titleDebounceMs: Long = 500,
        val levelSampleMs: Long = 100,
        val serviceWaitMs: Long = 10_000,
    )

    private enum class Stop { USER, TIME_LIMIT, SPACE, MICROPHONE }

    private enum class Service { IDLE, WAITING, FOREGROUND, REFUSED }

    private val timing = parts.timing
    private val mutableState = MutableStateFlow<RecordingState>(RecordingState.Idle)
    private val active = MutableStateFlow<String?>(null)
    private val service = MutableStateFlow(Service.IDLE)

    /** Serialises start and end: a Finish during the start waits for it. */
    private val operations = Mutex()

    // Touched only on parts.dispatcher, except the capture, which the level sampler reads.
    @Volatile private var capture: MicrophoneCapture? = null
    private var policy: Job? = null
    private var titleWrite: Job? = null
    private var silence: SilenceWatch? = null
    private var fallbackTitle = ""

    val state: StateFlow<RecordingState> = mutableState.asStateFlow()

    /** The note that records now; the startup work leaves it alone (`AppGraph.bindActiveRecording`). */
    val activeNoteId: StateFlow<String?> = active.asStateFlow()

    /**
     * The waveform: [BARS] values in 0..1 for the last 7 s, 125 ms each ([BAR_MS]), each the maximum of the 100 ms
     * amplitude samples in it. The newest is last. Bars follow the captured time, so they freeze while paused. The
     * sampler runs only while somebody collects.
     */
    val levels: StateFlow<List<Float>> = flow {
        while (true) {
            sampleLevel()?.let { emit(it) }
            delay(timing.levelSampleMs)
        }
    }.distinctUntilChanged().stateIn(parts.scope, SharingStarted.WhileSubscribed(), List(BARS) { 0f })

    // The waveform's bars, kept across subscriptions; only the one sampler of [levels] touches them.
    private val bars = FloatArray(BARS)
    private var barSource: MicrophoneCapture? = null
    private var lastBar = 0L

    /** The captured time now (the notification's chronometer); 0 without a capture. */
    val elapsedNowMs: Long get() = capture?.elapsedMs ?: 0L

    // Actions. ----------------------------------------------------------------------------------------------------

    /** Starts a recording titled [title] (blank: the default). False when one is already in progress. */
    fun start(title: String): Boolean {
        while (true) {
            val current = mutableState.value
            if (current.inProgress) return false
            if (mutableState.compareAndSet(current, RecordingState.Starting(title))) break
        }
        work { operations.withLock { begin(title) } }
        return true
    }

    /** The title field changed: the state keeps [title] as typed, and the row gets it 500 ms after the last change. */
    fun setTitle(title: String) {
        mutableState.update { current ->
            when (current) {
                is RecordingState.Starting -> current.copy(title = title)
                is RecordingState.Active -> current.copy(title = title)
                else -> current
            }
        }
        work { scheduleTitle() }
    }

    fun pause() = work {
        val mic = capture ?: return@work
        if (!mic.paused) mic.togglePause()
        update { it.copy(pause = RecordingPause.USER, elapsedMs = mic.elapsedMs) }
    }

    fun resume() = work {
        val mic = capture ?: return@work
        if (mic.paused) mic.togglePause()
        update { it.copy(pause = null) }
        silence?.resumed()
    }

    /**
     * Finish: stops and saves, asynchronously ([state] goes Finishing, then Ended). [title] is the field's final value;
     * null keeps the current one (the notification's Finish). A blank title becomes the default.
     */
    fun finish(title: String? = null) = work { operations.withLock { end(Stop.USER, title) } }

    /** The UI has handled [RecordingState.Ended]. */
    fun dismissEnded() {
        mutableState.update { if (it is RecordingState.Ended) RecordingState.Idle else it }
    }

    /** Set by `RecordingService` right after its `startForeground` returned. */
    fun onServiceForeground() {
        service.value = Service.FOREGROUND
    }

    /** `RecordingService` could not enter the foreground: the start fails (CouldNotStart). */
    fun onServiceRefused() {
        service.value = Service.REFUSED
    }

    /** Returns once the work posted before this call has run (tests). */
    @VisibleForTesting
    internal suspend fun awaitPosted() = withContext(parts.dispatcher) {}

    // Start. ------------------------------------------------------------------------------------------------------

    @Suppress("TooGenericExceptionCaught") // every failure of the start ends in CouldNotStart and is cleaned up
    private suspend fun begin(title: String) {
        var noteId: String? = null
        try {
            parts.ready()
            val notes = parts.notes()
            val createdAt = parts.clock()
            fallbackTitle = parts.defaultTitle(createdAt)
            val format = parts.capture.format()
            val id = notes.create(NoteTitles.clean(title) ?: fallbackTitle, createdAt, format)
            noteId = id
            active.value = id
            enterForeground()
            val mic = parts.capture.start(File(notes.directory(id), "capture.${format.extension}")) {
                work { operations.withLock { end(Stop.MICROPHONE, null) } }
            }
            capture = mic
            parts.platform.acquireWakeLock()
            silence = parts.platform.watchSilence(mic) { silenced -> work { onSilence(silenced) } }
            val typed = (mutableState.value as? RecordingState.Starting)?.title ?: title
            val usable = parts.platform.usableBytes(notes.directory(id))
            mutableState.value = RecordingState.Active(
                noteId = id,
                title = typed,
                format = format,
                sampleRateHz = format.sampleRateHz,
                elapsedMs = mic.elapsedMs,
                pause = null,
                space = RecordingSpace.of(mic.elapsedMs, usable, format.nominalBytesPerSecond),
                warning = null,
            )
            if (typed != title) scheduleTitle()
            policy = parts.scope.launch(parts.dispatcher) { policyLoop() }
        } catch (error: CancellationException) {
            // The application scope ends (tests only): recovery repairs the row at the next start.
            capture?.release()
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "The recording could not start", error)
            couldNotStart(noteId)
        }
    }

    /** Starts `RecordingService` and waits until it is in the foreground; throws when Android refused it. */
    private suspend fun enterForeground() {
        service.value = Service.WAITING
        check(parts.platform.startService()) { "Android refused to start the recording service" }
        val status = withTimeoutOrNull(timing.serviceWaitMs) { service.first { it != Service.WAITING } }
        check(status == Service.FOREGROUND) { "The recording service did not enter the foreground" }
    }

    @Suppress("TooGenericExceptionCaught") // cleanup is best effort; recovery repairs what is left
    private suspend fun couldNotStart(noteId: String?) {
        silence?.close()
        silence = null
        capture?.release()
        capture = null
        parts.platform.releaseWakeLock()
        if (noteId != null) {
            try {
                parts.notes().discard(noteId)
            } catch (error: Exception) {
                Log.w(TAG, "The row of a failed start stays for recovery", error)
            }
        }
        active.value = null
        service.value = Service.IDLE
        mutableState.value = RecordingState.Ended(null, RecordingOutcome.CouldNotStart)
    }

    // While recording. ----------------------------------------------------------------------------------------------

    private suspend fun policyLoop() {
        while (true) {
            delay(timing.tickMs)
            val stop = tick() ?: continue
            // A new coroutine: end() cancels this loop.
            work { operations.withLock { end(stop, null) } }
            return
        }
    }

    /** One policy decision (`RecordingPolicy`); returns the stop it asks for. */
    private fun tick(): Stop? {
        val mic = capture
        val current = mutableState.value as? RecordingState.Active
        if (mic == null || current == null) return null
        val elapsedMs = mic.elapsedMs
        val usable = parts.platform.usableBytes(mic.file.parentFile ?: mic.file)
        val rate = bytesPerSecond(mic, current.format, elapsedMs)
        val decision = RecordingPolicy.decide(elapsedMs, usable, rate)
        val warning = when (decision) {
            is RecordingPolicy.Decision.StorageLow -> RecordingWarning.StorageLow(decision.minutes)
            is RecordingPolicy.Decision.NearTimeLimit -> RecordingWarning.NearTimeLimit(decision.msLeft)
            else -> null
        }
        update { it.copy(elapsedMs = elapsedMs, space = RecordingSpace.of(elapsedMs, usable, rate), warning = warning) }
        return when (decision) {
            RecordingPolicy.Decision.StopAtTimeLimit -> Stop.TIME_LIMIT
            RecordingPolicy.Decision.StopForSpace -> Stop.SPACE
            else -> null
        }
    }

    /** The nominal rate of [format], or the measured growth of the file once it has recorded for 10 s. */
    private fun bytesPerSecond(mic: MicrophoneCapture, format: CaptureFormat, elapsedMs: Long): Long {
        if (elapsedMs < MEASURE_AFTER_MS) return format.nominalBytesPerSecond
        val measured = mic.file.length() * MS_PER_SECOND / elapsedMs
        return if (measured > 0) measured else format.nominalBytesPerSecond
    }

    private fun onSilence(silenced: Boolean) {
        // When the other app lets go, the recording stays paused until the user resumes (architecture 5.8).
        val mic = capture
        if (!silenced || mic == null || mic.paused) return
        mic.togglePause()
        update { it.copy(pause = RecordingPause.OTHER_APP, elapsedMs = mic.elapsedMs) }
    }

    private fun scheduleTitle() {
        val noteId = (mutableState.value as? RecordingState.Active)?.noteId ?: return
        titleWrite?.cancel()
        titleWrite = parts.scope.launch(parts.dispatcher) {
            delay(timing.titleDebounceMs)
            val typed = (mutableState.value as? RecordingState.Active)?.title ?: return@launch
            NoteTitles.clean(typed)?.let { guarded("write the title") { parts.notes().rename(noteId, it) } }
        }
    }

    // End. --------------------------------------------------------------------------------------------------------

    private suspend fun end(stop: Stop, title: String?) {
        val current = mutableState.value as? RecordingState.Active ?: return
        val mic = capture ?: return
        policy?.cancel()
        policy = null
        titleWrite?.cancelAndJoin()
        titleWrite = null
        silence?.close()
        silence = null
        val typed = title ?: current.title
        mutableState.value = RecordingState.Finishing(current.noteId, typed, mic.elapsedMs)
        var kept: String? = current.noteId
        var outcome = when (stop) {
            Stop.SPACE -> RecordingOutcome.StoppedForSpace
            Stop.MICROPHONE -> RecordingOutcome.MicrophoneFailed
            Stop.USER -> RecordingOutcome.Saved(AfterFinish.AUDIO_ONLY)
            Stop.TIME_LIMIT -> RecordingOutcome.Saved(AfterFinish.AUDIO_ONLY, atTimeLimit = true)
        }
        try {
            val notes = parts.notes()
            val file = stopCapture(mic)
            if (file == null) {
                guarded("discard an empty recording") { notes.discard(current.noteId) }
                kept = null
                outcome = RecordingOutcome.MicrophoneFailed
            } else {
                guarded("save the recording") {
                    notes.rename(current.noteId, NoteTitles.clean(typed) ?: fallbackTitle)
                    notes.finish(current.noteId, mic.elapsedMs)
                }
                if (outcome is RecordingOutcome.Saved) outcome = outcome.copy(next = next(current.noteId))
            }
        } finally {
            capture = null
            parts.platform.releaseWakeLock()
            active.value = null
            service.value = Service.IDLE
            mutableState.value = RecordingState.Ended(kept, outcome)
        }
    }

    /** Stops [mic] and returns its file, or null when nothing was captured. */
    @Suppress("TooGenericExceptionCaught") // whatever the capture throws, the audio written so far is kept
    private suspend fun stopCapture(mic: MicrophoneCapture): File? {
        val file = try {
            mic.stop()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "The capture did not stop cleanly; the audio so far is kept", error)
            mic.file
        }
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /** After a saved recording: the hand-off with consent, else the consent question or Audio only. */
    private suspend fun next(noteId: String): AfterFinish {
        var next = AfterFinish.AUDIO_ONLY
        guarded("hand the recording to processing") {
            next = when {
                parts.consent.consented() ->
                    if (parts.handoff.handOff(noteId)) AfterFinish.TRANSCRIBE else AfterFinish.AUDIO_ONLY
                parts.consent.declined() -> AfterFinish.AUDIO_ONLY
                else -> AfterFinish.ASK_CONSENT
            }
        }
        return next
    }

    // Helpers. ----------------------------------------------------------------------------------------------------

    /** One 100 ms amplitude sample into the bar of its captured time; null without a capture. */
    private fun sampleLevel(): List<Float>? {
        val mic = capture ?: return null
        val bar = mic.elapsedMs / BAR_MS
        if (mic !== barSource) {
            bars.fill(0f)
            barSource = mic
            lastBar = bar
        }
        val shift = (bar - lastBar).coerceIn(0, BARS.toLong()).toInt()
        if (shift > 0) {
            System.arraycopy(bars, shift, bars, 0, BARS - shift)
            bars.fill(0f, BARS - shift, BARS)
            lastBar = bar
        }
        val level = mic.amplitude.coerceIn(0, MAX_AMPLITUDE) / MAX_AMPLITUDE.toFloat()
        bars[BARS - 1] = maxOf(bars[BARS - 1], level)
        return bars.toList()
    }

    private fun work(block: suspend () -> Unit) {
        parts.scope.launch(parts.dispatcher) { block() }
    }

    private fun update(change: (RecordingState.Active) -> RecordingState.Active) {
        mutableState.update { current -> if (current is RecordingState.Active) change(current) else current }
    }

    @Suppress("TooGenericExceptionCaught") // best effort: logged, and recovery repairs the row at the next start
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Could not $what", error)
        }
    }

    companion object {
        /** The waveform: 56 bars of 125 ms, the last 7 s (features.md section 2). */
        const val BARS = 56
        const val BAR_MS = 125L

        private const val TAG = "RecordingController"
        private const val MAX_AMPLITUDE = 32_767
        private const val MEASURE_AFTER_MS = 10_000L
        private const val MS_PER_SECOND = 1_000L
    }
}
