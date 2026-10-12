package org.cassini.engine.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The recognizer configuration 0.1.0 used, unchanged: decoding results depend on every value. */
class ParakeetDecoderConfigTest {
    @get:Rule val folder = TemporaryFolder()

    private val files = ParakeetFiles(
        encoder = "/m/encoder.int8.onnx",
        decoder = "/m/decoder.int8.onnx",
        joiner = "/m/joiner.int8.onnx",
        tokens = "/m/tokens.txt",
    )

    @Test fun configMatchesTheZeroOneRecognizer() {
        val config = ParakeetDecoder.config(files)
        assertEquals(16000, config.featConfig.sampleRate)
        assertEquals(128, config.featConfig.featureDim)
        assertEquals(0f, config.featConfig.dither)
        with(config.modelConfig) {
            assertEquals("/m/encoder.int8.onnx", transducer.encoder)
            assertEquals("/m/decoder.int8.onnx", transducer.decoder)
            assertEquals("/m/joiner.int8.onnx", transducer.joiner)
            assertEquals("/m/tokens.txt", tokens)
            assertEquals("nemo_transducer", modelType)
            assertEquals("cpu", provider)
            assertEquals(2, numThreads)
        }
        assertEquals("greedy_search", config.decodingMethod)
    }

    @Test fun presentNeedsEveryFile() {
        val names = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        val paths = names.map { folder.root.resolve(it).absolutePath }
        val install = ParakeetFiles(paths[0], paths[1], paths[2], paths[3])
        assertFalse(install.present())
        names.dropLast(1).forEach { folder.newFile(it) }
        assertFalse(install.present())
        folder.newFile(names.last())
        assertTrue(install.present())
    }
}
