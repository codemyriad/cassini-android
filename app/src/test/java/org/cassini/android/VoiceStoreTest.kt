package org.cassini.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private fun store() = VoiceStore(folder.root) { _, _ -> }
    private fun axis(i: Int) = FloatArray(3).also { it[i] = 1f }

    @Test fun enrolledVoiceSurvivesReload() {
        val voice = store().enrol(null, "Anna", "m", floatArrayOf(3f, 4f, 0f), 12.5, 100)
        val loaded = store().load().single()
        assertEquals(voice.id, loaded.id); assertEquals("Anna", loaded.name); assertEquals(1, loaded.count)
        assertEquals(0.6f, loaded.mean[0], 1e-6f); assertEquals(12.5, loaded.seconds, 0.0)
        assertTrue(loaded.id.startsWith("voice_"))
    }

    @Test fun corruptFileReadsAsNoVoices() {
        File(folder.root, "voices.json").writeText("{not json")
        assertEquals(emptyList<Voice>(), store().load())
    }

    @Test fun enrolmentUpdatesRunningMeanAndCount() {
        val store = store()
        val first = store.enrol(null, "Anna", "m", axis(0), 10.0, 1)
        val second = store.enrol(first.id, "Anna", "m", axis(1), 5.0, 2)
        assertEquals(2, second.count); assertEquals(15.0, second.seconds, 0.0); assertEquals(2, second.updatedAt)
        assertEquals(second.mean[0], second.mean[1], 1e-6f)
        assertEquals(1, store.load().size)
    }

    @Test fun weightIsCappedSoTheVoiceKeepsDrifting() {
        val store = store()
        var voice = store.enrol(null, "Anna", "m", axis(0), 1.0, 0)
        repeat(99) { voice = store.enrol(voice.id, "Anna", "m", axis(0), 1.0, 0) }
        voice = store.enrol(voice.id, "Anna", "m", axis(1), 1.0, 0)
        assertEquals(101, voice.count)
        val expected = 1f / VoiceStore.MAX_COUNT_WEIGHT
        assertEquals(expected / kotlin.math.sqrt(1 + expected * expected), voice.mean[1], 1e-5f)
    }

    @Test fun renameAndForget() {
        val store = store()
        val anna = store.enrol(null, "Anna", "m", axis(0), 1.0, 0)
        assertTrue(store.rename(anna.id, "Annalisa")); assertEquals("Annalisa", store.load().single().name)
        assertFalse(store.rename("voice_missing", "X"))
        assertTrue(store.forget(anna.id)); assertEquals(emptyList<Voice>(), store.load())
        assertFalse(store.forget(anna.id))
    }

    @Test fun unenrolUndoesTheLastPrint() {
        val store = store()
        val anna = store.enrol(null, "Anna", "m", axis(0), 1.0, 0)
        store.enrol(anna.id, "Anna", "m", axis(1), 1.0, 0)
        store.unenrol(anna.id, axis(1))
        val back = store.load().single()
        assertEquals(1, back.count); assertEquals(1f, back.mean[0], 1e-5f); assertEquals(0f, back.mean[1], 1e-5f)
        store.unenrol(anna.id, axis(0))
        assertEquals(emptyList<Voice>(), store.load())
    }
}
