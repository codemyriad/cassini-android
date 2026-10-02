package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ModelStoreVadTest {
    @Test fun vadIsReadyOnlyWithItsFullFileAndVerificationMarker() {
        val filesDir = Files.createTempDirectory("files").toFile()
        try {
            assertEquals(File(filesDir, "vad/silero_vad.onnx").absolutePath, ModelStore.vadPath(filesDir))
            assertFalse(ModelStore.vadReady(filesDir))
            val directory = ModelStore.vadDirectory(filesDir).apply { mkdirs() }
            File(directory, "silero_vad.onnx").writeBytes(ByteArray(ModelStore.sileroVad.size.toInt()))
            assertFalse(ModelStore.vadReady(filesDir))
            File(directory, "verified").writeText("stale")
            assertFalse(ModelStore.vadReady(filesDir))
            File(directory, "verified").writeText(ModelStore.sileroVad.sha256)
            assertTrue(ModelStore.vadReady(filesDir))
            File(directory, "silero_vad.onnx").writeBytes(ByteArray(10))
            assertFalse(ModelStore.vadReady(filesDir))
            // A Parakeet install does not depend on the detector.
            assertFalse((ModelStore.int8Artifacts + ModelStore.fp32Artifacts).any { it.name == ModelStore.sileroVad.name })
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
