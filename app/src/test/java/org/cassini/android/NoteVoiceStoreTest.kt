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

    @Test fun variantsReusingSpeakerIdsKeepTheirOwnPrints() {
        val store = store()
        store.merge("mtg_1", "tr_a", "m", mapOf("spk_1" to SpeakerPrint(floatArrayOf(1f, 0f), 10.0, anna), "spk_2" to SpeakerPrint(floatArrayOf(0f, 1f), 3.0, null)))
        store.setMatch("mtg_1", "tr_a", "spk_1", Match("voice_a", 0.8f, Match.State.CONFIRMED))
        store.merge("mtg_1", "tr_b", "m", mapOf("spk_1" to SpeakerPrint(floatArrayOf(0f, 1f), 5.0, null)))
        val a = store().load("mtg_1", "tr_a"); val b = store().load("mtg_1", "tr_b")
        assertEquals(setOf("spk_1", "spk_2"), a.keys); assertEquals(Match.State.CONFIRMED, a["spk_1"]?.match?.state)
        assertEquals(setOf("spk_1"), b.keys); assertArrayEquals(floatArrayOf(0f, 1f), b["spk_1"]?.embedding, 0f); assertNull(b["spk_1"]?.match)
        store.merge("mtg_1", "tr_a", "m", mapOf("spk_3" to SpeakerPrint(floatArrayOf(1f), 1.0, null)))
        assertEquals(setOf("spk_3"), store.load("mtg_1", "tr_a").keys); assertEquals(setOf("spk_1"), store.load("mtg_1", "tr_b").keys)
        store.merge("mtg_1", "tr_c", "other", mapOf("spk_1" to SpeakerPrint(floatArrayOf(1f), 1.0, null)))
        assertEquals(emptyMap<String, SpeakerPrint>(), store.load("mtg_1", "tr_b")); assertEquals(setOf("spk_1"), store.load("mtg_1", "tr_c").keys)
    }

    @Test fun setMatchAndDelete() {
        val store = store()
        store.merge("mtg_1", "tr", "m", mapOf("s" to SpeakerPrint(floatArrayOf(1f), 4.0, anna)))
        store.setMatch("mtg_1", "tr", "s", Match("voice_b", 0.6f, Match.State.CONFIRMED))
        assertEquals(Match.State.CONFIRMED, store.load("mtg_1", "tr")["s"]?.match?.state)
        store.setMatch("mtg_1", "other", "s", null); assertEquals(Match.State.CONFIRMED, store.load("mtg_1", "tr")["s"]?.match?.state)
        store.setMatch("mtg_1", "tr", "s", null); assertNull(store.load("mtg_1", "tr")["s"]?.match)
        store.delete("mtg_1")
        assertFalse(File(folder.root, "voiceprints/mtg_1.json").exists()); assertEquals(emptyMap<String, SpeakerPrint>(), store.load("mtg_1", "tr"))
    }

    @Test fun forgettingAPersonScrubsEveryNote() {
        val store = store()
        store.merge("mtg_1", "tr", "m", mapOf("s" to SpeakerPrint(floatArrayOf(1f), 4.0, anna)))
        store.merge("mtg_2", "tr", "m", mapOf("s" to SpeakerPrint(floatArrayOf(1f), 4.0, anna), "t" to SpeakerPrint(floatArrayOf(1f), 4.0, Match("voice_b", 0.6f, Match.State.SUGGESTED))))
        store.forget("voice_a")
        assertNull(store.load("mtg_1", "tr")["s"]?.match); assertNull(store.load("mtg_2", "tr")["s"]?.match)
        assertEquals("voice_b", store.load("mtg_2", "tr")["t"]?.match?.voiceId)
    }

    @Test(expected = IllegalArgumentException::class) fun meetingIdCannotEscapeTheDirectory() { store().load("../voices", "tr") }
}
