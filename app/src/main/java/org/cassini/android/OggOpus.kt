package org.cassini.android

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.random.Random

/** Small bounded Ogg packet reader/muxer. Metadata and page layout never enter the audio digest. */
internal object OggOpus {
    const val MAX_FILE_BYTES = 64 * 1024 * 1024
    const val MAX_HEADER_BYTES = 8 * 1024 * 1024
    data class Stream(val head: ByteArray, val tags: ByteArray, val audio: List<ByteArray>,
                      val finalGranule: Long, val validFraming: Boolean) {
        val channels get() = head[9].toInt() and 255
        val preSkip get() = u16(head, 10)
        val sampleCount get() = minOf(audio.sumOf { packetSamples(it).toLong() }, finalGranule) - preSkip
        val durationMs get() = sampleCount * 1000 / 48000
        fun digest(): String {
            require(validFraming && head.size >= 19 && (head[8].toInt() and 255) in 1..15 && channels in 1..2)
            require(finalGranule >= preSkip && sampleCount >= 0)
            val hash = MessageDigest.getInstance("SHA-256")
            hash.update("org.cassini.opus-packets/1\u0000".toByteArray(Charsets.US_ASCII))
            val canonical = head.copyOf().also { for (i in 12..15) it[i] = 0 }
            fun packet(marker: Char, bytes: ByteArray) {
                hash.update(marker.code.toByte()); hash.update(le64(bytes.size.toLong())); hash.update(bytes)
            }
            packet('H', canonical)
            audio.forEach { packet('A', it) }
            hash.update('E'.code.toByte()); hash.update(le64(audio.size.toLong())); hash.update(le64(sampleCount))
            return hex(hash.digest())
        }
    }
    fun read(bytes: ByteArray): Stream {
        require(bytes.size <= MAX_FILE_BYTES)
        val packets = mutableListOf<ByteArray>()
        val partial = ByteArrayOutputStream()
        var pos = 0; var serial: Int? = null; var seq = 0; var valid = true; var eos = false; var final = -1L
        while (pos < bytes.size) {
            require(pos + 27 <= bytes.size && String(bytes, pos, 4, Charsets.US_ASCII) == "OggS")
            val count = bytes[pos + 26].toInt() and 255
            require(pos + 27 + count <= bytes.size)
            var end = pos + 27 + count
            repeat(count) { end += bytes[pos + 27 + it].toInt() and 255 }
            require(end <= bytes.size)
            val flags = bytes[pos + 5].toInt() and 255
            val pageSerial = u32(bytes, pos + 14)
            val pageSeq = u32(bytes, pos + 18)
            if (serial == null) { serial = pageSerial; if (flags and 2 == 0 || pageSeq != 0) valid = false }
            if (pageSerial != serial || pageSeq != seq++ || eos || bytes[pos + 4] != 0.toByte()) valid = false
            if ((flags and 1 != 0) != (partial.size() > 0)) valid = false
            val page = bytes.copyOfRange(pos, end)
            val expectedCrc = u32(page, 22)
            for (i in 22..25) page[i] = 0
            if (crc(page) != expectedCrc) valid = false
            var cursor = pos + 27 + count
            repeat(count) { i ->
                val length = bytes[pos + 27 + i].toInt() and 255
                require(partial.size() + length <= if (packets.size < 2) MAX_HEADER_BYTES else 65536)
                partial.write(bytes, cursor, length); cursor += length
                if (length < 255) {
                    packets += partial.toByteArray(); partial.reset()
                    // OpusHead is alone on the BOS page; audio starts after the tags page.
                    if ((packets.size == 1 || packets.size == 2) && i != count - 1) valid = false
                }
            }
            if (flags and 4 != 0) { eos = true; final = i64(bytes, pos + 6) }
            pos = end
        }
        require(packets.size >= 2 && packets[0].size >= 19 && packets[0].startsWith("OpusHead") && packets[1].startsWith("OpusTags"))
        return Stream(packets[0], packets[1], packets.drop(2), final, valid && eos && partial.size() == 0)
    }
    fun mux(stream: Stream, tags: ByteArray): ByteArray {
        require(stream.validFraming && tags.size <= MAX_HEADER_BYTES && stream.audio.isNotEmpty())
        val out = ByteArrayOutputStream()
        val serial = Random.nextInt(); var seq = 0
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
                le64(if (last) granule else -1).copyInto(page, 6)
                put32(page, 14, serial); put32(page, 18, seq++)
                page[26] = group.size.toByte()
                group.forEachIndexed { i, n -> page[27 + i] = n.toByte() }
                bytes.copyInto(page, 27 + group.size, offset, offset + size)
                put32(page, 22, crc(page)); out.write(page); offset += size
            }
        }
        packet(stream.head, 2, 0); packet(tags, 0, 0)
        var granule = 0L
        stream.audio.forEachIndexed { i, bytes ->
            granule += packetSamples(bytes)
            packet(bytes, if (i == stream.audio.lastIndex) 4 else 0,
                if (i == stream.audio.lastIndex) stream.finalGranule else granule)
        }
        require(out.size() <= MAX_FILE_BYTES)
        return out.toByteArray()
    }
    fun packetSamples(packet: ByteArray): Int {
        require(packet.isNotEmpty())
        val toc = packet[0].toInt() and 255
        val code = (toc shr 3) and 3
        val perFrame = when {
            toc and 128 != 0 -> (48000 shl code) / 400
            toc and 96 == 96 -> if (toc and 8 != 0) 960 else 480
            code == 3 -> 2880
            else -> (48000 shl code) / 100
        }
        val frames = when (toc and 3) {
            0 -> 1; 1, 2 -> 2
            else -> { require(packet.size >= 2); packet[1].toInt() and 63 }
        }
        require(frames > 0 && frames * perFrame <= 5760)
        return frames * perFrame
    }
    private val table = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) { value = (value shl 1) xor if (value < 0) 0x04c11db7 else 0 }
        value
    }
    fun crc(bytes: ByteArray): Int { var c = 0; bytes.forEach { c = (c shl 8) xor table[((c ushr 24) xor (it.toInt() and 255)) and 255] }; return c }
    fun u16(b: ByteArray, p: Int) = (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    fun u32(b: ByteArray, p: Int): Int { var v = 0; repeat(4) { v = v or ((b[p + it].toInt() and 255) shl (8 * it)) }; return v }
    fun i64(b: ByteArray, p: Int): Long { var v = 0L; repeat(8) { v = v or ((b[p + it].toLong() and 255) shl (8 * it)) }; return v }
    fun put32(b: ByteArray, p: Int, v: Int) { repeat(4) { b[p + it] = (v ushr (8 * it)).toByte() } }
    fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }
    fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    fun ByteArray.startsWith(value: String) = size >= value.length && String(this, 0, value.length, Charsets.US_ASCII) == value
}
