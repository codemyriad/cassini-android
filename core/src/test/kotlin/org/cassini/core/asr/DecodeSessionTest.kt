package org.cassini.core.asr

import org.cassini.core.audio.PcmAudio
import org.cassini.core.format.TimedWord
import org.cassini.core.format.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window and retry algorithm of 0.1.0 `Parakeet.Session` over a fake recognizer. The fake hears a word wherever
 * the audio holds a burst of non-zero samples: word `wN` is a burst of value N + 1, 300 ms long, a quarter second
 * into second N. Its times are relative to the decoded input, head padding included, as the real decoder's are.
 */
class DecodeSessionTest {
    private val rate = 16_000

    private class Call(val start: Int, val length: Int, val headPad: Int, val tailPad: Int)

    private class FakeDecoder(private val rate: Int, private val refuse: (Call) -> Boolean = { false }) : WordDecoder {
        val calls = mutableListOf<Call>()
        override fun decode(audio: PcmAudio, start: Int, length: Int, headPad: Int, tailPad: Int): List<TimedWord> {
            val call = Call(start, length, headPad, tailPad)
            calls += call
            if (refuse(call)) return emptyList()
            val words = mutableListOf<TimedWord>()
            var p = start
            while (p < start + length) {
                val value = audio.samples[p]
                if (value == 0f) {
                    p++
                    continue
                }
                val first = p
                while (p < start + length && audio.samples[p] == value) p++
                val startMs = (headPad + first - start) * 1000L / rate
                val endMs = (headPad + p - start) * 1000L / rate
                words += TimedWord(Word("spk_1", startMs, endMs, "w${value.toInt() - 1}"), endMs)
            }
            return words
        }
    }

    /** [seconds] of audio with a word in each second listed in [spoken]. */
    private fun audio(seconds: Int, spoken: Iterable<Int> = 0 until seconds) = PcmAudio(
        FloatArray(seconds * rate).also { samples ->
            for (n in spoken) samples.fill((n + 1).toFloat(), n * rate + rate / 4, n * rate + rate / 4 + rate * 3 / 10)
        },
        rate
    )

    private fun texts(words: List<TimedWord>) = words.map { it.word.text }

    @Test fun wholeDecodesTheRecordingOnce() {
        val audio = audio(12)
        val decoder = FakeDecoder(rate)
        val words = DecodeSession(decoder, audio, DecodePolicy.WHOLE_SPANS).whole()
        assertEquals(1, decoder.calls.size)
        with(decoder.calls.single()) {
            assertEquals(listOf(0, audio.samples.size, 0, 0), listOf(start, length, headPad, tailPad))
        }
        assertEquals((0 until 12).map { "w$it" }, words.map { it.text })
        assertEquals((0 until 12).map { it * 1000L + 250 }, words.map { it.startMs })
    }

    @Test fun fixedWindowsKeepEachWordOnceOnTheRecordingClock() {
        val audio = audio(40)
        val decoder = FakeDecoder(rate)
        val session = DecodeSession(decoder, audio, DecodePolicy.WHOLE_SPANS)
        val reached = mutableListOf<Int>()
        session.fixed { reached += it }
        assertEquals(SpeechWindows.fixed(audio.samples.size, rate).size, decoder.calls.size)
        assertEquals((0 until 40).map { "w$it" }, texts(session.words))
        assertEquals((0 until 40).map { it * 1000L + 250 }, session.words.map { it.word.startMs })
        assertEquals(reached.sorted(), reached)
        assertEquals(audio.samples.size, reached.last())
    }

    @Test fun quietCuttingSplitsALongSpanAtQuietPointsWithContext() {
        val audio = audio(70)
        val decoder = FakeDecoder(rate)
        val session = DecodeSession(decoder, audio, DecodePolicy.WHOLE_SPANS)
        val reached = mutableListOf<Int>()
        session.speech(Span(0, audio.samples.size)) { reached += it }
        val pieces = SpeechWindows.quietPieces(audio.samples, Span(0, audio.samples.size), rate)
        assertTrue("A 70 s span is cut", pieces.size > 1)
        assertTrue(decoder.calls.size >= pieces.size)
        // Neighbouring pieces are decoded with recorded context past the cut.
        assertTrue(decoder.calls.drop(1).any { call -> pieces.drop(1).any { call.start < it.start } })
        assertEquals((0 until 70).map { "w$it" }, texts(session.words))
        assertEquals(audio.samples.size, reached.last())
    }

    @Test fun speechSpansAreDecodedOneByOneInOrder() {
        val audio = audio(30, listOf(1, 2, 3, 10, 11, 20))
        val decoder = FakeDecoder(rate)
        val session = DecodeSession(decoder, audio, DecodePolicy.WHOLE_SPANS)
        val spans = listOf(Span(rate, 4 * rate), Span(10 * rate, 12 * rate), Span(20 * rate, 21 * rate))
        for (span in spans) session.speech(span) {}
        assertEquals(spans.map { it.start }, decoder.calls.map { it.start })
        assertTrue("A detected span gets synthetic tail silence", decoder.calls.all { it.tailPad > 0 })
        assertEquals(listOf("w1", "w2", "w3", "w10", "w11", "w20"), texts(session.words))
        assertEquals(listOf(1250L, 2250L, 3250L, 10250L, 11250L, 20250L), session.words.map { it.word.startMs })
    }

    @Test fun aSpanThatDecodesToNothingIsRetriedWithRecordedContext() {
        // A word at 4.25 s inside the detected span, and one at 3.25 s that only the retry's context reaches.
        val audio = audio(10, listOf(3, 4))
        val span = Span(3 * rate + rate * 6 / 10, 5 * rate)
        val decoder = FakeDecoder(rate) { it.start == span.start }
        val session = DecodeSession(decoder, audio, DecodePolicy.WHOLE_SPANS)
        session.speech(span) {}
        assertEquals(2, decoder.calls.size)
        val retry = decoder.calls[1]
        val context = SpeechWindows.samples(DecodeSession.RETRY_CONTEXT_MS, rate)
        assertEquals(span.start - context, retry.start)
        // Only words overlapping the detected speech are kept: w3 ends before the span starts.
        assertEquals(listOf("w4"), texts(session.words))
        assertEquals(4250L, session.words.single().word.startMs)
    }

    @Test fun aSpanWithNoWordsAtAllStaysEmptyAfterOneRetry() {
        val audio = audio(10, emptyList())
        val decoder = FakeDecoder(rate)
        val session = DecodeSession(decoder, audio, DecodePolicy.WHOLE_SPANS)
        session.speech(Span(2 * rate, 3 * rate)) {}
        assertEquals(2, decoder.calls.size)
        assertEquals(emptyList<String>(), texts(session.words))
    }

    @Test fun withoutSpanPreservationThereIsNoRetry() {
        val audio = audio(10, emptyList())
        val decoder = FakeDecoder(rate)
        val session = DecodeSession(decoder, audio, DecodePolicy())
        session.speech(Span(2 * rate, 3 * rate)) {}
        assertEquals(1, decoder.calls.size)
    }
}
