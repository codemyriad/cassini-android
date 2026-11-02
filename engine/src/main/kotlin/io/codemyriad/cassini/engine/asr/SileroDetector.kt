package io.codemyriad.cassini.engine.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.asr.SileroInputFilter
import io.codemyriad.cassini.core.asr.Span
import io.codemyriad.cassini.core.asr.SpeechFeed
import io.codemyriad.cassini.core.audio.PcmAudio
import io.codemyriad.cassini.core.audio.VAD_SAMPLE_RATE
import io.codemyriad.cassini.core.requireUser
import java.io.File

/**
 * Silero VAD over a 16 kHz copy of the recording, fed by [SpeechFeed]. One instance owns one native detector:
 * [close] it in a finally block. Each window goes through [filter] first (D26.1); its state belongs to the track, so
 * the detector's [reset] leaves it alone. Null feeds the raw samples, for the test that shows what the filter fixes.
 */
class SileroDetector(modelPath: String, private val filter: SileroInputFilter? = SileroInputFilter()) :
    SpeechFeed.Stream, AutoCloseable {
    companion object {
        /** Silero is a tiny stateful model run per 32 ms window: fastest on one CPU thread. */
        fun config(modelPath: String) = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                threshold = SpeechFeed.THRESHOLD,
                minSilenceDuration = SpeechFeed.MIN_SILENCE_SECONDS,
                minSpeechDuration = SpeechFeed.MIN_SPEECH_SECONDS,
                windowSize = SpeechFeed.WINDOW,
                maxSpeechDuration = SpeechFeed.MAX_SPEECH_SECONDS,
            ),
            sampleRate = VAD_SAMPLE_RATE,
            numThreads = 1,
            provider = "cpu",
            debug = false,
        )
    }

    private val vad: Vad

    init {
        requireUser(File(modelPath).isFile, Failure.MODEL)
        vad = Vad(config = config(modelPath))
    }

    override fun reset() = vad.reset()
    override fun acceptWaveform(window: FloatArray) = vad.acceptWaveform(filter?.process(window) ?: window)
    override fun empty() = vad.empty()
    override fun front() = vad.front().let { SpeechFeed.Segment(it.start, it.samples) }
    override fun pop() = vad.pop()
    override fun flush() = vad.flush()

    /**
     * Calls [onSpan] with each speech span of [audio], in order, and returns the detector's tail padding in
     * milliseconds for the timeline clamp (see [SpeechFeed.scan]). Reusable: every call starts from a reset.
     */
    fun detect(audio: PcmAudio, onSpan: (Span) -> Unit): Long = SpeechFeed.scan(this, audio, onSpan)

    override fun close() = vad.release()
}
