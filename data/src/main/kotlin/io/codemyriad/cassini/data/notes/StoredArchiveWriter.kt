package io.codemyriad.cassini.data.notes

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Streaming STORED ZIP entries from one immutable snapshot and its already-computed CRC. */
class StoredArchiveWriter internal constructor(private val zip: ZipOutputStream, private val limits: ArchiveLimits) {
    private val names = HashSet<String>()
    private var count = 0
    private var total = 0L
    val remainingBytes: Long get() = minOf(limits.fileBytes, limits.expandedBytes - total)

    suspend fun add(suggested: String, file: File, crc: Long) {
        currentCoroutineContext().ensureActive()
        val size = file.length()
        if (count >= limits.entries || size > remainingBytes) throw IOException("Archive is too large")
        val entry = ZipEntry(unique(suggested)).apply {
            method = ZipEntry.STORED
            this.size = size
            compressedSize = size
            this.crc = crc
        }
        zip.putNextEntry(entry)
        var copied = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                currentCoroutineContext().ensureActive()
                if (n < 0) break
                copied += n
                if (copied > size) throw IOException("Snapshot grew during export")
                zip.write(buffer, 0, n)
            }
        }
        if (copied != size) throw IOException("Snapshot changed during export")
        zip.closeEntry()
        count++
        total += size
    }

    private fun unique(suggested: String): String {
        require(NoteArchives.safeName(suggested) && '/' !in suggested && '\\' !in suggested)
        var name = suggested
        var suffix = 2
        while (!names.add(name.lowercase(Locale.ROOT))) {
            name = suggested.substringBeforeLast('.') + " ($suffix)." + suggested.substringAfterLast('.')
            suffix++
        }
        return name
    }
}
