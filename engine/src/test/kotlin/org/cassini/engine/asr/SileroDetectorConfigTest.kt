package org.cassini.engine.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The native detector's configuration; its feed loop is tested in :core (SpeechFeedTest). */
class SileroDetectorConfigTest {
    @Test fun configMatchesTheDesktopDefaults() {
        val config = SileroDetector.config("/models/silero_vad.onnx")
        with(config.sileroVadModelConfig) {
            assertEquals("/models/silero_vad.onnx", model)
            assertEquals(0.18f, threshold)
            assertEquals(0.5f, minSilenceDuration)
            assertEquals(0.10f, minSpeechDuration)
            assertEquals(512, windowSize)
            assertEquals(25f, maxSpeechDuration)
        }
        assertEquals(16000, config.sampleRate)
        assertEquals(1, config.numThreads)
        assertEquals("cpu", config.provider)
        assertFalse(config.debug)
    }
}
