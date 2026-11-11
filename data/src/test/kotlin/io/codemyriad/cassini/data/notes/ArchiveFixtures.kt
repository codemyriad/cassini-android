package io.codemyriad.cassini.data.notes

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal fun zipBytes(vararg entries: Pair<String, String>, stored: Boolean = false): ByteArray = ByteArrayOutputStream().also { bytes ->
    ZipOutputStream(bytes).use { zip ->
        for ((name, body) in entries) {
            val data = body.toByteArray()
            val entry = ZipEntry(name)
            if (stored) {
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = entry.size
                entry.crc = CRC32().apply { update(data) }.value
            }
            zip.putNextEntry(entry)
            zip.write(data)
            zip.closeEntry()
        }
    }
}.toByteArray()

internal fun ByteArray.recordOffsets(signature: Int): List<Int> = (0..size - 4).filter {
    little().getInt(it) == signature
}
internal fun ByteArray.intAt(offset: Int): Int = little().getInt(offset)
internal fun ByteArray.putInt(offset: Int, value: Int) {
    little().putInt(offset, value)
}
internal fun ByteArray.putShort(offset: Int, value: Int) {
    little().putShort(offset, value.toShort())
}
private fun ByteArray.putLong(offset: Int, value: Long) { little().putLong(offset, value) }
private fun ByteArray.little(): ByteBuffer = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN)

/** A small stored payload with all three ZIP64 directory/size/offset fields, without allocating gigabytes. */
internal fun zip64Bytes(): ByteArray {
    val name = "Meeting.opus".toByteArray()
    val data = "Audio".toByteArray()
    val crc = CRC32().apply { update(data) }.value.toInt()
    val localExtra = ByteArray(20).apply {
        putShort(0, 1)
        putShort(2, 16)
        putLong(4, data.size.toLong())
        putLong(12, data.size.toLong())
    }
    val local = ByteArray(30 + name.size + localExtra.size + data.size).apply {
        putInt(0, 0x04034b50)
        putShort(4, 45)
        putInt(14, crc)
        putInt(18, -1)
        putInt(22, -1)
        putShort(26, name.size)
        putShort(28, localExtra.size)
        name.copyInto(this, 30)
        localExtra.copyInto(this, 30 + name.size)
        data.copyInto(this, 30 + name.size + localExtra.size)
    }
    val extra = ByteArray(28).apply {
        putShort(0, 1)
        putShort(2, 24)
        putLong(4, data.size.toLong())
        putLong(12, data.size.toLong())
        putLong(20, 0)
    }
    val central = ByteArray(46 + name.size + extra.size).apply {
        putInt(0, 0x02014b50)
        putShort(4, 45)
        putShort(6, 45)
        putInt(16, crc)
        putInt(20, -1)
        putInt(24, -1)
        putShort(28, name.size)
        putShort(30, extra.size)
        putInt(42, -1)
        name.copyInto(this, 46)
        extra.copyInto(this, 46 + name.size)
    }
    return local + central + zip64End(local.size, central.size)
}

private fun zip64End(local: Int, central: Int): ByteArray {
    val record = ByteArray(56).apply {
        putInt(0, 0x06064b50)
        putLong(4, 44)
        putShort(12, 45)
        putShort(14, 45)
        putLong(24, 1)
        putLong(32, 1)
        putLong(40, central.toLong())
        putLong(48, local.toLong())
    }
    val locator = ByteArray(20).apply {
        putInt(0, 0x07064b50)
        putLong(8, (local + central).toLong())
        putInt(16, 1)
    }
    val end = ByteArray(22).apply {
        putInt(0, 0x06054b50)
        putShort(8, 65_535)
        putShort(10, 65_535)
        putInt(12, -1)
        putInt(16, -1)
    }
    return record + locator + end
}

/** A valid Unicode path override must not hide an unsafe legacy name (or vice versa). */
internal fun unicodePathZip(name: String, unicode: String): ByteArray = ByteArrayOutputStream().also { bytes ->
    val path = unicode.toByteArray()
    val extra = ByteArray(9 + path.size).apply {
        putShort(0, 0x7075)
        putShort(2, 5 + path.size)
        this[4] = 1
        putInt(5, CRC32().apply { update(name.toByteArray()) }.value.toInt())
        path.copyInto(this, 9)
    }
    ZipOutputStream(bytes).use { zip ->
        zip.putNextEntry(ZipEntry(name).apply { this.extra = extra })
        zip.write("audio".toByteArray())
        zip.closeEntry()
    }
}.toByteArray()

/** An untrusted member whose raw payload and declared metadata deliberately disagree. */
@Suppress("LongParameterList") // ZIP header fields are independently forged by the admission vectors.
internal fun rawMemberZip(
    name: String,
    payload: ByteArray,
    method: Int,
    size: Int = 0,
    crc: Int = 0,
    withAudio: Boolean = true,
    attributes: Int = 0,
): ByteArray {
    val bytes = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            val entries = listOfNotNull(("good.opus" to "audio".toByteArray()).takeIf { withAudio }, name to payload)
            for ((entryName, body) in entries) {
                zip.putNextEntry(
                    ZipEntry(entryName).apply {
                        this.method = ZipEntry.STORED
                        this.size = body.size.toLong()
                        compressedSize = this.size
                        this.crc = CRC32().apply { update(body) }.value
                    },
                )
                zip.write(body)
                zip.closeEntry()
            }
        }
    }.toByteArray()
    val local = bytes.recordOffsets(0x04034b50).last()
    val central = bytes.recordOffsets(0x02014b50).last()
    bytes.putShort(local + 8, method)
    bytes.putShort(central + 10, method)
    bytes.putInt(local + 22, size)
    bytes.putInt(central + 24, size)
    bytes.putInt(local + 14, crc)
    bytes.putInt(central + 16, crc)
    bytes.putInt(central + 38, attributes)
    return bytes
}
