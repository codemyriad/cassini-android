package org.cassini.android

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SessionUpgradeTest {
    @Test fun retiredModelPreferencesDoNotDiscardExistingNoteState() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "session-upgrade-${System.nanoTime()}").apply { mkdirs() }
        try {
            val transcript = Transcript(listOf(Word("spk_1", 100, 500, "Prima.")), "it")
            for (choice in listOf("auto", "int8", "fp32")) {
                val json = JSONObject().put("uri", "file:///old-recording.wav").put("name", "Old recording.wav")
                    .put("fp32", true).put("modelChoice", choice).put("resultPrecision", "FP32")
                    .put("transcript", JSONObject(transcript.json())).put("positionMs", 350)
                    .put("durationMs", 1000).put("inferenceMs", 250).put("processingMs", 400)
                    .put("libraryId", "old-note").put("selectedVariant", "old-variant").put("screen", "old-screen")
                File(directory, "session.json").writeText(json.toString())
                val store = SessionStore(directory)
                val note = store.load()
                assertEquals("file:///old-recording.wav", note.uri)
                assertEquals(transcript, note.transcript)
                assertEquals("FP32", note.resultPrecision)
                assertEquals(350, note.positionMs)
                assertEquals("old-note", note.libraryId)
                assertEquals("old-variant", note.selectedVariant)
                assertEquals("old-screen", note.screen)
                assertEquals(1000L, note.durationMs)
                assertEquals(250L, note.inferenceMs)
                assertEquals(400L, note.processingMs)
                store.save(note)
                assertEquals(note, store.load())
                val saved = JSONObject(File(directory, "session.json").readText())
                assertFalse(saved.has("fp32"))
                assertFalse(saved.has("modelChoice"))
            }
        } finally { directory.deleteRecursively() }
    }
}
