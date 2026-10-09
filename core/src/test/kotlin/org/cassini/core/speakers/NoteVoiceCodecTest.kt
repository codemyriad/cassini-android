package org.cassini.core.speakers

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteVoiceCodecTest {
    @Test fun printsAndMatchesRoundTripThroughVersionTwo() {
        val prints = mapOf(
            "tr/spk_1" to SpeakerPrint(floatArrayOf(1f, 0f), 10.0, Match("voice_a", 0.8f, Match.State.CONFIRMED)),
            "tr/spk_2" to SpeakerPrint(floatArrayOf(0f, 1f), 3.0, null),
        )
        val text = NoteVoiceCodec.encode("m", prints)
        val json = JSONObject(text)
        assertEquals(2, json.getInt("version"))
        assertEquals(
            "confirmed",
            json.getJSONObject("speakers").getJSONObject("tr/spk_1").getJSONObject("match").getString("state")
        )
        val (model, back) = NoteVoiceCodec.decode(text)
        assertEquals("m", model)
        assertEquals(prints.keys, back.keys)
        assertArrayEquals(floatArrayOf(1f, 0f), back.getValue("tr/spk_1").embedding, 0f)
        assertEquals(prints.getValue("tr/spk_1").match, back.getValue("tr/spk_1").match)
    }

    @Test fun aVersionMismatchThrowsAndSaysWhetherTheFileIsNewer() {
        val newer = assertThrows(NoteVoiceCodec.UnsupportedVersion::class.java) {
            NoteVoiceCodec.decode("""{"version":3,"model":"m","speakers":{}}""")
        }
        assertEquals(3, newer.found)
        assertTrue(newer.newer)
        val older = assertThrows(NoteVoiceCodec.UnsupportedVersion::class.java) {
            NoteVoiceCodec.decode("""{"version":1,"model":"m","speakers":{}}""")
        }
        assertFalse(older.newer)
    }

    @Test fun meetingAndVariantIdsCannotEscapeTheirPlace() {
        assertEquals("mtg_1-a.json", NoteVoiceCodec.fileName("mtg_1-a"))
        assertThrows(IllegalArgumentException::class.java) { NoteVoiceCodec.fileName("../voices") }
        assertThrows(IllegalArgumentException::class.java) { NoteVoiceCodec.fileName("") }
        assertEquals("tr/", NoteVoiceCodec.prefix("tr"))
        assertThrows(IllegalArgumentException::class.java) { NoteVoiceCodec.prefix("a/b") }
    }
}
