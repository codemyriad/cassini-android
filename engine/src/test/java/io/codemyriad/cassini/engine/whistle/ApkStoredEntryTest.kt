package io.codemyriad.cassini.engine.whistle

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkStoredEntryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun mapsExactStoredBytesAndRefusesCompressedMissingOrWrongSize() {
        val data = byteArrayOf(0, 1, 2, 3, 4)
        for (method in listOf(ZipEntry.STORED, ZipEntry.DEFLATED)) {
            val file = folder.newFile()
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.setComment("comment PK\u0005\u0006")
                zip.putNextEntry(ZipEntry("weights").apply {
                    this.method = method
                    size = data.size.toLong()
                    crc = CRC32().apply { update(data) }.value
                })
                zip.write(data)
                zip.closeEntry()
            }
            if (method == ZipEntry.STORED) {
                val offset = ApkStoredEntry.offset(file, "weights", data.size.toLong())
                RandomAccessFile(file, "r").use {
                    it.seek(offset)
                    val read = ByteArray(data.size)
                    it.readFully(read)
                    assertArrayEquals(data, read)
                }
                assertThrows(IllegalStateException::class.java) { ApkStoredEntry.offset(file, "weights", 1) }
            } else {
                assertThrows(IllegalStateException::class.java) { ApkStoredEntry.offset(file, "weights", data.size.toLong()) }
            }
            assertThrows(IllegalStateException::class.java) { ApkStoredEntry.offset(file, "missing", data.size.toLong()) }
        }
    }
}
