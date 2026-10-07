package org.cassini.android

import java.io.File

/** The optional speaker model, verified before use. Never part of the APK or an ASR download. */
internal class DiarizationModels(private val directory: File) {
    companion object {
        /** NVIDIA Nemotron-3-Diarization, exported to INT8 ONNX for sherpa-onnx's Sortformer runtime. */
        const val SOURCE_REVISION = "f667ed73aee57d40cc39428eb768b4fd87a0a29e"
        val model = ModelStore.Artifact("nemotron-3-diarization.int8.onnx", 103941758,
            "01d1f893f394cc0418ae78425d216ed62c334c0125e650135332a7a8e595f168")
        /** Seekable Zstandard copy of [model] on Cassini's model host, verified before decompression. */
        val download = ModelStore.Artifact("nemotron-3-diarization.int8.onnx.zst", 64982914,
            "51249515e4cb49dc00c5d5dc0a41ac59aa91157d689ec9586b3f4bf567c3f602")
        const val URL = "https://dist.gocassini.com/models/files/51249515e4cb49dc00c5d5dc0a41ac59aa91157d689ec9586b3f4bf567c3f602/model.int8.onnx.zst"
        fun inFiles(files: File) = DiarizationModels(File(files, "speaker-models"))

        /** Files of the earlier pyannote + ERes2Net pipeline. Removing them never touches recordings. */
        private val retired = listOf("segmentation.int8.onnx", "embedding.onnx", "segmentation.int8.onnx.part", "embedding.onnx.part")
        internal fun removeRetiredModels(filesDir: File): Boolean {
            val directory = File(filesDir, "speaker-models")
            val verified = File(directory, "verified")
            if (verified.isFile && verified.readText() != model.sha256) verified.delete()
            return retired.map { File(directory, it) }.all { !it.exists() || it.delete() }
        }
    }
    val modelPath get() = File(directory, model.name).absolutePath
    fun ready() = File(modelPath).length() == model.size &&
        File(directory, "verified").takeIf { it.isFile }?.readText() == model.sha256

    fun install(progress: (ModelStore.Progress) -> Unit) {
        directory.mkdirs()
        File(directory, "verified").delete()
        ModelStore.fetchZstd(URL, download, model, directory) { received, verifying ->
            progress(ModelStore.Progress((received * 100 / download.size).toInt(), verifying))
        }
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        File(directory, "verified").writeText(model.sha256)
    }
}
