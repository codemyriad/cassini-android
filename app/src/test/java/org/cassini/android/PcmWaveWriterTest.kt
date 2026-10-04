package org.cassini.android

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PcmWaveWriterTest {
    @get:Rule val folder = TemporaryFolder()

    private fun text(bytes: ByteArray, at: Int) = String(bytes, at, 4, Charsets.US_ASCII)

    @Test fun writesAValidMonoPcm16Header() {
        val file = folder.newFile("a.wav")
        val samples = ShortArray(10_000) { (it - 5000).toShort() }
        PcmWaveWriter(file).use { it.write(samples); it.write(shortArrayOf(7, 8, 9), 2) }
        val bytes = file.readBytes()
        val data = (10_000 + 2) * 2
        assertEquals(44 + data, bytes.size)
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", text(bytes, 0)); assertEquals(36 + data, b.getInt(4))
        assertEquals("WAVE", text(bytes, 8)); assertEquals("fmt ", text(bytes, 12)); assertEquals(16, b.getInt(16))
        assertEquals(1, b.getShort(20).toInt()); assertEquals(1, b.getShort(22).toInt())
        assertEquals(16000, b.getInt(24)); assertEquals(32000, b.getInt(28))
        assertEquals(2, b.getShort(32).toInt()); assertEquals(16, b.getShort(34).toInt())
        assertEquals("data", text(bytes, 36)); assertEquals(data, b.getInt(40))
        assertEquals(-5000, b.getShort(44).toInt()); assertEquals(4999, b.getShort(44 + 9999 * 2).toInt())
        assertEquals(7, b.getShort(44 + 10_000 * 2).toInt()); assertEquals(8, b.getShort(44 + 10_001 * 2).toInt())
    }

    @Test fun emptyFileIsStillAValidWave() {
        val file = folder.newFile("empty.wav")
        PcmWaveWriter(file).close()
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, file.length().toInt()); assertEquals(36, b.getInt(4)); assertEquals(0, b.getInt(40))
    }

    @Test fun littleEndianSamples() {
        val file = folder.newFile("le.wav")
        PcmWaveWriter(file).use { it.write(shortArrayOf(0x0102, -2)) }
        assertArrayEquals(byteArrayOf(2, 1, -2, -1), file.readBytes().copyOfRange(44, 48))
    }

    @Test fun closeIsIdempotent() {
        val file = folder.newFile("c.wav")
        val writer = PcmWaveWriter(file)
        writer.write(ShortArray(4))
        writer.close(); writer.close()
        assertEquals(52L, file.length())
        assertEquals(4L, writer.samplesWritten)
    }

    @Test fun rejectsWriteAfterCloseAndBadCounts() {
        val writer = PcmWaveWriter(folder.newFile("d.wav"))
        assertThrows(IllegalArgumentException::class.java) { writer.write(ShortArray(2), 3) }
        assertThrows(IllegalArgumentException::class.java) { writer.write(ShortArray(2), -1) }
        writer.close()
        assertThrows(IllegalStateException::class.java) { writer.write(ShortArray(2)) }
    }

    @Test fun truncatesAnExistingFile() {
        val file = File(folder.root, "old.wav").apply { writeBytes(ByteArray(500)) }
        PcmWaveWriter(file).close()
        assertEquals(44L, file.length())
    }
}
