package io.codemyriad.cassini.engine.asr

import io.codemyriad.cassini.core.checkpoint.RecognitionState
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.models.SpeechModel
import io.codemyriad.cassini.engine.capture.LiveMicrophonePcm
import io.codemyriad.cassini.engine.job.Recognizer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveMicrophoneRecognitionTest {
    @Test fun gapsResetTheRecognizerAndKeepTheOriginalTimeline() = runBlocking {
        val input = LiveMicrophonePcm(capacity = 2)
        val second = ShortArray(48_000) { 1 }
        input.offer(0, second, second.size)
        input.offer(96_000, second, second.size)
        input.finish()
        val previews = mutableListOf<LiveMicrophoneRecognition.Update>()
        val skipped = mutableListOf<LiveMicrophoneRecognition.MissingFrames>()
        LiveMicrophoneRecognition.follow(
            input,
            SpeechModel.PARAKEET,
            { _, _ -> Fixture() },
            LiveMicrophoneRecognition.Events(previews::add, skipped::add),
        )

        assertEquals(listOf(LiveMicrophoneRecognition.MissingFrames(48_000, 96_000)), skipped)
        assertEquals(listOf(0L, 2000L), previews.map { it.fromMs })
        assertEquals(listOf(100L, 2100L), previews.flatMap { it.words }.map { it.startMs })
    }

    private class Fixture : Recognizer {
        override var totalMs = 0L
        override val inferenceMs = 0L
        override var positionSamples = 0L
        override val doneMs get() = positionSamples / 16
        override val segmentation = "test"
        override val language = "en"

        override fun accept(samples: FloatArray, count: Int) {
            positionSamples += count
        }

        override fun finish(durationMs: Long): Transcript {
            assertEquals(1000L, durationMs)
            assertEquals(16_000L, positionSamples)
            return Transcript(listOf(Word("mic", 100, 250, "hello")), language)
        }

        override fun checkpoint(): RecognitionState = error("Preview never checkpoints")
        override fun close() = Unit
    }
}
