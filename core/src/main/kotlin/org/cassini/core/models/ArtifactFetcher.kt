package org.cassini.core.models

import org.cassini.core.Failure
import org.cassini.core.UserFacingException
import org.cassini.core.requireUser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads pinned artifacts. A file is installed only after its size and SHA-256 match; a verified file is never replaced. */
object ArtifactFetcher {
    /** Hex SHA-256 of [file], checking for interruption between blocks. */
    fun digest(file: File): String {
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

    /**
     * Places [artifact] in [directory] unless a verified copy is already there. The download goes to a
     * `.part` file that replaces nothing until its size and SHA-256 match. [progress] receives the bytes
     * done so far and whether they are being verified rather than downloaded.
     */
    fun fetch(url: String, artifact: Artifact, directory: File, progress: (Long, Boolean) -> Unit) {
        val destination = File(directory, artifact.name)
        if (destination.length() == artifact.size && digest(destination) == artifact.sha256) {
            progress(artifact.size, true)
            return
        }
        requireUser(directory.usableSpace > artifact.size + 32 * 1024 * 1024, Failure.SPACE)
        val partial = File(directory, "${artifact.name}.part")
        val connection = URL(url).openConnection() as HttpURLConnection
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
                            progress(received, false)
                            lastUpdate = System.currentTimeMillis()
                        }
                    }
                }
            }
            progress(artifact.size, true)
            requireUser(partial.length() == artifact.size && digest(partial) == artifact.sha256, Failure.VERIFY)
            requireUser(partial.renameTo(destination), Failure.INSTALL)
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    /**
     * Like [fetch] for a Zstandard-compressed [download] of [artifact]. The compressed file is verified,
     * then decompressed to a `.part` file that replaces nothing until the decompressed size and SHA-256
     * match. Neither temporary file is kept.
     */
    fun fetchZstd(url: String, download: Artifact, artifact: Artifact, directory: File,
                           progress: (Long, Boolean) -> Unit) {
        val destination = File(directory, artifact.name)
        if (destination.length() == artifact.size && digest(destination) == artifact.sha256) {
            progress(download.size, true)
            return
        }
        requireUser(directory.usableSpace > download.size + artifact.size + 32 * 1024 * 1024, Failure.SPACE)
        val compressed = File(directory, download.name)
        try {
            fetch(url, download, directory, progress)
            decompress(compressed, artifact, directory)
        } finally {
            compressed.delete()
        }
    }

    /** Decompresses a verified Zstandard [source] into [directory] as [artifact], or fails without a file. */
    fun decompress(source: File, artifact: Artifact, directory: File) {
        val destination = File(directory, artifact.name)
        val partial = File(directory, "${artifact.name}.part")
        try {
            // Seekable Zstandard ends with a skippable seek-table frame, which the decoder skips.
            com.github.luben.zstd.ZstdInputStream(source.inputStream().buffered()).use { input ->
                partial.outputStream().buffered().use { output ->
                    val bytes = ByteArray(128 * 1024)
                    var written = 0L
                    while (true) {
                        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                        val count = input.read(bytes)
                        if (count < 0) break
                        written += count
                        requireUser(written <= artifact.size, Failure.VERIFY)
                        output.write(bytes, 0, count)
                    }
                }
            }
            requireUser(partial.length() == artifact.size && digest(partial) == artifact.sha256, Failure.VERIFY)
            requireUser(partial.renameTo(destination), Failure.INSTALL)
        } catch (e: java.io.IOException) {
            throw UserFacingException(Failure.VERIFY, e.message ?: "Zstandard")
        } finally {
            partial.delete()
        }
    }
}
