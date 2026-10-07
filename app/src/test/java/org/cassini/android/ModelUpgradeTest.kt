package org.cassini.android

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

        assertTrue(ModelStore.removeLegacyModel(directory))
        assertFalse(legacy.exists())
        assertTrue(ModelStore.removeLegacyModel(directory))
        kept.forEach { name -> assertEquals(name, File(directory, name).readText()) }
    }
}
