package org.cassini.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceMatcherTest {
    private val t = Thresholds(auto = 0.72f, suggest = 0.50f, margin = 0.08f)
    /** A unit vector at [score] cosine from axis 0, leaning toward axis [other]. */
    private fun near(score: Float, other: Int = 1) = FloatArray(4).also { it[0] = score; it[other] = kotlin.math.sqrt(1 - score * score) }
    private fun axis(i: Int) = FloatArray(4).also { it[i] = 1f }
    private fun voice(id: String, mean: FloatArray, model: String = "m") = Voice(id, id, model, mean, 1, 10.0, 0, 0)

    @Test fun cosineOfNormalisedVectorsIsTheDotProduct() {
        assertEquals(0.8f, Voiceprints.cosine(near(0.8f), axis(0)), 1e-6f)
        assertEquals(0f, Voiceprints.cosine(axis(1), axis(0)), 1e-6f)
    }

    @Test fun scoreDecidesAutoSuggestionOrNothing() {
        val voices = listOf(voice("anna", axis(0)))
        assertEquals(Match.State.AUTO, VoiceMatcher.match(mapOf("s" to near(0.80f)), voices, "m", t)["s"]?.state)
        assertEquals(Match.State.SUGGESTED, VoiceMatcher.match(mapOf("s" to near(0.60f)), voices, "m", t)["s"]?.state)
        assertNull(VoiceMatcher.match(mapOf("s" to near(0.40f)), voices, "m", t)["s"])
    }

    @Test fun closeSecondPersonDemotesToSuggestion() {
        val bruno = Voiceprints.normalised(floatArrayOf(0.9f, 0.1f, 0f, 0f))!!
        val match = VoiceMatcher.match(mapOf("s" to near(0.85f)), listOf(voice("anna", axis(0)), voice("bruno", bruno)), "m", t)["s"]!!
        assertEquals(Match.State.SUGGESTED, match.state)
    }

    @Test fun onePersonNamesAtMostOneSpeaker() {
        val matches = VoiceMatcher.match(mapOf("a" to near(0.90f, 1), "b" to near(0.88f, 2)), listOf(voice("anna", axis(0))), "m", t)
        assertEquals("anna", matches["a"]?.voiceId)
        // The rival speaker is as close, so even the best one is only suggested.
        assertEquals(Match.State.SUGGESTED, matches["a"]?.state)
        assertNull(matches["b"])
    }

    @Test fun secondSpeakerMayTakeAnotherPerson() {
        val matches = VoiceMatcher.match(mapOf("a" to axis(0), "b" to axis(1)), listOf(voice("anna", axis(0)), voice("bruno", axis(1))), "m", t)
        assertEquals("anna", matches["a"]?.voiceId); assertEquals("bruno", matches["b"]?.voiceId)
        assertEquals(Match.State.AUTO, matches["b"]?.state)
    }

    @Test fun voicesOfAnotherModelAreIgnored() {
        assertEquals(emptyMap<String, Match>(), VoiceMatcher.match(mapOf("s" to axis(0)), listOf(voice("anna", axis(0), "old")), "m", t))
    }
}
