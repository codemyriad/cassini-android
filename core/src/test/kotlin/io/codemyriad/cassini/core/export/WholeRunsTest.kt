package io.codemyriad.cassini.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ReadingTurns.wholeRuns]: the speaker turns that the TXT export and `SpeakerStats` count. */
class WholeRunsTest {
    @Test fun emptyTranscriptHasNoRuns() {
        assertTrue(ReadingTurns.wholeRuns(emptyList()).isEmpty())
    }

    @Test fun runsAreMaximalRunsOfOneSpeaker() {
        val words = listOf(
            w("a", 0, 300, "Yes,"),
            w("a", 400, 900, "sure."),
            w("b", 800, 1200, "Good."),
            w("a", 1500, 1900, "So"),
            w("a", 2000, 2400, "café."),
        )
        val runs = ReadingTurns.wholeRuns(words)
        assertEquals(listOf("a", "b", "a"), runs.map { it.speaker })
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(3, 4)), runs.map { it.wordIndices })
        assertEquals(listOf(0L, 800L, 1500L), runs.map { it.startMs })
        assertEquals(listOf(900L, 1200L, 2400L), runs.map { it.endMs })
    }

    @Test fun runsKeepCanonicalAtomsAndUseAudibleBounds() {
        val words = listOf(w("a", 1000, 3000, "long"), w("a", 900, 1500, "short"))
        val run = ReadingTurns.wholeRuns(words).single()
        assertEquals(900L, run.startMs)
        assertEquals(3000L, run.endMs)
        assertEquals(listOf(0, 1), run.wordIndices)
    }
}
