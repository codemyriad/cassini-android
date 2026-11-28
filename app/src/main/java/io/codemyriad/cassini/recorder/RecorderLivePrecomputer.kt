package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.ProcessingPaused
import io.codemyriad.cassini.core.UserFacingException
import io.codemyriad.cassini.core.audio.DecoderResampler
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.audio.PcmAudio
import io.codemyriad.cassini.core.audio.PcmFile
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.WordBodyBudget
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.core.requireUser
import io.codemyriad.cassini.core.speakers.Diarization
import io.codemyriad.cassini.core.speakers.VoiceClipCache
import io.codemyriad.cassini.engine.job.JobEngines
import io.codemyriad.cassini.engine.job.JobEnvironment
import io.codemyriad.cassini.engine.job.JobStorage
import io.codemyriad.cassini.engine.job.JobVoices
import io.codemyriad.cassini.engine.job.ModelAccess
import io.codemyriad.cassini.engine.job.NativeBudget
import io.codemyriad.cassini.engine.job.OpusSink
import io.codemyriad.cassini.engine.job.RecognitionCheckpointMismatch
import io.codemyriad.cassini.engine.job.Recognizer
import io.codemyriad.cassini.engine.job.StreamingDiarizer
import io.codemyriad.cassini.engine.whistle.WhistleReplay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Incrementally decodes and resamples a growing 16-bit PCM WAV file into 16 kHz mono blocks.
 *
 * Reads only the bytes already flushed to [file]. Header `data` chunk length is ignored so a WAV
 * whose size grows as chunks arrive over Bluetooth can be consumed ahead of its final header update.
 */
internal class StreamingWavPrefixReader(
    private val file: File,
    private val included: Boolean,
    private val startSample: Long = 0L,
) : AutoCloseable {
    class WavHeader(val sampleRate: Int, val channels: Int, val dataOffset: Long)

    var corrupt: Boolean = false
        private set
    var sampleRate: Int = 0
        private set
    var channels: Int = 0
        private set
    var dataOffset: Long = 0L
        private set
    val frameBytes: Int
        get() = BYTES_PER_SAMPLE * channels

    private var headerReady = false
    private var nextByteOffset = 0L
    private var resampler: DecoderResampler? = null
    private var currentSink: ((FloatArray, Int) -> Unit)? = null
    private val block = ByteArray(READ_BLOCK_BYTES)
    private val mono = FloatArray(READ_BLOCK_BYTES / BYTES_PER_SAMPLE)

    fun poll(sink: (FloatArray, Int) -> Unit): Int =
        if (!corrupt && ensureHeader()) readNextBlock(sink) else 0

    @Suppress("ReturnCount") // Bounds and I/O guards return early with zero frames read.
    private fun readNextBlock(sink: (FloatArray, Int) -> Unit): Int {
        val available = ((file.length() - nextByteOffset).coerceAtLeast(0L) / frameBytes) * frameBytes
        if (available <= 0L) return 0
        val want = minOf(block.size.toLong(), available).toInt()
        val read = try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < nextByteOffset + want) return 0
                raf.seek(nextByteOffset)
                raf.readFully(block, 0, want)
                want
            }
        } catch (_: IOException) {
            return 0
        }
        nextByteOffset += read
        val count = read / frameBytes
        val buf = ByteBuffer.wrap(block, 0, read).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            var sum = 0f
            repeat(channels) { sum += buf.short / PCM_SCALE }
            mono[i] = sum / channels
        }
        currentSink = sink
        try {
            requireNotNull(resampler).push(mono, 0, count)
        } finally {
            currentSink = null
        }
        return count
    }

    @Suppress("ReturnCount") // Header readiness guards keep chunk parsing flat.
    private fun ensureHeader(): Boolean {
        if (headerReady) return true
        val len = file.length()
        if (len < WAV_HEADER_BYTES) return false
        return try {
            RandomAccessFile(file, "r").use { raf -> parseHeader(raf, len) }
        } catch (_: IOException) {
            false
        }
    }

    @Suppress("ReturnCount") // Validates RIFF/fmt/data framing and waits on incomplete prefix.
    private fun parseHeader(raf: RandomAccessFile, len: Long): Boolean {
        val riff = ByteArray(RIFF_HEADER_BYTES)
        raf.readFully(riff)
        val isWave = String(riff, 0, CHUNK_ID_BYTES, Charsets.US_ASCII) == "RIFF" &&
            String(riff, WAVE_ID_OFFSET, CHUNK_ID_BYTES, Charsets.US_ASCII) == "WAVE"
        if (!isWave) {
            corrupt = true
            return false
        }
        var foundRate = 0
        var foundChannels = 0
        while (raf.filePointer + CHUNK_HEADER_BYTES <= len) {
            val id = ByteArray(CHUNK_ID_BYTES).also(raf::readFully).toString(Charsets.US_ASCII)
            val size = Integer.reverseBytes(raf.readInt()).toLong() and UINT32_MASK
            val payloadOffset = raf.filePointer
            if (id == "fmt ") {
                if (size < FMT_MIN_BYTES || payloadOffset + FMT_MIN_BYTES > len) return false
                val format = java.lang.Short.reverseBytes(raf.readShort()).toInt() and UINT16_MASK
                foundChannels = java.lang.Short.reverseBytes(raf.readShort()).toInt() and UINT16_MASK
                foundRate = Integer.reverseBytes(raf.readInt())
                raf.skipBytes(FMT_BYTE_RATE_SKIP_BYTES)
                val bits = java.lang.Short.reverseBytes(raf.readShort()).toInt() and UINT16_MASK
                val validPcm = format == PCM_FORMAT && bits == BITS_PER_SAMPLE
                val validShape = foundChannels in 1..MAX_CHANNELS && foundRate in SAMPLE_RATE_MIN..SAMPLE_RATE_MAX
                if (!validPcm || !validShape) {
                    corrupt = true
                    return false
                }
            } else if (id == "data") {
                return initDataReader(foundRate, foundChannels, payloadOffset, len)
            }
            val nextChunk = payloadOffset + size + (size and 1L)
            if (nextChunk > len) return false
            raf.seek(nextChunk)
        }
        return false
    }

    private fun initDataReader(foundRate: Int, foundChannels: Int, payloadOffset: Long, len: Long): Boolean {
        var ready = false
        if (foundRate == 0) {
            corrupt = true
        } else {
            val seekFrame = (startSample * foundRate / Limits.ASR_RATE - foundRate).coerceAtLeast(0L)
            val initialFrame = if (included) 0L else seekFrame
            val startByte = payloadOffset + initialFrame * BYTES_PER_SAMPLE * foundChannels
            if (startByte <= len) {
                sampleRate = foundRate
                channels = foundChannels
                dataOffset = payloadOffset
                nextByteOffset = startByte
                resampler = DecoderResampler(
                    sampleRate,
                    initialFrame = initialFrame,
                    firstOutput = startSample,
                    included = included,
                ) { s, n -> currentSink?.invoke(s, n) }
                headerReady = true
                ready = true
            }
        }
        return ready
    }

    /** Flushes any buffered tail samples when the caller knows EOF has been reached (used in parity tests). */
    fun finish(sink: (FloatArray, Int) -> Unit) {
        if (!corrupt && headerReady) {
            currentSink = sink
            try {
                resampler?.finish()
            } finally {
                currentSink = null
            }
        }
    }

    override fun close() {
        currentSink = null
    }

    companion object {
        private const val WAV_HEADER_BYTES = 44L
        private const val RIFF_HEADER_BYTES = 12
        private const val CHUNK_HEADER_BYTES = 8L
        private const val CHUNK_ID_BYTES = 4
        private const val WAVE_ID_OFFSET = 8
        private const val FMT_MIN_BYTES = 16L
        private const val FMT_BYTE_RATE_SKIP_BYTES = 6
        private const val PCM_FORMAT = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = 2
        private const val MAX_CHANNELS = 2
        private const val SAMPLE_RATE_MIN = 8000
        private const val SAMPLE_RATE_MAX = 96000
        private const val READ_BLOCK_BYTES = 64 * 1024
        private const val PCM_SCALE = 32768f
        private const val UINT32_MASK = 0xffffffffL
        private const val UINT16_MASK = 0xffff

        fun inspectHeader(file: File): WavHeader? = StreamingWavPrefixReader(file, included = false).use { reader ->
            if (!reader.ensureHeader() || reader.corrupt) {
                null
            } else {
                WavHeader(reader.sampleRate, reader.channels, reader.dataOffset)
            }
        }

        fun whistleReplay(file: File): WhistleReplay = WhistleReplay { samples, sink ->
            if (samples > 0L) {
                var remaining = samples
                StreamingWavPrefixReader(file, included = true).use { reader ->
                    while (remaining > 0L && !reader.corrupt) {
                        val framesRead = reader.poll { block, count ->
                            val take = minOf(remaining, count.toLong()).toInt()
                            if (take > 0) {
                                sink(block, take)
                                remaining -= take
                            }
                        }
                        if (framesRead == 0) break
                    }
                }
                if (remaining != 0L) {
                    throw RecognitionCheckpointMismatch(
                        "Source ended before saved Whistle progress ($remaining samples missing)",
                    )
                }
            }
        }
    }
}

/**
 * Best-effort head-start precomputation worker attached while [RecorderLiveTailer] streams an active
 * recorder `.wav` into a `.part` file. Persists durable checkpoints via [RecorderLivePrecomputeStore] so
 * progress survives Bluetooth disconnects, preemptions, and post-Stop reconnects.
 */
@Suppress("TooManyFunctions") // Stage/preflight/inference/install steps each stay single-purpose.
class RecorderLivePrecomputer(
    private val parts: Parts,
) : LivePrecomputeSession {
    /** Collaborators and gates for a [RecorderLivePrecomputer] run. */
    @Suppress("LongParameterList") // One seam per collaborator, matching RecordingPrecomputer.
    class Parts(
        val scope: CoroutineScope,
        val partFile: File,
        val stagingDir: File,
        val speechModel: SpeechModel,
        val models: ModelAccess,
        val engines: JobEngines,
        val env: JobEnvironment,
        val storage: JobStorage,
        val voices: JobVoices,
        val speakerPrefix: () -> String = env.speakerPrefix,
        val shouldContinue: () -> Boolean = { true },
        val canInstallBlocking: (String) -> Boolean = { true },
        val runWithPriority: (() -> Unit) -> Unit = { it() },
        val onCheckpointed: (Long) -> Unit = {},
        val deleteStaging: (File) -> Unit = { it.deleteRecursively() },
    )

    internal val stagingDir: File get() = parts.stagingDir

    private val allowed = AtomicBoolean(true)
    private val installing = AtomicBoolean(false)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    // A job counts as completed before its completion handlers run, so joining [worker] alone does not wait for a
    // discard's deletion. [stopped] completes only after the worker has finished and any discard requested by then
    // has deleted the staging directory; [lifecycle] serializes that deletion with a discard that arrives later.
    private val lifecycle = Any()
    private var workerDone = false // Guarded by lifecycle.
    private var discardRequested = false // Guarded by lifecycle.
    private val stopped = CompletableDeferred<Unit>()
    private val worker: Job = parts.scope.launch { runWorker() }.also { job ->
        job.invokeOnCompletion { onWorkerFinished() }
    }

    override val isActive: Boolean
        get() = allowed.get() && worker.isActive

    override fun onBytesAvailable(bytes: Long) {
        if (bytes > 0L && allowed.get()) {
            wakeups.trySend(Unit)
        }
    }

    /** Stops precomputation immediately without deleting staged progress until [finishAndInstall]. */
    fun preempt() {
        allowed.set(false)
        wakeups.trySend(Unit)
    }

    override suspend fun awaitStopped() {
        allowed.set(false)
        wakeups.trySend(Unit)
        joinStopped()
    }

    override suspend fun finishAndInstall(noteId: String) {
        installing.set(true)
        allowed.set(false)
        wakeups.trySend(Unit)
        joinStopped()
        RecorderLivePrecomputeStore.installIntoNote(
            noteId = noteId,
            partFile = parts.partFile,
            stagingDir = parts.stagingDir,
            speechModel = parts.speechModel,
            models = parts.models,
            storage = parts.storage,
            voices = parts.voices,
            env = parts.env,
            canInstallBlocking = parts.canInstallBlocking,
        )
    }

    override fun close() {
        if (installing.get()) return
        allowed.set(false)
        wakeups.trySend(Unit)
        worker.cancel()
    }

    /**
     * Stops the worker and removes its staging directory once the worker has stopped writing to it. Deleting the
     * directory right away could race a checkpoint that the cancelled worker is still saving. [awaitStopped],
     * [finishAndInstall] and [invokeOnStopped] wait for that deletion to finish, so a replacement worker over the same
     * staging directory never opens files this discard is still removing.
     */
    fun discard() {
        if (installing.get()) return
        close()
        synchronized(lifecycle) {
            if (discardRequested) return
            discardRequested = true
            // Once the worker has finished, delete here, under the lock that awaitStopped also takes.
            if (workerDone) parts.deleteStaging(parts.stagingDir)
        }
    }

    /** Runs [handler] once the worker has stopped and any discard deletion has finished. */
    fun invokeOnStopped(handler: () -> Unit) {
        stopped.invokeOnCompletion { handler() }
    }

    private fun onWorkerFinished() {
        try {
            synchronized(lifecycle) {
                workerDone = true
                if (discardRequested) parts.deleteStaging(parts.stagingDir)
            }
        } finally {
            stopped.complete(Unit)
        }
    }

    private suspend fun joinStopped() {
        worker.cancelAndJoin()
        stopped.await()
        // A discard that arrived after the worker finished deletes under the lock: wait for it to release.
        withContext(NonCancellable + parts.env.io) { synchronized(lifecycle) {} }
    }

    @Suppress("TooGenericExceptionCaught") // Strictly best-effort: never throw into Bluetooth sync or UI.
    private suspend fun runWorker() {
        try {
            if (!preflightOk()) return
            NativeBudget.withBudget { runUnderBudget() }
        } catch (_: OutOfMemoryError) {
            parts.env.warn("Live recorder precompute stopped on OOM", OutOfMemoryError())
        } catch (_: CancellationException) {
            // Expected on stop/preempt/close.
        } catch (_: ProcessingPaused) {
            // Yielded cleanly.
        } catch (error: Exception) {
            parts.env.warn("Live recorder precompute stopped cleanly", error)
        }
    }

    private suspend fun preflightOk(): Boolean {
        if (!parts.shouldContinue() || !allowed.get() || parts.env.lowMemory(null)) return false
        val ready = withContext(parts.env.io) {
            parts.speechModel == SpeechModel.INCLUDED || parts.models.ready(ModelPart.SPEECH)
        }
        return ready && parts.shouldContinue() && allowed.get() && !parts.env.lowMemory(null)
    }

    private suspend fun runUnderBudget() {
        if (!checkActiveBool()) return
        val owned = AtomicReference<Session?>(null)
        try {
            val session = onInference { openSession(owned) }
            driveLoop(session)
        } finally {
            owned.getAndSet(null)?.let { session ->
                withContext(NonCancellable + parts.env.inference) { runCatching { session.close() } }
            }
        }
    }

    private suspend fun driveLoop(session: Session) {
        while (currentCoroutineContext().isActive && checkActiveBool() && !parts.env.lowMemory(null)) {
            val status = onPriorityInference { step(session) }
            when (status) {
                StepStatus.PROGRESSED -> Unit
                StepStatus.IDLE -> {
                    wakeups.receive()
                    if (!checkActiveBool()) return
                }
                StepStatus.STOP -> return
            }
        }
    }

    private fun openSession(owned: AtomicReference<Session?>): Session {
        val resumed = RecorderLivePrecomputeStore.loadStaged(
            partFile = parts.partFile,
            stagingDir = parts.stagingDir,
            speechModel = parts.speechModel,
            models = parts.models,
        )
        if (!parts.stagingDir.mkdirs() && !parts.stagingDir.isDirectory) {
            throw IOException("Cannot create live precompute staging directory ${parts.stagingDir}")
        }
        return if (resumed != null) {
            try {
                openSessionWithState(owned, resumed)
            } catch (_: RecognitionCheckpointMismatch) {
                RecorderLivePrecomputeStore.clearStagingFiles(parts.stagingDir)
                openSessionWithState(owned, null)
            }
        } else {
            RecorderLivePrecomputeStore.clearStagingFiles(parts.stagingDir)
            openSessionWithState(owned, null)
        }
    }

    private fun openSessionWithState(owned: AtomicReference<Session?>, resumed: StagedSnapshot?): Session {
        val audioFile = File(parts.stagingDir, RecorderLivePrecomputeStore.WORK_AUDIO_PART)
        val clipsFile = File(parts.stagingDir, RecorderLivePrecomputeStore.WORK_CLIPS)
        val clips = PcmFile(clipsFile, deleteOnClose = false)
        var reader: StreamingWavPrefixReader? = null
        var encoder: OpusSink? = null
        var diarizer: StreamingDiarizer? = null
        var session: Session? = null
        try {
            resumed?.let { clips.truncate(it.clipSamples) }
            reader = StreamingWavPrefixReader(
                parts.partFile,
                parts.speechModel == SpeechModel.INCLUDED,
                startSample = resumed?.positionSamples ?: 0L,
            )
            encoder = parts.engines.encoders.open(audioFile, resumed?.encoder)
            diarizer = resumed?.diarizer?.let(parts.engines.diarizers::open)
            val state = Session(
                reader = reader,
                encoder = encoder,
                clipsFile = clipsFile,
                clips = clips,
                clipCache = VoiceClipCache(clips, resumed?.voiceClips?.let(CheckpointState::json)),
                wordLog = StagedWordLog(parts.stagingDir),
                speakerPrefix = resumed?.speakerPrefix ?: parts.speakerPrefix(),
                speakerFailure = resumed?.speakerFailure ?: !parts.models.ready(ModelPart.SPEAKERS),
                printsReady = parts.models.ready(ModelPart.VOICES),
                began = parts.env.nanoTime() - (resumed?.elapsedMs ?: 0L) * NANOS_PER_MS,
            ).apply {
                this.diarizer = diarizer
                this.previousSaved = resumed?.doneMs ?: -1L
            }
            state.recognizer = parts.engines.recognizers.open(resumed?.recognition, callbacks(state))
            owned.set(state)
            session = state
            return state
        } finally {
            if (session == null) closePartial(encoder, reader, diarizer, clips)
        }
    }

    private fun callbacks(state: Session) = Recognizer.Callbacks(
        checkReplayActive = { checkActive() },
        onChunk = { chunk, startMs, words -> onWindow(state, chunk, startMs, words) },
        onStage = { _, _, _ -> checkActive() },
        onProgress = { progress ->
            checkActive()
            requireUser(WordBodyBudget.bytes(progress.words) <= parts.env.maxBodyBytes, Failure.LONG)
        },
    )

    private fun step(session: Session): StepStatus {
        checkActive()
        if (session.reader.corrupt) return StepStatus.STOP
        val recognizer = requireNotNull(session.recognizer)
        val count = session.reader.poll { samples, n ->
            checkActive()
            session.encoder.accept(samples, n)
            recognizer.accept(samples, n)
            if (recognizer.doneMs > maxOf(session.previousSaved, 0L)) {
                captureSnapshot(session, recognizer)
            }
        }
        return when {
            !checkActiveBool() || session.reader.corrupt -> StepStatus.STOP
            count > 0 -> StepStatus.PROGRESSED
            else -> StepStatus.IDLE
        }
    }

    private fun onWindow(session: Session, chunk: PcmAudio, startMs: Long, words: Transcript): Transcript {
        checkActive()
        if (session.speakerFailure) return oneVoice(session, words)
        return try {
            val diarizer = session.diarizer ?: parts.engines.diarizers.open(null).also { session.diarizer = it }
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
            @Suppress("TooGenericExceptionCaught") // Best-effort speaker degradation matches RecordingPrecomputer.
            error: Exception,
        ) {
            degradeOrRethrow(session, words, error)
        }
    }

    private fun degradeOrRethrow(session: Session, words: Transcript, error: Exception): Transcript {
        if (error is UserFacingException && error.failure == Failure.CANCELLED) throw error
        session.speakerFailure = true
        parts.env.warn("Live recorder precompute speakers unavailable", error)
        return oneVoice(session, words)
    }

    private fun captureSnapshot(session: Session, recognizer: Recognizer) {
        parts.env.syncFile(session.clipsFile) { session.clips.sync() }
        val encoderState = session.encoder.checkpoint()
        val recognitionState = recognizer.checkpoint()
        val snapshot = StagedSnapshot(
            recognition = recognitionState,
            encoder = encoderState,
            diarizer = session.diarizer?.checkpoint(),
            speakerPrefix = session.speakerPrefix,
            speakerFailure = session.speakerFailure,
            clipSamples = session.clips.sampleCount,
            voiceClips = if (session.printsReady) CheckpointState.voiceClips(session.clipCache.checkpoint()) else null,
            positionSamples = recognizer.positionSamples,
            doneMs = recognizer.doneMs,
            elapsedMs = (parts.env.nanoTime() - session.began) / NANOS_PER_MS,
        )
        RecorderLivePrecomputeStore.saveStaged(
            partFile = parts.partFile,
            stagingDir = parts.stagingDir,
            wordLog = session.wordLog,
            snapshot = snapshot,
            speechModel = parts.speechModel,
            sampleRate = session.reader.sampleRate,
            channels = session.reader.channels,
            dataOffset = session.reader.dataOffset,
        )
        session.previousSaved = recognizer.doneMs
        parts.onCheckpointed(recognizer.doneMs)
    }

    private fun checkActive() {
        if (!checkActiveBool()) throw ProcessingPaused()
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
    }

    private fun checkActiveBool(): Boolean = allowed.get() && parts.shouldContinue()

    private fun oneVoice(session: Session, words: Transcript): Transcript =
        words.copy(words = words.words.map { it.copy(speaker = "${session.speakerPrefix}1") })

    private suspend fun <T> onInference(block: () -> T): T = runInterruptible(parts.env.inference) {
        block()
    }

    private suspend fun <T> onPriorityInference(block: () -> T): T = runInterruptible(parts.env.inference) {
        var result: Any? = null
        parts.runWithPriority { result = block() }
        @Suppress("UNCHECKED_CAST")
        result as T
    }

    private fun closePartial(
        encoder: OpusSink?,
        reader: StreamingWavPrefixReader?,
        diarizer: StreamingDiarizer?,
        clips: PcmFile,
    ) {
        runCatching { encoder?.close() }
        runCatching { reader?.close() }
        runCatching { diarizer?.close() }
        clips.close()
    }

    private enum class StepStatus { PROGRESSED, IDLE, STOP }

    @Suppress("LongParameterList") // Holds open native handles & session metadata for teardown.
    private class Session(
        val reader: StreamingWavPrefixReader,
        val encoder: OpusSink,
        val clipsFile: File,
        val clips: PcmFile,
        val clipCache: VoiceClipCache,
        val wordLog: StagedWordLog,
        val speakerPrefix: String,
        var speakerFailure: Boolean,
        val printsReady: Boolean,
        val began: Long,
    ) : AutoCloseable {
        var recognizer: Recognizer? = null
        var diarizer: StreamingDiarizer? = null
        var previousSaved: Long = -1L

        override fun close() {
            runCatching { recognizer?.close() }
            runCatching { diarizer?.close() }
            runCatching { encoder.close() }
            runCatching { reader.close() }
            clips.close()
        }
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
