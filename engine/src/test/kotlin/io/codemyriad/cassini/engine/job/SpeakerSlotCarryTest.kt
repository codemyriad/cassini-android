package io.codemyriad.cassini.engine.job

import io.codemyriad.cassini.core.checkpoint.CheckpointNames
import io.codemyriad.cassini.core.format.CassiniDocument
import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.speakers.Match
import io.codemyriad.cassini.core.speakers.SpeakerPrint
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeakerSlotCarryTest {
    @Test fun onlyAnUnambiguousAcceptedVoiceMatchCarriesIdentity() {
        val words = listOf(Word("old_a", 0, 1, "a"), Word("old_b", 1, 2, "b"))
        val document = CassiniDocument.read(
            CassiniDocument.create(FIXTURE_OPUS.readBytes(), Transcript(words), "Meeting", JSONObject()),
        )
        val confirmed = Match("saved", 1f, Match.State.CONFIRMED)
        val one = mapOf("old_a" to SpeakerPrint(axis(1), 30.0, confirmed))
        val two = one + ("old_b" to SpeakerPrint(axis(1), 30.0, confirmed))
        val current = listOf(Word("diar_fresh_1", 0, 1, "a"))
        for ((previous, state, expected) in listOf(
            Triple(one, Match.State.AUTO, 0L),
            Triple(two, Match.State.AUTO, 2L),
            Triple(one, Match.State.SUGGESTED, 2L),
        )) {
            val slots = speakerSlotsForRun(
                document,
                current,
                CheckpointNames(emptyMap(), emptyMap()),
                mapOf("diar_fresh_1" to Match("saved", 1f, state)),
                previous,
            )
            assertEquals(expected, slots["diar_fresh_1"])
        }
    }
}
