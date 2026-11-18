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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** In-memory preview over the same recognizer and resampler as the saved-audio job. */
object LiveMicrophoneRecognition {
    data class Update(val fromMs: Long, val throughMs: Long, val words: List<Word>)
    data class MissingFrames(val first: Long, val endExclusive: Long)
    class Events(val updated: (Update) -> Unit, val missing: (MissingFrames) -> Unit)

    /**
     * Consumes until the recorder closes input or the caller cancels. [events] receives
     * snapshots and missing ranges on the inference thread. The saved-audio job
     * fills skipped ranges and still owns the published transcript.
     */
    suspend fun follow(
        input: LiveMicrophonePcm,
        model: SpeechModel,
        factory: RecognizerFactory,
        events: Events,
        settleOnStop: Boolean = true,
    ): Unit = withContext(InferenceDispatcher.dispatcher) {
        check(NativeBudget.tryAcquirePreview { cancel() }) { "Speech inference is busy" }
        var span: Span? = null
        var expected = 0L
        try {
            for (block in input.blocks) {
                ensureActive()
                if (block.firstFrame != expected) {
                    span?.finish(expected)
                    span?.close()
                    span = null
                    events.missing(MissingFrames(expected, block.firstFrame))
                }
                if (span == null) span = Span(block.firstFrame, model, factory, events.updated)
                span.accept(block.pcm16)
                expected = block.firstFrame + block.pcm16.size
            }
            if (settleOnStop) span?.finish(expected)
            if (expected < input.endFrame) events.missing(MissingFrames(expected, input.endFrame))
        } finally {
            try {
                span?.close()
            } finally {
                NativeBudget.releasePreview()
            }
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
            val callbacks = Recognizer.Callbacks(
                onChunk = { _, _, words -> words },
                onStage = { _, _, _ -> },
                onProgress = { value -> emit(value.doneMs, value.words) },
            )
            recognizer = factory.open(null, callbacks)
            whistle = if (model == SpeechModel.INCLUDED) WhistleResampler(CAPTURE_RATE, recognizer::accept) else null
            parakeet = if (whistle == null) StreamingResampler(CAPTURE_RATE, sink = recognizer::accept) else null
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
            val durationMs = if (whistle == null) {
                (end - firstFrame) * MS_PER_SECOND / CAPTURE_RATE
            } else {
                recognizer.positionSamples * MS_PER_SECOND / RECOGNIZER_RATE
            }
            if (durationMs >= MIN_FINAL_MS) emit(durationMs, recognizer.finish(durationMs).words)
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
            recognizer.close()
        }
    }

    private const val CAPTURE_RATE = 48_000
    private const val RECOGNIZER_RATE = 16_000
    private const val MIN_FINAL_MS = 300L
    private const val MS_PER_SECOND = 1000L
    private const val PCM16_SCALE = 32768f
}
