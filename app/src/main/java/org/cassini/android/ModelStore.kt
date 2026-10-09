package org.cassini.android

import org.cassini.core.Failure
import org.cassini.core.models.Artifact
import org.cassini.core.models.ArtifactFetcher
import org.cassini.core.models.ModelCatalog
import org.cassini.core.requireUser
import org.cassini.engine.asr.ParakeetFiles
import java.io.File

/** The Parakeet install in [directory] and the speech detector, over the pins of [ModelCatalog]. */
class ModelStore(private val directory: File) {
    data class Progress(val percent: Int, val verifying: Boolean = false)
    companion object {
        const val REVISION = ModelCatalog.PARAKEET_REVISION
        /** Upgrade cleanup is restricted to the retired model's private download directory. */
        internal fun removeLegacyModel(filesDir: File): Boolean {
            val legacy = File(filesDir, "parakeet-v3-fp32")
            return !legacy.exists() || legacy.deleteRecursively()
        }
        const val REPOSITORY = ModelCatalog.PARAKEET_REPOSITORY
        val int8Artifacts: List<Artifact> = ModelCatalog.parakeetArtifacts

        /** Silero VAD for chunked decoding. Installed on its own so an existing Parakeet install stays ready without it. */
        val sileroVad = ModelCatalog.sileroVad
        const val SILERO_VAD_URL = ModelCatalog.SILERO_VAD_URL
        fun vadDirectory(filesDir: File) = ModelCatalog.VAD.directory(filesDir)
        fun vadPath(filesDir: File) = ModelCatalog.VAD.path(filesDir, sileroVad.name)
        fun vadReady(filesDir: File) = ModelCatalog.VAD.ready(filesDir)

        fun installVad(filesDir: File, progress: (Progress) -> Unit) {
            val directory = vadDirectory(filesDir)
            directory.mkdirs()
            File(directory, ModelCatalog.VAD.markerFile).delete()
            ArtifactFetcher.fetch(SILERO_VAD_URL, sileroVad, directory) { received, verifying ->
                progress(Progress((received * 100 / sileroVad.size).toInt(), verifying))
            }
            File(directory, ModelCatalog.VAD.markerFile).writeText(ModelCatalog.VAD.markerValue)
        }
    }

    val revision = REVISION
    val repository = REPOSITORY
    val precision = ModelCatalog.PARAKEET_PRECISION
    private val artifacts = int8Artifacts
    private val marker = File(directory, ModelCatalog.PARAKEET.markerFile)
    fun modelPath(component: String) = path("$component.int8.onnx")

    /** The recognizer's files in this install, for the engine's [org.cassini.engine.asr.ParakeetDecoder]. */
    fun files() = ParakeetFiles(modelPath("encoder"), modelPath("decoder"), modelPath("joiner"), path("tokens.txt"))

    fun path(name: String) = File(directory, name).absolutePath
    fun ready() = artifacts.all {
        File(directory, it.name).length() == it.size
    } && marker.isFile && marker.readText() == revision

    fun install(progress: (Progress) -> Unit) {
        directory.mkdirs()
        val remaining = artifacts.filter { File(directory, it.name).length() != it.size }.sumOf { it.size }
        requireUser(directory.usableSpace > remaining + 128 * 1024 * 1024L, Failure.SPACE)
        marker.delete()
        val total = artifacts.sumOf { it.size }
        var completed = 0L
        artifacts.forEach { artifact ->
            ArtifactFetcher.fetch(artifact.url, artifact, directory) { received, verifying ->
                progress(Progress(((completed + received) * 100 / total).toInt(), verifying))
            }
            completed += artifact.size
        }
        marker.writeText(revision)
    }
}
