package org.cassini.engine.asr

import org.cassini.core.asr.DecodePolicy
import org.cassini.core.asr.IncrementalTranscript
import org.cassini.core.audio.PcmAudio
import org.cassini.core.checkpoint.RecognitionState
import org.cassini.core.format.Transcript

/**
 * One recognizer reused while the container decoder supplies short PCM blocks: [IncrementalTranscript] cuts the
 * windows and each window is decoded through [Parakeet.transcribeWithDecoder]. The recognizer is loaded with the
 * first window. The caller holds [org.cassini.engine.job.NativeBudget] from construction until after [close].
 *
 * [restored] and [checkpoint] are the typed `recognition` member of checkpoint.json: no JSON at each window.
 * [onChunk] receives each decoded window (its audio, its start on the recording clock and its words) and returns
 * the words to keep, with speakers.
 * [onStage] reports a window's span and whether the recognizer is still loading for it.
 */
class IncrementalRecognizer(
    private val models: ParakeetFiles,
    private val detectorModel: String?,
    restored: RecognitionState? = null,
    private val onChunk: (PcmAudio, Long, Transcript) -> Transcript = { _, _, transcript -> transcript },
    private val onStage: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
    private val onProgress: (Parakeet.Progress) -> Unit,
) : AutoCloseable {
    private var decoder: ParakeetDecoder? = null
    private val began = System.nanoTime()
    var totalMs = 0L
    var inferenceMs = restored?.inferenceMs ?: 0L
        private set
    private val chunks = IncrementalTranscript(restored?.chunks) { audio, startMs ->
        onStage(startMs, startMs + audio.durationMs, decoder == null)
        val recognizer = decoder ?: ParakeetDecoder(models).also { decoder = it }
        onStage(startMs, startMs + audio.durationMs, false)
        val started = System.nanoTime()
        val transcript = Parakeet.transcribeWithDecoder(audio, recognizer, detectorModel, DecodePolicy.WHOLE_SPANS, Parakeet.cutting(detectorModel)) {}
        inferenceMs += (System.nanoTime() - started) / 1_000_000
        onChunk(audio, startMs, transcript)
    }

    val positionSamples get() = chunks.positionSamples
    val doneMs get() = chunks.doneMs
    fun checkpoint(): RecognitionState = RecognitionState(inferenceMs, chunks.checkpoint())
    fun accept(samples: FloatArray, count: Int) {
        chunks.accept(samples, count) { doneMs, words ->
            onProgress(Parakeet.Progress(doneMs, totalMs.takeIf { it > 0 }?.let { maxOf(it, doneMs) } ?: 0, (System.nanoTime() - began) / 1_000_000, words))
        }
    }

    fun finish(durationMs: Long): Transcript {
        val transcript = chunks.finish()
        onProgress(Parakeet.Progress(durationMs, durationMs, (System.nanoTime() - began) / 1_000_000, transcript.words))
        return transcript
    }

    override fun close() { decoder?.close() }
}
