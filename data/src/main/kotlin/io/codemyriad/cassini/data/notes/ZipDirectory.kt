@file:Suppress("MagicNumber") // PKWARE APPNOTE record signatures, bit flags and fixed field offsets.

package io.codemyriad.cassini.data.notes

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.zip.CRC32

internal data class ArchiveEntry(
    val name: String,
    val size: Long,
    val compressed: Long,
    val offset: Long,
    val crc: Long,
    val method: Int,
    val directory: Boolean,
) {
    val supported: Boolean get() = !directory && NoteArchives.audio(name)
}

/** Bounded central-directory inspection, including ZIP64, before any note is imported. No entry data is inflated here. */
internal class ZipDirectory(
    private val file: RandomAccessFile,
    private val limits: ArchiveLimits,
    private val active: () -> Unit,
) {
    private data class Directory(val offset: Long, val bytes: Long, val entries: Long)
    private var cursor = 0L
    private var end = 0L
    private var centralStart = 0L

    fun read(): List<ArchiveEntry> {
        val directory = endRecord()
        archiveCheck(directory.entries <= limits.entries, ArchiveFailure.TOO_LARGE)
        archiveCheck(range(directory.offset, directory.bytes, file.length()))
        centralStart = directory.offset
        cursor = directory.offset
        end = cursor + directory.bytes
        var total = 0L
        val entries = ArrayList<ArchiveEntry>()
        repeat(directory.entries.toInt()) {
            active()
            val entry = entry()
            archiveCheck(entry.size <= limits.expandedBytes - total, ArchiveFailure.TOO_LARGE)
            archiveCheck(!overRatio(entry.size, entry.compressed, limits.ratio), ArchiveFailure.TOO_LARGE)
            total += entry.size
            entries += entry
        }
        // The optional central-directory digital-signature record contains no file entry.
        if (cursor < end) {
            file.seek(cursor)
            val signature = bytes(6)
            archiveCheck(signature.u32(0) == 0x05054b50L && range(cursor, 6L + signature.u16(4), end))
            cursor += 6L + signature.u16(4)
        }
        archiveCheck(cursor == end)
        return entries
    }

    private fun endRecord(): Directory {
        val length = file.length()
        archiveCheck(length >= 22)
        val tail = ByteArray(minOf(length, 65_557L).toInt())
        file.seek(length - tail.size)
        file.readFully(tail)
        val at = (tail.size - 22 downTo 0).firstOrNull {
            tail.u32(it) == 0x06054b50L && it + 22 + tail.u16(it + 20) == tail.size
        } ?: throw ArchiveRejected(ArchiveFailure.DAMAGED)
        archiveCheck(tail.u16(at + 4) == 0 && tail.u16(at + 6) == 0)
        val entries = tail.u16(at + 10)
        if (entries == 65_535 || tail.u32(at + 12) == 0xffffffffL || tail.u32(at + 16) == 0xffffffffL) {
            return zip64(length - tail.size + at)
        }
        archiveCheck(tail.u16(at + 8) == entries)
        val result = Directory(tail.u32(at + 16), tail.u32(at + 12), entries.toLong())
        archiveCheck(range(result.offset, result.bytes, length - tail.size + at))
        return result
    }

    private fun zip64(eocd: Long): Directory {
        archiveCheck(eocd >= 20)
        file.seek(eocd - 20)
        val locator = bytes(20)
        archiveCheck(locator.u32(0) == 0x07064b50L && locator.u32(4) == 0L && locator.u32(16) == 1L)
        val offset = locator.u64(8)
        archiveCheck(range(offset, 56, eocd - 20))
        file.seek(offset)
        val record = bytes(56)
        archiveCheck(record.u32(0) == 0x06064b50L && record.u64(4) >= 44)
        archiveCheck(range(offset, 12 + record.u64(4), eocd - 20))
        archiveCheck(record.u32(16) == 0L && record.u32(20) == 0L && record.u64(24) == record.u64(32))
        val result = Directory(record.u64(48), record.u64(40), record.u64(32))
        archiveCheck(range(result.offset, result.bytes, offset))
        return result
    }

    private fun entry(): ArchiveEntry {
        archiveCheck(range(cursor, 46, end))
        file.seek(cursor)
        val header = bytes(46)
        archiveCheck(header.u32(0) == 0x02014b50L)
        val flags = header.u16(8)
        archiveCheck(flags and 0x2041 == 0, ArchiveFailure.ENCRYPTED)
        val nameBytes = header.u16(28)
        val extraBytes = header.u16(30)
        val length = 46L + nameBytes + extraBytes + header.u16(32)
        archiveCheck(range(cursor, length, end))
        val rawName = bytes(nameBytes)
        val extra = extras(bytes(extraBytes), rawName)
        val name = extra.second ?: decode(rawName, flags)
        archiveCheck(
            NoteArchives.safeName(name) && NoteArchives.safeName(decode(rawName, flags)),
            ArchiveFailure.UNSAFE_PATHS,
        )
        cursor += length
        val sizes = sizes(header, extra.first)
        val data = local(sizes.third, rawName, header.u16(10), flags)
        archiveCheck(range(data, sizes.second, centralStart))
        val base = name.replace('\\', '/').trimEnd('/').substringAfterLast('/')
        val display = if (base.length <= 210) {
            base
        } else {
            base.substringBeforeLast('.').take(200) + "." + base.substringAfterLast('.', "").take(8)
        }
        return ArchiveEntry(
            display,
            sizes.first,
            sizes.second,
            data,
            header.u32(16),
            header.u16(10),
            name.endsWith('/') || name.endsWith('\\'),
        )
    }

    private fun local(offset: Long, centralName: ByteArray, method: Int, centralFlags: Int): Long {
        archiveCheck(range(offset, 30, centralStart))
        file.seek(offset)
        val header = bytes(30)
        archiveCheck(header.u32(0) == 0x04034b50L && header.u16(8) == method)
        val flags = header.u16(6)
        archiveCheck(flags and 0x2041 == 0, ArchiveFailure.ENCRYPTED)
        val name = bytes(header.u16(26))
        val extra = extras(bytes(header.u16(28)), name)
        archiveCheck(NoteArchives.safeName(decode(name, flags)), ArchiveFailure.UNSAFE_PATHS)
        archiveCheck(
            extra.second == null || NoteArchives.safeName(checkNotNull(extra.second)),
            ArchiveFailure.UNSAFE_PATHS,
        )
        archiveCheck(name.contentEquals(centralName) && (flags and 0x808) == (centralFlags and 0x808))
        return offset + 30 + header.u16(26) + header.u16(28)
    }

    private fun sizes(header: ByteArray, zip64: ByteArray?): Triple<Long, Long, Long> {
        var at = 0
        fun field(value: Long): Long {
            if (value != 0xffffffffL) return value
            archiveCheck(zip64 != null && at + 8 <= zip64.size)
            return checkNotNull(zip64).u64(at).also { at += 8 }
        }
        val size = field(header.u32(24))
        val compressed = field(header.u32(20))
        val offset = field(header.u32(42))
        val disk = header.u16(34)
        if (disk == 65_535) {
            archiveCheck(zip64 != null && at + 4 <= zip64.size && zip64.u32(at) == 0L)
        } else {
            archiveCheck(disk == 0)
        }
        return Triple(size, compressed, offset)
    }

    private fun extras(extra: ByteArray, rawName: ByteArray): Pair<ByteArray?, String?> {
        var at = 0
        var zip64: ByteArray? = null
        var unicode: String? = null
        while (at < extra.size) {
            archiveCheck(at + 4 <= extra.size)
            val id = extra.u16(at)
            val length = extra.u16(at + 2)
            archiveCheck(at + 4 + length <= extra.size)
            archiveCheck(id != 0x9901, ArchiveFailure.ENCRYPTED)
            if (id == 1) zip64 = extra.copyOfRange(at + 4, at + 4 + length)
            if (id == 0x7075 && length >= 5 && extra[at + 4] == 1.toByte()) {
                val crc = CRC32().apply { update(rawName) }.value
                if (extra.u32(at + 5) == crc) unicode = decode(extra.copyOfRange(at + 9, at + 4 + length), 0x800)
            }
            at += 4 + length
        }
        return zip64 to unicode
    }

    private fun decode(name: ByteArray, flags: Int): String =
        (if (flags and 0x800 != 0) Charsets.UTF_8 else Charset.forName("CP437")).newDecoder()
            .decode(java.nio.ByteBuffer.wrap(name)).toString()

    private fun bytes(count: Int): ByteArray = ByteArray(count).also(file::readFully)

    companion object {
        fun inspect(archive: File, limits: ArchiveLimits, active: () -> Unit = {}): List<ArchiveEntry> =
            RandomAccessFile(archive, "r").use { ZipDirectory(it, limits, active).read() }
    }
}

private fun ByteArray.u16(at: Int): Int = (this[at].toInt() and 255) or ((this[at + 1].toInt() and 255) shl 8)
private fun ByteArray.u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
private fun ByteArray.u64(at: Int): Long = (u32(at) or (u32(at + 4) shl 32)).also { archiveCheck(it >= 0) }
private fun range(start: Long, bytes: Long, end: Long): Boolean =
    start >= 0 && bytes >= 0 && start <= end && bytes <= end - start
internal fun archiveCheck(valid: Boolean, reason: ArchiveFailure = ArchiveFailure.DAMAGED) {
    if (!valid) throw ArchiveRejected(reason)
}
internal fun overRatio(expanded: Long, compressed: Long, ratio: Int): Boolean =
    expanded > 0 && (compressed <= 0 || (expanded - 1) / ratio >= compressed)
