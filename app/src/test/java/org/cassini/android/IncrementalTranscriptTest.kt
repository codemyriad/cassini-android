package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class IncrementalTranscriptTest {
    private val rate = Limits.ASR_RATE

    @Test fun publishesBeforeEndAndReconcilesOverlapsOnRecordingClock() {
        val calls = mutableListOf<Long>()
        val pipeline = IncrementalTranscript { audio, start ->
            calls.add(start)
            // A word per second, encoded in the audio itself so placement and borrowed-buffer
            // handling are checked independently of the window's reported offset.
            Transcript((0 until audio.samples.size / rate).map { second ->
                Word("spk_1", second * 1000L, second * 1000L + 600, "word${audio.samples[second * rate].toInt()}")
            })
        }
        val borrowed = FloatArray(rate)
        val reports = mutableListOf<Pair<Long, List<Word>>>()
        repeat(70) { second ->
            borrowed.fill(second.toFloat())
            pipeline.accept(borrowed, borrowed.size) { done, words -> reports.add(done to words) }
            if (second == 26) assertTrue(reports.isEmpty())
            if (second == 27) {
                assertEquals(28_000L, reports.single().first)
                assertEquals(28, reports.single().second.size)
            }
        }
        borrowed.fill(-1f)
        val result = pipeline.finish()
        assertEquals(listOf(0L, 26_000L, 52_000L), calls)
        assertEquals((0 until 70).map { "word$it" }, result.words.map { it.text })
        assertEquals((0 until 70).map { it * 1000L }, result.words.map { it.startMs })
        // Later merges must not mutate lists already delivered to the UI.
        assertEquals(28, reports.first().second.size)
    }

    @Test fun exactWindowDoesNotRecognizeTheRetainedOverlapAgain() {
        var calls = 0
        val pipeline = IncrementalTranscript { _, _ -> calls++; Transcript(emptyList()) }
        pipeline.accept(FloatArray(28 * rate), 28 * rate)
        pipeline.finish()
        assertEquals(1, calls)
    }

    @Test fun shortAndEmptyInputsFlushOnlyRealAudio() {
        for (count in listOf(0, 17, rate * 3 + 11)) {
            val sizes = mutableListOf<Int>()
            val pipeline = IncrementalTranscript { audio, _ -> sizes.add(audio.samples.size); Transcript(emptyList()) }
            pipeline.accept(FloatArray(count), count)
            pipeline.finish()
            assertEquals(if (count == 0) emptyList() else listOf(count), sizes)
        }
    }

    @Test fun cancellationStopsBeforeRecognition() {
        var calls = 0
        val pipeline = IncrementalTranscript { _, _ -> calls++; Transcript(emptyList()) }
        Thread.currentThread().interrupt()
        try {
            pipeline.accept(FloatArray(28 * rate), 28 * rate)
            fail("Cancelled audio must not start inference")
        } catch (error: UserFacingException) {
            assertEquals(Failure.CANCELLED, error.failure)
        } finally { Thread.interrupted() }
        assertEquals(0, calls)
    }
    @Test fun restoredBorrowedBufferTailContinuesWithoutRepeatingCompletedWindows() {
        val calls = mutableListOf<Long>()
        val recognize: (PcmAudio, Long) -> Transcript = { audio, start ->
            calls.add(start)
            Transcript((0 until audio.samples.size / rate).map { second ->
                Word("voice", second * 1000L, second * 1000L + 600, "word${audio.samples[second * rate].toInt()}")
            })
        }
        val audio = FloatArray(70 * rate) { (it / rate).toFloat() }
        val original = IncrementalTranscript(recognize = recognize)
        val split = 28 * rate + 177
        original.accept(audio.copyOfRange(0, split), split)
        val saved = org.json.JSONObject(original.checkpoint().toString())
        assertEquals(28000L, original.doneMs)
        val restored = IncrementalTranscript(saved, recognize)
        assertEquals(split.toLong(), restored.positionSamples)
        restored.accept(audio.copyOfRange(split, audio.size), audio.size - split)
        val result = restored.finish()
        assertEquals(listOf(0L, 26000L, 52000L), calls)
        assertEquals((0 until 70).map { "word$it" }, result.words.map { it.text })
    }

}
