package org.cassini.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NoteVoiceStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = NoteVoiceStore(folder.root) { _, _ -> }
    private val anna = Match("voice_a", 0.8f, Match.State.AUTO)

    @Test fun mergeKeepsSpeakersOfEarlierVariants() {
        val store = store()
        store.merge("mtg_1", "m", mapOf("diar_x_1" to SpeakerPrint(floatArrayOf(1f, 0f), 10.0, anna)))
        store.merge("mtg_1", "m", mapOf("diar_y_1" to SpeakerPrint(floatArrayOf(0f, 1f), 5.0, null)))
        val loaded = store().load("mtg_1")
        assertEquals(setOf("diar_x_1", "diar_y_1"), loaded.keys)
        assertEquals(anna, loaded["diar_x_1"]?.match); assertArrayEquals(floatArrayOf(0f, 1f), loaded["diar_y_1"]?.embedding, 0f)
        store.merge("mtg_1", "other", mapOf("diar_z_1" to SpeakerPrint(floatArrayOf(1f), 1.0, null)))
        assertEquals(setOf("diar_z_1"), store.load("mtg_1").keys)
    }

    @Test fun setMatchAndDelete() {
        val store = store()
        store.merge("mtg_1", "m", mapOf("s" to SpeakerPrint(floatArrayOf(1f), 4.0, anna)))
        store.setMatch("mtg_1", "s", Match("voice_b", 0.6f, Match.State.CONFIRMED))
        assertEquals(Match.State.CONFIRMED, store.load("mtg_1")["s"]?.match?.state)
        store.setMatch("mtg_1", "s", null); assertNull(store.load("mtg_1")["s"]?.match)
        store.delete("mtg_1")
        assertFalse(File(folder.root, "voiceprints/mtg_1.json").exists()); assertEquals(emptyMap<String, SpeakerPrint>(), store.load("mtg_1"))
    }

    @Test fun forgettingAPersonScrubsEveryNote() {
        val store = store()
        store.merge("mtg_1", "m", mapOf("s" to SpeakerPrint(floatArrayOf(1f), 4.0, anna)))
        store.merge("mtg_2", "m", mapOf("s" to SpeakerPrint(floatArrayOf(1f), 4.0, anna), "t" to SpeakerPrint(floatArrayOf(1f), 4.0, Match("voice_b", 0.6f, Match.State.SUGGESTED))))
        store.forget("voice_a")
        assertNull(store.load("mtg_1")["s"]?.match); assertNull(store.load("mtg_2")["s"]?.match)
        assertEquals("voice_b", store.load("mtg_2")["t"]?.match?.voiceId)
    }

    @Test(expected = IllegalArgumentException::class) fun meetingIdCannotEscapeTheDirectory() { store().load("../voices") }
}
