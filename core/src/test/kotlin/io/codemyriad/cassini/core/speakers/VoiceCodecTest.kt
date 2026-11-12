package io.codemyriad.cassini.core.speakers

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

    @Test fun slotsSurviveRenameSortingDeletionAndReload() {
        val (first, anna) = VoiceCodec.enrol(emptyList(), null, "Anna", "m", axis(0), 1.0, 100)
        val (second, bruno) = VoiceCodec.enrol(first, null, "Bruno", "m", axis(1), 1.0, 200)
        assertEquals(0, anna.colourIndex)
        assertEquals(1, bruno.colourIndex)
        val sorted = VoiceCodec.decode(VoiceCodec.encode(second.sortedByDescending { it.name }))
        assertEquals(mapOf(anna.id to 0, bruno.id to 1), sorted.associate { it.id to it.colourIndex })
        val remaining = VoiceCodec.decode(VoiceCodec.encode(sorted.filter { it.id != anna.id }))
        val (renamed, same) = VoiceCodec.enrol(remaining, bruno.id, "Aaron", "m", axis(1), 1.0, 300)
        assertEquals(1, same.colourIndex)
        val (_, third) = VoiceCodec.enrol(renamed, null, "Chris", "m", axis(2), 1.0, 400)
        assertEquals(2, third.colourIndex)
    }

    @Test fun olderStoresUseSaveTimeBeforeAnyDisplaySorting() {
        val later = Voice("later", "A", "m", axis(0), 1, 1.0, 20, 20)
        val earlier = Voice("earlier", "Z", "m", axis(1), 1, 1.0, 10, 10)
        val decoded = VoiceCodec.decode(VoiceCodec.encode(listOf(later, earlier)))
        assertEquals(listOf(1, 0), decoded.map { it.colourIndex })
        assertEquals(listOf(1, 0), VoiceCodec.decode(VoiceCodec.encode(decoded)).map { it.colourIndex })
    }

    @Test fun optionalColourHintsNeverDiscardVoiceprintsAndEqualClocksKeepSaveOrder() {
        val first = Voice("z", "First", "m", axis(0), 1, 1.0, 10, 10)
        val second = Voice("a", "Second", "m", axis(1), 1, 1.0, 10, 10)
        val json = JSONObject(VoiceCodec.encode(listOf(first, second)))
        json.getJSONArray("voices").getJSONObject(0).put("colourIndex", -1)
        val decoded = VoiceCodec.decode(json.toString())
        assertEquals(listOf("z", "a"), decoded.map { it.id })
        assertEquals(listOf(0, 1), decoded.map { it.colourIndex })
    }
}
