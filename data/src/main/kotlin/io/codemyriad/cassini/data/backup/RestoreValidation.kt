package io.codemyriad.cassini.data.backup

import io.codemyriad.cassini.core.backup.BackupManifest
import org.json.JSONException
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes

/** Only demonstrated corruption is disposable. Storage/permission failures remain retryable. */
internal class CorruptRestoreException(cause: Exception) : IOException(
    "Incomplete or damaged received library: ${cause.message}",
    cause,
)

internal object RestoreValidation {
    fun verify(stage: File) {
        try {
            val incoming = File(stage, "incoming")
            require(attributes(incoming).isDirectory) { "Unsafe restore root" }
            val inventory = File(incoming, BackupManifest.MANIFEST)
            val metadata = attributes(inventory)
            require(metadata.isRegularFile && metadata.size() <= BackupManifest.MAX_MANIFEST_BYTES) {
                "Invalid inventory"
            }
            require(attributes(File(stage, "receipts.tsv")).isRegularFile) { "Unsafe restore receipts" }
            val manifest = BackupManifest.read(inventory)
            RestoreReceipts.verify(stage, manifest)
            manifest.verify(incoming)
            val database = File(incoming, "cassini.db")
            if (Files.exists(database.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                val header = ByteArray(SQLITE_HEADER.length)
                DataInputStream(database.inputStream()).use { it.readFully(header) }
                val signature = SQLITE_HEADER.toByteArray(Charsets.US_ASCII)
                require(header.contentEquals(signature)) { "Invalid catalogue header" }
            }
        } catch (error: IllegalArgumentException) {
            reject(error)
        } catch (error: JSONException) {
            reject(error)
        } catch (error: NoSuchFileException) {
            reject(error)
        } catch (error: EOFException) {
            reject(error)
        }
    }

    private fun reject(error: Exception): Nothing = throw CorruptRestoreException(error)

    private fun attributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private const val SQLITE_HEADER = "SQLite format 3\u0000"
}
