package org.cassini.core.speakers

import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeakerStatsTest {
    private fun w(speaker: String, start: Long, end: Long, text: String = "parola") = Word(speaker, start, end, text)

    @Test fun anEmptyTranscriptHasNoSpeakers() {
        assertTrue(SpeakerStats.of(emptyList()).isEmpty())
    }

    @Test fun countsComeFromTheTurns() {
        val words = listOf(
            w("b", 500, 900, "Allora,"),
            w("b", 1000, 2500, "giovedì."),
            w("a", 2600, 3000, "Sì."),
            w("b", 3100, 3600, "Bene."),
            w("a", 3700, 4000, "Certo"),
            w("a", 4100, 4600, "Élodie."),
        )
        val stats = SpeakerStats.of(words)
        assertEquals(listOf("b", "a"), stats.keys.toList())

        val b = stats.getValue("b")
        assertEquals(SpeakerStat("b", 0, (2500 - 500) + (3600 - 3100L), 2, 3, 500, SampleWindow(500, 2500)), b)

        val a = stats.getValue("a")
        assertEquals(1, a.ordinal)
        assertEquals((3000 - 2600) + (4600 - 3700L), a.speakingMs)
        assertEquals(2, a.turnCount)
        assertEquals(3, a.wordCount)
        assertEquals(2600L, a.firstMs)
        assertNull("no turn of a reaches 1 s", a.sample)
    }

    @Test fun theSampleIsTheLongestTurnCutAtTenSeconds() {
        val words = listOf(
            w("a", 0, 2000),
            w("b", 2100, 2500),
            w("a", 3000, 20_000),
            w("b", 20_100, 20_500),
            w("a", 21_000, 30_000),
        )
        assertEquals(SampleWindow(3000, 13_000), SpeakerStats.of(words).getValue("a").sample)
    }

    @Test fun aTieGoesToTheEarliestTurn() {
        val words = listOf(
            w("a", 5000, 7000),
            w("b", 7100, 7200),
            w("a", 1000, 3000),
            w("b", 3100, 3200),
            w("a", 8000, 10_000),
        )
        assertEquals(SampleWindow(1000, 3000), SpeakerStats.of(words).getValue("a").sample)
    }

    @Test fun aTurnOfExactlyOneSecondIsASample() {
        assertEquals(SampleWindow(200, 1200), SpeakerStats.of(listOf(w("a", 200, 1200))).getValue("a").sample)
        assertNull(SpeakerStats.of(listOf(w("a", 200, 1199))).getValue("a").sample)
    }

    @Test fun aTurnSpansFirstStartToLastEnd() {
        // Several words make one turn: the sample covers the whole run, not one word.
        val words = listOf(w("a", 0, 400), w("a", 500, 900), w("a", 1000, 1400))
        val stat = SpeakerStats.of(words).getValue("a")
        assertEquals(1400L, stat.speakingMs)
        assertEquals(SampleWindow(0, 1400), stat.sample)
    }

    @Test fun aDraftAppliesItsMergesFirst() {
        val transcript = Transcript(
            listOf(
                w("x1", 0, 1000),
                w("x2", 1100, 1500),
                w("x3", 1600, 2000),
                w("x1", 2100, 2500),
            ),
        )
        val stats = SpeakerStats.of(transcript, mapOf("x2" to "x1"))
        assertEquals(listOf("x1", "x3"), stats.keys.toList())
        val x1 = stats.getValue("x1")
        // x1 and the merged x2 form one turn 0..1500, then x1 again 2100..2500.
        assertEquals(2, x1.turnCount)
        assertEquals(3, x1.wordCount)
        assertEquals(1500L + 400L, x1.speakingMs)
        assertEquals(SampleWindow(0, 1500), x1.sample)
        assertEquals(1, stats.getValue("x3").ordinal)
    }

    @Test fun withoutMergesTheDraftOverloadMatchesThePlainOne() {
        val words = listOf(w("a", 0, 1000), w("b", 1000, 3000), w("a", 3000, 3500))
        assertEquals(SpeakerStats.of(words), SpeakerStats.of(Transcript(words), emptyMap()))
    }
}
