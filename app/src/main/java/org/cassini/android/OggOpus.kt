package org.cassini.android

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.random.Random

/** Streaming Ogg packet reader/muxer with bounded packets. Metadata and page layout never enter the audio digest. */
internal object OggOpus {
    /** The first page holds only OpusHead: a 27-byte page header, one lacing value, then the magic. */
    const val HEAD_BYTES = 36
    fun looksLikeOpus(head: ByteArray) = head.size >= HEAD_BYTES && String(head, 0, 4, Charsets.US_ASCII) == "OggS" &&
        head[26].toInt() == 1 && String(head, 28, 8, Charsets.US_ASCII) == "OpusHead"
    const val MAX_HEADER_BYTES = 8 * 1024 * 1024
    data class Stream(val head: ByteArray, val tags: ByteArray, val audio: List<ByteArray>,
                      val finalGranule: Long, val validFraming: Boolean) {
        val channels get() = head[9].toInt() and 255
        val preSkip get() = u16(head, 10)
        val sampleCount get() = minOf(audio.sumOf { packetSamples(it).toLong() }, finalGranule) - preSkip
        val durationMs get() = sampleCount * 1000 / 48000
        fun digest(): String {
            require(validFraming)
            return Hasher(head).also { h -> audio.forEach(h::add) }.finish(finalGranule)
        }
    }
    /** What a streaming pass learns: headers, shape, and the audio digest when framing and packets are sound. */
    class Info(val head: ByteArray, val tags: ByteArray, val finalGranule: Long, val validFraming: Boolean,
               val sampleCount: Long, private val audioDigest: String?) {
        val channels get() = head[9].toInt() and 255
        val durationMs get() = sampleCount * 1000 / 48000
        fun digest(): String = audioDigest ?: throw IllegalArgumentException("Opus audio cannot be verified")
    }
    private class Hasher(head: ByteArray) {
        private val hash = MessageDigest.getInstance("SHA-256")
        private val preSkip = u16(head, 10)
        private var count = 0L
        var samples = 0L
            private set
        init {
            require(head.size >= 19 && (head[8].toInt() and 255) in 1..15 && (head[9].toInt() and 255) in 1..2)
            hash.update("org.cassini.opus-packets/1\u0000".toByteArray(Charsets.US_ASCII))
            packet('H', head.copyOf().also { for (i in 12..15) it[i] = 0 })
        }
        private fun packet(marker: Char, bytes: ByteArray) {
            hash.update(marker.code.toByte()); hash.update(le64(bytes.size.toLong())); hash.update(bytes)
        }
        fun add(bytes: ByteArray) { samples += packetSamples(bytes); packet('A', bytes); count++ }
        fun sampleCount(finalGranule: Long) = minOf(samples, finalGranule) - preSkip
        fun finish(finalGranule: Long): String {
            val sampleCount = sampleCount(finalGranule)
            require(finalGranule >= preSkip && sampleCount >= 0)
            hash.update('E'.code.toByte()); hash.update(le64(count)); hash.update(le64(sampleCount))
            return hex(hash.digest())
        }
    }
    /** Reads one page at a time. Truncation or a broken page structure throws; framing and CRC faults clear [valid]. */
    class Reader(private val input: InputStream) {
        var valid = true
            private set
        var eos = false
            private set
        var finalGranule = -1L
            private set
        val complete get() = valid && eos && partial.size() == 0
        private var serial: Int? = null
        private var seq = 0
        private var packets = 0
        private val partial = ByteArrayOutputStream()
        private val ready = ArrayDeque<ByteArray>()
        private val header = ByteArray(27 + 255)

        private fun fill(buffer: ByteArray, offset: Int, length: Int): Int {
            var got = 0
            while (got < length) { val n = input.read(buffer, offset + got, length - got); if (n < 0) break; got += n }
            return got
        }
        /** The next whole page, or null at a clean end of input. */
        fun page(): ByteArray? {
            val got = fill(header, 0, 27)
            if (got == 0) return null
            require(got == 27 && String(header, 0, 4, Charsets.US_ASCII) == "OggS")
            val count = header[26].toInt() and 255
            require(fill(header, 27, count) == count)
            var size = 0
            repeat(count) { size += header[27 + it].toInt() and 255 }
            val page = header.copyOf(27 + count + size)
            require(fill(page, 27 + count, size) == size)
            val flags = page[5].toInt() and 255
            val pageSerial = u32(page, 14)
            val pageSeq = u32(page, 18)
            if (serial == null) { serial = pageSerial; if (flags and 2 == 0 || pageSeq != 0) valid = false }
            if (pageSerial != serial || pageSeq != seq++ || eos || page[4] != 0.toByte()) valid = false
            if ((flags and 1 != 0) != (partial.size() > 0)) valid = false
            val expectedCrc = u32(page, 22)
            for (i in 22..25) page[i] = 0
            if (crc(page) != expectedCrc) valid = false
            put32(page, 22, expectedCrc)
            var cursor = 27 + count
            repeat(count) { i ->
                val length = page[27 + i].toInt() and 255
                require(partial.size() + length <= if (packets < 2) MAX_HEADER_BYTES else 65536)
                partial.write(page, cursor, length); cursor += length
                if (length < 255) {
                    ready.addLast(partial.toByteArray()); partial.reset(); packets++
                    // OpusHead is alone on the BOS page; audio starts after the tags page.
                    if ((packets == 1 || packets == 2) && i != count - 1) valid = false
                }
            }
            if (flags and 4 != 0) { eos = true; finalGranule = i64(page, 6) }
            return page
        }
        /** The next whole packet, or null once the input ends. */
        fun packet(): ByteArray? {
            while (ready.isEmpty()) page() ?: return null
            return ready.removeFirst()
        }
        fun headers(): Pair<ByteArray, ByteArray> {
            val head = packet(); val tags = packet()
            require(head != null && tags != null && head.startsWith("OpusHead") && tags.startsWith("OpusTags"))
            return head to tags
        }
    }
    fun read(bytes: ByteArray, headersOnly: Boolean = false): Stream {
        val reader = Reader(ByteArrayInputStream(bytes))
        val (head, tags) = reader.headers()
        if (headersOnly) return Stream(head, tags, emptyList(), -1, false)
        val audio = generateSequence { reader.packet() }.toList()
        return Stream(head, tags, audio, reader.finalGranule, reader.complete)
    }
    fun headers(input: InputStream) = Reader(input).headers()
    /** One pass over a whole stream, holding a single packet at a time. */
    fun scan(input: InputStream): Info {
        val reader = Reader(input)
        val (head, tags) = reader.headers()
        val hasher = try { Hasher(head) } catch (_: Exception) { null }
        var usable = hasher != null
        while (true) {
            val packet = reader.packet() ?: break
            if (usable) try { hasher!!.add(packet) } catch (_: Exception) { usable = false }
        }
        val digest = if (usable && reader.complete) try { hasher!!.finish(reader.finalGranule) } catch (_: Exception) { null } else null
        return Info(head, tags, reader.finalGranule, reader.complete, hasher?.sampleCount(reader.finalGranule) ?: 0, digest)
    }
    /** Lays out one packet per page group, exactly as every Cassini writer does, so equal packets give equal bytes. */
    private class Writer(private val out: OutputStream, private val serial: Int, initialSequence: Int = 0) {
        var seq = initialSequence
            private set
        fun packet(bytes: ByteArray, flags: Int, granule: Long) {
            val laces = MutableList(bytes.size / 255) { 255 }.also { it += bytes.size % 255 }
            var offset = 0
            laces.chunked(255).forEachIndexed { index, group ->
                val size = group.sum()
                val page = ByteArray(27 + group.size + size)
                "OggS".toByteArray().copyInto(page)
                val last = offset + size == bytes.size && group.last() < 255
                page[5] = ((if (index > 0) 1 else flags and 2) or (if (last) flags and 4 else 0)).toByte()
                le64(if (last) granule else -1).copyInto(page, 6)
                put32(page, 14, serial); put32(page, 18, seq++)
                page[26] = group.size.toByte()
                group.forEachIndexed { i, n -> page[27 + i] = n.toByte() }
                bytes.copyInto(page, 27 + group.size, offset, offset + size)
                put32(page, 22, crc(page)); out.write(page); offset += size
            }
        }
    }
    /**
     * [mux] as packets arrive: holds one packet back so the last one carries EOS. Packets past [finalGranule]
     * (encoder flush) are dropped; [finish] fails unless the packets cover it.
     */
    class Muxer(out: OutputStream, head: ByteArray, tags: ByteArray, private val finalGranule: Long, serial: Int = Random.nextInt()) {
        private val writer = Writer(out, serial)
        private var pending: ByteArray? = null
        private var granule = 0L
        init { require(tags.size <= MAX_HEADER_BYTES && finalGranule > 0); writer.packet(head, 2, 0); writer.packet(tags, 0, 0) }
        val full get() = granule >= finalGranule
        fun add(packet: ByteArray) {
            if (full) return
            pending?.let { writer.packet(it, 0, granule) }
            pending = packet; granule += packetSamples(packet)
        }
        fun finish() {
            require(full) { "Opus packets do not cover the final granule" }
            writer.packet(pending!!, 4, finalGranule); pending = null
        }
    }
    /**
     * Live capture: the final length is unknown until [finish]. Every page written so far is a valid prefix, so a
     * file cut short after a flush still decodes up to its last whole page.
     */
    data class LiveState(val serial: Int, val sequence: Int, val pending: ByteArray?, val granule: Long)
    class LiveMuxer(out: OutputStream, head: ByteArray, tags: ByteArray, private val serial: Int = Random.nextInt(), restored: LiveState? = null) {
        private val writer = Writer(out, serial, restored?.sequence ?: 0)
        private var pending: ByteArray? = restored?.pending
        var granule = restored?.granule ?: 0L
            private set
        val preSkip = u16(head, 10)
        init { require(tags.size <= MAX_HEADER_BYTES); if (restored == null) { writer.packet(head, 2, 0); writer.packet(tags, 0, 0) } }
        fun checkpoint() = LiveState(serial, writer.seq, pending?.copyOf(), granule)
        val empty get() = pending == null
        fun add(packet: ByteArray) {
            val samples = packetSamples(packet)
            pending?.let { writer.packet(it, 0, granule) }
            pending = packet; granule += samples
        }
        /** Ends the stream at [finalGranule], clamped to what the packets cover; null keeps every sample. */
        fun finish(finalGranule: Long? = null) {
            val last = pending ?: throw IllegalStateException("No Opus audio packets")
            writer.packet(last, 4, (finalGranule ?: granule).coerceIn(minOf(preSkip.toLong(), granule), granule)); pending = null
        }
    }
    fun mux(stream: Stream, tags: ByteArray, serial: Int = Random.nextInt()): ByteArray {
        require(stream.validFraming && tags.size <= MAX_HEADER_BYTES && stream.audio.isNotEmpty())
        val out = ByteArrayOutputStream()
        val writer = Writer(out, serial)
        writer.packet(stream.head, 2, 0); writer.packet(tags, 0, 0)
        var granule = 0L
        stream.audio.forEachIndexed { i, bytes ->
            granule += packetSamples(bytes)
            writer.packet(bytes, if (i == stream.audio.lastIndex) 4 else 0,
                if (i == stream.audio.lastIndex) stream.finalGranule else granule)
        }
        return out.toByteArray()
    }
    /**
     * [mux] without holding the audio: copies [input]'s packets behind new [tags], renumbering pages and
     * recomputing CRCs. Returns what it read, digest included; a damaged source throws after partial output.
     */
    fun rewrite(input: InputStream, tags: ByteArray, out: OutputStream, serial: Int = Random.nextInt()): Info {
        require(tags.size <= MAX_HEADER_BYTES)
        val reader = Reader(input)
        val (head, oldTags) = reader.headers()
        val hasher = Hasher(head)
        val writer = Writer(out, serial)
        writer.packet(head, 2, 0); writer.packet(tags, 0, 0)
        var previous = reader.packet() ?: throw IllegalArgumentException("No Opus audio packets")
        var granule = 0L
        while (true) {
            hasher.add(previous); granule += packetSamples(previous)
            val next = reader.packet()
            if (next == null) { require(reader.complete); writer.packet(previous, 4, reader.finalGranule); break }
            writer.packet(previous, 0, granule)
            previous = next
        }
        return Info(head, oldTags, reader.finalGranule, true, hasher.sampleCount(reader.finalGranule), hasher.finish(reader.finalGranule))
    }
    fun rewrite(source: File, tags: ByteArray, target: File, serial: Int = Random.nextInt()): Info =
        source.inputStream().buffered().use { input ->
            FileOutputStream(target).use { file ->
                val out = file.buffered()
                rewrite(input, tags, out, serial).also { out.flush(); file.fd.sync() }
            }
        }
    fun scan(file: File): Info = file.inputStream().buffered().use { scan(it as InputStream) }
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
