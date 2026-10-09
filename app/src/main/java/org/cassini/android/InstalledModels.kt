package org.cassini.android

import org.cassini.core.Failure
import org.cassini.core.models.Artifact
import org.cassini.core.models.ArtifactFetcher
import org.cassini.core.models.ModelCatalog
import org.cassini.core.models.ModelDescriptor
import org.cassini.core.requireUser
import org.cassini.engine.asr.ParakeetFiles
import java.io.File

/**
 * The models of [ModelCatalog] under [filesDir], in the 0.1.0 directories with the 0.1.0 marker files. A model is
 * installed through [ArtifactFetcher]: its marker is deleted first and written only after every file verified.
 * [url] says where each artifact is fetched from; tests point it at a loopback server, and [usableSpace] stands in
 * for the free space of a model directory.
 *
 * An interrupted download keeps its `.part` file in the model directory so the next attempt resumes it. Nothing
 * deletes such a file until that model installs, so up to one model's size (about 650 MB for Parakeet) can stay in
 * filesDir after a cancelled or failed download. The space check counts it as already fetched.
 */
class InstalledModels(
    private val filesDir: File,
    private val usableSpace: (File) -> Long = File::getUsableSpace,
    private val url: (Artifact) -> String = Artifact::url,
) {
    data class Progress(val percent: Int, val verifying: Boolean = false)

    fun ready(model: ModelDescriptor) = model.ready(filesDir)

    /** The absolute path of [name] in [model]'s directory; by default its only file. */
    fun path(model: ModelDescriptor, name: String = model.artifacts.single().name) = model.path(filesDir, name)

    /** The recognizer's files, for the engine's [org.cassini.engine.asr.ParakeetDecoder]. */
    fun parakeet() = ModelCatalog.PARAKEET.let {
        ParakeetFiles(
            encoder = path(it, "encoder.int8.onnx"),
            decoder = path(it, "decoder.int8.onnx"),
            joiner = path(it, "joiner.int8.onnx"),
            tokens = path(it, "tokens.txt"),
        )
    }

    /** The speech detector, when it is installed. Without it recordings are cut at quiet points. */
    fun detector(): String? = path(ModelCatalog.VAD).takeIf { ready(ModelCatalog.VAD) }

    /**
     * Parakeet is fetched only after consent, so once it is here the user has agreed. A missing optional model is
     * retried by the run itself and must not bring the dialog back or block auto-transcribe.
     */
    fun consented() = ready(ModelCatalog.PARAKEET)

    /** Whether everything one consented download installs is here: speech recognition, speaker turns, voiceprints. */
    fun ready() = ModelCatalog.consented.all(::ready)

    /** Bytes still to fetch for the consented models, which is what the consent dialog quotes. */
    fun missingBytes() = ModelCatalog.consented.filterNot(::ready).sumOf { it.downloadBytes }

    /**
     * The bytes [install] still downloads for [model]'s uncompressed artifacts: a file of the pinned size and the
     * kept `.part` prefix of an unfinished one are not fetched again.
     */
    fun bytesToFetch(model: ModelDescriptor): Long {
        val directory = model.directory(filesDir)
        return model.artifacts.filter { File(directory, it.name).length() != it.size }
            .sumOf { ArtifactFetcher.bytesToFetch(it, directory) }
    }

    fun install(model: ModelDescriptor, progress: (Progress) -> Unit) {
        val directory = model.directory(filesDir)
        directory.mkdirs()
        if (model == ModelCatalog.PARAKEET) {
            requireUser(usableSpace(directory) > bytesToFetch(model) + FREE_SPACE_MARGIN, Failure.SPACE)
        }
        val marker = File(directory, model.markerFile)
        marker.delete()
        val download = model.download
        if (download != null) {
            val artifact = model.artifacts.single()
            ArtifactFetcher.fetchZstd(url(download), download, artifact, directory) { received, verifying ->
                // This screen shows no checking progress: only the end of the check is passed on, as before.
                if (!verifying || received == download.size) {
                    progress(Progress((received * PERCENT / download.size).toInt(), verifying))
                }
            }
        } else {
            val total = model.artifacts.sumOf { it.size }
            var completed = 0L
            model.artifacts.forEach { artifact ->
                ArtifactFetcher.fetch(url(artifact), artifact, directory) { received, verifying ->
                    if (!verifying || received == artifact.size) {
                        progress(Progress(((completed + received) * PERCENT / total).toInt(), verifying))
                    }
                }
                completed += artifact.size
            }
        }
        // As in 0.1.0, a cancelled speaker download is not marked; Parakeet and the detector are. Both speaker
        // models, and only they, live in the diarizer's directory.
        if (model.directory == ModelCatalog.DIARIZATION.directory) {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        }
        marker.writeText(model.markerValue)
    }

    companion object {
        private const val PERCENT = 100
        private const val FREE_SPACE_MARGIN = 128 * 1024 * 1024L

        /** Files of the earlier pyannote + ERes2Net pipeline. Removing them never touches recordings. */
        private val retired = listOf(
            "segmentation.int8.onnx",
            "embedding.onnx",
            "segmentation.int8.onnx.part",
            "embedding.onnx.part",
        )

        /** Upgrade cleanup is restricted to the retired FP32 model's private download directory. */
        fun removeLegacyModel(filesDir: File): Boolean {
            val legacy = File(filesDir, "parakeet-v3-fp32")
            return !legacy.exists() || legacy.deleteRecursively()
        }

        /** Removes the retired speaker models and a `verified` marker that names another model. */
        fun removeRetiredModels(filesDir: File): Boolean {
            val directory = File(filesDir, "speaker-models")
            val verified = File(directory, "verified")
            if (verified.isFile && verified.readText() != ModelCatalog.diarizationModel.sha256) verified.delete()
            return retired.map { File(directory, it) }.all { !it.exists() || it.delete() }
        }
    }
}
