package org.cassini.android

import org.cassini.core.Failure
import org.cassini.core.models.ArtifactFetcher
import org.cassini.core.models.ModelCatalog
import org.cassini.core.requireUser
import java.io.File

/** The optional speaker model, verified before use. Never part of the APK or an ASR download. */
internal class DiarizationModels(private val directory: File) {
    companion object {
        /** NVIDIA Nemotron-3-Diarization, exported to INT8 ONNX for sherpa-onnx's Sortformer runtime. */
        const val SOURCE_REVISION = ModelCatalog.DIARIZATION_SOURCE_REVISION
        val model = ModelCatalog.diarizationModel

        /** Seekable Zstandard copy of [model] on Cassini's model host, verified before decompression. */
        val download = ModelCatalog.diarizationDownload
        const val URL = ModelCatalog.DIARIZATION_URL
        fun inFiles(files: File) = DiarizationModels(ModelCatalog.DIARIZATION.directory(files))

        /** Files of the earlier pyannote + ERes2Net pipeline. Removing them never touches recordings. */
        private val retired = listOf("segmentation.int8.onnx", "embedding.onnx", "segmentation.int8.onnx.part", "embedding.onnx.part")
        internal fun removeRetiredModels(filesDir: File): Boolean {
            val directory = File(filesDir, "speaker-models")
            val verified = File(directory, "verified")
            if (verified.isFile && verified.readText() != model.sha256) verified.delete()
            return retired.map { File(directory, it) }.all { !it.exists() || it.delete() }
        }
    }
    private val marker = File(directory, ModelCatalog.DIARIZATION.markerFile)
    val modelPath get() = File(directory, model.name).absolutePath
    fun ready() = File(modelPath).length() == model.size && marker.isFile && marker.readText() == model.sha256

    fun install(progress: (ModelStore.Progress) -> Unit) {
        directory.mkdirs()
        marker.delete()
        ArtifactFetcher.fetchZstd(URL, download, model, directory) { received, verifying ->
            progress(ModelStore.Progress((received * 100 / download.size).toInt(), verifying))
        }
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        marker.writeText(model.sha256)
    }
}
