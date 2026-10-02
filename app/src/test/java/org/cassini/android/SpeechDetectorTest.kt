package org.cassini.android

import com.k2fsa.sherpa.onnx.SpeechSegment
import org.junit.Assert.*
import org.junit.Test

class SpeechDetectorTest {
    /** Records the calls the feed loop makes and releases queued segments once enough windows are in. */
    private class FakeStream(val release: Map<Int, List<SpeechSegment>> = emptyMap()) : SpeechDetector.Stream {
        val calls = mutableListOf<String>()
        val windows = mutableListOf<FloatArray>()
        private val queue = ArrayDeque<SpeechSegment>()
        override fun reset() { calls += "reset" }
        override fun acceptWaveform(window: FloatArray) {
            calls += "accept"
            windows += window
            release[windows.size]?.let(queue::addAll)
        }
        override fun empty() = queue.isEmpty().also { calls += "empty" }
        override fun front() = queue.first()
        override fun pop() { queue.removeFirst() }
        override fun flush() { calls += "flush" }
    }

    @Test fun feedsExactWindowsAndZeroPadsOnlyTheLast() {
        val samples = FloatArray(16000 * 12 + 300) { (it % 977 + 1).toFloat() }
        val stream = FakeStream()
        SpeechDetector.feed(stream, samples) { fail("no segments queued") }
        assertTrue(stream.windows.all { it.size == 512 })
        assertEquals((samples.size + 511) / 512, stream.windows.size)
        val fed = stream.windows.flatMap { it.asList() }
        assertEquals(samples.asList(), fed.take(samples.size))
        assertTrue(fed.drop(samples.size).all { it == 0f })
        assertEquals(512 - 300, fed.size - samples.size)
    }

    @Test fun feedReturnsTheZeroPaddingOfTheLastWindow() {
        assertEquals(512 - 300, SpeechDetector.feed(FakeStream(), FloatArray(16000 * 12 + 300)) {})
        assertEquals(511, SpeechDetector.feed(FakeStream(), FloatArray(1)) {})
        assertEquals(0, SpeechDetector.feed(FakeStream(), FloatArray(1024)) {})
        assertEquals(0, SpeechDetector.feed(FakeStream(), FloatArray(0)) {})
    }

    /** gocassini stt_chunk_test.go TestSamplesToCeilMSMatchesActualVADTailPadding. */
    @Test fun samplesToCeilMsMatchesTheVadTailPadding() {
        assertEquals(0L, samplesToCeilMs(0, 16000))
        assertEquals(32L, samplesToCeilMs(511, 16000))
        assertEquals(500L, samplesToCeilMs(8000, 16000))
        assertEquals(0L, samplesToCeilMs(-1, 16000))
        assertEquals(0L, samplesToCeilMs(100, 0))
        assertEquals(134_218L, samplesToCeilMs(Int.MAX_VALUE, 16_000_000))
    }

    @Test fun scanReportsTheTailPaddingOfTheSixteenKilohertzCopy() {
        // 480300 samples at 48 kHz: a 160100-sample copy, 156 zeros in its last window, ceil(9.75) = 10 ms.
        val audio = PcmAudio(FloatArray(480300), 48000)
        val stream = FakeStream(mapOf(313 to listOf(SpeechSegment(159744, FloatArray(512)))))
        val spans = mutableListOf<Span>()
        val paddedTailMs = SpeechDetector.scan(stream, audio, spans::add)
        assertEquals(10L, paddedTailMs)
        assertEquals(156, stream.windows.size * 512 - 160100)
        // The span still stops at the recording's end; the padding travels separately.
        assertEquals(listOf(Span(479232, 480300)), spans)
        // The desktop clamp keeps a word starting by audioEndMs + paddedTailMs as a zero-length end word.
        val audioEndMs = audio.durationMs
        assertEquals(10006L, audioEndMs)
        assertTrue(10011 <= audioEndMs + paddedTailMs)
        assertFalse(10017 <= audioEndMs + paddedTailMs)
        // A copy ending on a window boundary has no padding: only words at audioEndMs itself survive.
        assertEquals(0L, SpeechDetector.scan(FakeStream(), PcmAudio(FloatArray(512 * 3 * 3), 48000)) {})
    }

    @Test fun resetsOnceDrainsEveryFiveSecondsThenFlushesAndDrains() {
        val samples = FloatArray(16000 * 12 + 300)
        val stream = FakeStream()
        SpeechDetector.feed(stream, samples) {}
        // 157 windows are the first to reach 80000 fed samples; the count restarts after each drain.
        val expected = listOf("reset") + List(157) { "accept" } + "empty" + List(157) { "accept" } + "empty" +
            List(stream.windows.size - 314) { "accept" } + "flush" + "empty"
        assertEquals(expected, stream.calls)
    }

    @Test fun deliversSegmentsInOrderAtTheNextDrainAndSkipsEmptyOnes() {
        val first = SpeechSegment(1000, FloatArray(4000))
        val second = SpeechSegment(30000, FloatArray(8000))
        val last = SpeechSegment(150000, FloatArray(2000))
        val stream = FakeStream(mapOf(10 to listOf(first, SpeechSegment(9000, FloatArray(0))), 200 to listOf(second), 320 to listOf(last)))
        val seen = mutableListOf<Pair<Int, Int>>()
        SpeechDetector.feed(stream, FloatArray(16000 * 12)) { seen += it.start to stream.windows.size }
        // Drains follow windows 157 and 314; the last segment comes from the drain after flush.
        assertEquals(listOf(1000 to 157, 30000 to 314, 150000 to 375), seen)
    }

    @Test fun cancellationStopsAtTheNextDrain() {
        Thread.currentThread().interrupt()
        try {
            SpeechDetector.feed(FakeStream(), FloatArray(16000 * 6)) {}
            fail("expected cancellation")
        } catch (error: UserFacingException) {
            assertEquals(Failure.CANCELLED, error.failure)
        } finally {
            Thread.interrupted()
        }
    }

    @Test fun spansMapBackToTheRecordingRate() {
        assertEquals(Span(48000, 72000), SpeechDetector.recordingSpan(16000, 8000, 48000, 480000))
        assertEquals(Span(16000, 24000), SpeechDetector.recordingSpan(16000, 8000, 16000, 480000))
        assertEquals(Span(8000, 12000), SpeechDetector.recordingSpan(16000, 8000, 8000, 480000))
        // 44.1 kHz: start rounds down (2.76 -> 2), end rounds up (5.51 -> 6).
        assertEquals(Span(2, 6), SpeechDetector.recordingSpan(1, 1, 44100, 1000))
        // The zero-padded final window never reaches past the recording.
        assertEquals(Span(47616, 48000), SpeechDetector.recordingSpan(15872, 512, 48000, 48000))
        assertNull(SpeechDetector.recordingSpan(16000, 512, 48000, 48000))
        // A long recording at 96 kHz does not overflow.
        assertEquals(Span(1_999_999_992, 1_999_999_998), SpeechDetector.recordingSpan(333_333_332, 1, 96000, 2_000_000_000))
    }

    @Test fun configMatchesTheDesktopDefaults() {
        val config = SpeechDetector.config("/models/silero_vad.onnx")
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
