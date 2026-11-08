package io.codemyriad.cassini.core.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParakeetSuggestionTest {
    @Test fun rejectsEveryIncapableCaseAndAcceptsTheExactRamAndDiskBoundary() {
        assertTrue(ParakeetSuggestion.eligible(true, false, false, 4_000_000_000L, 100, 100))
        assertFalse(ParakeetSuggestion.eligible(true, false, false, 3_999_999_999L, 100, 100))
        assertFalse(ParakeetSuggestion.eligible(true, true, false, 4_000_000_000L, 100, 100))
        assertFalse(ParakeetSuggestion.eligible(true, false, true, 4_000_000_000L, 100, 100))
        assertFalse(ParakeetSuggestion.eligible(false, false, false, 4_000_000_000L, 100, 100))
        assertFalse(ParakeetSuggestion.eligible(true, false, false, 4_000_000_000L, 99, 100))
        assertFalse(ParakeetSuggestion.eligible(true, false, false, 4_000_000_000L, 100, 0))
    }
}
