package io.codemyriad.cassini.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTurnsTest {
    @Test fun emptyTranscriptHasNoTurns() {
        assertTrue(TranscriptTurns.of(emptyList()).isEmpty())
    }

    @Test fun turnsAreMaximalRunsOfOneSpeaker() {
        val words = listOf(
            w("a", 0, 300, "Yes,"),
            w("a", 400, 900, "sure."),
            w("b", 800, 1200, "Good."),
            w("a", 1500, 1900, "So"),
            w("a", 2000, 2400, "café."),
        )
        val turns = TranscriptTurns.of(words)
        assertEquals(listOf("a", "b", "a"), turns.map { it.speaker })
        assertEquals(listOf("Yes, sure.", "Good.", "So café."), turns.map { it.text })
        assertEquals(listOf(0L, 800L, 1500L), turns.map { it.startMs })
        assertEquals(listOf(900L, 1200L, 2400L), turns.map { it.endMs })
    }

    @Test fun runsKeepCanonicalAtomsAndUseAudibleBounds() {
        val words = listOf(w("a", 1000, 3000, "long"), w("a", 900, 1500, "short"))
        val turn = TranscriptTurns.of(words).single()
        assertEquals(900L, turn.startMs)
        assertEquals(3000L, turn.endMs)
        assertEquals("long short", turn.text)
    }
}
