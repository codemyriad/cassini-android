package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The model host's manifest against the compiled pins. `manifest-2026-10-27.json` is the published manifest of that
 * day, byte for byte: every model of the catalog must resolve in it, and any change to a pinned pair or file must
 * refuse the download.
 */
class DistManifestTest {
    private val published: String = checkNotNull(javaClass.getResourceAsStream("/model-host/manifest-2026-10-27.json"))
        .bufferedReader().use { it.readText() }

    private val all = listOf(ModelCatalog.PARAKEET, ModelCatalog.VAD, ModelCatalog.DIARIZATION, ModelCatalog.VOICEPRINT)

    private fun refusal(text: String, model: ModelDescriptor, policy: RedirectPolicy = RedirectPolicy.DOWNLOADS) =
        try {
            DistManifest.parse(text).urls(model, policy)
            fail("The manifest must refuse ${model.id}")
            error("unreachable")
        } catch (error: UserFacingException) {
            error
        }

    /** The published manifest with [change] applied to its JSON. */
    private fun edited(change: (JSONObject) -> Unit): String = JSONObject(published).also(change).toString()

    private fun JSONObject.model(id: String): JSONObject {
        val models = getJSONArray("models")
        return (0 until models.length()).map(models::getJSONObject).single { it.getString("id") == id }
    }

    private fun JSONObject.artifact(id: String): JSONObject {
        val artifacts = getJSONArray("artifacts")
        return (0 until artifacts.length()).map(artifacts::getJSONObject).single { it.getString("id") == id }
    }

    @Test fun everyPinnedModelResolvesToItsFilesOnTheModelHost() {
        val manifest = DistManifest.parse(published)
        for (model in all) {
            val urls = manifest.urls(model)
            assertEquals(model.artifacts.map { it.name }, urls.keys.toList())
            for (artifact in model.artifacts) {
                // The manifest's URL is the content-addressed location the catalog names for the notices and CI.
                assertEquals(artifact.download!!.url, urls.getValue(artifact.name))
                assertTrue(urls.getValue(artifact.name).startsWith("https://dist.gocassini.com/models/files/"))
            }
        }
    }

    @Test fun aPairTheManifestNoLongerListsIsNotDownloaded() {
        // A newer export under the same id: the app keeps its pinned source hash and refuses.
        val newer = edited { it.model("silero-vad").put("source_sha256", "0".repeat(64)) }
        val error = refusal(newer, ModelCatalog.VAD)
        assertEquals(Failure.VERIFY, error.failure)
        assertTrue(error.message!!, "does not list silero-vad" in error.message!!)
        val gone = edited { json ->
            val models = json.getJSONArray("models")
            val index = (0 until models.length()).first { models.getJSONObject(it).getString("id") == "silero-vad" }
            models.remove(index)
        }
        assertEquals(Failure.VERIFY, refusal(gone, ModelCatalog.VAD).failure)
        // Other models are not affected.
        DistManifest.parse(gone).urls(ModelCatalog.PARAKEET)
    }

    @Test fun anArtifactThatDisagreesWithAnyPinIsNotDownloaded() {
        val encoder = ModelCatalog.parakeetArtifacts.first()
        val id = encoder.download!!.sha256
        val changes = listOf<(JSONObject) -> Unit>(
            { it.artifact(id).put("size", encoder.download!!.size + 1) },
            { it.artifact(id).put("sha256", "1".repeat(64)) },
            { it.artifact(id).put("uncompressed_size", encoder.size - 1) },
            { it.artifact(id).put("uncompressed_sha256", "2".repeat(64)) },
            { it.artifact(id).remove("uncompressed_sha256") },
        )
        for (change in changes) {
            assertEquals(Failure.VERIFY, refusal(edited(change), ModelCatalog.PARAKEET).failure)
        }
    }

    private fun extra(required: Boolean) = JSONObject().put("path", "extra.onnx")
        .put("artifact", "models/files/x/extra.onnx.zst").put("required", required)

    @Test fun aFileListThatDiffersFromThePinsIsNotDownloaded() {
        val otherArtifact = edited { json ->
            json.model("parakeet-ultra-0.6b-int8").getJSONArray("files").getJSONObject(0)
                .put("artifact", "models/files/${"3".repeat(64)}/encoder.int8.onnx.zst")
        }
        assertEquals(Failure.VERIFY, refusal(otherArtifact, ModelCatalog.PARAKEET).failure)
        val extraFile = edited { it.model("silero-vad").getJSONArray("files").put(extra(required = true)) }
        assertEquals(Failure.VERIFY, refusal(extraFile, ModelCatalog.VAD).failure)
        val optionalExtra = edited { it.model("silero-vad").getJSONArray("files").put(extra(required = false)) }
        DistManifest.parse(optionalExtra).urls(ModelCatalog.VAD)
    }

    @Test fun aFileElsewhereThanTheModelHostIsNotDownloaded() {
        val id = ModelCatalog.sileroVad.download!!.sha256
        for (url in listOf(
            "http://dist.gocassini.com/models/files/$id/silero_vad.onnx.zst",
            "https://example.com/models/files/$id/silero_vad.onnx.zst",
            "https://dist.gocassini.com.example.com/silero_vad.onnx.zst",
            "not a url",
        )) {
            val moved = edited { it.artifact(id).put("url", url) }
            assertEquals(url, Failure.VERIFY, refusal(moved, ModelCatalog.VAD).failure)
        }
    }

    @Test fun onlyASchemaTwoManifestIsRead() {
        for (text in listOf(edited { it.put("schema_version", 3) }, edited { it.remove("schema_version") }, "<html>")) {
            try {
                DistManifest.parse(text)
                fail("Read $text")
            } catch (error: UserFacingException) {
                assertEquals(Failure.VERIFY, error.failure)
            }
        }
        val broken = edited { it.remove("artifacts") }
        assertEquals(Failure.VERIFY, refusal(broken, ModelCatalog.VAD).failure)
    }
}
