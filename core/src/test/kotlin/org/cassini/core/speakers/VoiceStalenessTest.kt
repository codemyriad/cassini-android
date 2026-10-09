package org.cassini.core.speakers

import org.cassini.core.models.ModelCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStalenessTest {
    private val pinned = ModelCatalog.voiceprintModel.sha256

    private fun voice(model: String, dim: Int) = Voice("v", "Anna", model, FloatArray(dim), 1, 5.0, 0, 0)

    @Test fun aVoiceOfThePinnedModelAndDimensionIsCurrent() {
        assertFalse(VoiceStaleness.isStale(voice(pinned, ModelCatalog.VOICEPRINT_DIM)))
    }

    @Test fun anotherModelIsStale() {
        assertTrue(VoiceStaleness.isStale(voice("0".repeat(64), ModelCatalog.VOICEPRINT_DIM)))
    }

    @Test fun anotherDimensionIsStale() {
        assertTrue(VoiceStaleness.isStale(voice(pinned, 192)))
        assertTrue(VoiceStaleness.isStale(voice(pinned, ModelCatalog.VOICEPRINT_DIM + 1)))
    }

    @Test fun thePinnedDimensionIs512() {
        assertEquals(512, ModelCatalog.VOICEPRINT_DIM)
    }
}
