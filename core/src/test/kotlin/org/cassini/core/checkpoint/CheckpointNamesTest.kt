package org.cassini.core.checkpoint

import org.cassini.core.speakers.Match
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two names.json members added in 0.2 and the reducers that change a draft's names. */
class CheckpointNamesTest {
    private val empty = CheckpointNames(emptyMap(), emptyMap())
    private val names = mapOf("luca" to "Luca", "anna" to "Anna Ricci", "paolo" to "Paolo")

    private fun auto(voice: String, score: Float = 0.8f) = Match(voice, score, Match.State.AUTO)
    private fun suggested(voice: String, score: Float = 0.6f) = Match(voice, score, Match.State.SUGGESTED)
    private fun confirmed(voice: String, score: Float = 0.6f) = Match(voice, score, Match.State.CONFIRMED)

    private fun roundTrip(names: CheckpointNames) =
        CheckpointNames.decode(JSONObject(CheckpointNames.encode(names).toString()))

    // Codec

    @Test fun liveAndRejectedRoundTrip() {
        val draft = CheckpointNames(
            labels = mapOf("s1" to "Élodie"),
            merges = mapOf("s4" to "s1"),
            remembered = mapOf("s1" to "anna"),
            automatic = mapOf("s2" to "Luca"),
            generation = 3,
            live = mapOf("s1" to confirmed("anna", 0.61f), "s2" to auto("luca", 0.83f), "s3" to suggested("paolo")),
            rejected = mapOf("s3" to setOf("luca", "anna")),
        )
        val json = JSONObject(CheckpointNames.encode(draft).toString())
        assertEquals(CheckpointNames.KEYS + CheckpointNames.OPTIONAL_KEYS, json.keys().asSequence().toSet())
        assertEquals(draft, roundTrip(draft))
        // The 3.6 shape: voice, score and the state by name; rejected voices as an array in the order given.
        val live = json.getJSONObject("live").getJSONObject("s1")
        assertEquals("anna", live.getString("voice"))
        assertEquals("CONFIRMED", live.getString("state"))
        assertEquals("0.61", live.get("score").toString())
        assertEquals("[\"luca\",\"anna\"]", json.getJSONObject("rejected").getJSONArray("s3").toString())
    }

    @Test fun emptyMembersAreNotWritten() {
        val json = JSONObject(CheckpointNames.encode(CheckpointNames(mapOf("a" to "Ada"), emptyMap())).toString())
        assertEquals(CheckpointNames.KEYS, json.keys().asSequence().toSet())
    }

    @Test fun missingMembersReadAsEmpty() {
        val json = JSONObject().put("labels", JSONObject()).put("merges", JSONObject()).put("generation", 2)
        val decoded = CheckpointNames.decode(json)
        assertEquals(emptyMap<String, Match>(), decoded.live)
        assertEquals(emptyMap<String, Set<String>>(), decoded.rejected)
    }

    @Test fun anUnknownStateIsRefused() {
        val json = JSONObject().put("labels", JSONObject()).put("merges", JSONObject())
            .put("live", JSONObject().put("s", JSONObject().put("voice", "v").put("score", 0.5).put("state", "MAYBE")))
        assertThrows(IllegalArgumentException::class.java) { CheckpointNames.decode(json) }
    }

    // confirm

    @Test fun yesLucaNamesRemembersAndConfirms() {
        val draft = empty.copy(generation = 2).applyLive(mapOf("s" to suggested("luca", 0.62f)), names)
        val after = draft.confirm("s", "luca", "Luca", 2, 0.62f)
        assertEquals("Luca", after.labels["s"])
        assertEquals("luca", after.remembered["s"])
        assertEquals(confirmed("luca", 0.62f), after.live["s"])
        assertEquals(2L, after.generation)
        assertFalse(after.openForLive("s"))
    }

    @Test fun confirmAfterForgetEveryoneDropsEarlierChoices() {
        val draft = empty.copy(generation = 1).confirm("a", "anna", "Anna Ricci", 1, 0.7f)
        val after = draft.confirm("b", "luca", "Luca", 2, 0.6f)
        assertEquals(mapOf("b" to "luca"), after.remembered)
        assertEquals(2L, after.generation)
        // A confirmation needs its remember choice; the label stays.
        assertNull(after.live["a"])
        assertEquals("Anna Ricci", after.labels["a"])
    }

    @Test fun confirmDropsAnotherSpeakersUnconfirmedMatchToTheSameVoice() {
        val draft = empty.applyLive(mapOf("a" to suggested("luca"), "b" to auto("paolo")), names)
            .copy(automatic = mapOf("a" to "Luca"))
        val after = draft.confirm("b", "luca", "Luca", 0, 0.6f)
        assertNull(after.live["a"])
        assertNull(after.automatic["a"])
        assertNull(after.automatic["b"])
    }

    @Test fun confirmClearsARejectionOfThatVoice() {
        val after = empty.reject("s", "luca").reject("s", "anna").confirm("s", "luca", "Luca", 0, 0.5f)
        assertEquals(mapOf("s" to setOf("anna")), after.rejected)
        val again = empty.reject("s", "luca").confirm("s", "luca", "Luca", 0, 0.5f)
        assertEquals(emptyMap<String, Set<String>>(), again.rejected)
    }

    // reject

    @Test fun notLucaIsNeverSuggestedAgain() {
        val draft = empty.applyLive(mapOf("s" to suggested("luca")), names)
        val after = draft.reject("s", "luca", "Luca")
        assertNull(after.live["s"])
        assertEquals(mapOf("s" to setOf("luca")), after.rejected)
        assertEquals(after, after.applyLive(mapOf("s" to suggested("luca", 0.9f)), names))
        assertEquals(mapOf("s" to setOf("luca")), after.excluded(listOf("s", "t")))
    }

    @Test fun notLucaRemovesTheAutomaticName() {
        val draft = empty.applyLive(mapOf("s" to auto("luca")), names)
        assertEquals("Luca", draft.automatic["s"])
        val after = draft.reject("s", "luca", "Luca")
        assertNull(after.automatic["s"])
        assertNull(after.live["s"])
    }

    @Test fun notLucaAfterYesLucaReturnsToThePlaceholderAndEnrolsNothing() {
        val after = empty.confirm("s", "luca", "Luca", 0, 0.6f).reject("s", "luca", "Luca")
        assertNull(after.labels["s"])
        assertNull(after.remembered["s"])
        assertNull(after.live["s"])
        assertTrue(after.openForLive("s"))
    }

    @Test fun notLucaKeepsAMatchToSomeoneElse() {
        val draft = empty.applyLive(mapOf("s" to suggested("anna")), names)
        val after = draft.reject("s", "luca", "Luca")
        assertEquals(suggested("anna"), after.live["s"])
    }

    @Test fun aLabelTheUserTypedStaysWhenItIsAnotherName() {
        val after = empty.rename("s", "Luca Bianchi").reject("s", "luca", "Luca")
        assertEquals("Luca Bianchi", after.labels["s"])
    }

    // The race: the job computes a live round from the state it read, the user answers the card meanwhile.

    @Test fun aLiveRoundAndNotLucaCommute() {
        val before = empty.applyLive(mapOf("s" to suggested("luca", 0.55f), "t" to auto("paolo")), names)
        val rounds = listOf(
            mapOf("s" to suggested("luca", 0.58f), "t" to auto("paolo")),
            mapOf("s" to auto("luca", 0.9f)),
            mapOf("s" to suggested("anna"), "t" to suggested("luca")),
            emptyMap(),
        )
        for (round in rounds) {
            val liveFirst = before.applyLive(round, names).reject("s", "luca", "Luca")
            val userFirst = before.reject("s", "luca", "Luca").applyLive(round, names)
            assertEquals("round $round", liveFirst, userFirst)
            assertTrue(liveFirst.rejected.getValue("s").contains("luca"))
            assertFalse(liveFirst.live["s"]?.voiceId == "luca")
        }
    }

    @Test fun aLiveRoundAndYesLucaCommute() {
        val before = empty.copy(generation = 5).applyLive(mapOf("s" to suggested("luca", 0.55f)), names)
        val rounds = listOf(
            mapOf("s" to suggested("luca", 0.58f), "t" to auto("paolo")),
            mapOf("s" to suggested("anna"), "t" to suggested("luca")),
            mapOf("t" to auto("luca")),
            emptyMap(),
        )
        for (round in rounds) {
            val liveFirst = before.applyLive(round, names).confirm("s", "luca", "Luca", 5, 0.55f)
            val userFirst = before.confirm("s", "luca", "Luca", 5, 0.55f).applyLive(round, names)
            assertEquals("round $round", liveFirst, userFirst)
            assertEquals(confirmed("luca", 0.55f), liveFirst.live["s"])
            assertEquals("luca", liveFirst.remembered["s"])
        }
    }

    @Test fun aLiveRoundAndNotLucaAfterYesLucaCommute() {
        // The job skips a confirmed speaker, so its round has no entry for it.
        val before = empty.confirm("s", "luca", "Luca", 0, 0.6f)
        val round = mapOf("t" to suggested("anna"))
        val liveFirst = before.applyLive(round, names).reject("s", "luca", "Luca")
        val userFirst = before.reject("s", "luca", "Luca").applyLive(round, names)
        assertEquals(liveFirst, userFirst)
    }

    // applyLive

    @Test fun aLiveRoundReplacesTheEarlierOne() {
        val first = empty.applyLive(mapOf("a" to auto("luca"), "b" to suggested("anna")), names)
        assertEquals(mapOf("a" to "Luca"), first.automatic)
        val second = first.applyLive(mapOf("b" to auto("anna")), names)
        assertEquals(mapOf("b" to auto("anna")), second.live)
        assertEquals(mapOf("b" to "Anna Ricci"), second.automatic)
    }

    @Test fun aLiveRoundLeavesNamedConfirmedAndMergedSpeakersAlone() {
        val draft = empty.rename("named", "Giulia").confirm("sure", "luca", "Luca", 0, 0.7f).merge("gone", "named")
        val after = draft.applyLive(
            mapOf(
                "named" to auto("anna"),
                "sure" to auto("paolo"),
                "gone" to suggested("paolo"),
                "other" to suggested("luca"),
                "free" to suggested("paolo"),
            ),
            names,
        )
        assertEquals(mapOf("sure" to confirmed("luca", 0.7f), "free" to suggested("paolo")), after.live)
        assertEquals(emptyMap<String, String>(), after.automatic)
    }

    @Test fun noteLocalVoicesAreNoLiveMatch() {
        val after = empty.applyLive(mapOf("s" to auto("note:s1")), mapOf("note:s1" to "Giulia"))
        assertEquals(empty, after)
    }

    @Test fun onlyAutoAndSuggestedMatchesCount() {
        val round = mapOf(
            "a" to Match("luca", 0.9f, Match.State.CONFIRMED),
            "b" to Match("anna", 0.9f, Match.State.NONE),
            "c" to Match("paolo", 0.9f, Match.State.REJECTED),
        )
        assertEquals(empty, empty.applyLive(round, names))
    }

    @Test fun anAutoMatchToAnUnknownVoiceGetsNoAutomaticName() {
        val after = empty.applyLive(mapOf("s" to auto("gone")), names)
        assertEquals(auto("gone"), after.live["s"])
        assertNull(after.automatic["s"])
    }

    @Test fun excludedAddsVoicesConfirmedForOtherSpeakers() {
        val draft = empty.confirm("a", "luca", "Luca", 0, 0.7f).reject("b", "anna")
        assertEquals(mapOf("b" to setOf("anna", "luca"), "c" to setOf("luca")), draft.excluded(listOf("a", "b", "c")))
    }

    // rename and remember

    @Test fun renameWinsOverTheAutomaticName() {
        val draft = empty.applyLive(mapOf("s" to auto("luca")), names)
        val after = draft.rename("s", "  Giulia ")
        assertEquals("Giulia", after.labels["s"])
        assertNull(after.automatic["s"])
        assertNull(after.live["s"])
        assertFalse(after.openForLive("s"))
    }

    @Test fun aBlankOrPlaceholderNameReturnsToTheDefault() {
        val draft = empty.rename("s", "Giulia").remember("s", "", 0)
        for (name in listOf("", "   ", "Speaker 3", "Voce 2")) {
            val after = draft.rename("s", name)
            assertNull(name, after.labels["s"])
            assertNull(name, after.remembered["s"])
            assertTrue(name, after.openForLive("s"))
        }
    }

    @Test fun renameKeepsAConfirmation() {
        val after = empty.confirm("s", "anna", "Anna", 0, 0.7f).rename("s", "Anna Ricci")
        assertEquals(confirmed("anna", 0.7f), after.live["s"])
        assertEquals("anna", after.remembered["s"])
    }

    @Test fun rememberingAnotherVoiceEndsTheConfirmation() {
        val draft = empty.confirm("s", "anna", "Anna", 0, 0.7f)
        assertNull(draft.remember("s", "", 0).live["s"])
        assertNull(draft.remember("s", null, 0).live["s"])
        assertEquals(confirmed("anna", 0.7f), draft.remember("s", "anna", 0).live["s"])
    }

    @Test fun rememberFollowsTheGeneration() {
        val draft = empty.copy(generation = 1).remember("a", "anna", 1).remember("b", "", 1)
        assertEquals(mapOf("a" to "anna", "b" to ""), draft.remembered)
        val after = draft.remember("c", "luca", 2)
        assertEquals(mapOf("c" to "luca"), after.remembered)
        assertEquals(2L, after.generation)
        assertEquals(mapOf("a" to "anna"), draft.remember("b", null, 1).remembered)
    }

    // merge

    @Test fun mergeDropsTheSourceState() {
        val draft = empty.applyLive(mapOf("a" to auto("luca"), "b" to suggested("anna")), names)
            .rename("b", "Bruno").remember("b", "", 0).reject("a", "paolo")
        val after = draft.merge("a", "b")
        assertEquals(mapOf("a" to "b"), after.merges)
        assertEquals(mapOf("b" to "Bruno"), after.labels)
        assertEquals(mapOf("b" to ""), after.remembered)
        assertEquals(emptyMap<String, String>(), after.automatic)
        assertNull(after.live["a"])
        assertEquals(emptyMap<String, Set<String>>(), after.rejected)
        assertFalse(after.openForLive("a"))
    }

    @Test fun mergeCanFreezeTheTargetsShownName() {
        val draft = empty.applyLive(mapOf("b" to auto("luca")), names)
        assertEquals("Luca", draft.merge("a", "b", "Luca").labels["b"])
        assertNull(draft.merge("a", "b").labels["b"])
    }

    @Test fun reducersFollowMerges() {
        val draft = empty.merge("a", "b")
        assertEquals(mapOf("b" to "Bruno"), draft.rename("a", "Bruno").labels)
        assertEquals(mapOf("b" to setOf("luca")), draft.reject("a", "luca").rejected)
        assertThrows(IllegalArgumentException::class.java) { draft.merge("a", "b") }
    }
}
