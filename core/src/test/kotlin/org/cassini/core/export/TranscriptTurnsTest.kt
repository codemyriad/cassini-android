package org.cassini.core.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTurnsTest {
    @Test fun emptyTranscriptHasNoTurns() {
        assertTrue(TranscriptTurns.of(emptyList()).isEmpty())
    }

    @Test fun turnsAreMaximalRunsOfOneSpeaker() {
        val words = listOf(
            w("a", 0, 300, "Sì,"),
            w("a", 400, 900, "certo."),
            w("b", 800, 1200, "Bene."),
            w("a", 1500, 1900, "Allora"),
            w("a", 2000, 2400, "giovedì."),
        )
        val turns = TranscriptTurns.of(words)
        assertEquals(listOf("a", "b", "a"), turns.map { it.speaker })
        assertEquals(listOf("Sì, certo.", "Bene.", "Allora giovedì."), turns.map { it.text })
        assertEquals(listOf(0L, 800L, 1500L), turns.map { it.startMs })
        assertEquals(listOf(900L, 1200L, 2400L), turns.map { it.endMs })
    }

    @Test fun aTurnEndsAtItsLastWordAndKeepsCanonicalOrder() {
        val words = listOf(w("a", 1000, 3000, "lunga"), w("a", 900, 1500, "breve"))
        val turn = TranscriptTurns.of(words).single()
        assertEquals(1000L, turn.startMs)
        assertEquals(1500L, turn.endMs)
        assertEquals("lunga breve", turn.text)
    }
}
