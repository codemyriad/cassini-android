package org.cassini.android

import org.cassini.core.models.ModelCatalog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class InstalledModelsTest {
    @get:Rule val folder = TemporaryFolder()
    private fun sized(file: File, size: Long) {
        file.parentFile!!.mkdirs()
        RandomAccessFile(file, "rw").use { it.setLength(size) }
    }

    private fun installParakeet() {
        val directory = File(folder.root, "parakeet-v3")
        ModelCatalog.parakeetArtifacts.forEach { sized(File(directory, it.name), it.size) }
        File(directory, "verified").writeText(ModelCatalog.PARAKEET_REVISION)
    }
    private fun installSpeakers() {
        val directory = File(folder.root, "speaker-models")
        sized(File(directory, ModelCatalog.diarizationModel.name), ModelCatalog.diarizationModel.size)
        File(directory, "verified").writeText(ModelCatalog.diarizationModel.sha256)
    }
    private fun installVoiceprint() {
        val directory = File(folder.root, "speaker-models")
        sized(File(directory, "voiceprint-campplus.onnx"), ModelCatalog.voiceprintModel.size)
        File(directory, "verified-voiceprint").writeText(ModelCatalog.voiceprintModel.sha256)
    }

    @Test fun readinessNeedsAllThreeModelsAndMissingBytesShrinkAsTheyArrive() {
        val models = InstalledModels(folder.root)
        assertFalse(models.ready())
        assertEquals(ModelCatalog.totalBytes, models.missingBytes())
        installParakeet()
        installSpeakers()
        assertFalse(models.ready())
        assertEquals(ModelCatalog.voiceprintModel.size, models.missingBytes())
        assertEquals(28L, ModelCatalog.mebibytes(models.missingBytes()))
        installVoiceprint()
        assertTrue(models.ready())
        assertEquals(0L, models.missingBytes())
    }

    @Test fun onceParakeetIsInstalledAMissingOptionalModelDoesNotAskAgain() {
        val models = InstalledModels(folder.root)
        assertFalse(models.consented())
        installParakeet()
        assertTrue(models.consented())
        assertFalse(models.ready())
    }

    @Test fun aVoiceprintMarkerForAnotherModelIsNotReady() {
        installVoiceprint()
        assertTrue(InstalledModels(folder.root).ready(ModelCatalog.VOICEPRINT))
        File(folder.root, "speaker-models/verified-voiceprint").writeText("other")
        assertFalse(InstalledModels(folder.root).ready(ModelCatalog.VOICEPRINT))
    }

    @Test fun parakeetFilesAreTheInt8GraphsAndTokensOfTheInstall() {
        val files = InstalledModels(folder.root).parakeet()
        val directory = File(folder.root, "parakeet-v3")
        assertEquals(File(directory, "encoder.int8.onnx").absolutePath, files.encoder)
        assertEquals(File(directory, "decoder.int8.onnx").absolutePath, files.decoder)
        assertEquals(File(directory, "joiner.int8.onnx").absolutePath, files.joiner)
        assertEquals(File(directory, "tokens.txt").absolutePath, files.tokens)
        val diarizer = File(folder.root, "speaker-models/nemotron-3-diarization.int8.onnx")
        assertEquals(diarizer.absolutePath, InstalledModels(folder.root).path(ModelCatalog.DIARIZATION))
    }

    @Test fun vadIsReadyOnlyWithItsFullFileAndVerificationMarker() {
        val models = InstalledModels(folder.root)
        val path = File(folder.root, "vad/silero_vad.onnx").absolutePath
        assertEquals(path, models.path(ModelCatalog.VAD))
        assertNull(models.detector())
        val directory = File(folder.root, "vad").apply { mkdirs() }
        File(directory, "silero_vad.onnx").writeBytes(ByteArray(ModelCatalog.sileroVad.size.toInt()))
        assertNull(models.detector())
        File(directory, "verified").writeText("stale")
        assertNull(models.detector())
        File(directory, "verified").writeText(ModelCatalog.sileroVad.sha256)
        assertEquals(path, models.detector())
        File(directory, "silero_vad.onnx").writeBytes(ByteArray(10))
        assertNull(models.detector())
        // A Parakeet install does not depend on the detector.
        assertFalse(ModelCatalog.parakeetArtifacts.any { it.name == ModelCatalog.sileroVad.name })
    }

    @Test fun theStatedDownloadSizeMatchesTheArtifacts() {
        val stated = File("src/main/res/values/strings.xml").readText()
            .let { Regex("""name="models_size">(\d+) MiB<""").find(it)!!.groupValues[1].toLong() }
        assertEquals(stated, ModelCatalog.mebibytes(ModelCatalog.totalBytes))
        val italian = File("src/main/res/values-it/strings.xml").readText()
        assertTrue(italian.contains("""name="models_size">$stated MiB<"""))
    }
}
