package io.codemyriad.cassini.engine.asr

import io.codemyriad.cassini.core.audio.StreamingResampler
import io.codemyriad.cassini.core.audio.whistle.WhistleResampler
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.engine.capture.LiveMicrophonePcm
import io.codemyriad.cassini.engine.job.InferenceDispatcher
import io.codemyriad.cassini.engine.job.NativeBudget
import io.codemyriad.cassini.engine.job.Recognizer
import io.codemyriad.cassini.engine.job.RecognizerFactory
import kotlinx.coroutines.withContext

/** In-memory preview over the same recognizer and resampler as the saved-audio job. */
object LiveMicrophoneRecognition {
    data class Update(val fromMs: Long, val throughMs: Long, val words: List<Word>)
    data class MissingFrames(val first: Long, val endExclusive: Long)

    /**
     * Consumes until the recorder closes input or the caller cancels. [updated] receives
     * snapshots for a contiguous span, on the inference thread. Use saved audio to fill
     * [missing] ranges. The saved-audio job still owns the published transcript.
     */
    suspend fun follow(
        input: LiveMicrophonePcm,
        model: SpeechModel,
        factory: RecognizerFactory,
        updated: (Update) -> Unit,
        missing: (MissingFrames) -> Unit,
    ): Unit = withContext(InferenceDispatcher.dispatcher) {
        var span: Span? = null
        var expected = 0L
        try {
            for (block in input.blocks) {
                if (block.firstFrame != expected) {
                    span?.finish(expected)
                    span?.close()
                    span = null
                    missing(MissingFrames(expected, block.firstFrame))
                }
                if (span == null) span = Span(block.firstFrame, model, factory, updated)
                span.accept(block.pcm16)
                expected = block.firstFrame + block.pcm16.size
            }
            span?.finish(expected)
            if (expected < input.endFrame) missing(MissingFrames(expected, input.endFrame))
        } finally {
            span?.close()
        }
    }

    private class Span(
        private val firstFrame: Long,
        model: SpeechModel,
        factory: RecognizerFactory,
        private val update: (Update) -> Unit,
    ) : AutoCloseable {
        private val recognizer: Recognizer
        private val whistle: WhistleResampler?
        private val parakeet: StreamingResampler?

        init {
            check(NativeBudget.tryAcquire(0)) { "Speech inference is busy" }
            var opened: Recognizer? = null
            try {
                val callbacks = Recognizer.Callbacks(
                    onChunk = { _, _, words -> words },
                    onStage = { _, _, _ -> },
                    onProgress = { value -> emit(value.doneMs, value.words) },
                )
                recognizer = factory.open(null, callbacks).also { opened = it }
                whistle = if (model == SpeechModel.INCLUDED) WhistleResampler(CAPTURE_RATE, recognizer::accept) else null
                parakeet = if (whistle == null) StreamingResampler(CAPTURE_RATE, sink = recognizer::accept) else null
            } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                try {
                    opened?.close()
                } finally {
                    NativeBudget.release()
                }
                throw failure
            }
        }

        private var closed = false
        private var latest: Update? = null

        fun accept(pcm: ShortArray) {
            val floats = FloatArray(pcm.size) { pcm[it] / PCM16_SCALE }
            if (whistle != null) whistle.accept(floats) else requireNotNull(parakeet).push(floats)
        }

        fun finish(end: Long) {
            whistle?.finish()
            parakeet?.finish()
            val durationMs = (end - firstFrame) * MS_PER_SECOND / CAPTURE_RATE
            emit(durationMs, recognizer.finish(durationMs).words)
        }

        private fun emit(throughMs: Long, words: List<Word>) {
            val base = firstFrame * MS_PER_SECOND / CAPTURE_RATE
            val placed = words.map { word ->
                word.copy(startMs = base + word.startMs, endMs = base + word.endMs)
            }
            val next = Update(base, base + throughMs, placed)
            if (next != latest) {
                latest = next
                update(next)
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                recognizer.close()
            } finally {
                NativeBudget.release()
            }
        }
    }

    private const val CAPTURE_RATE = 48_000
    private const val MS_PER_SECOND = 1000L
    private const val PCM16_SCALE = 32768f
}
