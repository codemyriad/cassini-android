package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

class WordGateTest {
    private val rates = intArrayOf(16_000, 48_000)

    private fun timed(text: String, startMs: Long, endMs: Long, capMs: Long = 0) = TimedWord(Word("spk_1", startMs, endMs, text), capMs)
    private fun word(text: String, startMs: Long, endMs: Long) = Word("spk_1", startMs, endMs, text)

    /** [totalMs] of digital silence with steady ±0.05 energy, far above the -66 dBFS floor, over each [from, to) span. */
    private fun activeAudio(sampleRate: Int, totalMs: Long, vararg spans: LongRange): FloatArray {
        val samples = FloatArray((totalMs * sampleRate / 1000).toInt())
        spans.forEach { span ->
            var i = span.first * sampleRate / 1000
            while (i < span.last * sampleRate / 1000 && i < samples.size) {
                samples[i.toInt()] = if (i % 2 == 0L) .05f else -.05f
                i++
            }
        }
        return samples
    }

    private fun fill(samples: FloatArray, sampleRate: Int, fromMs: Int, toMs: Int, value: Float) {
        for (i in fromMs * sampleRate / 1000 until toMs * sampleRate / 1000) samples[i] = value
    }

    @Test fun clampKeepsBoundaryTokensWithinDecoderPadding() {
        val words = listOf(
            timed("within", 13000, 14000), timed("straddles", 14000, 14950), timed("boundary", 14455, 14800),
            timed("inside-padding", 14800, 14950), timed("padding-limit", 14955, 15000),
            timed("beyond-padding", 14956, 15000), timed("reversed-padding", 14800, 14799),
        )
        val got = WordGate.clampWordsToTimelineEnd(words, 14455, 500).map { it.word }
        assertEquals(listOf(
            word("within", 13000, 14000), word("straddles", 14000, 14455), word("boundary", 14455, 14455),
            word("inside-padding", 14455, 14455), word("padding-limit", 14455, 14455),
        ), got)
    }

    @Test fun clampWithoutPaddingKeepsOnlyTheExactBoundary() {
        val got = WordGate.clampWordsToTimelineEnd(listOf(timed("boundary", 1000, 1100), timed("past-boundary", 1001, 1100)), 1000, 0)
        assertEquals(listOf(word("boundary", 1000, 1000)), got.map { it.word })
    }

    @Test fun clampSaturatesAnOverflowingPaddedEnd() {
        val got = WordGate.clampWordsToTimelineEnd(listOf(timed("far", Long.MAX_VALUE - 1, Long.MAX_VALUE - 1)), 1000, Long.MAX_VALUE)
        assertEquals(listOf(word("far", 1000, 1000)), got.map { it.word })
    }

    /** Synthetic padding cannot justify reaching into it: the cap is clipped to the real PCM end too. */
    @Test fun clampClipsTheExtentCap() {
        val got = WordGate.clampWordsToTimelineEnd(listOf(
            timed("straddles", 900, 1100, 1400), timed("boundary", 1000, 1000, 1400), timed("inside", 800, 900, 950),
        ), 1000, 32)
        assertEquals(listOf(1000L, 1000L, 950L), got.map { it.capMs })
    }

    @Test fun offsetRemovesHeadPaddingThenMovesWordsAndCapsOntoTheRecordingClock() {
        val got = WordGate.offsetDecoderWords(listOf(timed("a", 100, 400, 600), timed("pad", 0, 20, 30)), 5000, 50)
        assertEquals(listOf(timed("a", 5050, 5350, 5550), timed("pad", 5000, 5000, 5000)), got)
        assertEquals(listOf(timed("a", 5100, 5400, 5600)), WordGate.offsetDecoderWords(listOf(timed("a", 100, 400, 600)), 5000, 0))
    }

    @Test fun overlappingSpeechIsStrictAtBothEdges() {
        val words = listOf(timed("before", 500, 1000), timed("into", 900, 1100), timed("inside", 1200, 1300),
            timed("at-end", 2000, 2000), timed("out", 2100, 2200))
        assertEquals(listOf("into", "inside"), WordGate.wordsOverlappingSpeech(words, 1000, 2000).map { it.word.text })
    }

    @Test fun gateRejectsSilenceAndClicksButKeepsQuietInterjections() = rates.forEach { rate ->
        val samples = FloatArray(3 * rate)
        // A quiet 30 ms utterance 50 ms before its timestamp, exactly at the -60 dBFS peak floor.
        fill(samples, rate, 950, 980, WordGate.MIN_PEAK)
        // Negative PCM counts by magnitude.
        fill(samples, rate, 1500, 1530, -.01f)
        // A lone full-scale click passes peak and RMS but not the active-duration floor.
        samples[1950 * rate / 1000] = 1f
        val words = listOf(timed("quiet", 1000, 1100), timed("negative", 1500, 1600), timed("click", 2000, 2100),
            timed("silence", 2400, 2500), timed("outside", 4000, 4100))
        assertEquals("$rate Hz", listOf(word("quiet", 1000, 1100), word("negative", 1500, 1600)), WordGate.filterWordsByEnergy(samples, rate, words))
    }

    @Test fun gateAllowsTheMeasuredDecoderLead() = rates.forEach { rate ->
        assertEquals(100L, WordGate.PRE_MARGIN_MS)
        assertEquals(200L, WordGate.POST_MARGIN_MS)
        // Parakeet has placed a word up to 180 ms before its own PCM.
        val samples = FloatArray(2 * rate).also { fill(it, rate, 780, 830, .01f) }
        assertEquals("$rate Hz", listOf(word("delayed-energy", 500, 600)),
            WordGate.filterWordsByEnergy(samples, rate, listOf(timed("delayed-energy", 500, 600))))
    }

    @Test fun gateRejectsMalformedTimestamps() = rates.forEach { rate ->
        val samples = FloatArray(rate) { .1f }
        val words = listOf(timed("max", Long.MAX_VALUE, Long.MAX_VALUE), timed("min", Long.MIN_VALUE, Long.MIN_VALUE), timed("reversed", 800, 700))
        assertTrue(WordGate.filterWordsByEnergy(samples, rate, words).isEmpty())
    }

    @Test fun finalizeClampsBeforeTheGate() = rates.forEach { rate ->
        val samples = FloatArray(rate) { .01f }
        val words = listOf(timed("straddles", 900, 1100), timed("boundary", 1000, 1200),
            timed("inside-vad-padding", 1032, 1100), timed("beyond-vad-padding", 1033, 1100))
        val want = listOf(word("straddles", 900, 1000), word("boundary", 1000, 1000), word("inside-vad-padding", 1000, 1000))
        assertEquals("$rate Hz", want, WordGate.finalizeTranscriptWords(samples, rate, words, 1000, 32))
        assertEquals("$rate Hz", want, WordGate.finalizeTranscriptWords(PcmAudio(samples, rate), words, 32))
    }

    /** The duration head saturates at 320 ms; the gate follows the speaker's audio to its real end, and not past it. */
    @Test fun gateFollowsAudioPastTheLastSpeechToken() = rates.forEach { rate ->
        val samples = activeAudio(rate, 3000, 1000L..1600L)
        assertEquals("$rate Hz", listOf(word("Okay.", 1000, 1600)), WordGate.filterWordsByEnergy(samples, rate, listOf(timed("Okay.", 1000, 1150, 2500))))
    }

    @Test fun gateStopsWhereTheAudioStops() = rates.forEach { rate ->
        val samples = activeAudio(rate, 3000, 1000L..1150L, 2400L..2600L)
        assertEquals("$rate Hz", listOf(word("Yeah.", 1000, 1150)), WordGate.filterWordsByEnergy(samples, rate, listOf(timed("Yeah.", 1000, 1150, 2500))))
    }

    @Test fun gateBridgesAClosureButNotAPause() = rates.forEach { rate ->
        assertEquals(200L, WordGate.GAP_TOLERANCE_MS)
        val spoken = listOf(timed("Okay.", 1000, 1150, 2500))
        // "o-", "-kay" after a 60 ms closure, then the next utterance where the "." is stamped.
        val closure = activeAudio(rate, 3000, 1000L..1150L, 1210L..1450L, 2400L..2600L)
        assertEquals("$rate Hz", 1450L, WordGate.filterWordsByEnergy(closure, rate, spoken).single().endMs)
        val tolerance = WordGate.GAP_TOLERANCE_MS
        val justUnder = activeAudio(rate, 3000, 1000L..1150L, (1150 + tolerance - 10)..1600L)
        assertEquals("$rate Hz", 1600L, WordGate.filterWordsByEnergy(justUnder, rate, spoken).single().endMs)
        val justOver = activeAudio(rate, 3000, 1000L..1150L, (1150 + tolerance + 10)..1600L)
        assertEquals("$rate Hz", 1150L, WordGate.filterWordsByEnergy(justOver, rate, spoken).single().endMs)
    }

    @Test fun gateNeverExceedsTheCapAndNeverExtendsAnUncappedWord() = rates.forEach { rate ->
        val samples = activeAudio(rate, 4000, 1000L..3500L)
        val got = WordGate.filterWordsByEnergy(samples, rate, listOf(timed("capped", 1000, 1150, 1400), timed("uncapped", 2000, 2150)))
        assertEquals("$rate Hz", listOf(1400L, 2150L), got.map { it.endMs })
    }

    @Test fun gateStillDropsSilentWordsWithACap() = rates.forEach { rate ->
        val samples = activeAudio(rate, 4000, 3000L..3500L)
        assertTrue(WordGate.filterWordsByEnergy(samples, rate, listOf(timed("hallucinated.", 1000, 1150, 3400))).isEmpty())
    }

    /** Seam test: the token stage decides whether a cap exists and the gate how much of it to spend. */
    @Test fun punctuationOnlyWordIsNotExtendedOverTheNextUtterance() = rates.forEach { rate ->
        // "Okay" 1000-1300 ms, a pause, then "Marco" 1600-2400 ms; the mark is stamped 100 ms before it.
        val samples = activeAudio(rate, 3500, 1000L..1300L, 1600L..2400L)
        val words = Transcript.timedWordsFromTokens(arrayOf("▁Okay", "▁…", "▁Marco", "."),
            floatArrayOf(1f, 1.5f, 1.6f, 2.6f), floatArrayOf(.3f, .32f, .32f, 0f))
        assertEquals("…", words[1].word.text)
        assertEquals(words[1].word.endMs, words[1].extentCapMs)
        val got = WordGate.filterWordsByEnergy(samples, rate, words)
        assertEquals("$rate Hz: the mark must clear the gate for this to test anything", 3, got.size)
        assertEquals(word("…", 1500, 1500), got[1])
        // The control: the spoken word in the same call is extended from 1920 ms to where its audio ends.
        assertEquals(word("Marco.", 1600, 2400), got[2])
    }

    /** A start never moves, an end never goes backwards or collapses, and an end never passes the cap. */
    @Test fun extensionPropertiesHoldForEveryLayout() = intArrayOf(16_000, 44_100, 48_000).forEach { rate ->
        val layouts = listOf(
            arrayOf(1000L..1150L), arrayOf(1000L..1600L), arrayOf(1000L..1150L, 1210L..1450L),
            arrayOf(1000L..1150L, 1400L..1450L, 1900L..2600L), arrayOf(900L..2999L),
        )
        layouts.forEachIndexed { layout, spans ->
            val samples = activeAudio(rate, 3000, *spans)
            for (endMs in longArrayOf(1050, 1150, 1320, 1470)) for (capMs in longArrayOf(0, 1000, 1155, 1600, 2500, 9000)) {
                val input = timed("w", 1000, endMs, capMs)
                val out = WordGate.filterWordsByEnergy(samples, rate, listOf(input)).singleOrNull() ?: continue
                val where = "$rate Hz layout $layout end=$endMs cap=$capMs -> ${out.startMs}-${out.endMs}"
                assertEquals(where, input.word.startMs, out.startMs)
                assertTrue(where, out.endMs >= input.word.endMs)
                assertTrue(where, out.endMs <= input.extentCapMs)
                assertTrue(where, out.endMs > out.startMs)
            }
        }
    }

    @Test fun gateWithoutAudioPassesWordsThrough() {
        assertEquals(listOf(word("a", 0, 10)), WordGate.filterWordsByEnergy(FloatArray(0), 48_000, listOf(timed("a", 0, 10, 50))))
    }

    /** The punctuation-inclusive end travels as a cap only; the end still stops at the last speech-bearing piece. */
    @Test fun tokensCarryThePunctuationEndAsACapOnly() {
        val got = Transcript.timedWordsFromTokens(arrayOf("▁Right", "?", "!", "▁U", ".", "S", ".", "▁then", ".", "▁Next"),
            floatArrayOf(1f, 3.5f, 3.52f, 3.6f, 3.72f, 3.8f, 3.96f, 4.2f, 9f, 9.1f),
            floatArrayOf(.4f, .08f, .08f, .12f, .08f, .16f, .08f, .8f, .08f, .2f))
        assertEquals(listOf("Right?!", "U.S.", "then.", "Next"), got.map { it.word.text })
        assertEquals(listOf(1400L, 3960L, 5000L, 9300L), got.map { it.word.endMs })
        assertEquals(listOf(3600L, 4040L, 9080L, 9300L), got.map { it.extentCapMs })
        assertEquals(Transcript.fromTokens(arrayOf("▁Right", "?"), floatArrayOf(1f, 3.5f), floatArrayOf(.4f, .08f)).words,
            Transcript.timedWordsFromTokens(arrayOf("▁Right", "?"), floatArrayOf(1f, 3.5f), floatArrayOf(.4f, .08f)).map { it.word })
    }

    @Test fun tokensDoNotInheritThePreviousCap() {
        // The zero-duration word shares the mark's timestamp, so a leaked cap would sit 80 ms above its end.
        val got = Transcript.timedWordsFromTokens(arrayOf("▁Done", ".", "▁mm"), floatArrayOf(1f, 9f, 9f), floatArrayOf(.56f, .08f, 0f))
        assertEquals(9080L, got[0].extentCapMs)
        assertEquals(got[1].word.endMs, got[1].extentCapMs)
        assertEquals(9000L, got[1].capMs)
    }
}
