package io.codemyriad.cassini.data.notes

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

private const val MAX_ARCHIVE_BYTES = 4L shl 30
private const val MAX_ARCHIVE_INPUT_BYTES = MAX_ARCHIVE_BYTES + (16L shl 20)
private const val MAX_ENTRY_NAME = 1_024

/** Archive limits count actual bytes, including ignored entries; nested archives are never expanded. */
data class ArchiveLimits(
    val entries: Int = 1_000,
    val expandedBytes: Long = MAX_ARCHIVE_BYTES,
    val inputBytes: Long = MAX_ARCHIVE_INPUT_BYTES,
)

data class ImportSummary(val imported: Int = 0, val duplicates: Int = 0, val ignored: Int = 0, val failed: Int = 0) {
    operator fun plus(other: ImportSummary) = ImportSummary(
        imported + other.imported,
        duplicates + other.duplicates,
        ignored + other.ignored,
        failed + other.failed,
    )

    fun add(outcome: ImportOutcome) = this + when (outcome) {
        is ImportOutcome.Created -> ImportSummary(imported = 1)
        is ImportOutcome.Existing -> ImportSummary(duplicates = 1)
        is ImportOutcome.Rejected -> ImportSummary(failed = 1)
    }
}

/** Streaming ZIP transport. Call on IO; the caller owns streams and the private scratch directory. */
class NoteArchives(private val limits: ArchiveLimits = ArchiveLimits()) {
    suspend fun read(
        input: InputStream,
        scratch: File,
        progress: (ImportSummary) -> Unit = {},
        consume: suspend (File, String) -> ImportOutcome,
    ): ImportSummary {
        var summary = ImportSummary()
        var total = 0L
        var count = 0
        ZipInputStream(BoundedInput(input, limits.inputBytes)).use { zip ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zip.nextEntry ?: break
                guard(++count <= limits.entries && safeName(entry.name), "Unsafe archive entry")
                guard(entry.size <= limits.expandedBytes - total, "Archive is too large")
                val (bytes, result) = readEntry(zip, entry, scratch, limits.expandedBytes - total, consume)
                total += bytes
                summary += result
                progress(summary)
                zip.closeEntry()
            }
        }
        return summary
    }

    private suspend fun readEntry(
        zip: ZipInputStream,
        entry: ZipEntry,
        scratch: File,
        remaining: Long,
        consume: suspend (File, String) -> ImportOutcome,
    ): Pair<Long, ImportSummary> {
        val staged = if (!entry.isDirectory && audio(entry.name)) {
            File.createTempFile("entry-", ".tmp", scratch)
        } else {
            null
        }
        try {
            val bytes = staged?.outputStream().use { copyEntry(zip, it, remaining) }
            val summary = if (staged != null) {
                ImportSummary().add(consume(staged, entry.name.substringAfterLast('/')))
            } else {
                ImportSummary(ignored = 1)
            }
            return bytes to summary
        } finally {
            staged?.delete()
        }
    }

    private suspend fun copyEntry(input: InputStream, output: OutputStream?, remaining: Long): Long {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) return total
            total += n
            guard(total <= remaining, "Archive is too large")
            output?.write(buffer, 0, n)
        }
    }

    private fun guard(valid: Boolean, message: String) {
        if (!valid) throw IOException(message)
    }

    /** Each input is an immutable private snapshot, so CRC/length and the stored entry describe the same bytes. */
    suspend fun write(output: OutputStream, files: List<Pair<String, File>>) {
        require(files.size <= limits.entries) { "Too many notes" }
        var total = 0L
        val names = HashSet<String>()
        ZipOutputStream(output).use { zip ->
            for ((suggested, file) in files) {
                currentCoroutineContext().ensureActive()
                total += file.length()
                if (total > limits.expandedBytes) throw IOException("Archive is too large")
                val crc = CRC32()
                file.inputStream().use { pump(it) { bytes, n -> crc.update(bytes, 0, n) } }
                val entry = ZipEntry(unique(suggested, names)).apply {
                    method = ZipEntry.STORED
                    size = file.length()
                    compressedSize = size
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                file.inputStream().use { pump(it) { bytes, n -> zip.write(bytes, 0, n) } }
                zip.closeEntry()
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

    private fun unique(suggested: String, used: MutableSet<String>): String {
        require(safeName(suggested) && '/' !in suggested)
        var name = suggested
        var suffix = 2
        while (!used.add(name.lowercase(Locale.ROOT))) {
            name = suggested.substringBeforeLast('.') + " ($suffix)." + suggested.substringAfterLast('.')
            suffix++
        }
        return name
    }

    private class BoundedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        private var count = 0L
        override fun read(): Int = super.read().also { if (it >= 0) add(1) }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int =
            `in`.read(bytes, offset, length).also { if (it > 0) add(it) }
        private fun add(n: Int) {
            count += n
            if (count > limit) throw IOException("Archive input is too large")
        }
    }

    companion object {
        private val AUDIO = setOf(
            "opus",
            "ogg",
            "wav",
            "mp3",
            "m4a",
            "aac",
            "flac",
            "3gp",
            "amr",
            "awb",
            "mp4",
            "m4b",
            "oga",
            "webm",
            "mka",
            "mkv",
            "ts",
            "3gpp",
            "3g2",
        )
        fun audio(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in AUDIO
        fun safeName(name: String): Boolean = name.isNotEmpty() && name.length <= MAX_ENTRY_NAME &&
            !name.startsWith('/') && '\\' !in name && ':' !in name && '\u0000' !in name &&
            name.trimEnd('/').split('/').none { it == ".." || it == "." || it.isEmpty() }
    }
}
