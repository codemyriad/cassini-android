package org.cassini.engine.asr

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.cassini.core.Failure
import org.cassini.core.asr.TokenWords
import org.cassini.core.asr.WordDecoder
import org.cassini.core.audio.PcmAudio
import org.cassini.core.format.TimedWord
import org.cassini.core.requireUser
import java.io.File

/** The files of an installed Parakeet TDT transducer: three ONNX graphs and the token table. */
data class ParakeetFiles(val encoder: String, val decoder: String, val joiner: String, val tokens: String) {
    /** Whether every file exists. The native loader aborts the process on a missing file instead of throwing. */
    fun present() = listOf(encoder, decoder, joiner, tokens).all { File(it).isFile }
}

/**
 * One native recognizer, owned and closed by the decoding thread. The caller holds
 * [org.cassini.engine.job.NativeBudget] from before construction until after [close].
 */
class ParakeetDecoder(models: ParakeetFiles) : WordDecoder, AutoCloseable {
    companion object {
        fun config(models: ParakeetFiles) = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128, dither = 0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.encoder,
                    decoder = models.decoder,
                    joiner = models.joiner,
                ),
                tokens = models.tokens,
                modelType = "nemo_transducer",
                provider = "cpu",
                numThreads = 2,
            ),
            decodingMethod = "greedy_search",
        )
    }

    private val recognizer: OfflineRecognizer
    private var closed = false
    init {
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        recognizer = OfflineRecognizer(config = config(models))
    }
    override fun decode(audio: PcmAudio, start: Int, length: Int, headPad: Int, tailPad: Int): List<TimedWord> {
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        val stream = recognizer.createStream()
        try {
            // The decoder delivers the feature rate, so sherpa does not resample again.
            stream.acceptWaveform(if (headPad == 0 && tailPad == 0 && start == 0 && length == audio.samples.size) audio.samples
                else FloatArray(headPad + length + tailPad).also { audio.samples.copyInto(it, headPad, start, start + length) }, audio.sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            requireUser(result.text.isBlank() || result.tokens.isNotEmpty(), Failure.TIMINGS)
            return TokenWords.timedWordsFromTokens(result.tokens, result.timestamps, result.durations)
        } finally {
            stream.release()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        recognizer.release()
    }
}
