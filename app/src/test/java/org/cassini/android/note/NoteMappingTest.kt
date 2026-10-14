package org.cassini.android.note

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Document info texts that NoteMapping derives from a document (features.md section 6). */
class NoteMappingTest {
    @Test
    fun documentInfoNamesTheSpeechModel() {
        assertEquals("Parakeet Ultra, INT8", NoteMapping.modelName("moondream/parakeet-ultra INT8"))
        assertEquals("Parakeet Ultra", NoteMapping.modelName("moondream/parakeet-ultra"))
        assertEquals("moondream/parakeet-ultra-2 INT8", NoteMapping.modelName("moondream/parakeet-ultra-2 INT8"))
        // A document of 0.2.0 or earlier keeps the name of its model.
        assertEquals("Parakeet TDT 0.6B v3, INT8", NoteMapping.modelName("nvidia/parakeet-tdt-0.6b-v3 INT8"))
        assertEquals("Parakeet TDT 0.6B v3", NoteMapping.modelName("nvidia/parakeet-tdt-0.6b-v3"))
        assertEquals("a model this app does not know", "whisper-large", NoteMapping.modelName("whisper-large"))
        assertEquals("nvidia/parakeet-tdt-0.6b-v30", NoteMapping.modelName("nvidia/parakeet-tdt-0.6b-v30"))
    }
}
