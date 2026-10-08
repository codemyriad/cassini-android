package org.cassini.android

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LiveMuxerTest {
    private val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 1, -128, -69, 0, 0, 0, 0, 0)
    /** 20 ms CELT packets (TOC config 31, one frame) of varying size. */
    private fun packet(i: Int) = ByteArray(40 + i % 300) { if (it == 0) (31 shl 3).toByte() else (i + it).toByte() }

    private fun pages(bytes: ByteArray): List<ByteArray> {
        val reader = OggOpus.Reader(ByteArrayInputStream(bytes))
        return generateSequence { reader.page() }.toList()
    }

    @Test fun finishedStreamIsCompleteWithMonotonicGranulesAndExactLength() {
        val out = ByteArrayOutputStream()
        val muxer = OggOpus.LiveMuxer(out, head, CassiniDocument.tagsPacket(emptyList()), serial = 7)
        repeat(500) { muxer.add(packet(it)) }
        val captured = 500 * 960L - 700
        muxer.finish(muxer.preSkip + captured)
        val bytes = out.toByteArray()
        val reader = OggOpus.Reader(ByteArrayInputStream(bytes))
        val granules = generateSequence { reader.page() }.map { OggOpus.i64(it, 6) }.toList()
        assertTrue(reader.complete)
        assertEquals(granules.sorted(), granules)
        val info = OggOpus.scan(ByteArrayInputStream(bytes))
        assertEquals(captured, info.sampleCount)
        info.digest()
        // Re-muxing the same packets with the whole-stream muxer gives the same audio digest.
        val stream = OggOpus.read(bytes)
        assertEquals(stream.digest(), OggOpus.read(OggOpus.mux(stream, CassiniDocument.tagsPacket(emptyList()), 9)).digest())
    }

    @Test fun truncatedCaptureStillReadsUpToItsLastWholePage() {
        val out = ByteArrayOutputStream()
        val muxer = OggOpus.LiveMuxer(out, head, CassiniDocument.tagsPacket(emptyList()))
        repeat(120) { muxer.add(packet(it)) }
        val bytes = out.toByteArray()
        val whole = pages(bytes)
        assertEquals(2 + 119, whole.size)
        val cut = bytes.copyOf(bytes.size - 17)
        val reader = OggOpus.Reader(ByteArrayInputStream(cut))
        var read = 0
        try { while (reader.page() != null) read++ } catch (_: IllegalArgumentException) {}
        assertEquals(whole.size - 1, read)
        assertTrue(reader.valid)
        assertFalse(reader.eos)
    }

    @Test fun finishClampsToCoveredSamplesAndNeedsAudio() {
        val out = ByteArrayOutputStream()
        val muxer = OggOpus.LiveMuxer(out, head, CassiniDocument.tagsPacket(emptyList()))
        try { muxer.finish(); fail() } catch (_: IllegalStateException) {}
        repeat(3) { muxer.add(packet(it)) }
        muxer.finish(1_000_000)
        assertEquals(3 * 960L, OggOpus.scan(ByteArrayInputStream(out.toByteArray())).let { it.sampleCount + 312 })
    }

    @Test fun clocksShowHoursOnlyFromOneHour() {
        assertEquals("00:00", clock(0))
        assertEquals("02:59", clock(179_999))
        assertEquals("59:59", clock(3_599_000))
        assertEquals("1:00:00", clock(3_600_000))
        assertEquals("2:00:00", clock(Limits.MAX_RECORDING_MS))
    }
}
