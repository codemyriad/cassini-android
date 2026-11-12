package io.codemyriad.cassini.core.speakers

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCodecTest {
    private fun axis(i: Int) = FloatArray(3).also { it[i] = 1f }

    @Test fun counterUsesStrictMetadataAndCoversExistingAndLegacySlots() {
        val stored = Voice("stored", "Stored", "m", axis(0), 1, 1.0, 0, 0, 4)
        val legacy = Voice("legacy", "Legacy", "m", axis(1), 1, 1.0, 1, 1)
        for ((hint, expected) in listOf(2 to 5, 8 to 8, "100" to 5)) {
            val json = JSONObject(VoiceCodec.encode(listOf(stored, legacy))).put("nextColourIndex", hint)
            val book = VoiceCodec.decodeBook(json.toString())
            assertEquals(4, book.voices[0].colourIndex)
            assertEquals(expected, book.voices[1].colourIndex)
            assertEquals(expected + 1L, book.nextColourIndex)
        }
    }

    @Test fun deletingLatestVoiceKeepsMonotonicSaveOrderAcrossReload() {
        val (annaOnly, _) = VoiceCodec.enrol(emptyList(), null, "Anna", "m", axis(0), 1.0, 0)
        val (both, bruno) = VoiceCodec.enrol(annaOnly, null, "Bruno", "m", axis(1), 1.0, 1)
        val book = VoiceCodec.decodeBook(VoiceCodec.encode(both))
        val remaining = VoiceCodec.encode(book.voices.filter { it.id != bruno.id }, book.nextColourIndex)
        val reloaded = VoiceCodec.decodeBook(remaining)
        val (_, chris) = VoiceCodec.enrol(
            reloaded.voices,
            null,
            "Chris",
            "m",
            axis(2),
            1.0,
            2,
            reloaded.nextColourIndex,
        )
        assertEquals(2, chris.colourIndex)
        assertEquals(2L, reloaded.nextColourIndex)
    }

    @Test fun voiceSlotParsingRejectsCoercionAndOverflow() {
        val json = Voice("v", "Anna", "m", axis(0), 1, 1.0, 0, 0).json()
        for (hint in listOf("1.5", "2", 1.5, 1e30, true, -1L, 3000000000L, 4294967296L)) {
            json.put("colourIndex", hint)
            assertNull("Invalid optional slot $hint", Voice.fromJson(json).colourIndex)
        }
        json.put("colourIndex", Int.MAX_VALUE)
        assertEquals(Int.MAX_VALUE, Voice.fromJson(json).colourIndex)
    }

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

    @Test fun maximumOptionalSlotsDoNotOverflowLegacyAssignmentOrEnrolment() {
        for (slot in listOf(Int.MAX_VALUE - 1, Int.MAX_VALUE)) {
            val stored = Voice("stored", "Stored", "m", axis(0), 1, 1.0, 0, 0, slot)
            val first = Voice("first", "First", "m", axis(1), 1, 1.0, 1, 1)
            val second = Voice("second", "Second", "m", axis(2), 1, 1.0, 2, 2)
            val decoded = VoiceCodec.decode(VoiceCodec.encode(listOf(stored, first, second)))
            assertEquals(slot, decoded.first().colourIndex)
            assertTrue(decoded.all { it.colourIndex!! >= 0 })
            val (_, enrolled) = VoiceCodec.enrol(listOf(stored), null, "New", "m", axis(1), 1.0, 3)
            assertTrue(enrolled.colourIndex!! >= 0)
        }
    }
}
