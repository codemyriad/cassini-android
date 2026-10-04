package org.cassini.android

import java.io.File

/** Optional speaker models, verified before use. Never part of the APK or an ASR download. */
internal class DiarizationModels(private val directory: File) {
    companion object {
        const val SEGMENTATION_REVISION = "9403a6902bb58e3d5ae8c7e77c3422de279db2e0"
        val segmentation = ModelStore.Artifact("segmentation.int8.onnx", 1540506,
            "d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d")
        val embedding = ModelStore.Artifact("embedding.onnx", 39593761,
            "1a331345f04805badbb495c775a6ddffcdd1a732567d5ec8b3d5749e3c7a5e4b")
        const val SEGMENTATION_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/$SEGMENTATION_REVISION/model.int8.onnx"
        const val EMBEDDING_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_eres2net_base_sv_zh-cn_3dspeaker_16k.onnx"
        val verification = "${segmentation.sha256}\n${embedding.sha256}"
        fun inFiles(files: File) = DiarizationModels(File(files, "speaker-models"))
    }
    val segmentationPath get() = File(directory, segmentation.name).absolutePath
    val embeddingPath get() = File(directory, embedding.name).absolutePath
    fun ready() = File(segmentationPath).length() == segmentation.size && File(embeddingPath).length() == embedding.size &&
        File(directory, "verified").takeIf { it.isFile }?.readText() == verification

    fun install(progress: (ModelStore.Progress) -> Unit) {
        directory.mkdirs()
        File(directory, "verified").delete()
        val total = segmentation.size + embedding.size
        var completed = 0L
        for ((artifact, url) in listOf(segmentation to SEGMENTATION_URL, embedding to EMBEDDING_URL)) {
            ModelStore.fetch(url, artifact, directory) { received, verifying ->
                progress(ModelStore.Progress(((completed + received) * 100 / total).toInt(), verifying))
            }
            completed += artifact.size
        }
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        File(directory, "verified").writeText(verification)
    }
}
