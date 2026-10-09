package org.cassini.android

import java.io.File

/**
 * CAM++ speaker-embedding model, used to recognise saved voices across notes. Fetched verbatim from the pinned
 * sherpa-onnx release and verified; voiceprints it produces stay on this phone.
 */
internal class VoiceprintModel(private val directory: File, private val url: String = SOURCE) {
    companion object {
        const val FILE = "voiceprint-campplus.onnx"
        const val MARKER = "verified-voiceprint"
        const val SOURCE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx"
        val model = ModelStore.Artifact(FILE, 29596978, "357a834f702b80161e5b981182c038e18553c1f2ca752ed6cec2052365d4129b")
        /** Shares the speaker-models directory, whose retired-file cleanup never lists these names. */
        fun inFiles(files: File) = VoiceprintModel(File(files, "speaker-models"))
    }
    val modelPath get() = File(directory, FILE).absolutePath
    fun ready() = File(modelPath).length() == model.size &&
        File(directory, MARKER).takeIf { it.isFile }?.readText() == model.sha256

    fun install(progress: (ModelStore.Progress) -> Unit) {
        directory.mkdirs()
        File(directory, MARKER).delete()
        ModelStore.fetch(url, model, directory) { received, verifying ->
            progress(ModelStore.Progress((received * 100 / model.size).toInt(), verifying))
        }
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        File(directory, MARKER).writeText(model.sha256)
    }
}
