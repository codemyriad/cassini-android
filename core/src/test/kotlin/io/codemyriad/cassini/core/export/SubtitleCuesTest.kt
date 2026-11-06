package io.codemyriad.cassini.core.export

import io.codemyriad.cassini.core.format.Word
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCuesTest {
    private fun cues(words: List<Word>, srt: Boolean = false) = SubtitleCues.build(transcript(words), srt)

    private fun texts(words: List<Word>, srt: Boolean = false) = cues(words, srt).map { it.text }

    @Test fun noWordsGiveNoCues() {
        assertEquals(emptyList<Cue>(), cues(emptyList()))
    }

    @Test fun rule1SpeakerChange() {
        assertEquals(listOf("Yes Good"), texts(listOf(w("spk_0", 0, 400, "Yes"), w("spk_0", 500, 900, "Good"))))
        assertEquals(listOf("Yes", "Good"), texts(listOf(w("spk_0", 0, 400, "Yes"), w("spk_1", 500, 900, "Good"))))
    }

    @Test fun rule2PauseOfAtLeast1500Ms() {
        assertEquals(listOf("one two"), texts(listOf(w("spk_0", 0, 400, "one"), w("spk_0", 1899, 2300, "two"))))
        assertEquals(listOf("one", "two"), texts(listOf(w("spk_0", 0, 400, "one"), w("spk_0", 1900, 2300, "two"))))
    }

    @Test fun rule3CueLongerThan6000Ms() {
        assertEquals(listOf("one two"), texts(listOf(w("spk_0", 0, 5000, "one"), w("spk_0", 5000, 6000, "two"))))
        assertEquals(listOf("one", "two"), texts(listOf(w("spk_0", 0, 5000, "one"), w("spk_0", 5000, 6001, "two"))))
    }

    @Test fun rule4TextLongerThan84() {
        val eleven = List(11) { "sample" }.joinToString(" ")
        val fits = said("spk_0", 0, "$eleven seventh x", stepMs = 100)
        assertEquals(listOf("$eleven seventh", "x"), texts(fits))
        assertEquals(84, texts(fits).first().length)
        val over = said("spk_0", 0, "$eleven eighth_8", stepMs = 100)
        assertEquals(listOf(eleven, "eighth_8"), texts(over))
    }

    @Test fun rule4CountsTheSrtPrefixOfASpeakersFirstCue() {
        val eleven = List(11) { "sample" }.joinToString(" ")
        // "Paolo: " (7) + 76 + " x" would be 85 characters.
        val words = said("spk_0", 0, "$eleven x", stepMs = 100) + w("spk_1", 3000, 3300, "Ok")
        assertEquals(listOf("$eleven x", "Ok"), texts(words, srt = false))
        val srt = cues(words, srt = true)
        assertEquals(listOf(eleven, "x", "Ok"), srt.map { it.text })
        assertEquals(listOf("Paolo: ", "", "Speaker 2: "), srt.map { it.prefix })
    }

    @Test fun rule4WithOneSpeakerHasNoPrefixBudget() {
        val eleven = List(11) { "sample" }.joinToString(" ")
        val one = cues(said("spk_0", 0, "$eleven x", stepMs = 100), srt = true)
        assertEquals(listOf("$eleven x"), one.map { it.text })
        assertEquals("", one.single().prefix)
    }

    @Test fun rule5SentenceEndOnceTheCueLasts1500Ms() {
        assertEquals(listOf("Yes. Good"), texts(listOf(w("spk_0", 0, 1499, "Yes."), w("spk_0", 1499, 1800, "Good"))))
        for (end in listOf(".", "?", "!", "…", "。", "？", "！")) {
            val words = listOf(w("spk_0", 0, 1500, "Yes$end"), w("spk_0", 1500, 1800, "Good"))
            assertEquals(end, listOf("Yes$end", "Good"), texts(words))
        }
        assertEquals(listOf("Yes, Good"), texts(listOf(w("spk_0", 0, 1600, "Yes,"), w("spk_0", 1600, 1800, "Good"))))
    }

    @Test fun shortCuesLastOneSecond() {
        val cue = cues(listOf(w("spk_0", 200, 400, "Ok"))).single()
        assertEquals(200L, cue.startMs)
        assertEquals(1200L, cue.endMs)
    }

    @Test fun theMinimumStopsAtTheSameSpeakersNextCue() {
        // Rule 3 splits the two words; the second starts 600 ms after the first.
        val split = cues(listOf(w("spk_0", 0, 200, "Ok"), w("spk_0", 600, 6700, "lengthyword")))
        assertEquals(listOf(0L to 600L, 600L to 6700L), split.map { it.startMs to it.endMs })
        // The next cue after a pause leaves the full second.
        val pause = cues(listOf(w("spk_0", 0, 200, "Ok"), w("spk_0", 1700, 1900, "Good")))
        assertEquals(listOf(0L to 1000L, 1700L to 2700L), pause.map { it.startMs to it.endMs })
    }

    @Test fun differentSpeakersMayOverlap() {
        val overlap = cues(listOf(w("spk_0", 0, 200, "Ok"), w("spk_1", 300, 700, "Yes")))
        assertEquals(listOf(0L to 1000L, 300L to 1300L), overlap.map { it.startMs to it.endMs })
    }

    @Test fun cuesAreSortedWhileWordsKeepCanonicalOrder() {
        val words = listOf(
            w("spk_0", 5000, 5400, "second"),
            w("spk_0", 4900, 5600, "phrase"),
            w("spk_1", 1000, 1400, "first"),
            w("spk_1", 1500, 1900, "part"),
        )
        val sorted = cues(words)
        assertEquals(listOf("first part", "second phrase"), sorted.map { it.text })
        assertEquals(listOf(1000L to 2000L, 5000L to 6000L), sorted.map { it.startMs to it.endMs })
    }

    @Test fun equalStartsKeepCanonicalOrder() {
        val words = listOf(w("spk_1", 1000, 1400, "one"), w("spk_0", 1000, 1400, "two"))
        assertEquals(listOf("one", "two"), texts(words))
    }

    @Test fun srtPrefixFollowsOutputOrder() {
        // Canonical order: Speaker 2, then Paolo twice (a pause splits Paolo). Sorted: Paolo, Speaker 2, Paolo.
        val words = listOf(
            w("spk_1", 1000, 1400, "So"),
            w("spk_0", 0, 300, "I"),
            w("spk_0", 400, 700, "suggest"),
            w("spk_0", 2500, 2900, "anyway"),
            w("spk_0", 4500, 4900, "enough"),
        )
        val srt = cues(words, srt = true)
        assertEquals(listOf("I suggest", "So", "anyway", "enough"), srt.map { it.text })
        assertEquals(listOf("Paolo: ", "Speaker 2: ", "Paolo: ", ""), srt.map { it.prefix })
        assertEquals(
            listOf("Paolo: I suggest", "Speaker 2: So", "Paolo: anyway", "enough"),
            srt.map { it.lines.single() },
        )
    }

    @Test fun shortTextStaysOnOneLine() {
        val text = "a".repeat(42)
        assertEquals(listOf(text), SubtitleCues.lines(text))
    }

    @Test fun longTextIsBalancedOnTwoLines() {
        // The balanced split keeps both English lines within 42 characters.
        val text = "We agreed that the date is right, but we still need signatures to finish"
        assertEquals(
            listOf("We agreed that the date is right, but", "we still need signatures to finish"),
            SubtitleCues.lines(text),
        )
        // The spaces at 20 and 22 of 43 characters balance equally (20 + 22, 22 + 20): the shorter first line wins.
        val a = "a".repeat(20)
        val c = "c".repeat(20)
        assertEquals(listOf(a, "b $c"), SubtitleCues.lines("$a b $c"))
    }

    @Test fun withoutABalancedSplitTheLastSpaceWithin42Wins() {
        val long = "x".repeat(45)
        assertEquals(listOf("The code is", long), SubtitleCues.lines("The code is $long"))
        val giant = "y".repeat(50)
        assertEquals(listOf(giant, "e poi"), SubtitleCues.lines("$giant e poi"))
        assertEquals(listOf("z".repeat(90)), SubtitleCues.lines("z".repeat(90)))
    }

    @Test fun theSplitNeverFallsInsideTheLabelPrefix() {
        val text = "Speaker 2: " + "a".repeat(35) + " " + "b".repeat(10)
        assertEquals(listOf("Speaker 2: " + "a".repeat(35), "b".repeat(10)), SubtitleCues.lines(text, from = 11))
    }
}
