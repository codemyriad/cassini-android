package org.cassini.engine.job

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.asr.IncrementalTranscript
import org.cassini.core.audio.Limits
import org.cassini.core.audio.PcmAudio
import org.cassini.core.audio.PcmSource
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.checkpoint.DiarizerState
import org.cassini.core.checkpoint.EncoderState
import org.cassini.core.checkpoint.RecognitionState
import org.cassini.core.format.CassiniDocument
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelPart
import org.cassini.core.models.ModelPlan
import org.cassini.core.speakers.PrintWindow
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.SpeakerTurn
import org.cassini.core.speakers.Voice
import org.cassini.engine.asr.Parakeet
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// Fakes for every seam and port of TranscriptionJob. The scripted audio is deterministic on the recording clock:
// a word every second (w<second>, from .100 to .800) and a speaker turn every 10 s (speaker = tens of seconds mod 3),
// so a run that stops and resumes sees exactly what an uninterrupted run sees.

/** The Ogg Opus clip committed under fixtures/audio, found from the module directory Gradle runs tests in. */
val FIXTURE_OPUS: File by lazy {
    generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
        .map { File(it, "fixtures/audio/fleurs-it-short.opus") }
        .first { it.isFile }
}

/** A unit vector along [axis] in [DIM] dimensions: the fake print of speaker number [axis]. */
fun axis(axis: Int, dim: Int = DIM) = FloatArray(dim).also { it[axis] = 1f }

/** A unit vector with cosine [score] to [axis] (the rest goes to the last axis). */
fun near(axis: Int, score: Float) = FloatArray(DIM).also {
    it[axis] = score
    it[DIM - 1] = kotlin.math.sqrt(1 - score * score)
}

const val DIM = 8

fun voice(id: String, name: String, mean: FloatArray) =
    Voice(id, name, ModelCatalog.voiceprintModel.sha256, mean, 1, 30.0, 0, 0)

/** Hooks and records shared by the fake seams of one harness. */
class Script {
    /** Window starts on the recording clock, in decode order. */
    val windows = CopyOnWriteArrayList<Long>()

    /** Runs inside the "native" decode of the window starting at the given millisecond. */
    @Volatile var beforeDecode: (Long) -> Unit = {}

    /** Runs inside the "native" print extraction; the argument counts the calls from 1. */
    @Volatile var beforePrints: (Int) -> Unit = {}

    /** Fails the diarizer from this window start on. */
    @Volatile var diarizerFailsFrom: Long? = null

    val recognizers = CopyOnWriteArrayList<FakeRecognizer>()
    val diarizers = CopyOnWriteArrayList<FakeDiarizer>()
    val sinks = CopyOnWriteArrayList<FakeSink>()
    val printCalls = AtomicLong()

    /** What the last print extraction returned. */
    @Volatile var lastPrints: Map<String, ExtractedPrint> = emptyMap()
}

/** Window-relative words of the window [startMs] .. [startMs] + [durationMs]. */
fun scriptedWords(startMs: Long, durationMs: Long): List<Word> {
    val endMs = startMs + durationMs
    return (startMs / 1000..endMs / 1000).mapNotNull { second ->
        val start = second * 1000 + 100
        val end = second * 1000 + 800
        if (start >= startMs && end <= endMs) Word("", start - startMs, end - startMs, "w$second") else null
    }
}

class FakeRecognizer(
    restored: RecognitionState?,
    private val callbacks: Recognizer.Callbacks,
    private val script: Script,
) : Recognizer {
    override var totalMs = 0L
    override var inferenceMs = restored?.inferenceMs ?: 0L
        private set
    override val segmentation = "Fake segmentation: 28 s windows"

    @Volatile var closed = false
    private var loaded = false
    private val chunks = IncrementalTranscript(restored?.chunks) { audio, startMs ->
        callbacks.onStage(startMs, startMs + audio.durationMs, !loaded)
        script.beforeDecode(startMs)
        loaded = true
        callbacks.onStage(startMs, startMs + audio.durationMs, false)
        script.windows += startMs
        inferenceMs += 1000
        callbacks.onChunk(audio, startMs, Transcript(scriptedWords(startMs, audio.durationMs)))
    }

    override val positionSamples: Long get() = chunks.positionSamples
    override val doneMs: Long get() = chunks.doneMs

    override fun accept(samples: FloatArray, count: Int) = chunks.accept(samples, count) { done, words ->
        callbacks.onProgress(Parakeet.Progress(done, maxOf(totalMs, done), 0, words))
    }

    override fun finish(durationMs: Long): Transcript = chunks.finish().also {
        callbacks.onProgress(Parakeet.Progress(durationMs, durationMs, 0, it.words))
    }

    override fun checkpoint() = RecognitionState(inferenceMs, chunks.checkpoint())

    override fun close() {
        closed = true
    }
}

class FakeDiarizer(restored: DiarizerState?, private val script: Script) : StreamingDiarizer {
    private val found = restored?.turns.orEmpty().toMutableList()
    override val turns: List<SpeakerTurn> get() = found.toList()
    override var elapsedMs = restored?.elapsedMs ?: 0L
        private set

    @Volatile var closed = false

    override fun process(audio: PcmAudio, startMs: Long): List<SpeakerTurn> {
        script.diarizerFailsFrom?.let { check(startMs < it) { "Sortformer failed" } }
        elapsedMs += 100
        val endMs = startMs + audio.durationMs
        return (startMs / TURN_MS..(endMs - 1) / TURN_MS).map { k ->
            val start = maxOf(k * TURN_MS, startMs)
            val end = minOf((k + 1) * TURN_MS, endMs)
            val speaker = (k % SPEAKERS).toInt()
            found += SpeakerTurn(start, end, speaker)
            SpeakerTurn(start - startMs, end - startMs, speaker)
        }
    }

    override fun checkpoint() = DiarizerState(floatArrayOf(1f, 2f, 3f), elapsedMs, turns)

    override fun close() {
        closed = true
    }

    companion object {
        const val TURN_MS = 10_000L
        const val SPEAKERS = 3
    }
}

/** Prints along the axis of the speaker number that ends the id ("diar_test_2" → axis 2). */
class FakePrints(private val script: Script) : PrintExtractor {
    override val dimension = DIM

    override fun compute(
        audio: PcmSource,
        windows: Map<String, List<PrintWindow>>,
        active: () -> Unit,
    ): Map<String, ExtractedPrint> {
        active()
        script.beforePrints(script.printCalls.incrementAndGet().toInt())
        active()
        return windows.mapValues { (id, spans) ->
            spans.forEach { audio.read(it.startSample, minOf(it.endSample - it.startSample, Limits.ASR_RATE)) }
            val seconds = spans.sumOf { it.endSample - it.startSample }.toDouble() / Limits.ASR_RATE
            axis(id.last().digitToInt()) to seconds
        }.also { script.lastPrints = it }
    }
}

/** [totalMs] of quiet 16 kHz audio in 250 ms blocks, whatever the file holds. */
class FakeAudio(private val totalMs: Long) : AudioSource {
    val starts = CopyOnWriteArrayList<Long>()

    override fun stream(
        file: File,
        startSample: Long,
        onSamples: (FloatArray, Int) -> Unit,
        onProgress: (Long, Long?) -> Unit,
    ): Long {
        starts += startSample
        if (!file.isFile) throw UserFacingException(Failure.OPEN)
        val total = totalMs * Limits.ASR_RATE / 1000
        val block = FloatArray(BLOCK)
        var position = startSample
        while (position < total) {
            val count = minOf(BLOCK.toLong(), total - position).toInt()
            for (i in 0 until count) block[i] = ((position + i) % 100).toFloat() / 1000f
            onSamples(block, count)
            position += count
            onProgress(position * 1000 / Limits.ASR_RATE, totalMs)
        }
        return totalMs
    }

    private companion object {
        const val BLOCK = 4000
    }
}

/** Writes 4 bytes per block; [finish] puts a real Ogg Opus clip in place so the seal can package it. */
class FakeSink(private val file: File, restored: EncoderState?) : OpusSink {
    override var sampleCount = restored?.samples ?: 0L
        private set
    var syncs = 0
    var closed = false
    var finished = false

    init {
        if (restored == null) {
            file.writeBytes(ByteArray(0))
        } else {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(restored.length) }
        }
    }

    override fun accept(samples: FloatArray, count: Int) {
        file.appendBytes(byteArrayOf(1, 2, 3, 4))
        sampleCount += count
    }

    override fun checkpoint(): EncoderState {
        syncs++
        val frame = FloatArray(CheckpointState.FRAME_FLOATS)
        return EncoderState(null, frame, sampleCount, 0, file.length(), 7, syncs, 0, null)
    }

    override fun finish() {
        FIXTURE_OPUS.copyTo(file, overwrite = true)
        finished = true
    }

    override fun close() {
        closed = true
    }
}

/** A note directory with a fake catalogue, names owner and work directory. [log] records the order of writes. */
class FakeStorage(
    noteDir: File,
    private val audio: File,
    private val log: MutableList<String>,
) : JobStorage {
    val names = AtomicReference(CheckpointNames(emptyMap(), emptyMap()))
    val saved = CopyOnWriteArrayList<CheckpointState>()

    /** Names as the job left them after each of its own changes. */
    val jobNames = CopyOnWriteArrayList<CheckpointNames>()

    @Volatile var checkpointJson: String? = null

    @Volatile var mismatch = false

    @Volatile var namingClosed = false

    @Volatile var existing: CassiniDocument? = null

    val adoptions = CopyOnWriteArrayList<Adoption>()

    @Volatile var adoptFailure: Exception? = null

    /** Runs inside the note lock of the seal. */
    @Volatile var duringSeal: () -> Unit = {}

    /** Runs right after the marker is written. */
    @Volatile var afterMarker: () -> Unit = {}

    /** Thrown by [saveCheckpoint] instead of writing, when set. */
    @Volatile var checkpointFailure: java.io.IOException? = null

    /** How many times the job opened naming. */
    val opens = AtomicLong()

    /** Called with each checkpoint before it is written, to check what was fsynced. */
    @Volatile var onCheckpoint: (CheckpointState) -> Unit = {}

    val work = File(noteDir, "work")
    val marker = File(work, "sealed")
    private val lock = Any()

    override suspend fun loadCheckpoint(noteId: String): CheckpointLoad = when {
        mismatch -> CheckpointLoad.Mismatch("signature")
        else -> checkpointJson?.let { CheckpointLoad.Valid(CheckpointState.decode(JSONObject(it))) }
            ?: CheckpointLoad.Missing
    }

    override fun saveCheckpoint(noteId: String, state: CheckpointState) {
        checkpointFailure?.let { throw it }
        onCheckpoint(state)
        // Through the codec, as CheckpointRepository writes it.
        checkpointJson = CheckpointState.encode(state).toString()
        saved += state
        log += "checkpoint ${state.doneMs}"
    }

    override fun names(noteId: String) = names

    override fun mutateNames(noteId: String, reducer: (CheckpointNames) -> CheckpointNames): CheckpointNames {
        check(!namingClosed) { "Naming is closed" }
        return names.updateAndGet(reducer).also { jobNames += it }
    }

    override fun openNames(noteId: String) {
        opens.incrementAndGet()
        namingClosed = false
    }

    override fun closeNames(noteId: String): CheckpointNames {
        namingClosed = true
        return names.get()
    }

    override suspend fun audioFile(noteId: String) = audio

    override fun workFiles(noteId: String): WorkFiles {
        work.mkdirs()
        return WorkFiles(File(work, "audio.opus.part"), File(work, "clips.pcm"))
    }

    override fun sealTarget(noteId: String) = File(work, "sealed.opus")

    override fun <T> withNoteLockBlocking(noteId: String, block: () -> T): T = synchronized(lock) {
        duringSeal()
        block()
    }

    override fun existingDocumentBlocking(noteId: String) = existing

    override fun titleBlocking(noteId: String) = "Weekly sync"

    override fun markSealedBlocking(noteId: String) {
        check(sealTarget(noteId).isFile)
        marker.writeText("ok")
        log += "marker"
        afterMarker()
    }

    override fun discardSealedBlocking(noteId: String) {
        marker.delete()
        sealTarget(noteId).delete()
        log += "discard sealed"
    }

    override suspend fun adopt(noteId: String, adoption: Adoption) {
        log += "adopt"
        adoptFailure?.let { throw it }
        adoptions += adoption
    }
}

class FakeVoices(private val log: MutableList<String>) : JobVoices {
    @Volatile var people: List<Voice> = emptyList()

    @Volatile var generation = 3L

    @Volatile var noteVoices: Map<String, SpeakerPrint> = emptyMap()

    val pending = CopyOnWriteArrayList<Pending>()

    /** What `voices/pending/<id>.json` holds now: the last saved prints, or null once discarded. */
    @Volatile var pendingFile: Pending? = null

    data class Pending(
        val variantId: String,
        val model: String,
        val prints: Map<String, SpeakerPrint>,
        val generation: Long,
    )

    override fun peopleBlocking() = people

    override fun generationBlocking() = generation

    override fun noteVoicesBlocking(noteId: String) = noteVoices

    override fun savePendingBlocking(
        noteId: String,
        variantId: String,
        model: String,
        prints: Map<String, SpeakerPrint>,
        generation: Long,
    ) {
        pending += Pending(variantId, model, prints, generation)
        pendingFile = pending.last()
        log += "pending"
    }

    override fun discardPendingBlocking(noteId: String) {
        pendingFile = null
        log += "discard pending"
    }
}

class FakeModels : ModelAccess {
    @Volatile var readyParts: Set<ModelPart> = ModelPart.entries.toSet()

    @Volatile var consent = true

    @Volatile var outcome: InstallOutcome? = null
    val installs = CopyOnWriteArrayList<Set<ModelPart>>()

    override suspend fun consented() = consent

    override fun ready(part: ModelPart) = part in readyParts

    override fun plan() = ModelPlan.of { model -> ModelPart.entries.any { it.primary == model && it in readyParts } }

    override fun hasValidatedNetwork() = true

    /** Makes [install] wait until it is cancelled, after counting down [installing]. */
    @Volatile var hang = false
    val installing = java.util.concurrent.CountDownLatch(1)

    override suspend fun install(parts: Set<ModelPart>, onProgress: (DownloadProgress) -> Unit): InstallOutcome {
        installs += parts
        parts.forEach { onProgress(DownloadProgress(it, 1, 2, verifying = false)) }
        installing.countDown()
        if (hang) kotlinx.coroutines.awaitCancellation()
        val result = outcome ?: InstallOutcome.Installed()
        if (result is InstallOutcome.Installed) readyParts = readyParts + (parts - result.skipped - result.failed.keys)
        return result
    }
}
