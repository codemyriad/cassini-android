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

    @Test fun parakeetIsTheModelHostsUltraInt8MarkedByItsSourceHash() {
        val parakeet = ModelCatalog.PARAKEET
        assertEquals("parakeet-ultra-0.6b-int8", parakeet.id)
        assertEquals("18cb623004dd6c15999b93cb70b14d8a1f6e8f3a2a97ff733f104bfc3494f6a9", parakeet.sourceSha256)
        assertEquals(
            "models/parakeet-ultra-0.6b-int8/18cb623004dd6c15999b93cb70b14d8a1f6e8f3a2a97ff733f104bfc3494f6a9",
            parakeet.directory,
        )
        assertEquals("verified", parakeet.markerFile)
        assertEquals(parakeet.sourceSha256, parakeet.markerValue)
        assertEquals(ModelCatalog.PARAKEET_REVISION, parakeet.sourceSha256)
        val names = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
        assertEquals(names, parakeet.artifacts.map { it.name })
        assertEquals(listOf(652282922L, 11845274L, 6355277L, 93939L), parakeet.artifacts.map { it.size })
        assertEquals(listOf(452306705L, 8841708L, 3714640L, 45831L), parakeet.artifacts.map { it.download!!.size })
        assertEquals("moondream/parakeet-ultra", ModelCatalog.PARAKEET_MODEL_ID)
        assertEquals("Parakeet Ultra", ModelCatalog.PARAKEET_DISPLAY_NAME)
    }

    @Test fun theSpeechModelOfThisBuildHasAName() {
        assertEquals(mapOf(ModelCatalog.PARAKEET_MODEL_ID to "Parakeet Ultra"), ModelCatalog.SPEECH_MODEL_NAMES)
    }

    @Test fun theDetectorAndSpeakerModelsLiveInTheirOwnDirectories() {
        assertEquals("models/silero-vad/${ModelCatalog.sileroVad.sha256}", ModelCatalog.VAD.directory)
        assertEquals("9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6", ModelCatalog.VAD.markerValue)
        assertEquals("silero_vad.onnx", ModelCatalog.sileroVad.name)
        val diarizer = "47c221ea9b4d4e7f6c108bd098e769bc706cdc986c29f6133c93cae335fe6779"
        assertEquals("models/nemotron-3-diarization-int8/$diarizer", ModelCatalog.DIARIZATION.directory)
        assertEquals(diarizer, ModelCatalog.DIARIZATION.markerValue)
        assertEquals(diarizer, ModelCatalog.diarizationModel.sha256)
        assertEquals("model.int8.onnx", ModelCatalog.diarizationModel.name)
        assertEquals("f667ed73aee57d40cc39428eb768b4fd87a0a29e", ModelCatalog.DIARIZATION_SOURCE_REVISION)
        // The saved-voice identity of 0.2 and 0.3: the same CAM++ file, so saved voices stay valid.
        val voiceprint = "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b"
        assertEquals(voiceprint, ModelCatalog.voiceprintModel.sha256)
        assertEquals(29596978L, ModelCatalog.voiceprintModel.size)
        assertEquals("models/campplus-voxceleb-16k/$voiceprint", ModelCatalog.VOICEPRINT.directory)
        assertEquals("3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx", ModelCatalog.voiceprintModel.name)
        val all = ModelCatalog.consented + ModelCatalog.VAD
        assertEquals(all.size, all.map { it.directory }.toSet().size)
        assertTrue(all.all { it.markerFile == "verified" && it.markerValue == it.sourceSha256 })
    }

    @Test fun everyFileIsASeekableZstandardObjectOnTheModelHost() {
        val files = (ModelCatalog.consented + ModelCatalog.VAD).flatMap { it.artifacts }
        for (file in files) {
            val download = file.download!!
            assertEquals("${file.name}.zst", download.name)
            assertEquals("https://dist.gocassini.com/models/files/${download.sha256}/${file.name}.zst", download.url)
            assertTrue(file.url.isEmpty())
        }
        assertEquals(listOf("dist.gocassini.com"), ModelCatalog.DOWNLOAD_HOSTS)
        assertEquals("https://dist.gocassini.com/manifest.json", ModelCatalog.DIST_MANIFEST_URL)
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

    @Test fun theDiarizerAndVoiceprintAreInstalledApart() {
        install(ModelCatalog.VOICEPRINT)
        assertTrue(ModelCatalog.VOICEPRINT.ready(folder.root))
        assertFalse(ModelCatalog.DIARIZATION.ready(folder.root))
    }

    @Test fun theConsentDialogQuotesTheCompressedBytesFetched() {
        assertEquals(ModelCatalog.parakeetArtifacts.sumOf { it.download!!.size }, ModelCatalog.parakeetBytes)
        assertEquals(ModelCatalog.diarizationModel.download!!.size, ModelCatalog.speakerBytes)
        assertEquals(ModelCatalog.voiceprintModel.download!!.size, ModelCatalog.voiceprintBytes)
        assertEquals(440L, ModelCatalog.mebibytes(ModelCatalog.parakeetBytes))
        assertEquals(26L, ModelCatalog.mebibytes(ModelCatalog.voiceprintBytes))
        assertEquals(1L, ModelCatalog.mebibytes(1))
    }

    /** scripts/ci-models.json gives the CI models workflow the same files, URLs and markers as the catalog. */
    @Test fun theCiModelListIsTheCatalog() {
        val file = File(System.getProperty("cassini.ciModels", "../scripts/ci-models.json")!!)
        val listed = JSONObject(file.readText()).getJSONArray("models")
        // Every model that the app installs: the consented download and the speech detector.
        val catalog = (ModelCatalog.consented + ModelCatalog.VAD).sortedBy { it.directory }
        fun artifact(json: JSONObject): Artifact = Artifact(
            json.getString("name"),
            json.getLong("size"),
            json.getString("sha256"),
            json.optString("url"),
            json.optJSONObject("download")?.let(::artifact),
        )
        val models = (0 until listed.length()).map { index ->
            val json = listed.getJSONObject(index)
            val artifacts = json.getJSONArray("artifacts")
            val model = ModelDescriptor(
                json.getString("id"),
                json.getString("sourceSha256"),
                (0 until artifacts.length()).map { artifact(artifacts.getJSONObject(it)) },
            )
            assertEquals(model.directory, json.getString("directory"))
            assertEquals(model.markerFile, json.getString("marker"))
            assertEquals(model.markerValue, json.getString("markerValue"))
            model
        }
        assertEquals(catalog, models.sortedBy { it.directory })
    }
}
