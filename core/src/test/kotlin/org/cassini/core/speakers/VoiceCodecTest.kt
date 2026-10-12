package org.cassini.core.speakers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCodecTest {
    private fun axis(i: Int) = FloatArray(3).also { it[i] = 1f }

    @Test fun voicesRoundTripThroughVersionOne() {
        val (voices, anna) = VoiceCodec.enrol(emptyList(), null, "Anna", "m", floatArrayOf(3f, 4f, 0f), 12.5, 100)
        val text = VoiceCodec.encode(voices)
        assertEquals(1, JSONObject(text).getInt("version"))
        val back = VoiceCodec.decode(text).single()
        assertEquals(anna.id, back.id)
        assertEquals(0.6f, back.mean[0], 1e-6f)
        assertTrue(back.id.startsWith("voice_"))
    }

    @Test fun anotherVersionIsRefusedWithItsNumber() {
        val newer = assertThrows(VoiceCodec.UnsupportedVersion::class.java) {
            VoiceCodec.decode("""{"version":2,"voices":[]}""")
        }
        assertEquals(2, newer.found)
    }

    @Test fun unenrolInvertsTheRunningMean() {
        val (first, anna) = VoiceCodec.enrol(emptyList(), null, "Anna", "m", axis(0), 1.0, 0)
        val (_, twice) = VoiceCodec.enrol(first, anna.id, "Anna", "m", axis(1), 1.0, 0)
        val back = VoiceCodec.unenrol(twice, axis(1))!!
        assertEquals(1, back.count)
        assertEquals(1f, back.mean[0], 1e-5f)
        assertEquals(0f, back.mean[1], 1e-5f)
        assertNull(VoiceCodec.unenrol(twice, FloatArray(2)))
    }
}
