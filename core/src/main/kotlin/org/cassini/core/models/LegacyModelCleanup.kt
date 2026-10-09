package org.cassini.core.models

import java.io.File

/** Startup cleanup of retired model downloads. It only ever touches the retired models' own files. */
object LegacyModelCleanup {
    /** Upgrade cleanup is restricted to the retired model's private download directory. */
    fun removeLegacyModel(filesDir: File): Boolean {
        val legacy = File(filesDir, "parakeet-v3-fp32")
        return !legacy.exists() || legacy.deleteRecursively()
    }

    /** Files of the earlier pyannote + ERes2Net pipeline. Removing them never touches recordings. */
    private val retired =
        listOf("segmentation.int8.onnx", "embedding.onnx", "segmentation.int8.onnx.part", "embedding.onnx.part")

    /** Removes the retired speaker files and revokes a `verified` marker that names another model. */
    fun removeRetiredModels(filesDir: File): Boolean {
        val directory = File(filesDir, ModelCatalog.DIARIZATION.directory)
        val verified = File(directory, ModelCatalog.DIARIZATION.markerFile)
        if (verified.isFile && verified.readText() != ModelCatalog.diarizationModel.sha256) verified.delete()
        return retired.map { File(directory, it) }.all { !it.exists() || it.delete() }
    }
}
