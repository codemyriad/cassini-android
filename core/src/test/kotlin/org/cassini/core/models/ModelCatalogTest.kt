package org.cassini.core.models

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile

/** The pins, URLs, directories and markers of the installed models. Installed phones depend on every one of them. */
class ModelCatalogTest {
    @get:Rule val folder = TemporaryFolder()

    private fun install(model: ModelDescriptor, marker: String = model.markerValue) {
        val directory = model.directory(folder.root).apply { mkdirs() }
        for (artifact in model.artifacts) {
            RandomAccessFile(File(directory, artifact.name), "rw").use { it.setLength(artifact.size) }
        }
        File(directory, model.markerFile).writeText(marker)
    }

    @Test fun parakeetIsTheHuggingFaceRevisionMarkedByThatRevision() {
        val parakeet = ModelCatalog.PARAKEET
        assertEquals("parakeet-ultra", parakeet.directory)
        assertEquals("verified", parakeet.markerFile)
        assertEquals("b86784a3a5356cbd126f710b61cd90c5fe5898f1", parakeet.markerValue)
        val names = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        assertEquals(names, parakeet.artifacts.map { it.name })
        assertEquals(listOf(652282347L, 11845275L, 6355277L, 93939L), parakeet.artifacts.map { it.size })
        parakeet.artifacts.forEach {
            assertEquals(
                "https://huggingface.co/tpmaerk/parakeet-ultra-sherpa-onnx-int8/resolve/" +
                    "b86784a3a5356cbd126f710b61cd90c5fe5898f1/${it.name}",
                it.url,
            )
        }
        assertEquals("moondream/parakeet-ultra", ModelCatalog.PARAKEET_MODEL_ID)
        assertEquals("Parakeet Ultra", ModelCatalog.PARAKEET_DISPLAY_NAME)
    }

    /** An install of v3 in `parakeet-v3/` (0.1.0 to 0.2.0) never makes Ultra ready, and its directory is retired. */
    @Test fun anInstallOfParakeetV3IsNotUltra() {
        val v3 = File(folder.root, "parakeet-v3").apply { mkdirs() }
        val sizes = listOf(652184281L, 11845275L, 6355277L, 93939L)
        ModelCatalog.parakeetArtifacts.zip(sizes).forEach { (artifact, size) ->
            RandomAccessFile(File(v3, artifact.name), "rw").use { it.setLength(size) }
        }
        File(v3, "verified").writeText(ModelCatalog.PARAKEET_V3_REVISION)
        assertFalse(ModelCatalog.PARAKEET.ready(folder.root))
        assertEquals(listOf("parakeet-v3"), ModelCatalog.RETIRED_DIRECTORIES)
        val installed = ModelPart.entries.flatMap { part -> part.models.map { it.directory } }
        assertTrue(ModelCatalog.RETIRED_DIRECTORIES.none { it in installed })
    }

    @Test fun everySpeechModelOfADocumentHasAName() {
        assertEquals("Parakeet Ultra", ModelCatalog.SPEECH_MODEL_NAMES[ModelCatalog.PARAKEET_MODEL_ID])
        assertEquals("Parakeet TDT 0.6B v3", ModelCatalog.SPEECH_MODEL_NAMES["nvidia/parakeet-tdt-0.6b-v3"])
        assertEquals("2bda32ec70b097a55adaa07d9a7173915b43cc78", ModelCatalog.PARAKEET_V3_REVISION)
    }

    @Test fun theDetectorAndSpeakerModelsAreMarkedByTheirHashes() {
        assertEquals("vad", ModelCatalog.VAD.directory)
        assertEquals("verified", ModelCatalog.VAD.markerFile)
        assertEquals(ModelCatalog.sileroVad.sha256, ModelCatalog.VAD.markerValue)
        assertEquals(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            ModelCatalog.sileroVad.url
        )
        assertEquals("speaker-models", ModelCatalog.DIARIZATION.directory)
        assertEquals("verified", ModelCatalog.DIARIZATION.markerFile)
        assertEquals(
            "01d1f893f394cc0418ae78425d216ed62c334c0125e650135332a7a8e595f168",
            ModelCatalog.DIARIZATION.markerValue
        )
        assertEquals(ModelCatalog.diarizationDownload, ModelCatalog.DIARIZATION.download)
        assertTrue(ModelCatalog.diarizationDownload.url.startsWith("https://dist.gocassini.com/models/files/"))
        assertEquals("speaker-models", ModelCatalog.VOICEPRINT.directory)
        assertEquals("verified-voiceprint", ModelCatalog.VOICEPRINT.markerFile)
        assertEquals(
            "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b",
            ModelCatalog.VOICEPRINT.markerValue
        )
        assertEquals("voiceprint-campplus.onnx", ModelCatalog.voiceprintModel.name)
    }

    @Test fun onlyTheThreeModelHostsAreContacted() {
        val hosts = (ModelCatalog.consented + ModelCatalog.VAD).flatMap { model -> (model.artifacts + listOfNotNull(model.download)) }
            .filter { it.url.isNotEmpty() }.map { java.net.URI(it.url).host }.toSet()
        assertEquals(setOf("huggingface.co", "github.com", "dist.gocassini.com"), hosts)
    }

    @Test fun aModelIsReadyOnlyWithEveryFullFileAndItsMarker() {
        for (model in listOf(
            ModelCatalog.PARAKEET,
            ModelCatalog.VAD,
            ModelCatalog.DIARIZATION,
            ModelCatalog.VOICEPRINT
        )) {
            assertFalse(model.ready(folder.root))
            install(model, marker = "stale")
            assertFalse(model.ready(folder.root))
            install(model)
            assertTrue(model.ready(folder.root))
            File(model.directory(folder.root), model.artifacts.first().name).writeBytes(ByteArray(10))
            assertFalse(model.ready(folder.root))
        }
    }

    @Test fun theDiarizerAndVoiceprintShareADirectoryWithoutSharingAMarker() {
        install(ModelCatalog.VOICEPRINT)
        assertTrue(ModelCatalog.VOICEPRINT.ready(folder.root))
        assertFalse(ModelCatalog.DIARIZATION.ready(folder.root))
    }

    @Test fun theConsentDialogQuotesTheBytesFetched() {
        assertEquals(ModelCatalog.parakeetArtifacts.sumOf { it.size }, ModelCatalog.parakeetBytes)
        assertEquals(ModelCatalog.diarizationDownload.size, ModelCatalog.speakerBytes)
        assertEquals(ModelCatalog.voiceprintModel.size, ModelCatalog.voiceprintBytes)
        assertEquals(28L, ModelCatalog.mebibytes(ModelCatalog.voiceprintBytes))
        assertEquals(1L, ModelCatalog.mebibytes(1))
    }

    /** scripts/ci-models.json gives the CI models workflow the same files, URLs and markers as the catalog. */
    @Test fun theCiModelListIsTheCatalog() {
        val file = File(System.getProperty("cassini.ciModels", "../scripts/ci-models.json")!!)
        val listed = JSONObject(file.readText()).getJSONArray("models")
        // Every model that the app installs: the consented download and the speech detector.
        val catalog = (ModelCatalog.consented + ModelCatalog.VAD).sortedBy { it.directory }
        fun artifact(json: JSONObject) = Artifact(
            json.getString("name"),
            json.getLong("size"),
            json.getString("sha256"),
            json.getString("url"),
        )
        val models = (0 until listed.length()).map { index ->
            val json = listed.getJSONObject(index)
            val artifacts = json.getJSONArray("artifacts")
            ModelDescriptor(
                json.getString("directory"),
                (0 until artifacts.length()).map { artifact(artifacts.getJSONObject(it)) },
                json.getString("marker"),
                json.getString("markerValue"),
                json.optJSONObject("download")?.let(::artifact),
            )
        }
        assertEquals(catalog, models.sortedBy { it.directory })
    }
}
