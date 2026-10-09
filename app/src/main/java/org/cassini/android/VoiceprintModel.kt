package org.cassini.android

import org.cassini.core.Failure
import org.cassini.core.models.ArtifactFetcher
import org.cassini.core.models.ModelCatalog
import org.cassini.core.requireUser
import java.io.File

/**
 * CAM++ speaker-embedding model, used to recognise saved voices across notes. Fetched verbatim from the pinned
 * sherpa-onnx release and verified; voiceprints it produces stay on this phone.
 */
internal class VoiceprintModel(private val directory: File, private val url: String = SOURCE) {
    companion object {
        val FILE = ModelCatalog.voiceprintModel.name
        val MARKER = ModelCatalog.VOICEPRINT.markerFile
        const val SOURCE = ModelCatalog.VOICEPRINT_URL
        val model = ModelCatalog.voiceprintModel

        /** Shares the speaker-models directory, whose retired-file cleanup never lists these names. */
        fun inFiles(files: File) = VoiceprintModel(ModelCatalog.VOICEPRINT.directory(files))
    }
    val modelPath get() = File(directory, FILE).absolutePath
    fun ready() = File(modelPath).length() == model.size &&
        File(directory, MARKER).takeIf { it.isFile }?.readText() == model.sha256

    fun install(progress: (ModelStore.Progress) -> Unit) {
        directory.mkdirs()
        File(directory, MARKER).delete()
        ArtifactFetcher.fetch(url, model, directory) { received, verifying ->
            progress(ModelStore.Progress((received * 100 / model.size).toInt(), verifying))
        }
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        File(directory, MARKER).writeText(model.sha256)
    }
}
