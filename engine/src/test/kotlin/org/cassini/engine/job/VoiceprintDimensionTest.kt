package org.cassini.engine.job

import org.cassini.core.audio.PcmAudio
import org.cassini.core.audio.source
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.PrintWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * `ModelCatalog.VOICEPRINT_DIM` decides which saved voices are stale; the voiceprint adapter is what produces prints.
 * The native CAM++ model cannot run in a JVM test, so a fake extraction stands in for it and the adapter's own check is
 * what is tested.
 */
class VoiceprintDimensionTest {
    private val audio = PcmAudio(FloatArray(16_000), 16_000).source()
    private val windows = mapOf("spk_0" to listOf(PrintWindow(0, 16_000)))
    private val model = File("speaker-models/voiceprint-campplus.onnx")

    @Test fun theCatalogueDimensionIsTheAdaptersDimension() {
        assertEquals(ModelCatalog.VOICEPRINT_DIM, VoiceprintPrints.DIMENSION)
        assertEquals(ModelCatalog.VOICEPRINT_DIM, VoiceprintPrints({ model }).dimension)
    }

    @Test fun printsOfTheDocumentedLengthPassThrough() {
        var used: File? = null
        val prints = VoiceprintPrints({ model }) { file, _, requested, active ->
            used = file
            active()
            requested.mapValues { FloatArray(ModelCatalog.VOICEPRINT_DIM) { 1f / ModelCatalog.VOICEPRINT_DIM } to 1.0 }
        }.compute(audio, windows) {}
        assertSame(model, used)
        assertEquals(setOf("spk_0"), prints.keys)
        assertEquals(ModelCatalog.VOICEPRINT_DIM, prints.getValue("spk_0").first.size)
    }

    @Test fun aPrintOfAnotherLengthIsRefused() {
        // ERes2Net, the retired model, produced 192 values: a wrong model file must not reach matching.
        val extractor = VoiceprintPrints({ model }) { _, _, requested, _ ->
            requested.mapValues { FloatArray(192) to 1.0 }
        }
        try {
            extractor.compute(audio, windows) {}
            fail("A 192-value print must be refused")
        } catch (error: IllegalStateException) {
            assertEquals("Voiceprint of spk_0 has 192 values, expected 512", error.message)
        }
    }
}
