package io.codemyriad.cassini.engine.whistle

import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.asr.IncrementalState
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.audio.PcmAudio
import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.checkpoint.WhistleResume
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.format.WordBodyBudget
import io.codemyriad.cassini.core.requireUser
import io.codemyriad.cassini.engine.asr.Parakeet
import io.codemyriad.cassini.engine.job.Recognizer
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.math.roundToLong

/** The isolated worker's synchronous, bounded 16 kHz transport. Neither implementation may retain borrowed PCM. */
interface WhistleTransport : AutoCloseable {
    fun accept(samples: FloatArray, count: Int)
    fun finish()
}

/** One segment from the desktop speech gate, on the 16 kHz track clock; JSON timestamps are segment-relative. */
data class WhistleResult(val start: Long, val end: Long, val json: ByteArray)

/** Reproduces exactly [samples] samples from the track's origin, using the same pinned decoder policy. */
fun interface WhistleReplay {
    fun prefix(samples: Long, sink: (FloatArray, Int) -> Unit)
}

/**
 * Adapts the isolated stream to the job's recognizer seam. The caller supplies raw 16 kHz PCM after the desktop
 * FIR stage, never the VAD-only high-pass. Diarization receives the same raw segment and relative words as Parakeet.
 *
 * A checkpoint contains settled words and the accepted sample position, not a Parakeet window or serialized neural
 * state. Resume replays the exact source prefix into a new worker, without repeating callbacks/diarization, and
 * verifies every replayed word and time against the saved prefix before accepting new input. This costs inference
 * time proportional to the prefix but keeps memory bounded and reconstructs Silero/filter/gate state exactly.
 * The job must additionally validate its persisted engine/model/pipeline identity before opening this adapter.
 */
class WhistleRecognizer(
    restored: RecognitionState?,
    private val callbacks: Recognizer.Callbacks,
    replay: WhistleReplay,
    open: ((WhistleResult) -> Unit) -> WhistleTransport,
    private val nanoTime: () -> Long = System::nanoTime,
) : Recognizer {
    private val raw = FloatArray(RING_SIZE)
    private val words = ArrayList<Word>()
    private val pcmHash = MessageDigest.getInstance("SHA-256")
    private var callbackNanos = 0L
    private var position = 0L
    private var settled = 0L
    private var replaying = restored != null
    private var replayed = 0
    private var finished = false
    private var closed = false
    private var failed = false
    private val began = nanoTime()
    private val transport: WhistleTransport
    override var totalMs = 0L
    override var inferenceMs = restored?.inferenceMs ?: 0L
        private set
    override val positionSamples: Long get() = position
    override val doneMs: Long get() = settled * MS / RATE
    override val segmentation = "cassini-fir-48k-16k-v1; desktop Silero v6.2.1 SpeechGate"
    override val language = Transcript.UNDETERMINED

    init {
        restored?.chunks?.let {
            val marker = requireNotNull(restored.whistle) { "Not a Whistle checkpoint" }
            require(marker.pipeline == PIPELINE) { "Not a compatible Whistle checkpoint" }
            require(marker.pcmSha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid saved PCM hash" }
            require(it.start == it.end && it.window.isEmpty()) { "Not a Whistle replay checkpoint" }
            require(it.start in 0..Limits.MAX_SAMPLES)
            requireUser(WordBodyBudget.fits(it.words), Failure.LONG)
            words.addAll(it.words)
        }
        transport = open { result ->
            val start = nanoTime()
            try {
                segment(result)
            } finally {
                callbackNanos += nanoTime() - start
            }
        }
        try {
            restored?.let {
                val limit = it.chunks.start
                replay.prefix(limit) { samples, count ->
                    require(count >= 0 && position + count <= limit) { "Replay exceeded saved position" }
                    accept(samples, count)
                }
                if (it.whistle?.finished == true) {
                    measured { transport.finish() }
                    finished = true
                    settled = position
                }
                require(position == limit && replayed == words.size) { "Whistle replay does not match saved progress" }
                require(hash() == it.whistle?.pcmSha256) { "Whistle replay PCM differs from saved progress" }
            }
            replaying = false
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            try {
                close()
            } catch (@Suppress("TooGenericExceptionCaught") cleanup: Throwable) {
                error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    override fun accept(samples: FloatArray, count: Int) {
        check(!closed && !finished && !failed)
        require(count in 0..samples.size && position + count <= Limits.MAX_SAMPLES)
        failed = true
        var at = 0
        while (at < count) {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val size = minOf(WhistleStream.INPUT_BLOCK, count - at)
            val block = samples.copyOfRange(at, at + size)
            require(block.all { it.isFinite() })
            val bytes = ByteBuffer.allocate(size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in block) bytes.putFloat(sample)
            pcmHash.update(bytes.array())
            for (i in block.indices) raw[((position + i) and RING_MASK).toInt()] = block[i]
            position += size
            measured { transport.accept(block, size) }
            at += size
        }
        failed = false
    }

    override fun finish(durationMs: Long): Transcript {
        check(!closed && !failed)
        require(durationMs == position * MS / RATE)
        if (!finished) {
            failed = true
            measured { transport.finish() }
            settled = position
            finished = true
            failed = false
        }
        progress(durationMs)
        return Transcript(words.toList(), language)
    }

    override fun checkpoint(): RecognitionState {
        check(!closed && !failed && !replaying)
        return RecognitionState(
            inferenceMs,
            IncrementalState(position, position, FloatArray(0), words.toList()),
            WhistleResume(PIPELINE, finished, hash()),
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    private fun segment(result: WhistleResult) {
        require(result.start >= settled && result.end > result.start && result.end <= position)
        require(result.end - result.start <= MAX_SEGMENT && result.start >= position - RING_SIZE)
        val relative = parse(result)
        if (replaying) {
            val placed = place(relative.words, result.start)
            for (word in placed) {
                val saved = words.getOrNull(replayed++)
                val matches = saved != null && saved.text == word.text && saved.startMs == word.startMs && saved.endMs == word.endMs
                require(matches) {
                    "Whistle replayed words differ from saved progress"
                }
            }
        } else {
            val startMs = result.start * MS / RATE
            callbacks.onStage(startMs, result.end * MS / RATE, false)
            val pcm = FloatArray((result.end - result.start).toInt()) {
                raw[((result.start + it) and RING_MASK).toInt()]
            }
            val assigned = callbacks.onChunk(PcmAudio(pcm, RATE), startMs, relative)
            words.addAll(place(assigned.words, result.start))
            requireUser(WordBodyBudget.fits(words), Failure.LONG)
        }
        settled = result.end
        if (!replaying) progress(doneMs)
    }

    private fun parse(result: WhistleResult): Transcript {
        require(result.json.size <= MAX_JSON)
        val items = JSONObject(result.json.toString(Charsets.UTF_8)).getJSONArray("words")
        val duration = (result.end - result.start) * MS / RATE
        val found = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            val text = item.getString("word").trim()
            val start = item.getDouble("start")
            val end = item.getDouble("end")
            val valid = text.isNotEmpty() && text.none(Char::isWhitespace) && start.isFinite() && end.isFinite() && end >= start
            require(valid)
            Word(
                "speaker_0",
                (start * MS).roundToLong().coerceIn(0, duration),
                (end * MS).roundToLong().coerceIn(0, duration),
                text,
            )
        }
        return Transcript(found, language)
    }

    private fun place(found: List<Word>, start: Long): List<Word> {
        val offset = start * MS / RATE
        return found.map { it.copy(startMs = it.startMs + offset, endMs = it.endMs + offset) }
    }

    private fun progress(done: Long) = callbacks.onProgress(
        Parakeet.Progress(
            done,
            totalMs.takeIf { it > 0 }?.coerceAtLeast(done) ?: 0,
            (nanoTime() - began) / NANOS_PER_MS,
            words.toList(),
        ),
    )

    private inline fun measured(block: () -> Unit) {
        val start = nanoTime()
        val callbacksBefore = callbackNanos
        try {
            block()
        } finally {
            inferenceMs += (nanoTime() - start - (callbackNanos - callbacksBefore)).coerceAtLeast(0) / NANOS_PER_MS
        }
    }

    private fun hash(): String = (pcmHash.clone() as MessageDigest).digest().joinToString("") { "%02x".format(it) }

    companion object {
        const val PIPELINE = "whistle-needle-93e88ed-ca52876-fir-v1-silero-6.2.1-gate-v1"
        private const val RATE = 16000
        private const val MS = 1000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val RING_SIZE = 262144
        private const val RING_MASK = 262143L
        private const val MAX_SEGMENT = 240512L
        private const val MAX_JSON = 262144
    }
}
