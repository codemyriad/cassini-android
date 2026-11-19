package io.codemyriad.cassini.processing

import io.codemyriad.cassini.core.asr.TranscriptionMode
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.engine.job.PrecomputeStopReason
import io.codemyriad.cassini.recording.CaptureFormat
import io.codemyriad.cassini.recording.RecordingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coordinates recording-time head-start precomputation lifecycle (D-1124 / `contract/HEAD-START-TRANSCRIPTION.md`).
 *
 * Starts at most one background-priority precompute follower when:
 * - recording is [RecordingState.Active] with [CaptureFormat.OPUS] (never API 26-28 AAC) and unpaused,
 * - [TranscriptionMode] evaluates `spawnWorkerDuringRecording == true`,
 * - the app is visible (per [ForegroundPolicy]),
 * - device thermal status is below `THERMAL_STATUS_SEVERE`,
 * - no foreground saved-audio job or manual preemption is active.
 *
 * Yields immediately on pause, backgrounding, thermal throttling, OOM/low memory, model switch,
 * preemption, or recording Stop without ever disturbing `OpusCapture`.
 */
class RecordingPrecomputeController(
    private val parts: Parts,
) {
    /** Collaborators and observable gates driving recording-time precomputation. */
    @Suppress("LongParameterList") // One seam per observable lifecycle gate.
    class Parts(
        val scope: CoroutineScope,
        val recordingState: StateFlow<RecordingState>,
        val transcriptionMode: Flow<TranscriptionMode>,
        val visible: StateFlow<Boolean>,
        val throttled: StateFlow<Boolean>,
        val foregroundBusy: StateFlow<Boolean>,
        val speechModel: Flow<SpeechModel?>,
        val runPrecompute: suspend (noteId: String, shouldContinue: () -> Boolean) -> PrecomputeStopReason,
    )

    private data class Snapshot(
        val recording: RecordingState,
        val mode: TranscriptionMode,
        val visible: Boolean,
        val throttled: Boolean,
        val foregroundBusy: Boolean,
        val speechModel: SpeechModel?,
    )

    private val lock = Mutex()
    private val allowed = AtomicBoolean(false)
    private var worker: Job? = null
    private var activeNoteId: String? = null
    private var activeSpeechModel: SpeechModel? = null
    private var suppressedNoteId: String? = null

    private val watcher: Job = parts.scope.launch {
        val inputs = combine(
            parts.recordingState,
            parts.transcriptionMode,
            parts.visible,
            parts.throttled,
            parts.foregroundBusy,
        ) { r, m, v, t, f ->
            Snapshot(r, m, v, t, f, speechModel = null)
        }
        combine(inputs, parts.speechModel) { snap, model -> snap.copy(speechModel = model) }
            .collect { snap -> reconcile(snap) }
    }

    /** Immediately signals and cancels any running precompute follower (non-blocking). */
    fun preempt() {
        allowed.set(false)
        worker?.cancel(CancellationException("Preempted"))
    }

    /** Signals cancellation and waits until any active precompute follower has released its resources. */
    suspend fun stopAndJoin() {
        allowed.set(false)
        val running = lock.withLock {
            val current = worker
            worker = null
            activeNoteId = null
            activeSpeechModel = null
            current
        }
        running?.cancelAndJoin()
    }

    /** Stops observing and cancels any running precompute follower. */
    fun close() {
        allowed.set(false)
        watcher.cancel()
        worker?.cancel()
    }

    private suspend fun reconcile(snap: Snapshot) {
        val active = snap.recording as? RecordingState.Active
        if (active == null) {
            suppressedNoteId = null
        }
        val canRun = shouldRun(snap)
        val targetNote = active?.noteId
        val targetModel = snap.speechModel

        if (!canRun || targetNote == null) {
            stopAndJoin()
            return
        }

        val needsRestart = lock.withLock {
            val same = worker?.isActive == true &&
                activeNoteId == targetNote &&
                activeSpeechModel == targetModel
            !same
        }
        if (!needsRestart) return

        stopAndJoin()
        lock.withLock {
            if (!shouldRun(snap)) return@withLock
            allowed.set(true)
            activeNoteId = targetNote
            activeSpeechModel = targetModel
            worker = parts.scope.launch {
                val reason = try {
                    parts.runPrecompute(targetNote) { allowed.get() }
                } catch (_: CancellationException) {
                    PrecomputeStopReason.CANCELLED
                }
                if (reason == PrecomputeStopReason.OUT_OF_MEMORY || reason == PrecomputeStopReason.LOW_MEMORY) {
                    suppressedNoteId = targetNote
                }
            }
        }
    }

    private fun shouldRun(snap: Snapshot): Boolean {
        val active = snap.recording as? RecordingState.Active
        if (active == null || !isEligible(snap, active)) return false
        val decision = TranscriptionMode.evaluate(
            rawMode = snap.mode.canonical,
            hasForegroundSavedJob = snap.foregroundBusy,
            manualTranscribeRequested = false,
            platformSupportsPrecompute = active.format == CaptureFormat.OPUS,
        )
        return decision.spawnWorkerDuringRecording
    }

    private fun isEligible(snap: Snapshot, active: RecordingState.Active): Boolean =
        active.pause == null &&
            suppressedNoteId != active.noteId &&
            snap.visible &&
            !snap.throttled
}
