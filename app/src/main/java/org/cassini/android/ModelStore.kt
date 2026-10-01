package org.cassini.android

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

class ModelStore(private val directory: File, val fp32: Boolean = false) {
    data class Artifact(val name: String, val size: Long, val sha256: String)
    data class Progress(val percent: Int, val verifying: Boolean = false)
    companion object {
        const val REVISION = "2bda32ec70b097a55adaa07d9a7173915b43cc78"
        const val REPOSITORY = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
        val int8Artifacts = listOf(
            Artifact("encoder.int8.onnx", 652184281, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
            Artifact("decoder.int8.onnx", 11845275, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
            Artifact("joiner.int8.onnx", 6355277, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
            Artifact("tokens.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        )
        val fp32Artifacts = listOf(
            Artifact("encoder.onnx", 41766257, "3eed7ce424bf8339ad09233533c687e2dbd07e74ccf5027b5e7344019ea373b0"),
            Artifact("encoder.weights", 2435420160, "3af3f51af5f2d01dbbf5af47d42c7962a2c205f11004254bb4f2b979862f39a8"),
            Artifact("decoder.onnx", 47233743, "d593cdb0e571f5a457ec2219af9968cbf6b0e8198e8f7839b40a8754593bf68c"),
            Artifact("joiner.onnx", 25286330, "b9b0bcf88ac571902e69a6536223ed2d94885e981b85045410f1403d53121a63"),
            Artifact("tokens.txt", 93939, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
        )
    }

    val revision = if (fp32) "1a468a35cbba69418f126de829e75261dea4a4e4" else REVISION
    val repository = if (fp32) REPOSITORY.removeSuffix("-int8") else REPOSITORY
    val precision = if (fp32) "FP32" else "INT8"
    private val artifacts = if (fp32) fp32Artifacts else int8Artifacts
    fun modelPath(component: String) = path("$component${if (fp32) "" else ".int8"}.onnx")

    fun path(name: String) = File(directory, name).absolutePath
    fun ready() = artifacts.all { File(directory, it.name).length() == it.size } &&
        File(directory, "verified").readTextOrNull() == revision

    private fun File.readTextOrNull() = if (isFile) readText() else null
    private fun digest(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                val count = input.read(bytes)
                if (count < 0) break
                hash.update(bytes, 0, count)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    fun install(progress: (Progress) -> Unit) {
        directory.mkdirs()
        val remaining = artifacts.filter { File(directory, it.name).length() != it.size }.sumOf { it.size }
        requireUser(directory.usableSpace > remaining + 128 * 1024 * 1024L, Failure.SPACE)
        File(directory, "verified").delete()
        val total = artifacts.sumOf { it.size }
        var completed = 0L
        artifacts.forEach { artifact ->
            val destination = File(directory, artifact.name)
            if (destination.length() == artifact.size && digest(destination) == artifact.sha256) {
                completed += artifact.size
                progress(Progress((completed * 100 / total).toInt(), true))
                return@forEach
            }
            requireUser(directory.usableSpace > artifact.size + 32 * 1024 * 1024, Failure.SPACE)
            val partial = File(directory, "${artifact.name}.part")
            val connection = URL("https://huggingface.co/$repository/resolve/$revision/${artifact.name}")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 30_000
            try {
                if (connection.responseCode != 200) throw UserFacingException(Failure.DOWNLOAD, "HTTP ${connection.responseCode}")
                connection.inputStream.buffered().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val bytes = ByteArray(128 * 1024)
                        var received = 0L
                        var lastUpdate = 0L
                        while (true) {
                            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                            val count = input.read(bytes)
                            if (count < 0) break
                            received += count
                            requireUser(received <= artifact.size, Failure.VERIFY)
                            output.write(bytes, 0, count)
                            if (System.currentTimeMillis() - lastUpdate > 500) {
                                progress(Progress(((completed + received) * 100 / total).toInt()))
                                lastUpdate = System.currentTimeMillis()
                            }
                        }
                    }
                }
                progress(Progress(((completed + artifact.size) * 100 / total).toInt(), true))
                requireUser(partial.length() == artifact.size && digest(partial) == artifact.sha256, Failure.VERIFY)
                requireUser(partial.renameTo(destination), Failure.INSTALL)
                completed += artifact.size
            } finally {
                connection.disconnect()
                partial.delete()
            }
        }
        File(directory, "verified").writeText(revision)
    }
}
