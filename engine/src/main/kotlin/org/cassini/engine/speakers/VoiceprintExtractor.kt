package org.cassini.engine.speakers

import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import org.cassini.core.audio.PcmAudio
import org.cassini.core.audio.PcmSource
import org.cassini.core.audio.source
import org.cassini.core.speakers.PrintWindow
import org.cassini.core.speakers.Voiceprints
import java.io.File

/**
 * CAM++ voiceprints of selected clips ([Voiceprints] chooses them). Native: the caller holds
 * [org.cassini.engine.job.NativeBudget] around each call.
 */
object VoiceprintExtractor {
    /** L2-normalised print and seconds of speech behind it, per speaker. [active] throws to cancel. */
    fun compute(model: File, audio: PcmAudio, windows: Map<String, List<PrintWindow>>, active: () -> Unit): Map<String, Pair<FloatArray, Double>> {
        return compute(model, audio.source(), windows, active = active)
    }

    fun compute(model: File, audio: PcmSource, windows: Map<String, List<PrintWindow>>, active: () -> Unit): Map<String, Pair<FloatArray, Double>> {
        if (windows.isEmpty()) return emptyMap()
        active()
        val extractor = SpeakerEmbeddingExtractor(config = SpeakerEmbeddingExtractorConfig(model = model.absolutePath, numThreads = 2, provider = "cpu"))
        try {
            val prints = LinkedHashMap<String, Pair<FloatArray, Double>>()
            for ((speaker, spans) in windows) {
                active()
                val stream = extractor.createStream()
                try {
                    var samples = 0L
                    for (window in spans) {
                        active()
                        val s = window.startSample.coerceIn(0, audio.sampleCount); val e = window.endSample.coerceIn(s, audio.sampleCount)
                        if (e > s) { stream.acceptWaveform(audio.read(s, e - s), audio.sampleRate); samples += e - s }
                    }
                    stream.inputFinished()
                    if (samples == 0L || !extractor.isReady(stream)) continue
                    Voiceprints.normalised(extractor.compute(stream))?.let { prints[speaker] = it to samples.toDouble() / audio.sampleRate }
                } finally { stream.release() }
            }
            active()
            return prints
        } finally { extractor.release() }
    }
}
