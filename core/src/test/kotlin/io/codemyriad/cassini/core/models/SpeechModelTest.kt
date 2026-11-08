package io.codemyriad.cassini.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechModelTest {
    @Test fun includedIsTheNewDefaultOnlyWhenAvailable() {
        assertEquals(SpeechModel.INCLUDED, SpeechModel.chosen(null, true))
        assertEquals(SpeechModel.PARAKEET, SpeechModel.chosen(null, false))
        assertEquals(SpeechModel.PARAKEET, SpeechModel.chosen(SpeechModel.PARAKEET, true))
        assertEquals(SpeechModel.PARAKEET, SpeechModel.chosen(SpeechModel.INCLUDED, false))
    }

    @Test fun unknownJobPinsDoNotFallBackToAnotherEngine() {
        assertThrows(IllegalArgumentException::class.java) { SpeechModel.identity("unknown-model") }
        assertEquals(SpeechModel.INCLUDED, SpeechModel.identity(SpeechModel.INCLUDED.identity))
    }
}
