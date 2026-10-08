package org.cassini.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

class ModelBundleTest {
    @get:Rule val folder = TemporaryFolder()
    private fun sized(file: File, size: Long) { file.parentFile!!.mkdirs(); RandomAccessFile(file, "rw").use { it.setLength(size) } }
    private fun installParakeet() {
        val directory = File(folder.root, "parakeet-v3")
        ModelStore.int8Artifacts.forEach { sized(File(directory, it.name), it.size) }
        File(directory, "verified").writeText(ModelStore.REVISION)
    }
    private fun installSpeakers() {
        val directory = File(folder.root, "speaker-models")
        sized(File(directory, DiarizationModels.model.name), DiarizationModels.model.size)
        File(directory, "verified").writeText(DiarizationModels.model.sha256)
    }
    private fun installVoiceprint() {
        val directory = File(folder.root, "speaker-models")
        sized(File(directory, VoiceprintModel.FILE), VoiceprintModel.model.size)
        File(directory, VoiceprintModel.MARKER).writeText(VoiceprintModel.model.sha256)
    }

    @Test fun readinessNeedsAllThreeModelsAndMissingBytesShrinkAsTheyArrive() {
        val bundle = ModelBundle.inFiles(folder.root)
        assertFalse(bundle.ready())
        assertEquals(ModelBundle.totalBytes, bundle.missingBytes())
        installParakeet(); installSpeakers()
        assertFalse(bundle.ready())
        assertEquals(VoiceprintModel.model.size, bundle.missingBytes())
        assertEquals(28L, ModelBundle.mebibytes(bundle.missingBytes()))
        installVoiceprint()
        assertTrue(bundle.ready())
        assertEquals(0L, bundle.missingBytes())
    }

    @Test fun aVoiceprintMarkerForAnotherModelIsNotReady() {
        installVoiceprint()
        File(folder.root, "speaker-models/${VoiceprintModel.MARKER}").writeText("other")
        assertFalse(VoiceprintModel.inFiles(folder.root).ready())
    }

    @Test fun theStatedDownloadSizeMatchesTheArtifacts() {
        val stated = File("src/main/res/values/strings.xml").readText()
            .let { Regex("""name="models_size">(\d+) MiB<""").find(it)!!.groupValues[1].toLong() }
        assertEquals(stated, ModelBundle.mebibytes(ModelBundle.totalBytes))
        assertTrue(File("src/main/res/values-it/strings.xml").readText().contains("""name="models_size">$stated MiB<"""))
    }
}
