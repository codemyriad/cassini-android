package org.cassini.android

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

object Parakeet {
    fun transcribe(audio: PcmAudio, models: ModelStore): Transcript {
        requireUser(models.ready(), Failure.MODEL)
        val recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128, dither = 0f),
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = models.modelPath("encoder"),
                    decoder = models.modelPath("decoder"),
                    joiner = models.modelPath("joiner"),
                ),
                tokens = models.path("tokens.txt"),
                modelType = "nemo_transducer",
                provider = "cpu", numThreads = 2,
            ),
            decodingMethod = "greedy_search",
        ))
        try {
            val stream = recognizer.createStream()
            try {
                // Sherpa resamples to the feature rate. Keep the input's original sample rate.
                stream.acceptWaveform(audio.samples, audio.sampleRate)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                requireUser(result.text.isBlank() || result.tokens.isNotEmpty(), Failure.TIMINGS)
                return Transcript.fromTokens(result.tokens, result.timestamps, result.durations)
            } finally {
                stream.release()
            }
        } finally {
            recognizer.release()
        }
    }
}
