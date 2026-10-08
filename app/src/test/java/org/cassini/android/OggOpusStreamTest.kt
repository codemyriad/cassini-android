package org.cassini.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

class OggOpusStreamTest {
    @get:Rule val folder = TemporaryFolder()
    private val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 1, -128, -69, 0, 0, 0, 0, 0)

    /** The whole-buffer muxer as it shipped before streaming, kept verbatim as the golden reference. */
    private fun legacyMux(stream: OggOpus.Stream, tags: ByteArray, serial: Int): ByteArray {
        val out = ByteArrayOutputStream(); var seq = 0
        fun packet(bytes: ByteArray, flags: Int, granule: Long) {
            val laces = MutableList(bytes.size / 255) { 255 }.also { it += bytes.size % 255 }
            var offset = 0
            laces.chunked(255).forEachIndexed { index, group ->
                val size = group.sum()
                val page = ByteArray(27 + group.size + size)
                "OggS".toByteArray().copyInto(page)
                page[5] = ((if (index > 0) 1 else flags and 2) or
                    (if (offset + size == bytes.size && group.last() < 255) flags and 4 else 0)).toByte()
                val last = offset + size == bytes.size && group.last() < 255
                OggOpus.le64(if (last) granule else -1).copyInto(page, 6)
                OggOpus.put32(page, 14, serial); OggOpus.put32(page, 18, seq++)
                page[26] = group.size.toByte()
                group.forEachIndexed { i, n -> page[27 + i] = n.toByte() }
                bytes.copyInto(page, 27 + group.size, offset, offset + size)
                OggOpus.put32(page, 22, OggOpus.crc(page)); out.write(page); offset += size
            }
        }
        packet(stream.head, 2, 0); packet(tags, 0, 0)
        var granule = 0L
        stream.audio.forEachIndexed { i, bytes ->
            granule += OggOpus.packetSamples(bytes)
            packet(bytes, if (i == stream.audio.lastIndex) 4 else 0, if (i == stream.audio.lastIndex) stream.finalGranule else granule)
        }
        return out.toByteArray()
    }

    /** Packet sizes straddle the 255-byte lacing edges; the tags span several pages. */
    private fun fixture(random: Random = Random(7)): ByteArray {
        val audio = listOf(1, 254, 255, 256, 510, 1275, 4000, 65025, 65026, 3).map { n ->
            ByteArray(n).also { random.nextBytes(it); it[0] = -8 }
        }
        val tags = CassiniDocument.tagsPacket(listOf("TITLE=fixture", "X_BIG=" + "x".repeat(70000)))
        return legacyMux(OggOpus.Stream(head, tags, audio, audio.size * 960L + 312 - 100, true), tags, 0x1234)
    }

    private fun fixtures(): List<ByteArray> {
        val directory = File(System.getProperty("cassini.conformance", "../cassini-format/spec/conformance")!!)
        val vectors = directory.walk().filter { it.isFile && it.extension == "opus" }.map { it.readBytes() }
            .filter { runCatching { OggOpus.read(it).validFraming && OggOpus.read(it).audio.isNotEmpty() }.getOrDefault(false) }.toList()
        return vectors + fixture()
    }

    @Test fun rewriteIsByteIdenticalToTheLegacyMux() {
        val tags = CassiniDocument.tagsPacket(listOf("TITLE=rewritten", "X_PAD=" + "y".repeat(300)))
        for (bytes in fixtures()) {
            val stream = OggOpus.read(bytes)
            val expected = legacyMux(stream, tags, 99)
            val out = ByteArrayOutputStream()
            val info = OggOpus.rewrite(ByteArrayInputStream(bytes), tags, out, 99)
            assertArrayEquals(expected, out.toByteArray())
            assertArrayEquals(expected, OggOpus.mux(stream, tags, 99))
            assertEquals(stream.digest(), info.digest()); assertEquals(stream.sampleCount, info.sampleCount)
            val source = folder.newFile().also { it.writeBytes(bytes) }; val target = folder.newFile()
            assertEquals(stream.digest(), OggOpus.rewrite(source, tags, target, 99).digest())
            assertArrayEquals(expected, target.readBytes())
        }
    }

    @Test fun muxerStreamsTheSameBytesAndDropsFlushPackets() {
        val tags = CassiniDocument.tagsPacket(emptyList())
        for (bytes in fixtures()) {
            val stream = OggOpus.read(bytes)
            var covered = 0L
            val used = stream.audio.takeWhile { if (covered >= stream.finalGranule) false else { covered += OggOpus.packetSamples(it); true } }
            if (covered < stream.finalGranule) continue
            val out = ByteArrayOutputStream()
            val muxer = OggOpus.Muxer(out, stream.head, tags, stream.finalGranule, 7)
            (stream.audio + stream.audio.last()).forEach(muxer::add); muxer.finish()
            assertArrayEquals(legacyMux(stream.copy(audio = used), tags, 7), out.toByteArray())
        }
        val stream = OggOpus.read(fixture())
        val short = OggOpus.Muxer(ByteArrayOutputStream(), stream.head, tags, stream.finalGranule + 1_000_000, 7)
        stream.audio.forEach(short::add)
        try { short.finish(); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test fun scanMatchesTheWholeBufferReader() {
        for (bytes in fixtures()) {
            val stream = OggOpus.read(bytes); val info = OggOpus.scan(ByteArrayInputStream(bytes))
            assertEquals(stream.digest(), info.digest())
            assertEquals(stream.sampleCount, info.sampleCount); assertEquals(stream.durationMs, info.durationMs)
            assertArrayEquals(stream.tags, info.tags)
        }
    }

    @Test fun corruptCrcIsRejectedWhileStreaming() {
        val bytes = fixture()
        val last = bytes.size - 1 // the final page's payload
        val changed = bytes.copyOf().also { it[last] = (it[last].toInt() xor 1).toByte() }
        val info = OggOpus.scan(ByteArrayInputStream(changed))
        assertFalse(info.validFraming)
        assertThrows(IllegalArgumentException::class.java) { info.digest() }
        assertThrows(IllegalArgumentException::class.java) { OggOpus.rewrite(ByteArrayInputStream(changed), info.tags, ByteArrayOutputStream()) }
        assertThrows(IllegalArgumentException::class.java) { OggOpus.scan(ByteArrayInputStream(bytes.copyOf(bytes.size - 1))) }
    }

    @Test fun fileDocumentsVerifyFromDiskAndHeadersOnlyStayUnverified() {
        val audio = folder.newFile().also { it.writeBytes(fixture()) }
        val target = File(folder.root, "doc.opus")
        val transcript = Transcript(listOf(Word("spk_1", 0, 20, "Ciao")), "it")
        val doc = CassiniDocument.create(audio, transcript, "Streamed", JSONObject(), null, emptyMap(), target)
        assertEquals("ok", doc.state)
        assertEquals("ok", CassiniDocument.read(target).state)
        val headers = CassiniDocument.read(target, verify = false)
        assertEquals("unverified", headers.state); assertEquals(transcript, headers.selected(null)?.transcript)
        assertEquals(OggOpus.scan(audio).digest(), OggOpus.scan(target).digest())
    }
}
