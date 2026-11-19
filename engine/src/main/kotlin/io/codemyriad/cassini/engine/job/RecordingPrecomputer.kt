package io.codemyriad.cassini.engine.job

import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.ProcessingPaused
import io.codemyriad.cassini.core.UserFacingException
import io.codemyriad.cassini.core.audio.PcmAudio
import io.codemyriad.cassini.core.audio.PcmFile
import io.codemyriad.cassini.core.checkpoint.CheckpointNames
import io.codemyriad.cassini.core.checkpoint.CheckpointOrigin
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.checkpoint.CommittedPrefix
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.WordBodyBudget
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.core.requireUser
import io.codemyriad.cassini.core.speakers.Diarization
import io.codemyriad.cassini.core.speakers.VoiceClipCache
import io.codemyriad.cassini.engine.decode.CommittedOggOpusDecoder
import io.codemyriad.cassini.engine.decode.CommittedPrefixDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** Why a recording-time precomputation session stopped. */
enum class PrecomputeStopReason {
    /** Yielded cleanly (pause, backgrounding, thermal throttling, preemption, mode change, or Stop). */
    YIELDED,

    /** Underlying Ogg Opus stream exposed EOS (recording finished). */
    EOS_REACHED,

    /** Skipped because the required speech model was not ready locally (never downloads during recording). */
    MODEL_NOT_READY,

    /** Skipped because low-memory preflight refused inference. */
    LOW_MEMORY,

    /** Stopped cleanly after catching `OutOfMemoryError`. */
    OUT_OF_MEMORY,

    /** Stopped cleanly after container framing corruption or decode error; post-Stop batch decode will handle it. */
    DECODE_ERROR,

    /** Cancelled by coordinator or lifecycle teardown. */
    CANCELLED,
}

/**
 * Recording-time head-start precomputation follower (D-1124 / `contract/HEAD-START-TRANSCRIPTION.md`).
 *
 * Reads ONLY already-flushed, CRC-valid non-EOS Ogg Opus pages from disk via [CommittedPrefixDecoder]
 * (`RandomAccessFile(..., "r")`) at background thread priority under [NativeBudget]. Populates Android's
 * existing `work/checkpoint.json` (with `origin = "precompute"` and `committedPrefix`), `work/words.log`,
 * `work/clips.pcm`, and (for Whistle) `work/whistle-segments.json` so post-Stop [TranscriptionJob] resumes
 * seamlessly from the last committed window/segment.
 *
 * Never shares locks, queues, observers, or allocations with `OpusCapture`, never triggers model downloads,
 * and never fails or disturbs recording on any error or OOM.
 */
@Suppress("LongParameterList", "TooManyFunctions") // One explicit seam per collaborator and one helper per step.
class RecordingPrecomputer(
    val noteId: String,
    private val audioFile: File,
    private val speechModel: SpeechModel,
    private val storage: JobStorage,
    private val voices: JobVoices,
    private val models: ModelAccess,
    private val engines: JobEngines,
    private val env: JobEnvironment,
    private val openDecoder: (File, Boolean, Long) -> CommittedPrefixDecoder = { file, included, start ->
        CommittedOggOpusDecoder(file, included, start)
    },
    private val shouldContinue: () -> Boolean = { true },
    private val waitForMoreBytes: suspend () -> Boolean = { false },
    private val onCheckpointed: (Long) -> Unit = {},
) {
    /**
     * Runs until [shouldContinue] becomes false, [waitForMoreBytes] returns false, EOS is seen,
     * or a resource/error boundary yields cleanly. Never throws non-fatal errors to the recorder.
     */
    suspend fun run(): PrecomputeStopReason {
        val preflightFailure = preflight()
        if (preflightFailure != null) return preflightFailure
        return try {
            NativeBudget.withBudget { runUnderBudget() }
        } catch (_: OutOfMemoryError) {
            PrecomputeStopReason.OUT_OF_MEMORY
        } catch (_: CancellationException) {
            PrecomputeStopReason.CANCELLED
        } catch (_: ProcessingPaused) {
            PrecomputeStopReason.YIELDED
        } catch (error: UserFacingException) {
            classifyUserFailure(error)
        } catch (
            @Suppress("TooGenericExceptionCaught") // Precompute is strictly best-effort and must never disturb capture.
            error: Exception,
        ) {
            env.warn("Recording precomputation stopped cleanly", error)
            PrecomputeStopReason.DECODE_ERROR
        }
    }

    private suspend fun preflight(): PrecomputeStopReason? {
        val modelReady = if (shouldContinue() && !env.lowMemory(null)) {
            withContext(env.io) { speechModel == SpeechModel.INCLUDED || models.ready(ModelPart.SPEECH) }
        } else {
            false
        }
        return when {
            !shouldContinue() -> PrecomputeStopReason.YIELDED
            env.lowMemory(null) -> PrecomputeStopReason.LOW_MEMORY
            !modelReady -> PrecomputeStopReason.MODEL_NOT_READY
            else -> null
        }
    }

    private fun classifyUserFailure(error: UserFacingException): PrecomputeStopReason =
        if (error.failure == Failure.CANCELLED) {
            PrecomputeStopReason.CANCELLED
        } else {
            env.warn("Recording precomputation stopped cleanly", error)
            PrecomputeStopReason.DECODE_ERROR
        }

    private suspend fun runUnderBudget(): PrecomputeStopReason {
        if (!shouldContinue()) return PrecomputeStopReason.YIELDED
        val resumed = loadResumableCheckpoint()
        val generation = withContext(env.io) { voices.generationBlocking() }
        var session: Session? = null
        return try {
            session = onBackgroundInference { openSession(resumed, generation) }
            driveLoop(session)
        } finally {
            session?.let { closeSession(it) }
        }
    }

    private suspend fun driveLoop(session: Session): PrecomputeStopReason {
        var stopReason: PrecomputeStopReason? = null
        while (stopReason == null && currentCoroutineContext().isActive && shouldContinue()) {
            stopReason = when (onBackgroundInference { step(session) }) {
                StepStatus.PROGRESSED -> null
                StepStatus.IDLE -> if (shouldContinue() && waitForMoreBytes()) null else PrecomputeStopReason.YIELDED
                StepStatus.EOS -> PrecomputeStopReason.EOS_REACHED
                StepStatus.CORRUPT -> PrecomputeStopReason.DECODE_ERROR
                StepStatus.YIELDED -> PrecomputeStopReason.YIELDED
            }
        }
        return stopReason ?: PrecomputeStopReason.YIELDED
    }

    private suspend fun closeSession(session: Session) {
        try {
            runInterruptible(env.inference) { session.close() }
        } catch (_: CancellationException) {
            session.close()
        } catch (
            @Suppress("TooGenericExceptionCaught") // Best-effort teardown; never leak cleanup errors to recording.
            _: RuntimeException,
        ) {
            // Best-effort teardown.
        }
    }

    private suspend fun loadResumableCheckpoint(): CheckpointState? = when (val load = storage.loadCheckpoint(noteId)) {
        is CheckpointLoad.Valid -> load.state.takeIf { it.model == speechModel.identity }
        CheckpointLoad.Missing, is CheckpointLoad.Mismatch -> null
    }

    private fun openSession(resumed: CheckpointState?, generation: Long): Session {
        val files = storage.workFiles(noteId)
        if (resumed == null) {
            files.encodedAudio.delete()
            files.clips.delete()
        }
        val clips = PcmFile(files.clips, deleteOnClose = false)
        var decoder: CommittedPrefixDecoder? = null
        var diarizer: StreamingDiarizer? = null
        var session: Session? = null
        try {
            resumed?.let { clips.truncate(it.clipSamples) }
            if (resumed == null) {
                storage.mutateNames(noteId) { CheckpointNames(emptyMap(), emptyMap(), generation = generation) }
            }
            decoder = openDecoder(audioFile, speechModel == SpeechModel.INCLUDED, resumed?.positionSamples ?: 0L)
            diarizer = resumed?.diarizer?.let(engines.diarizers::open)
            val state = buildSession(resumed, files, clips, decoder, diarizer)
            state.recognizer = engines.recognizers.open(resumed?.recognition, callbacks(state))
            session = state
            return state
        } finally {
            if (session == null) closePartial(diarizer, decoder, clips)
        }
    }

    private fun buildSession(
        resumed: CheckpointState?,
        files: WorkFiles,
        clips: PcmFile,
        decoder: CommittedPrefixDecoder,
        diarizer: StreamingDiarizer?,
    ): Session = Session(
        decoder = decoder,
        clips = clips,
        files = files,
        clipCache = VoiceClipCache(clips, resumed?.voiceClips?.let(CheckpointState::json)),
        speakerPrefix = resumed?.speakerPrefix ?: env.speakerPrefix(),
        speakerFailure = resumed?.speakerFailure ?: !models.ready(ModelPart.SPEAKERS),
        printsReady = models.ready(ModelPart.VOICES),
        diarizer = diarizer,
        previousSaved = resumed?.doneMs ?: -1L,
        began = env.nanoTime() - (resumed?.elapsedMs ?: 0L) * NANOS_PER_MS,
    )

    private fun callbacks(state: Session) = Recognizer.Callbacks(
        checkReplayActive = { checkPrecomputeActive() },
        onChunk = { chunk, startMs, words -> onWindow(state, chunk, startMs, words) },
        onStage = { _, _, _ -> checkPrecomputeActive() },
        onProgress = { progress ->
            checkPrecomputeActive()
            requireUser(WordBodyBudget.bytes(progress.words) <= env.maxBodyBytes, Failure.LONG)
        },
    )

    private fun closePartial(diarizer: StreamingDiarizer?, decoder: CommittedPrefixDecoder?, clips: PcmFile) {
        try {
            diarizer?.close()
        } finally {
            try {
                decoder?.close()
            } finally {
                clips.close()
            }
        }
    }

    private enum class StepStatus { PROGRESSED, IDLE, EOS, CORRUPT, YIELDED }

    private fun step(session: Session): StepStatus {
        checkPrecomputeActive()
        if (session.decoder.corrupt) return StepStatus.CORRUPT
        val recognizer = requireNotNull(session.recognizer)
        val packets = session.decoder.poll { samples, count ->
            checkPrecomputeActive()
            recognizer.accept(samples, count)
            if (recognizer.doneMs > maxOf(session.previousSaved, 0L) && session.decoder.committedBytes > 0L) {
                saveCheckpoint(session, recognizer)
            }
        }
        return when {
            !shouldContinue() -> StepStatus.YIELDED
            session.decoder.corrupt -> StepStatus.CORRUPT
            session.decoder.sawEos -> StepStatus.EOS
            packets > 0 -> StepStatus.PROGRESSED
            else -> StepStatus.IDLE
        }
    }

    private fun onWindow(session: Session, chunk: PcmAudio, startMs: Long, words: Transcript): Transcript {
        checkPrecomputeActive()
        if (session.speakerFailure) return oneVoice(session, words)
        return try {
            val diarizer = session.diarizer ?: engines.diarizers.open(null).also { session.diarizer = it }
            val found = diarizer.process(chunk, startMs)
            val assigned = if (found.isEmpty()) {
                oneVoice(session, words).words
            } else {
                Diarization.assign(words.words, found, session.speakerPrefix, stableIds = true)
            }
            if (session.printsReady) session.clipCache.accept(chunk, found, assigned, startMs)
            words.copy(words = assigned)
        } catch (error: CancellationException) {
            throw error
        } catch (error: ProcessingPaused) {
            throw error
        } catch (
            @Suppress("TooGenericExceptionCaught") // Same best-effort speaker degradation as TranscriptionJob.
            error: Exception,
        ) {
            degradeOrRethrow(session, words, error)
        }
    }

    private fun degradeOrRethrow(session: Session, words: Transcript, error: Exception): Transcript {
        if (error is UserFacingException && error.failure == Failure.CANCELLED) throw error
        session.speakerFailure = true
        env.warn("Precompute speakers unavailable", error)
        return oneVoice(session, words)
    }

    private fun saveCheckpoint(session: Session, recognizer: Recognizer) {
        env.syncFile(session.files.clips) { session.clips.sync() }
        val recognition = recognizer.checkpoint()
        val committedBytes = session.decoder.committedBytes
        if (committedBytes <= 0L) return
        val prefix = CommittedPrefix(
            kind = CommittedPrefix.KIND_OGG_OPUS_BYTES,
            committedUnitCount = committedBytes,
            prefixSha256 = session.decoder.committedSha256(),
            decodedPcmSha256 = recognition.whistle?.pcmSha256,
        )
        val state = CheckpointState(
            source = audioFile.name,
            signature = CheckpointState.signature(audioFile),
            model = speechModel.identity,
            speakers = ModelCatalog.diarizationModel.sha256,
            recognition = recognition,
            encoder = null,
            diarizer = session.diarizer?.checkpoint(),
            speakerPrefix = session.speakerPrefix,
            speakerFailure = session.speakerFailure,
            clipSamples = session.clips.sampleCount,
            voiceClips = CheckpointState.voiceClips(session.clipCache.checkpoint()),
            positionSamples = recognizer.positionSamples,
            doneMs = recognizer.doneMs,
            durationMs = 0L,
            elapsedMs = (env.nanoTime() - session.began) / NANOS_PER_MS,
            origin = CheckpointOrigin.PRECOMPUTE,
            committedPrefix = prefix,
        )
        try {
            storage.saveCheckpoint(noteId, state)
            session.previousSaved = recognizer.doneMs
            onCheckpointed(recognizer.doneMs)
        } catch (error: IOException) {
            env.warn("Could not save precompute checkpoint", error)
            throw ProcessingPaused().apply { initCause(error) }
        }
    }

    private fun checkPrecomputeActive() {
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        if (!shouldContinue()) throw ProcessingPaused()
    }

    private fun oneVoice(session: Session, words: Transcript): Transcript =
        words.copy(words = words.words.map { it.copy(speaker = "${session.speakerPrefix}1") })

    private suspend fun <T> onBackgroundInference(block: () -> T): T = runInterruptible(env.inference) {
        var result: Any? = null
        env.runAtBackgroundPriority {
            result = block()
        }
        @Suppress("UNCHECKED_CAST")
        result as T
    }

    private class Session(
        val decoder: CommittedPrefixDecoder,
        val clips: PcmFile,
        val files: WorkFiles,
        val clipCache: VoiceClipCache,
        val speakerPrefix: String,
        var speakerFailure: Boolean,
        val printsReady: Boolean,
        var diarizer: StreamingDiarizer?,
        var previousSaved: Long,
        val began: Long,
    ) : AutoCloseable {
        var recognizer: Recognizer? = null

        override fun close() {
            try {
                recognizer?.close()
            } finally {
                try {
                    diarizer?.close()
                } finally {
                    try {
                        decoder.close()
                    } finally {
                        clips.close()
                    }
                }
            }
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
