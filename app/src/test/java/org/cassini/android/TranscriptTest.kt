package org.cassini.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TranscriptTest {
    @Test fun sessionRoundTripPreservesLanguageAndOverlappingTurnOrder() {
        val original = Transcript(listOf(Word("a", 2000, 2500, "perché?"), Word("b", 1000, 2200, "Ciao.")), "it")
        assertEquals(original, Transcript.fromJson(original.json()))
    }

    @Test fun restoredBodyTreatsNullItemsAsEmptyWithoutAssumingALanguage() {
        val restored = Transcript.fromJson("""{"format":"cassini.words.v1","items":null,"wordCount":0,"futureHint":true}""")
        assertTrue(restored.words.isEmpty())
        assertEquals("", restored.language)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsCorruptPersistedWordExtents() {
        Transcript.fromJson("""{"format":"cassini.words.v1","items":[{"speaker":"a","startMs":900,"endMs":100,"text":"ciao"}]}""")
    }

    @Test fun italianPiecesAndDelayedPunctuationKeepAcousticExtent() {
        val transcript = Transcript.fromTokens(
            arrayOf("▁C", "iao", ",", "▁per", "ché", "?", "▁Sì", "."),
            floatArrayOf(.1f, .2f, 1.5f, 1.5f, 1.6f, 3f, 3f, 5f),
            floatArrayOf(.1f, .1f, 0f, .1f, .2f, 0f, .2f, 0f),
        )
        assertEquals(listOf("Ciao,", "perché?", "Sì."), transcript.words.map { it.text })
        assertEquals(listOf(100L, 1500L, 3000L), transcript.words.map { it.startMs })
        assertEquals(listOf(300L, 1800L, 3200L), transcript.words.map { it.endMs })
        val json = JSONObject(transcript.json())
        assertEquals("cassini.words.v1", json.getString("format"))
        assertEquals("it", json.getString("language"))
        assertEquals(3, json.getInt("wordCount"))
        assertEquals("perché?", json.getJSONArray("items").getJSONObject(1).getString("text"))
    }

    @Test fun blankBoundaryAndApostropheDoNotBecomeExtraWords() {
        val transcript = Transcript.fromTokens(
            arrayOf("▁", "l", "'", "acqua", "▁è", "▁fredda", "."),
            floatArrayOf(0f, .1f, .15f, .2f, .5f, .7f, 1f), FloatArray(7) { .1f },
        )
        assertEquals(listOf("l'acqua", "è", "fredda."), transcript.words.map { it.text })
        assertTrue(transcript.words.all { it.endMs >= it.startMs })
    }

    @Test fun preservesSpeakerTurnOrderEvenWithOverlappingSpeakers() {
        val transcript = Transcript(listOf(Word("a", 2000, 2500, "Sì."), Word("b", 1000, 2200, "Ciao.")))
        val items = JSONObject(transcript.json()).getJSONArray("items")
        assertEquals("a", items.getJSONObject(0).getString("speaker"))
        assertEquals(1000L, items.getJSONObject(1).getLong("startMs"))
    }

    @Test fun silenceExportsAnEmptyValidBody() {
        val json = JSONObject(Transcript.fromTokens(emptyArray(), floatArrayOf(), floatArrayOf()).json())
        assertEquals(0, json.getInt("wordCount"))
        assertEquals(0, json.getJSONArray("items").length())
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsMissingAlignment() {
        Transcript.fromTokens(arrayOf("▁ciao"), floatArrayOf(), floatArrayOf())
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonfiniteAlignment() {
        Transcript.fromTokens(arrayOf("▁ciao"), floatArrayOf(Float.NaN), floatArrayOf(.1f))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsBackwardTimes() {
        Transcript.fromTokens(arrayOf("▁ciao", "▁ciao"), floatArrayOf(1f, .5f), floatArrayOf(.1f, .1f))
    }
}
