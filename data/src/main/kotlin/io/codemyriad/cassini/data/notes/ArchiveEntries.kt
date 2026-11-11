package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.audio.Limits
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipEntry

/** Each directory entry is verified at its bounded data range; actual inflater consumption detects forged ratios. */
internal class ArchiveEntries(private val archive: File, private val limits: ArchiveLimits) {
    private var total = 0L

    suspend fun stage(entry: ArchiveEntry, scratch: File): File? {
        val staged = if (entry.supported && entry.size <= limits.fileBytes) {
            File.createTempFile("entry-", ".tmp", scratch)
        } else {
            null
        }
        var successful = false
        try {
            if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
                throw IOException("Unsupported ZIP compression")
            }
            val range = RangeInput(archive, entry.offset, entry.compressed)
            range.use { raw ->
                if (entry.method == ZipEntry.STORED) {
                    verify(raw, entry, staged) { raw.consumed }
                } else {
                    inflate(raw, entry, staged)
                }
            }
            successful = true
            return staged
        } catch (error: ArchiveRejected) {
            throw error
        } catch (_: IOException) {
            archiveCheck(!entry.directory)
            return null
        } finally {
            if (!successful) staged?.delete()
        }
    }

    private suspend fun inflate(raw: InputStream, entry: ArchiveEntry, staged: File?) {
        val inflater = Inflater(true)
        try {
            InflaterInputStream(raw, inflater).use { verify(it, entry, staged) { inflater.bytesRead } }
        } finally {
            inflater.end()
        }
    }

    private suspend fun verify(input: InputStream, entry: ArchiveEntry, staged: File?, compressed: () -> Long) {
        val crc = CRC32()
        var expanded = 0L
        var nextSpaceCheck = 0L
        staged?.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = boundedRead(input, buffer, expanded, compressed)
                if (n < 0) break
                expanded += n
                total += n
                archiveCheck(total <= limits.expandedBytes, ArchiveFailure.TOO_LARGE)
                // A directory cannot hide data, even when its declared size is zero.
                archiveCheck(!entry.directory)
                // This bound uses the complete compressed size, never the bytes consumed at an intermediate prefix.
                archiveCheck(!overRatio(expanded, entry.compressed, limits.ratio), ArchiveFailure.TOO_LARGE)
                if (staged != null && expanded >= nextSpaceCheck) {
                    nextSpaceCheck = expanded + SPACE_CHECK_BYTES
                    dataCheck(staged.usableSpace >= Limits.MIN_FREE_BYTES)
                }
                crc.update(buffer, 0, n)
                if (expanded <= entry.size) output?.write(buffer, 0, n)
            }
        }
        archiveCheck(!overRatio(expanded, compressed(), limits.ratio), ArchiveFailure.TOO_LARGE)
        dataCheck(expanded == entry.size && compressed() == entry.compressed && crc.value == entry.crc)
    }

    private fun boundedRead(input: InputStream, buffer: ByteArray, expanded: Long, compressed: () -> Long): Int = try {
        input.read(buffer)
    } catch (error: ArchiveRejected) {
        throw error
    } catch (error: IOException) {
        // A corrupt tail must not turn a runtime ratio refusal into an ignorable per-entry failure.
        archiveCheck(!overRatio(expanded, compressed(), limits.ratio), ArchiveFailure.TOO_LARGE)
        throw error
    }

    private fun dataCheck(valid: Boolean) {
        if (!valid) throw IOException("Incorrect or unavailable entry data")
    }

    private class RangeInput(file: File, offset: Long, private val size: Long) : InputStream() {
        private val input = FileInputStream(file).apply { channel.position(offset) }
        var consumed = 0L
            private set
        override fun read(): Int = if (consumed == size) -1 else input.read().also { if (it >= 0) consumed++ }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (consumed == size) return -1
            val n = input.read(buffer, offset, minOf(length.toLong(), size - consumed).toInt())
            if (n > 0) consumed += n
            return n
        }
        override fun close() = input.close()
    }

    private companion object {
        const val SPACE_CHECK_BYTES = 8L shl 20
    }
}
