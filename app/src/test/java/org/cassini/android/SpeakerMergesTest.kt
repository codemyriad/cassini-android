package org.cassini.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeakerMergesTest {
    @Test fun chainedMergesApplyToCurrentAndFutureWordsWithoutChangingTheirClock() {
        val first = SpeakerMerges.merge(emptyMap(), "b", "a")
        val next = SpeakerMerges.merge(first, "a", "c")
        assertEquals(mapOf("b" to "c", "a" to "c"), next)
        val raw = Transcript(listOf(Word("b", 200, 400, "Ciao"), Word("a", 300, 500, "mondo"), Word("d", 600, 700, "!")), "it")
        val edited = SpeakerMerges.apply(raw, next)
        assertEquals(listOf("c", "c", "d"), edited.words.map { it.speaker })
        assertEquals(raw, edited.copy(words = edited.words.zip(raw.words).map { (word, original) -> word.copy(speaker = original.speaker) }))
        assertEquals("c", SpeakerMerges.apply(Transcript(listOf(Word("b", 10000, 11000, "Poi"))), next).words.single().speaker)
    }
    @Test(expected = IllegalArgumentException::class) fun anExistingMergeCannotBecomeACycle() {
        SpeakerMerges.merge(mapOf("b" to "a"), "a", "b")
    }
    @Test fun provenanceRecordsAnEditWithoutMutatingTheRecognitionRecord() {
        val before = JSONObject().put("engine", "Parakeet").put("x-inferenceMs", 23)
        val edited = SpeakerMerges.processing(before, mapOf("b" to "a"), "words")
        assertFalse(before.has("x-speakerMerges"))
        assertEquals("Parakeet", edited.getString("engine"))
        assertEquals("words", edited.getString("x-derivedFromTranscript"))
        assertEquals("a", edited.getJSONObject("x-speakerMerges").getString("b"))
    }
    @Test fun mergedVoiceClipsStayWithinTheFortyFiveSecondBudget() {
        val rate = Limits.ASR_RATE
        val clips = mapOf("a" to listOf(PrintWindow(0, 10 * rate), PrintWindow(10 * rate, 20 * rate), PrintWindow(20 * rate, 30 * rate)),
            "b" to listOf(PrintWindow(30 * rate, 40 * rate), PrintWindow(40 * rate, 50 * rate), PrintWindow(50 * rate, 60 * rate)))
        val result = SpeakerMerges.windows(clips, mapOf("b" to "a"))
        assertEquals(setOf("a"), result.keys)
        assertEquals(45 * rate, result.getValue("a").sumOf { it.endSample - it.startSample })
        assertTrue(result.getValue("a").all { it.endSample - it.startSample <= 10 * rate })
    }
}
