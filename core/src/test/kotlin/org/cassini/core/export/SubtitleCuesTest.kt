package org.cassini.core.export

import org.cassini.core.format.Word
import org.junit.Assert.assertEquals
import org.junit.Test

class SubtitleCuesTest {
    private fun cues(words: List<Word>, srt: Boolean = false) = SubtitleCues.build(transcript(words), srt)

    private fun texts(words: List<Word>, srt: Boolean = false) = cues(words, srt).map { it.text }

    @Test fun noWordsGiveNoCues() {
        assertEquals(emptyList<Cue>(), cues(emptyList()))
    }

    @Test fun rule1SpeakerChange() {
        assertEquals(listOf("Sì Bene"), texts(listOf(w("spk_0", 0, 400, "Sì"), w("spk_0", 500, 900, "Bene"))))
        assertEquals(listOf("Sì", "Bene"), texts(listOf(w("spk_0", 0, 400, "Sì"), w("spk_1", 500, 900, "Bene"))))
    }

    @Test fun rule2PauseOfAtLeast1500Ms() {
        assertEquals(listOf("uno due"), texts(listOf(w("spk_0", 0, 400, "uno"), w("spk_0", 1899, 2300, "due"))))
        assertEquals(listOf("uno", "due"), texts(listOf(w("spk_0", 0, 400, "uno"), w("spk_0", 1900, 2300, "due"))))
    }

    @Test fun rule3CueLongerThan6000Ms() {
        assertEquals(listOf("uno due"), texts(listOf(w("spk_0", 0, 5000, "uno"), w("spk_0", 5000, 6000, "due"))))
        assertEquals(listOf("uno", "due"), texts(listOf(w("spk_0", 0, 5000, "uno"), w("spk_0", 5000, 6001, "due"))))
    }

    @Test fun rule4TextLongerThan84() {
        val eleven = List(11) { "parola" }.joinToString(" ")
        val fits = said("spk_0", 0, "$eleven settimo x", stepMs = 100)
        assertEquals(listOf("$eleven settimo", "x"), texts(fits))
        assertEquals(84, texts(fits).first().length)
        val over = said("spk_0", 0, "$eleven ottavo_8", stepMs = 100)
        assertEquals(listOf(eleven, "ottavo_8"), texts(over))
    }

    @Test fun rule4CountsTheSrtPrefixOfASpeakersFirstCue() {
        val eleven = List(11) { "parola" }.joinToString(" ")
        // "Paolo: " (7) + 76 + " x" would be 85 characters.
        val words = said("spk_0", 0, "$eleven x", stepMs = 100) + w("spk_1", 3000, 3300, "Ok")
        assertEquals(listOf("$eleven x", "Ok"), texts(words, srt = false))
        val srt = cues(words, srt = true)
        assertEquals(listOf(eleven, "x", "Ok"), srt.map { it.text })
        assertEquals(listOf("Paolo: ", "", "Speaker 2: "), srt.map { it.prefix })
    }

    @Test fun rule4WithOneSpeakerHasNoPrefixBudget() {
        val eleven = List(11) { "parola" }.joinToString(" ")
        val one = cues(said("spk_0", 0, "$eleven x", stepMs = 100), srt = true)
        assertEquals(listOf("$eleven x"), one.map { it.text })
        assertEquals("", one.single().prefix)
    }

    @Test fun rule5SentenceEndOnceTheCueLasts1500Ms() {
        assertEquals(listOf("Sì. Bene"), texts(listOf(w("spk_0", 0, 1499, "Sì."), w("spk_0", 1499, 1800, "Bene"))))
        for (end in listOf(".", "?", "!", "…", "。", "？", "！")) {
            val words = listOf(w("spk_0", 0, 1500, "Sì$end"), w("spk_0", 1500, 1800, "Bene"))
            assertEquals(end, listOf("Sì$end", "Bene"), texts(words))
        }
        assertEquals(listOf("Sì, Bene"), texts(listOf(w("spk_0", 0, 1600, "Sì,"), w("spk_0", 1600, 1800, "Bene"))))
    }

    @Test fun shortCuesLastOneSecond() {
        val cue = cues(listOf(w("spk_0", 200, 400, "Ok"))).single()
        assertEquals(200L, cue.startMs)
        assertEquals(1200L, cue.endMs)
    }

    @Test fun theMinimumStopsAtTheSameSpeakersNextCue() {
        // Rule 3 splits the two words; the second starts 600 ms after the first.
        val split = cues(listOf(w("spk_0", 0, 200, "Ok"), w("spk_0", 600, 6700, "lunghissima")))
        assertEquals(listOf(0L to 600L, 600L to 6700L), split.map { it.startMs to it.endMs })
        // The next cue after a pause leaves the full second.
        val pause = cues(listOf(w("spk_0", 0, 200, "Ok"), w("spk_0", 1700, 1900, "Bene")))
        assertEquals(listOf(0L to 1000L, 1700L to 2700L), pause.map { it.startMs to it.endMs })
    }

    @Test fun differentSpeakersMayOverlap() {
        val overlap = cues(listOf(w("spk_0", 0, 200, "Ok"), w("spk_1", 300, 700, "Sì")))
        assertEquals(listOf(0L to 1000L, 300L to 1300L), overlap.map { it.startMs to it.endMs })
    }

    @Test fun cuesAreSortedWhileWordsKeepCanonicalOrder() {
        val words = listOf(
            w("spk_0", 5000, 5400, "seconda"),
            w("spk_0", 4900, 5600, "frase"),
            w("spk_1", 1000, 1400, "prima"),
            w("spk_1", 1500, 1900, "parte"),
        )
        val sorted = cues(words)
        assertEquals(listOf("prima parte", "seconda frase"), sorted.map { it.text })
        assertEquals(listOf(1000L to 2000L, 5000L to 6000L), sorted.map { it.startMs to it.endMs })
    }

    @Test fun equalStartsKeepCanonicalOrder() {
        val words = listOf(w("spk_1", 1000, 1400, "uno"), w("spk_0", 1000, 1400, "due"))
        assertEquals(listOf("uno", "due"), texts(words))
    }

    @Test fun srtPrefixFollowsOutputOrder() {
        // Canonical order: Speaker 2, then Paolo twice (a pause splits Paolo). Sorted: Paolo, Speaker 2, Paolo.
        val words = listOf(
            w("spk_1", 1000, 1400, "Allora"),
            w("spk_0", 0, 300, "Io"),
            w("spk_0", 400, 700, "direi"),
            w("spk_0", 2500, 2900, "comunque"),
            w("spk_0", 4500, 4900, "basta"),
        )
        val srt = cues(words, srt = true)
        assertEquals(listOf("Io direi", "Allora", "comunque", "basta"), srt.map { it.text })
        assertEquals(listOf("Paolo: ", "Speaker 2: ", "Paolo: ", ""), srt.map { it.prefix })
        assertEquals(
            listOf("Paolo: Io direi", "Speaker 2: Allora", "Paolo: comunque", "basta"),
            srt.map { it.lines.single() },
        )
    }

    @Test fun shortTextStaysOnOneLine() {
        val text = "a".repeat(42)
        assertEquals(listOf(text), SubtitleCues.lines(text))
    }

    @Test fun longTextIsBalancedOnTwoLines() {
        // 71 characters; the spaces at 28, 36 and 39 give two lines of at most 42, and 36 gives 36 + 34.
        val text = "Abbiamo deciso che la data è giusta, ma prima di chiudere servono firme"
        assertEquals(
            listOf("Abbiamo deciso che la data è giusta,", "ma prima di chiudere servono firme"),
            SubtitleCues.lines(text),
        )
        // The spaces at 20 and 22 of 43 characters balance equally (20 + 22, 22 + 20): the shorter first line wins.
        val a = "a".repeat(20)
        val c = "c".repeat(20)
        assertEquals(listOf(a, "b $c"), SubtitleCues.lines("$a b $c"))
    }

    @Test fun withoutABalancedSplitTheLastSpaceWithin42Wins() {
        val long = "x".repeat(45)
        assertEquals(listOf("Il codice è", long), SubtitleCues.lines("Il codice è $long"))
        val giant = "y".repeat(50)
        assertEquals(listOf(giant, "e poi"), SubtitleCues.lines("$giant e poi"))
        assertEquals(listOf("z".repeat(90)), SubtitleCues.lines("z".repeat(90)))
    }

    @Test fun theSplitNeverFallsInsideTheLabelPrefix() {
        val text = "Speaker 2: " + "a".repeat(35) + " " + "b".repeat(10)
        assertEquals(listOf("Speaker 2: " + "a".repeat(35), "b".repeat(10)), SubtitleCues.lines(text, from = 11))
    }
}
