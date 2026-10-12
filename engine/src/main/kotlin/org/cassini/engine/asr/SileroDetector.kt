package org.cassini.engine.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import org.cassini.core.Failure
import org.cassini.core.asr.Span
import org.cassini.core.asr.SpeechFeed
import org.cassini.core.audio.PcmAudio
import org.cassini.core.audio.VAD_SAMPLE_RATE
import org.cassini.core.requireUser
import java.io.File

/**
 * Silero VAD over a 16 kHz copy of the recording, fed by [SpeechFeed]. One instance owns one native detector:
 * [close] it in a finally block.
 */
class SileroDetector(modelPath: String) : SpeechFeed.Stream, AutoCloseable {
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
    override fun acceptWaveform(window: FloatArray) = vad.acceptWaveform(window)
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
