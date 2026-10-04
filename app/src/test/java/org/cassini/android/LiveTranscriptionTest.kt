package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class LiveTranscriptionTest {
    private fun signal(ms: Int) = ShortArray(ms * 16) { 1000 }

    @Test fun showsWordsBeforeStopAndFlushesTheTailOnTheRecordingClock() {
        val audio = LiveAudioBuffer(maxSeconds = 10)
        audio.append(signal(5000))
        val preview = CountDownLatch(1)
        val result = AtomicReference<Transcript>()
        val failure = AtomicReference<Throwable>()
        val progress = mutableListOf<Parakeet.Progress>()
        var calls = 0
        val worker = Thread {
            try {
                result.set(LiveTranscription.transcribe(audio, { pcm, padding ->
                    assertEquals(8000, padding)
                    if (calls++ == 0) {
                        assertEquals(5000, pcm.durationMs)
                        listOf(TimedWord(Word("spk_1", 4300, 4600, "Hello")))
                    } else {
                        assertEquals(2500, pcm.durationMs)
                        listOf(TimedWord(Word("spk_1", 300, 600, "Hello")), TimedWord(Word("spk_1", 2000, 2300, "world.")))
                    }
                }) { progress.add(it); preview.countDown() })
            } catch (error: Throwable) { failure.set(error) }
        }
        worker.start()
        try {
            assertTrue("A decode must arrive while capture is still open", preview.await(5, TimeUnit.SECONDS))
            assertNull(result.get())
            assertFalse(audio.finished)
            assertEquals("Hello", progress.first().words.single().text)
            audio.append(signal(1500))
            audio.finish()
            worker.join(5000)
            assertFalse("Final flush must drain and stop", worker.isAlive)
            failure.get()?.let { throw AssertionError(it) }
            assertEquals(listOf("Hello", "world."), result.get().words.map { it.text })
            assertEquals(6000, result.get().words.last().startMs)
            assertTrue(result.get().words.all { it.endMs <= 6500 })
            assertEquals(2, calls)
        } finally { audio.cancel(); worker.interrupt(); worker.join(1000) }
    }

    @Test fun disagreeingBoundaryWordKeepsOneReadingAfterAnAlignedAnchor() {
        val audio = LiveAudioBuffer(maxSeconds = 10)
        audio.append(signal(10000)); audio.finish()
        var calls = 0
        val result = LiveTranscription.transcribe(audio, { _, _ ->
            if (calls++ == 0) listOf(
                TimedWord(Word("spk_1", 3500, 3800, "Catena")),
                TimedWord(Word("spk_1", 4100, 4300, "della")),
                TimedWord(Word("spk_1", 4400, 4800, "sentinelle.")),
            ) else listOf(
                TimedWord(Word("spk_1", 100, 300, "della")),
                TimedWord(Word("spk_1", 400, 800, "sentinella")),
                TimedWord(Word("spk_1", 1300, 1600, "vanta")),
            )
        }) {}
        assertEquals(listOf("Catena", "della", "sentinella", "vanta"), result.words.map { it.text })
    }

    @Test fun exactChunkBoundaryDoesNotDecodeTheOverlapAgain() {
        val audio = LiveAudioBuffer(maxSeconds = 5)
        audio.append(signal(5000)); audio.finish()
        var calls = 0
        val transcript = LiveTranscription.transcribe(audio, { _, _ -> calls++; emptyList() }) {}
        assertEquals(1, calls)
        assertTrue(transcript.words.isEmpty())
    }

    @Test fun failedInferenceLeavesProducerAndSamplesUsable() {
        val audio = LiveAudioBuffer(maxSeconds = 10)
        audio.append(signal(5000))
        val failure = IllegalStateException("Decoder failed")
        try { LiveTranscription.transcribe(audio, { _, _ -> throw failure }) {}; fail("Expected decoder failure") }
        catch (actual: IllegalStateException) { assertSame(failure, actual) }
        assertEquals(16000, audio.append(signal(1000)))
        audio.finish()
        assertEquals(6000, audio.snapshot().durationMs)
    }

    @Test fun cancelledCaptureCannotBecomeACompletedTranscript() {
        val audio = LiveAudioBuffer(maxSeconds = 5)
        audio.append(signal(500)); audio.cancel()
        try { LiveTranscription.transcribe(audio, { _, _ -> fail("No decoding after cancellation"); emptyList() }) {}; fail("Expected cancellation") }
        catch (expected: UserFacingException) { assertEquals(Failure.CANCELLED, expected.failure) }
    }
}
