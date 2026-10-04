package org.cassini.android

import org.junit.Assert.*
import org.junit.Test

/** Inputs and expectations mirror the desktop pipeline's overlap-dedup tests. */
class SeamMergeTest {
    private fun w(text: String, startMs: Long, endMs: Long) = Word("a", startMs, endMs, text)
    private fun merge(acc: List<Word>, next: List<Word>, windowStartMs: Long, overlapMs: Long, first: Boolean = false) =
        SeamMerge.merge(acc, next, first, windowStartMs, overlapMs)
    private fun mergeFallback(acc: List<Word>, next: List<Word>, windowStartMs: Long, overlapMs: Long, first: Boolean = false) =
        SeamMerge.merge(acc, next, first, windowStartMs, overlapMs, midpointOnDisagreement = true)

    @Test fun keepsOverlapWordOnce() {
        val acc = listOf(w("hello", 1000, 1400), w("world", 5000, 5400), w("shared", 14600, 14760), w("late", 14800, 14980))
        val next = listOf(w("shared", 14600, 14760), w("late", 14800, 14980), w("again", 16000, 16400), w("more", 20000, 20400))
        val got = merge(acc, next, 14500, 500)
        assertEquals(listOf(w("hello", 1000, 1400), w("world", 5000, 5400), w("shared", 14600, 14760),
            w("late", 14800, 14980), w("again", 16000, 16400), w("more", 20000, 20400)), got)
        // "shared" has more left-window context, "late" more right-window context.
        assertSame(acc[2], got[2])
        assertSame(next[1], got[3])
    }

    @Test fun handlesTimestampJitterAcrossOldMidpoint() {
        for ((accStart, nextStart) in listOf(9740L to 9760L, 9760L to 9740L)) {
            val acc = listOf(w("before", 9000, 9200), w("Shared,", accStart, accStart + 100))
            val next = listOf(w("shared", nextStart, nextStart + 100), w("after", 10200, 10400))
            val got = merge(acc, next, 9500, 500)
            assertEquals(3, got.size)
            assertEquals(1, got.count { SeamMerge.normalize(it.text) == "shared" })
        }
    }

    @Test fun retainsOneSidedOverlapWords() {
        val acc = listOf(w("before", 9000, 9200), w("old-only", 9800, 9900))
        val next = listOf(w("new-only", 9700, 9800), w("after", 10200, 10400))
        assertEquals(listOf("before", "new-only", "old-only", "after"), merge(acc, next, 9500, 500).map { it.text })
    }

    @Test fun disagreeingFallbackSeamUsesMidpointOwnership() {
        val acc = listOf(w("before", 14000, 14200), w("recognize", 14600, 14900), w("old-late", 14820, 14920))
        val next = listOf(w("wreck", 14610, 14650), w("a", 14660, 14700), w("nice", 14810, 14850), w("after", 15100, 15300))
        assertEquals(listOf(acc[0], acc[1], next[2], next[3]), mergeFallback(acc, next, 14500, 500))
        // The default policy keeps one-sided lexical evidence from both hypotheses.
        assertEquals(acc.size + next.size, merge(acc, next, 14500, 500).size)
    }

    @Test fun boundaryContactDoesNotTriggerDisagreementCut() {
        val acc = listOf(w("before", 9300, 9500))
        val next = listOf(w("new-only", 9600, 9700), w("after", 10100, 10300))
        assertEquals(listOf(acc[0], next[0], next[1]), mergeFallback(acc, next, 9500, 500))
    }

    @Test fun confidentMatchKeepsOneSidedWordUnderFallbackPolicy() {
        val acc = listOf(w("shared", 9600, 9700))
        val next = listOf(w("shared", 9610, 9710), w("new-only", 9700, 9740), w("after", 10100, 10300))
        assertEquals(listOf("shared", "new-only", "after"), mergeFallback(acc, next, 9500, 500).map { it.text })
    }

    @Test fun replacesClampedBoundaryCopy() {
        val acc = listOf(w("before", 9300, 9500), w("final", 10000, 10000))
        val next = listOf(w("final", 9800, 9950), w("after", 10100, 10300))
        assertEquals(listOf(w("before", 9300, 9500), w("final", 9800, 9950), w("after", 10100, 10300)), merge(acc, next, 9500, 500))
    }

    @Test fun alignsShiftedPhrase() {
        val acc = listOf(w("I", 9400, 9500), w("can", 9500, 9660), w("pass", 9660, 9820), w("it", 9820, 9980))
        val next = listOf(w("pass", 9960, 10120), w("it", 10120, 10280), w("to", 10280, 10440))
        assertEquals(listOf("I", "can", "pass", "it", "to"), merge(acc, next, 9500, 500).map { it.text })
    }

    @Test fun keepsRapidRepeatedSingleton() {
        val acc = listOf(w("yes", 9550, 9650))
        val next = listOf(w("yes", 9900, 10000))
        assertEquals(listOf(acc[0], next[0]), merge(acc, next, 9500, 500))
    }

    @Test fun keepsRapidZeroLengthNextSingleton() {
        val acc = listOf(w("yes", 9600, 9700))
        val next = listOf(w("yes", 10000, 10000))
        assertEquals(listOf(acc[0], next[0]), merge(acc, next, 9500, 500))
    }

    @Test fun preservesSemanticPunctuation() {
        val acc = listOf(w("C++", 9700, 9800))
        val next = listOf(w("C#", 9710, 9810), w("C", 9720, 9820))
        assertEquals(3, merge(acc, next, 9500, 500).size)
        for ((word, want) in listOf("C++" to "c++", "C#" to "c#", "a-b" to "a-b", "ab" to "ab", "\u201CDON\u2019T!\u201D" to "don't",
            "morning." to "morning", "morning," to "morning", ".NET" to ".net", "3.14" to "3.14")) {
            assertEquals(word, want, SeamMerge.normalize(word))
        }
    }

    /** Expectations produced by running the Go normalizer on the same strings. */
    @Test fun normalizeMatchesGoUnicodeHandling() {
        for ((word, want) in listOf(
            "Perché?" to "perché", "È" to "è", "dell\u2019" to "dell'", "L\u2019Aquila," to "l'aquila", "\u2018Sì\u2019" to "'sì'",
            "po'" to "po'", "«Ciao»" to "«ciao»", "E\u0300" to "e\u0300", "\u00A0più\u00A0" to "più", "\u2007x\u202F" to "x",
            "\u001Cok" to "\u001Cok", "\u200Bx" to "\u200Bx", "²" to "²", "½." to "½", "Ⅻ" to "ⅻ", "İstanbul" to "istanbul",
            "ΣΟΦΟΣ" to "σοφοσ", "\uD801\uDC00" to "\uD801\uDC28", "..." to "", "\u201C?\u201D" to "", "morning,." to "morning,",
            "(nota.)" to "nota",
        )) assertEquals(word, want, SeamMerge.normalize(word))
    }

    @Test fun ignoresSentenceEndPunctuation() {
        val acc = listOf(w("Sunday", 85430, 85830), w("morning.", 85830, 86040))
        val next = listOf(w("Sunday", 85520, 85840), w("morning,", 85840, 86160), w("first", 86160, 86320))
        val got = merge(acc, next, 85540, 500)
        assertEquals(3, got.size)
        assertEquals("sunday", SeamMerge.normalize(got[0].text))
        assertEquals("morning", SeamMerge.normalize(got[1].text))
        assertEquals("first", got[2].text)
    }

    @Test fun italianApostropheVariantsMergeOnce() {
        val acc = listOf(w("prima", 9000, 9200), w("dell\u2019anno.", 9700, 9900))
        val next = listOf(w("dell'anno,", 9720, 9920), w("dopo", 10100, 10300))
        // The newer copy's midpoint sits 320 ms past the window start versus 200 ms before the overlap end.
        assertEquals(listOf("prima", "dell'anno,", "dopo"), merge(acc, next, 9500, 500).map { it.text })
    }

    @Test fun keepsStableSourceOrderForEqualStarts() {
        val acc = listOf(w("old", 9800, 10000))
        val next = listOf(w("new", 9800, 9900))
        assertEquals(listOf(acc[0], next[0]), merge(acc, next, 9500, 500))
    }

    @Test fun firstWindowVerbatim() {
        val next = listOf(w("a", 0, 100), w("b", 200, 300))
        assertEquals(next, merge(emptyList(), next, 0, 500, first = true))
    }

    @Test fun expandedVadTurnSeamEmitsSharedWordOnce() {
        // Context crops [0, 24000) and [16000, 40000) at 16 kHz: the seam starts at 1000 ms and overlaps 500 ms.
        val older = listOf(w("yes.", 1200, 1350))
        val newer = listOf(w("yes,", 1210, 1360), w("next", 1600, 1800))
        val got = merge(older, newer, 16000L / 16, (24000L - 16000) / 16)
        assertEquals(2, got.size)
        assertEquals("yes", SeamMerge.normalize(got[0].text))
        assertEquals("next", got[1].text)
    }

    /** Model-free analogue of the chunked fallback: a word per second decoded by every window that covers it. */
    @Test fun chunkedFallbackEndToEndKeepsEverySecondOnce() {
        for (rate in listOf(16000, 48000)) {
            val total = 75 * rate
            val window = 15 * rate
            val overlap = rate / 2
            val overlapMs = overlap.toLong() * 1000 / rate
            val bounds = ArrayList<Pair<Int, Int>>()
            var start = 0
            while (start < total) {
                val end = start + window
                if (end >= total) { bounds += start to total; break }
                bounds += start to end
                start += window - overlap
            }
            assertTrue(bounds.size >= 2)
            var merged = emptyList<Word>()
            bounds.forEachIndexed { index, (from, to) ->
                val startMs = from.toLong() * 1000 / rate
                val endMs = to.toLong() * 1000 / rate
                val words = generateSequence((startMs / 1000) * 1000) { it + 1000 }.takeWhile { it < endMs }
                    .filter { it >= startMs }.map { w("w${it / 1000}", it, it + 200) }.toList()
                merged = mergeFallback(merged, words, startMs, overlapMs, first = index == 0)
            }
            assertEquals((0 until 75).map { "w$it" }, merged.map { it.text })
            assertTrue(merged.zipWithNext().all { (a, b) -> a.startMs <= b.startMs })
        }
    }

    @Test fun alignmentPrefersMoreMatchesThenSmallerDistance() {
        val acc = listOf(w("si", 9600, 9700), w("si", 9800, 9900))
        val next = listOf(w("si", 9790, 9890))
        assertEquals(listOf(SeamMerge.Match(1, 0)), SeamMerge.align(acc, next, 9500, 10000, SeamMerge.DUPLICATE_TOLERANCE_MS))
        assertEquals(1L, SeamMerge.midpointMs(w("x", 1, 2)))
        assertEquals(5L, SeamMerge.midpointMs(w("x", 5, 5)))
    }

    // A forced cut at 11.0 s decoded with a second of the recording on each side: the overlap is 10.0 to 12.0 s.
    private fun splice(acc: List<Word>, next: List<Word>) = SeamMerge.splice(acc, next, 10_000, 2_000)

    @Test fun aWordTheCutRunsThroughIsKeptOnceAndEachEdgeComesFromTheOtherDecode() {
        val acc = listOf(w("prima", 9_000, 9_400), w("la", 10_600, 10_750), w("batterica.", 10_780, 11_300), w("poi", 11_350, 11_600),
            w("tronc", 11_800, 12_000))
        val next = listOf(w("ella", 10_050, 10_300), w("la", 10_620, 10_760), w("batterica.", 10_800, 11_310), w("poi", 11_360, 11_610),
            w("troncato", 11_790, 12_300), w("dopo", 12_350, 12_600))
        val got = splice(acc, next)
        assertEquals(listOf("prima", "la", "batterica.", "poi", "troncato", "dopo"), got.map { it.text })
        // Joined at the agreed word nearest the cut: the earlier decode up to it, the later one after it.
        assertSame(acc[2], got[2])
        assertSame(next[3], got[3])
    }

    @Test fun twoReadingsOfTheSameAudioAtACutKeepOne() {
        val got = splice(listOf(w("della", 10_400, 10_700), w("batterica.", 10_780, 11_300)),
            listOf(w("della", 10_410, 10_700), w("batteria", 10_790, 11_250), w("dopo", 11_400, 11_700)))
        assertEquals(listOf("della", "batteria", "dopo"), got.map { it.text })
    }

    @Test fun aWordTimedOnOppositeSidesOfTheCutIsNeitherLostNorDoubled() {
        // The same short word 240 ms apart: too far for a lone pair in the window merge, and with no time in common.
        for ((early, late) in listOf(11_040L to 10_800L, 10_800L to 11_040L)) {
            val got = splice(listOf(w("prima", 9_000, 9_400), w("sì", early, early + 80)), listOf(w("sì", late, late + 80), w("dopo", 12_100, 12_400)))
            assertEquals(listOf("prima", "sì", "dopo"), got.map { it.text })
        }
    }

    @Test fun oneReadingOfASplitWordIsTakenWhole() {
        // One decode hears a word, the other two shorter ones: never half of each.
        val acc = listOf(w("che", 10_300, 10_500), w("alimento", 10_800, 11_240))
        val next = listOf(w("che", 10_310, 10_500), w("a", 10_840, 10_920), w("lento", 10_920, 11_280), w("dopo", 11_500, 11_800))
        assertEquals(listOf("che", "a", "lento", "dopo"), splice(acc, next).map { it.text })
        // Without any agreed word the join is an instant outside every word: again one whole reading.
        val alone = splice(listOf(w("alimento", 10_800, 11_240)), listOf(w("a", 10_840, 10_920), w("lento", 10_920, 11_280)))
        assertTrue(alone.map { it.text }.toString(), alone.map { it.text } in listOf(listOf("alimento"), listOf("a", "lento")))
    }

    @Test fun wordsOnlyOneDecodeFoundStayOnItsOwnSideOfTheJoin() {
        val acc = listOf(w("prima", 10_200, 10_500), w("fantasma", 11_500, 11_700))
        val next = listOf(w("eco", 10_550, 10_700), w("dopo", 11_800, 12_100))
        val got = splice(acc, next).map { it.text }
        assertTrue(got.toString(), got.first() == "prima" && got.last() == "dopo" && got.size in 2..3)
        assertEquals(listOf("solo"), splice(listOf(w("solo", 10_200, 10_500)), emptyList()).map { it.text })
    }

    @Test fun aRepeatedWordShiftedByMoreThanTheLoneToleranceIsStillOneWordAtACut() {
        // Measured on the phone: copies of one word 220 ms apart, above the 200 ms the desktop accepts for a lone pair.
        val got = splice(listOf(w("di", 10_368, 10_448), w("cronometro.", 10_448, 10_928)), listOf(w("Cronometro", 10_668, 11_228), w("che", 11_228, 11_388)))
        assertEquals(listOf("di", "cronometro", "che"), got.map { SeamMerge.normalize(it.text) })
    }

    @Test fun aZeroLengthCopyAtTheJoinGivesWayToTheOtherDecodesCopyWithDuration() {
        // The earlier decode stamped "sì" in padding and it was clamped to the overlap end; the later one heard it whole.
        val clamped = w("sì", 12_000, 12_000)
        val whole = w("sì", 11_800, 12_320)
        val got = splice(listOf(w("prima", 9_000, 9_400), clamped), listOf(whole, w("dopo", 12_400, 12_700)))
        assertEquals(listOf("prima", "sì", "dopo"), got.map { it.text })
        assertSame(whole, got[1])
        // The other way round the copy with duration is already the earlier decode's and stays.
        val kept = w("sì", 11_800, 12_320)
        val back = splice(listOf(w("prima", 9_000, 9_400), kept), listOf(w("sì", 12_000, 12_000), w("dopo", 12_400, 12_700)))
        assertSame(kept, back[1])
        assertEquals(3, back.size)
    }
}
