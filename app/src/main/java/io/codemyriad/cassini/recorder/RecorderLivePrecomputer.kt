package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.ProcessingPaused
import io.codemyriad.cassini.core.UserFacingException
import io.codemyriad.cassini.core.audio.DecoderResampler
import io.codemyriad.cassini.core.audio.PcmAudio
import io.codemyriad.cassini.core.audio.PcmFile
import io.codemyriad.cassini.core.checkpoint.CheckpointNames
import io.codemyriad.cassini.core.checkpoint.CheckpointOrigin
import io.codemyriad.cassini.core.checkpoint.CheckpointState
import io.codemyriad.cassini.core.checkpoint.DiarizerState
import io.codemyriad.cassini.core.checkpoint.EncoderState
import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.WordBodyBudget
import io.codemyriad.cassini.core.models.ModelCatalog
import io.codemyriad.cassini.core.models.ModelPart
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.core.requireUser
import io.codemyriad.cassini.core.speakers.Diarization
import io.codemyriad.cassini.core.speakers.PrintWindow
import io.codemyriad.cassini.core.speakers.VoiceClipCache
import io.codemyriad.cassini.engine.job.JobEngines
import io.codemyriad.cassini.engine.job.JobEnvironment
import io.codemyriad.cassini.engine.job.JobStorage
import io.codemyriad.cassini.engine.job.JobVoices
import io.codemyriad.cassini.engine.job.ModelAccess
import io.codemyriad.cassini.engine.job.NativeBudget
import io.codemyriad.cassini.engine.job.OpusSink
import io.codemyriad.cassini.engine.job.Recognizer
import io.codemyriad.cassini.engine.job.StreamingDiarizer
import kotlinx.coroutines.CancellationException
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
) : AutoCloseable {
    var corrupt: Boolean = false
        private set
    private var headerReady = false
    private var sampleRate = 0
    private var channels = 0
    private var dataOffset = 0L
    private var nextByteOffset = 0L
    private var resampler: DecoderResampler? = null
    private var currentSink: ((FloatArray, Int) -> Unit)? = null
    private val block = ByteArray(READ_BLOCK_BYTES)
    private val mono = FloatArray(READ_BLOCK_BYTES / BYTES_PER_SAMPLE)

    fun poll(sink: (FloatArray, Int) -> Unit): Int =
        if (!corrupt && ensureHeader()) readNextBlock(sink) else 0

    @Suppress("ReturnCount") // Bounds and I/O guards return early with zero frames read.
    private fun readNextBlock(sink: (FloatArray, Int) -> Unit): Int {
        val frameBytes = BYTES_PER_SAMPLE * channels
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
                if (foundRate == 0) {
                    corrupt = true
                    return false
                }
                sampleRate = foundRate
                channels = foundChannels
                dataOffset = payloadOffset
                nextByteOffset = payloadOffset
                resampler = DecoderResampler(sampleRate, initialFrame = 0L, firstOutput = 0L, included = included) { s, n ->
                    currentSink?.invoke(s, n)
                }
                headerReady = true
                return true
            }
            val nextChunk = payloadOffset + size + (size and 1L)
            if (nextChunk > len) return false
            raf.seek(nextChunk)
        }
        return false
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

    private companion object {
        const val WAV_HEADER_BYTES = 44L
        const val RIFF_HEADER_BYTES = 12
        const val CHUNK_HEADER_BYTES = 8L
        const val CHUNK_ID_BYTES = 4
        const val WAVE_ID_OFFSET = 8
        const val FMT_MIN_BYTES = 16L
        const val FMT_BYTE_RATE_SKIP_BYTES = 6
        const val PCM_FORMAT = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = 2
        const val MAX_CHANNELS = 2
        const val SAMPLE_RATE_MIN = 8000
        const val SAMPLE_RATE_MAX = 96000
        const val READ_BLOCK_BYTES = 64 * 1024
        const val PCM_SCALE = 32768f
        const val UINT32_MASK = 0xffffffffL
        const val UINT16_MASK = 0xffff
    }
}

/**
 * Best-effort head-start precomputation worker attached while [RecorderLiveTailer] streams an active
 * recorder `.wav` into a `.part` file.
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
        val onCheckpointed: (Long) -> Unit = {},
    )

    private val allowed = AtomicBoolean(true)
    private val installing = AtomicBoolean(false)
    private val discarded = AtomicBoolean(false)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private val latestSnapshot = AtomicReference<StagedSnapshot?>(null)
    private val worker: Job = parts.scope.launch { runWorker() }

    override fun onBytesAvailable(bytes: Long) {
        if (bytes > 0L && allowed.get()) {
            wakeups.trySend(Unit)
        }
    }

    /** Stops precomputation immediately without deleting staged progress until [close] or [finishAndInstall]. */
    fun preempt() {
        allowed.set(false)
        wakeups.trySend(Unit)
    }

    override suspend fun awaitStopped() {
        allowed.set(false)
        wakeups.trySend(Unit)
        worker.cancelAndJoin()
    }

    override suspend fun finishAndInstall(noteId: String) {
        installing.set(true)
        allowed.set(false)
        wakeups.trySend(Unit)
        worker.cancelAndJoin()
        val snap = latestSnapshot.get()
        withContext(NonCancellable + parts.env.io) {
            try {
                if (snap != null && snap.doneMs > 0L) installSnapshot(noteId, snap)
            } catch (error: IOException) {
                parts.env.warn("Could not install live recorder precompute checkpoint", error)
            } finally {
                parts.stagingDir.deleteRecursively()
            }
        }
    }

    override fun close() {
        if (installing.get()) return
        discarded.set(true)
        allowed.set(false)
        wakeups.trySend(Unit)
        worker.cancel()
        if (worker.isCompleted) {
            parts.stagingDir.deleteRecursively()
        }
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
        } finally {
            if (discarded.get() && !installing.get()) {
                parts.stagingDir.deleteRecursively()
            }
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
            val session = onBackgroundInference { openSession(owned) }
            driveLoop(session)
        } finally {
            owned.getAndSet(null)?.let { session ->
                withContext(NonCancellable + parts.env.inference) { runCatching { session.close() } }
            }
        }
    }

    private suspend fun driveLoop(session: Session) {
        while (currentCoroutineContext().isActive && checkActiveBool() && !parts.env.lowMemory(null)) {
            val status = onBackgroundInference { step(session) }
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
        if (!parts.stagingDir.mkdirs() && !parts.stagingDir.isDirectory) {
            throw IOException("Cannot create live precompute staging directory ${parts.stagingDir}")
        }
        val audioFile = File(parts.stagingDir, WORK_AUDIO_PART)
        val clipsFile = File(parts.stagingDir, WORK_CLIPS)
        audioFile.delete()
        clipsFile.delete()
        val clips = PcmFile(clipsFile, deleteOnClose = false)
        var reader: StreamingWavPrefixReader? = null
        var encoder: OpusSink? = null
        var session: Session? = null
        try {
            reader = StreamingWavPrefixReader(parts.partFile, parts.speechModel == SpeechModel.INCLUDED)
            encoder = parts.engines.encoders.open(audioFile, null)
            val state = Session(
                reader = reader,
                encoder = encoder,
                clipsFile = clipsFile,
                clips = clips,
                clipCache = VoiceClipCache(clips, null),
                speakerPrefix = parts.speakerPrefix(),
                speakerFailure = !parts.models.ready(ModelPart.SPEAKERS),
                printsReady = parts.models.ready(ModelPart.VOICES),
                began = parts.env.nanoTime(),
            )
            state.recognizer = parts.engines.recognizers.open(null, callbacks(state))
            owned.set(state)
            session = state
            return state
        } finally {
            if (session == null) closePartial(encoder, reader, clips)
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
        latestSnapshot.set(snapshot)
        session.previousSaved = recognizer.doneMs
        parts.onCheckpointed(recognizer.doneMs)
    }

    private suspend fun installSnapshot(noteId: String, snap: StagedSnapshot) {
        val audioFile = parts.storage.audioFile(noteId)
        val stagedOpus = File(parts.stagingDir, WORK_AUDIO_PART)
        val stagedClips = File(parts.stagingDir, WORK_CLIPS)
        val minClipsBytes = snap.clipSamples.toLong() * BYTES_PER_FLOAT
        withContext(parts.env.io) {
            parts.storage.withNoteLockBlocking(noteId) {
                val valid = parts.canInstallBlocking(noteId) &&
                    audioFile.isFile &&
                    stagedOpus.isFile && stagedOpus.length() >= snap.encoder.length &&
                    stagedClips.isFile && stagedClips.length() >= minClipsBytes
                if (valid) {
                    val work = parts.storage.workFiles(noteId)
                    val generation = parts.voices.generationBlocking()
                    parts.storage.mutateNames(noteId) {
                        CheckpointNames(emptyMap(), emptyMap(), generation = generation)
                    }

                    copyTruncated(stagedOpus, work.encodedAudio, snap.encoder.length)
                    copyTruncated(stagedClips, work.clips, minClipsBytes)
                    val stagedWhistle = File(parts.stagingDir, WORK_WHISTLE_SEGMENTS)
                    if (stagedWhistle.isFile) {
                        parts.storage.whistleSegmentCache(noteId)?.let { dest ->
                            stagedWhistle.copyTo(dest, overwrite = true)
                        }
                    }
                    val state = CheckpointState(
                        source = audioFile.name,
                        signature = CheckpointState.signature(audioFile),
                        model = parts.speechModel.identity,
                        speakers = ModelCatalog.diarizationModel.sha256,
                        recognition = snap.recognition,
                        encoder = snap.encoder,
                        diarizer = snap.diarizer,
                        speakerPrefix = snap.speakerPrefix,
                        speakerFailure = snap.speakerFailure,
                        clipSamples = snap.clipSamples,
                        voiceClips = snap.voiceClips,
                        positionSamples = snap.positionSamples,
                        doneMs = snap.doneMs,
                        durationMs = 0L,
                        elapsedMs = snap.elapsedMs,
                        origin = CheckpointOrigin.PRECOMPUTE,
                        committedPrefix = null,
                    )
                    parts.storage.saveCheckpoint(noteId, state)
                }
            }
        }
    }

    private fun copyTruncated(src: File, dst: File, length: Long) {
        src.copyTo(dst, overwrite = true)
        RandomAccessFile(dst, "rw").use { raf ->
            raf.setLength(length)
            parts.env.syncFile(dst) { raf.fd.sync() }
        }
    }

    private fun checkActive() {
        if (!checkActiveBool()) throw ProcessingPaused()
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
    }

    private fun checkActiveBool(): Boolean = allowed.get() && parts.shouldContinue()

    private fun oneVoice(session: Session, words: Transcript): Transcript =
        words.copy(words = words.words.map { it.copy(speaker = "${session.speakerPrefix}1") })

    private suspend fun <T> onBackgroundInference(block: () -> T): T = runInterruptible(parts.env.inference) {
        var result: Any? = null
        parts.env.runAtBackgroundPriority { result = block() }
        @Suppress("UNCHECKED_CAST")
        result as T
    }

    private fun closePartial(encoder: OpusSink?, reader: StreamingWavPrefixReader?, clips: PcmFile) {
        runCatching { encoder?.close() }
        runCatching { reader?.close() }
        clips.close()
    }

    private enum class StepStatus { PROGRESSED, IDLE, STOP }

    @Suppress("LongParameterList") // Snapshot mirrors CheckpointState fields cleanly.
    private class StagedSnapshot(
        val recognition: RecognitionState,
        val encoder: EncoderState,
        val diarizer: DiarizerState?,
        val speakerPrefix: String,
        val speakerFailure: Boolean,
        val clipSamples: Int,
        val voiceClips: Map<String, List<PrintWindow>>?,
        val positionSamples: Long,
        val doneMs: Long,
        val elapsedMs: Long,
    )

    @Suppress("LongParameterList") // Holds open native handles & session metadata for teardown.
    private class Session(
        val reader: StreamingWavPrefixReader,
        val encoder: OpusSink,
        val clipsFile: File,
        val clips: PcmFile,
        val clipCache: VoiceClipCache,
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
        const val WORK_AUDIO_PART = "audio.opus.part"
        const val WORK_CLIPS = "clips.pcm"
        const val WORK_WHISTLE_SEGMENTS = "whistle-segments.json"
        const val BYTES_PER_FLOAT = 4L
        const val NANOS_PER_MS = 1_000_000L
    }
}
