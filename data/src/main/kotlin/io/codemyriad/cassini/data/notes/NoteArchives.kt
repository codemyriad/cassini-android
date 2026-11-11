package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.audio.Limits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipOutputStream

/** Published D96 limits; GB is decimal. A single entry retains the existing app import limit. */
data class ArchiveLimits(
    val entries: Int = 2_000,
    val expandedBytes: Long = 20_000_000_000L,
    val ratio: Int = 100,
    val fileBytes: Long = Limits.MAX_IMPORT_BYTES,
    val inputBytes: Long = Long.MAX_VALUE,
)

enum class ArchiveFailure { DAMAGED, ENCRYPTED, UNSAFE_PATHS, TOO_LARGE, EMPTY }

class ArchiveRejected(
    val reason: ArchiveFailure,
    val summary: ImportSummary = ImportSummary(),
) : IOException(reason.name)

data class ImportSummary(
    val imported: Int = 0,
    val duplicates: Int = 0,
    val unsupported: Int = 0,
    val failed: Int = 0,
    val archiveFailures: Set<ArchiveFailure> = emptySet(),
) {
    operator fun plus(other: ImportSummary) = ImportSummary(
        imported + other.imported,
        duplicates + other.duplicates,
        unsupported + other.unsupported,
        failed + other.failed,
        archiveFailures + other.archiveFailures,
    )

    fun add(outcome: ImportOutcome) = this + when (outcome) {
        is ImportOutcome.Created -> ImportSummary(imported = 1)
        is ImportOutcome.Existing -> ImportSummary(duplicates = 1)
        is ImportOutcome.Rejected -> ImportSummary(failed = 1)
    }
}

/** Central-directory preflight, then bounded validation/staging, then normal imports. No path is extracted. */
class NoteArchives(private val limits: ArchiveLimits = ArchiveLimits()) {
    suspend fun read(
        input: InputStream,
        scratch: File,
        progress: (ImportSummary) -> Unit = {},
        consume: suspend (File, String) -> ImportOutcome,
    ): ImportSummary {
        val archive = File.createTempFile("archive-", ".zip", scratch)
        val staged = ArrayList<Pair<File, String>>()
        try {
            spool(input, archive)
            val entries = directory(archive)
            if (entries.none { it.supported }) {
                val summary = ImportSummary(unsupported = entries.count { !it.directory })
                throw ArchiveRejected(ArchiveFailure.EMPTY, summary)
            }
            val reader = ArchiveEntries(archive, limits)
            var summary = ImportSummary()
            for (entry in entries) {
                currentCoroutineContext().ensureActive()
                val result = reader.stage(entry, scratch)
                if (result != null) {
                    staged += result to entry.name
                } else if (entry.supported) {
                    summary += ImportSummary(failed = 1)
                } else if (!entry.directory) {
                    summary += ImportSummary(unsupported = 1)
                }
            }
            progress(summary)
            for ((file, name) in staged) {
                summary = summary.add(consume(file, name))
                progress(summary)
                file.delete()
            }
            return summary
        } finally {
            staged.forEach { (file, _) -> file.delete() }
            archive.delete()
        }
    }

    private suspend fun directory(file: File): List<ArchiveEntry> = try {
        val context = currentCoroutineContext()
        ZipDirectory.inspect(file, limits) { context.ensureActive() }
    } catch (error: ArchiveRejected) {
        throw error
    } catch (_: IOException) {
        throw ArchiveRejected(ArchiveFailure.DAMAGED)
    }

    private suspend fun spool(input: InputStream, target: File) {
        var total = 0L
        var nextSpaceCheck = 0L
        target.outputStream().use { output ->
            pump(input) { bytes, n ->
                total += n
                archiveCheck(total <= limits.inputBytes, ArchiveFailure.TOO_LARGE)
                if (total >= nextSpaceCheck) {
                    nextSpaceCheck = total + SPACE_CHECK_BYTES
                    if (target.usableSpace < Limits.MIN_FREE_BYTES) throw IOException("Not enough space")
                }
                output.write(bytes, 0, n)
            }
        }
    }

    /** The caller creates and releases one snapshot around each writer.add call. Nothing stages the complete ZIP. */
    suspend fun write(output: OutputStream, block: suspend (StoredArchiveWriter) -> Unit) {
        ZipOutputStream(output.buffered()).use { zip -> block(StoredArchiveWriter(zip, limits)) }
    }

    /** Convenience for immutable files already owned by a caller; ownership of those files stays with the caller. */
    suspend fun write(output: OutputStream, files: List<Pair<String, File>>) {
        require(files.size <= limits.entries) { "Too many notes" }
        write(output) { writer ->
            for ((name, file) in files) {
                val crc = CRC32()
                file.inputStream().use { pump(it) { bytes, n -> crc.update(bytes, 0, n) } }
                writer.add(name, file, crc.value)
            }
        }
    }

    private suspend fun pump(input: InputStream, write: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) return
            write(buffer, n)
        }
    }

    companion object {
        private const val SPACE_CHECK_BYTES = 8L shl 20
        private val DRIVE = Regex("^[A-Za-z]:/")
        private val AUDIO = setOf(
            "opus", "ogg", "wav", "mp3", "m4a", "aac", "flac", "3gp", "amr", "awb",
            "mp4", "m4b", "oga", "webm", "mka", "mkv", "ts", "3gpp", "3g2",
        )
        fun audio(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO
        fun safeName(name: String): Boolean {
            val path = name.replace('\\', '/')
            return path.isNotEmpty() && !path.startsWith('/') && !DRIVE.containsMatchIn(path) &&
                '\u0000' !in path && path.split('/').none { it == ".." }
        }
    }
}
