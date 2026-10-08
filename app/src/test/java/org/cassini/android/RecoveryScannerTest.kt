package org.cassini.android

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class RecoveryScannerTest {
    @get:Rule val folder = TemporaryFolder()
    private val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 1, -128, -69, 0, 0, 0, 0, 0)

    /** A live capture of [packets] 20 ms packets, cut before [OggOpus.LiveMuxer.finish]; sizes cross the lacing edge. */
    private fun capture(packets: Int, finish: Boolean = false, finalGranule: Long? = null): ByteArray {
        val out = ByteArrayOutputStream(); val random = Random(3)
        val muxer = OggOpus.LiveMuxer(out, head, CassiniDocument.tagsPacket(listOf("TITLE=live")), 42)
        repeat(packets) { i -> muxer.add(ByteArray(listOf(80, 255, 300, 600)[i % 4]).also { random.nextBytes(it); it[0] = -8 }) }
        if (finish) muxer.finish(finalGranule)
        return out.toByteArray()
    }

    private fun file(bytes: ByteArray) = File(folder.root, "${Random.nextInt()}.rec.opus").also { it.writeBytes(bytes) }

    private data class Page(val seq: Int, val granule: Long, val flags: Int, val crcOk: Boolean)
    private fun pages(bytes: ByteArray): List<Page> {
        val reader = OggOpus.Reader(ByteArrayInputStream(bytes)); val list = mutableListOf<Page>()
        while (true) {
            val page = reader.page() ?: break
            val copy = page.copyOf().also { for (i in 22..25) it[i] = 0 }
            list += Page(OggOpus.u32(page, 18), OggOpus.i64(page, 6), page[5].toInt() and 255, OggOpus.crc(copy) == OggOpus.u32(page, 22))
        }
        return list
    }

    private fun assertSound(bytes: ByteArray, expectedPackets: Int) {
        val all = pages(bytes)
        assertTrue(all.all { it.crcOk })
        assertEquals(all.indices.toList(), all.map { it.seq })
        val granules = all.map { it.granule }.filter { it >= 0 }
        assertEquals(granules.sorted(), granules)
        assertEquals(4, all.last().flags and 4); assertEquals(1, all.count { it.flags and 4 != 0 })
        val stream = OggOpus.read(bytes)
        assertTrue(stream.validFraming); assertEquals(expectedPackets, stream.audio.size)
        assertEquals(OggOpus.scan(ByteArrayInputStream(bytes)).digest(), stream.digest())
    }

    @Test fun cutCaptureKeepsEveryWrittenPacket() {
        // The muxer holds one packet back, so 50 added packets leave 49 on disk.
        val target = file(capture(50))
        val result = RecoveryScanner.repair(target)!!
        assertTrue(result.repaired)
        assertSound(target.readBytes(), 49)
        assertEquals((49 * 960L - 312) * 1000 / 48000, result.durationMs)
    }

    @Test fun tailGarbageAndTornPagesAreCut() {
        val clean = capture(50)
        val torn = clean.copyOf(clean.size - 100)
        val garbage = clean + ByteArray(5000).also { Random(9).nextBytes(it) }
        val flipped = clean.copyOf().also { it[it.size - 3] = (it[it.size - 3].toInt() xor 1).toByte() }
        val zeros = clean + ByteArray(4096)
        for (bytes in listOf(torn, garbage, flipped, zeros)) {
            val target = file(bytes)
            assertTrue(RecoveryScanner.repair(target)!!.repaired)
            val repaired = target.readBytes()
            assertSound(repaired, OggOpus.read(repaired).audio.size)
            assertTrue(OggOpus.read(repaired).audio.size >= 47)
            val reader = OggOpus.Reader(ByteArrayInputStream(repaired)); var last = 0
            while (true) last = (reader.page() ?: break).size
            val kept = repaired.size - last
            assertArrayEquals(clean.copyOf(kept), repaired.copyOf(kept))
            assertEquals(0, clean[kept + 5].toInt() and 4)
        }
    }

    @Test fun backwardsGranuleStopsTheKeptPrefix() {
        val bytes = capture(20)
        val offsets = mutableListOf<Int>(); var at = 0
        while (at < bytes.size) { offsets += at; at += 27 + (bytes[at + 26].toInt() and 255) + (0 until (bytes[at + 26].toInt() and 255)).sumOf { bytes[at + 27 + it].toInt() and 255 } }
        val bad = offsets[10]
        OggOpus.le64(1).copyInto(bytes, bad + 6)
        for (i in 22..25) bytes[bad + i] = 0
        val crc = OggOpus.crc(bytes.copyOfRange(bad, offsets[11])); OggOpus.put32(bytes, bad + 22, crc)
        val target = file(bytes)
        assertTrue(RecoveryScanner.repair(target)!!.repaired)
        assertEquals(bad.toLong(), target.length())
        assertSound(target.readBytes(), OggOpus.read(target.readBytes()).audio.size)
    }

    @Test fun finishedCaptureIsLeftUntouchedAndEmptyOnesAreNotRecoverable() {
        val finished = capture(10, finish = true)
        val target = file(finished)
        assertEquals(false, RecoveryScanner.repair(target)!!.repaired)
        assertArrayEquals(finished, target.readBytes())
        assertNull(RecoveryScanner.repair(file(capture(1))))
        assertNull(RecoveryScanner.repair(file(ByteArray(0))))
        assertNull(RecoveryScanner.repair(file(capture(1).copyOf(20))))
    }

    @Test fun trimmedEndOfStreamIsNotMistakenForCorruption() {
        // A clean stop pads one zero frame, so the EOS granule sits below the previous page's.
        val finished = capture(10, finish = true, finalGranule = 312L + 8 * 960 + 123)
        val target = file(finished)
        assertTrue(RecoveryScanner.endsCleanly(target))
        assertEquals(false, RecoveryScanner.repair(target)!!.repaired)
        assertArrayEquals(finished, target.readBytes())
        assertFalse(RecoveryScanner.endsCleanly(file(capture(10))))
    }
}
