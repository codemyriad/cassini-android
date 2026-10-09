package org.cassini.android

import org.cassini.core.models.ModelCatalog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelUpgradeTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun cleanupRemovesOnlyRetiredDownloadsAndCanRunAgain() {
        val directory = folder.root
        val legacy = File(directory, "parakeet-v3-fp32").apply { mkdirs() }
        File(legacy, "encoder.weights").writeText("obsolete weights")
        File(legacy, "encoder.onnx.part").writeText("interrupted download")
        val kept = listOf("parakeet-v3/verified", "vad/verified", "speaker-models/verified",
            "documents/old-fp32.opus", "documents/old-live.wav", "session.json", "library.json")
        kept.forEach { name -> File(directory, name).apply { parentFile!!.mkdirs(); writeText(name) } }

        assertTrue(InstalledModels.removeLegacyModel(directory))
        assertFalse(legacy.exists())
        assertTrue(InstalledModels.removeLegacyModel(directory))
        kept.forEach { name -> assertEquals(name, File(directory, name).readText()) }
    }

    @Test fun retiredSpeakerModelsAreRemovedAndTheirVerificationRevoked() {
        val directory = File(folder.root, "speaker-models").apply { mkdirs() }
        listOf("segmentation.int8.onnx", "embedding.onnx", "embedding.onnx.part").forEach { File(directory, it).writeText("old weights") }
        File(directory, "verified").writeText("old\nhashes")
        val recording = File(folder.root, "documents/note.opus").apply { parentFile!!.mkdirs(); writeText("audio") }

        assertTrue(InstalledModels.removeRetiredModels(folder.root))
        assertEquals(emptyList<String>(), directory.list()!!.toList())
        assertEquals("audio", recording.readText())

        File(directory, "verified").writeText(ModelCatalog.diarizationModel.sha256)
        assertTrue(InstalledModels.removeRetiredModels(folder.root))
        assertEquals(ModelCatalog.diarizationModel.sha256, File(directory, "verified").readText())
        assertTrue(InstalledModels.removeRetiredModels(File(folder.root, "absent")))
    }

    @Test fun retiredCleanupKeepsTheVoiceprintModel() {
        val directory = File(folder.root, "speaker-models").apply { mkdirs() }
        File(directory, "voiceprint-campplus.onnx").writeText("weights")
        File(directory, "verified-voiceprint").writeText(ModelCatalog.voiceprintModel.sha256)
        assertTrue(InstalledModels.removeRetiredModels(folder.root))
        assertEquals("weights", File(directory, "voiceprint-campplus.onnx").readText())
        assertEquals(ModelCatalog.voiceprintModel.sha256, File(directory, "verified-voiceprint").readText())
    }
}
