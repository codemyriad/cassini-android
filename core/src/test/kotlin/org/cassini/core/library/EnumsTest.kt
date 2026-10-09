package org.cassini.core.library

import org.junit.Assert.assertEquals
import org.junit.Test

/** The names are persisted in Room and checkpoints: this test fails when one is renamed or removed. */
class EnumsTest {
    @Test fun persistedNamesStayStable() {
        assertEquals(
            listOf("RECORDED", "IMPORTED_AUDIO", "IMPORTED_DOCUMENT", "RECOVERED"),
            Origin.entries.map { it.name },
        )
        assertEquals(listOf("LIVE_CAPTURE", "PLAIN", "DOCUMENT", "UNPLAYABLE"), AudioKind.entries.map { it.name })
        assertEquals(listOf("QUEUED", "RUNNING", "PAUSED", "FAILED", "NEEDS_CONSENT"), JobState.entries.map { it.name })
        assertEquals(listOf("TRANSCRIBE", "TRANSCRIBE_AGAIN"), JobKind.entries.map { it.name })
        assertEquals(listOf("USER", "SYSTEM", "MEMORY", "INTERRUPTED"), PauseReason.entries.map { it.name })
        assertEquals(listOf("TRANSCRIBED", "MERGED"), VariantKind.entries.map { it.name })
    }
}
