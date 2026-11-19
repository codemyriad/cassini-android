package io.codemyriad.cassini.core.format

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

/** One complete Opus audio packet released from a committed, CRC-valid non-EOS Ogg page. */
class CommittedOggPacket(
    val bytes: ByteArray,
    /** Starting 48 kHz granule position of this packet on the raw Ogg clock (before pre-skip subtraction). */
    val granuleStart: Long,
    /** Decoded 48 kHz sample count of this packet (`OggOpus.packetSamples`). */
    val sampleCount: Int,
    /** End byte offset in the Ogg file of the page that completed this packet. */
    val pageEndBytes: Long,
)

/**
 * Read-only incremental scanner for a growing Ogg Opus file written by `OggOpus.LiveMuxer` (D-1124).
 *
 * Opens [file] strictly read-only (`RandomAccessFile(file, "r")`) and advances only across complete,
 * CRC32-valid, framing-valid, non-EOS Ogg pages. Holding back [trailingPagesMargin] complete pages
 * behind the writer head ensures decoder lookahead and resampler history stay strictly inside already-flushed
 * pages that remain byte-identical after `LiveMuxer.finish(...)` appends trailing frames and EOS.
 *
 * Never mutates [file] and never shares locks or state with `OpusCapture`.
 */
@Suppress("TooManyFunctions") // One small helper per Ogg page, framing, CRC, and packet step.
class OggOpusPrefixScanner(
    private val file: File,
    private val trailingPagesMargin: Int = DEFAULT_TRAILING_PAGES_MARGIN,
) {
    init {
        require(trailingPagesMargin >= 0) { "trailingPagesMargin must be non-negative" }
    }

    private class ScannedPage(
        val rawBytes: ByteArray,
        val endOffset: Long,
        val completedAudio: List<CommittedOggPacket>,
        val completeBoundary: Boolean,
        val cumulativeGranule: Long,
    )

    private val committedHash = MessageDigest.getInstance("SHA-256")
    private val heldPages = ArrayDeque<ScannedPage>()
    private val partial = ByteArrayOutputStream()
    private val header = ByteArray(PAGE_HEADER_BYTES + MAX_SEGMENTS)
    private val crcSaved = ByteArray(CRC_BYTES)

    private var scanOffset = 0L
    private var serial: Int? = null
    private var nextSequence = 0
    private var packetCount = 0
    private var scannedGranule = 0L

    var head: ByteArray? = null
        private set
    var tags: ByteArray? = null
        private set
    var committedBytes: Long = 0L
        private set
    var committedGranule: Long = 0L
        private set
    var sawEos: Boolean = false
        private set
    var corrupt: Boolean = false
        private set

    val preSkip: Int
        get() = head?.let { OggOpus.u16(it, PRE_SKIP_OFFSET) } ?: 0

    val channels: Int
        get() = head?.let { it[CHANNELS_OFFSET].toInt() and BYTE_MASK } ?: 0

    /** SHA-256 lowercase hex digest over exact file bytes `[0 ..< committedBytes)`. */
    fun committedSha256(): String = OggOpus.hex((committedHash.clone() as MessageDigest).digest())

    internal val heldPageCount: Int
        get() = heldPages.size

    /**
     * Scans up to [maxPages] pages and [maxBytes] bytes of newly flushed complete CRC-valid non-EOS pages
     * from [file], releasing committed audio packets incrementally to [onPacket] as soon as at least
     * [trailingPagesMargin] complete pages follow them.
     *
     * Checks [shouldContinue] between pages and stops early when it returns false.
     * Returns the number of committed audio packets delivered to [onPacket].
     */
    fun poll(
        maxPages: Int = DEFAULT_MAX_POLL_PAGES,
        maxBytes: Long = DEFAULT_MAX_POLL_BYTES,
        shouldContinue: () -> Boolean = { !Thread.currentThread().isInterrupted },
        onPacket: (CommittedOggPacket) -> Unit,
    ): Int {
        require(maxPages > 0) { "maxPages must be positive" }
        require(maxBytes > 0L) { "maxBytes must be positive" }
        if (corrupt || sawEos || !file.isFile) return 0
        var delivered = 0
        RandomAccessFile(file, "r").use { raf ->
            val fileLength = raf.length()
            var pagesRead = 0
            var bytesRead = 0L
            while (hasMorePages(fileLength) && withinPollBudget(pagesRead, bytesRead, maxPages, maxBytes) && shouldContinue()) {
                val page = readNextNonEosPage(raf, fileLength) ?: break
                pagesRead++
                bytesRead += page.rawBytes.size
                heldPages.addLast(page)
                delivered += releaseCommitted(onPacket)
            }
        }
        return delivered
    }

    /**
     * Bounded convenience overload returning newly committed audio packets as a list
     * (at most [DEFAULT_MAX_POLL_PAGES] pages and [DEFAULT_MAX_POLL_BYTES] bytes per call).
     */
    fun poll(): List<CommittedOggPacket> {
        val released = ArrayList<CommittedOggPacket>()
        poll(DEFAULT_MAX_POLL_PAGES, DEFAULT_MAX_POLL_BYTES) { released.add(it) }
        return released
    }

    private fun hasMorePages(fileLength: Long): Boolean =
        scanOffset + PAGE_HEADER_BYTES <= fileLength && !sawEos && !corrupt

    private fun withinPollBudget(pagesRead: Int, bytesRead: Long, maxPages: Int, maxBytes: Long): Boolean =
        pagesRead == 0 || (pagesRead < maxPages && bytesRead < maxBytes)

    private fun readNextNonEosPage(raf: RandomAccessFile, fileLength: Long): ScannedPage? {
        val raw = readCompletePageBytes(raf, fileLength) ?: return null
        return if (verifyCrc(raw)) parseValidPage(raw) else null
    }

    private fun readCompletePageBytes(raf: RandomAccessFile, fileLength: Long): ByteArray? {
        raf.seek(scanOffset)
        raf.readFully(header, 0, PAGE_HEADER_BYTES)
        if (!hasOggMagic(header) || header[VERSION_OFFSET] != 0.toByte()) {
            corrupt = true
            return null
        }
        val segmentCount = header[SEGMENT_COUNT_OFFSET].toInt() and BYTE_MASK
        val tableEnd = scanOffset + PAGE_HEADER_BYTES + segmentCount
        var result: ByteArray? = null
        if (tableEnd <= fileLength) {
            raf.readFully(header, PAGE_HEADER_BYTES, segmentCount)
            var bodySize = 0
            for (i in 0 until segmentCount) bodySize += header[PAGE_HEADER_BYTES + i].toInt() and BYTE_MASK
            val pageSize = PAGE_HEADER_BYTES + segmentCount + bodySize
            if (scanOffset + pageSize <= fileLength) {
                val page = ByteArray(pageSize)
                header.copyInto(page, 0, 0, PAGE_HEADER_BYTES + segmentCount)
                raf.readFully(page, PAGE_HEADER_BYTES + segmentCount, bodySize)
                result = page
            }
        }
        return result
    }

    private fun parseValidPage(page: ByteArray): ScannedPage? {
        val flags = page[FLAGS_OFFSET].toInt() and BYTE_MASK
        val framingOk = checkPageFraming(flags, OggOpus.u32(page, SERIAL_OFFSET), OggOpus.u32(page, SEQUENCE_OFFSET))
        return when {
            !framingOk -> {
                corrupt = true
                null
            }
            (flags and FLAG_EOS) != 0 -> {
                sawEos = true
                null
            }
            else -> consumePageSegments(page)
        }
    }

    private fun checkPageFraming(flags: Int, pageSerial: Int, pageSeq: Int): Boolean {
        val sequenceOk = if (serial == null) {
            val bos = (flags and FLAG_BOS) != 0 && pageSeq == 0
            if (bos) serial = pageSerial
            bos
        } else {
            pageSerial == serial && pageSeq == nextSequence && (flags and FLAG_BOS) == 0
        }
        val continuationOk = (flags and FLAG_CONTINUATION != 0) == (partial.size() > 0)
        return sequenceOk && continuationOk
    }

    private fun consumePageSegments(page: ByteArray): ScannedPage? {
        val pageEnd = scanOffset + page.size
        val segmentCount = page[SEGMENT_COUNT_OFFSET].toInt() and BYTE_MASK
        val completedAudio = ArrayList<CommittedOggPacket>()
        var cursor = PAGE_HEADER_BYTES + segmentCount
        var ok = true
        for (i in 0 until segmentCount) {
            val segLen = page[PAGE_HEADER_BYTES + i].toInt() and BYTE_MASK
            if (!appendSegment(page, cursor, segLen, i == segmentCount - 1, completedAudio)) {
                ok = false
                break
            }
            cursor += segLen
        }
        if (!ok) {
            corrupt = true
            return null
        }
        nextSequence++
        scanOffset = pageEnd
        return ScannedPage(
            rawBytes = page,
            endOffset = pageEnd,
            completedAudio = completedAudio,
            completeBoundary = partial.size() == 0,
            cumulativeGranule = scannedGranule,
        )
    }

    private fun appendSegment(
        page: ByteArray,
        cursor: Int,
        segLen: Int,
        isLastSegmentOnPage: Boolean,
        outAudio: MutableList<CommittedOggPacket>,
    ): Boolean {
        val maxPacket = if (packetCount < HEADER_PACKETS) OggOpus.MAX_HEADER_BYTES else MAX_AUDIO_PACKET_BYTES
        if (partial.size() + segLen > maxPacket) return false
        partial.write(page, cursor, segLen)
        return if (segLen < MAX_LACING) {
            val packet = partial.toByteArray()
            partial.reset()
            handlePacket(packet, isLastSegmentOnPage, scanOffset + page.size, outAudio)
        } else {
            true
        }
    }

    private fun handlePacket(
        packet: ByteArray,
        isLastSegmentOnPage: Boolean,
        pageEnd: Long,
        outAudio: MutableList<CommittedOggPacket>,
    ): Boolean = when (packetCount) {
        0 -> {
            val ok = isLastSegmentOnPage && validOpusHead(packet)
            if (ok) {
                head = packet
                packetCount++
            }
            ok
        }
        1 -> {
            val ok = isLastSegmentOnPage && hasAsciiPrefix(packet, "OpusTags")
            if (ok) {
                tags = packet
                packetCount++
            }
            ok
        }
        else -> handleAudioPacket(packet, pageEnd, outAudio)
    }

    private fun handleAudioPacket(
        packet: ByteArray,
        pageEnd: Long,
        outAudio: MutableList<CommittedOggPacket>,
    ): Boolean {
        val samples = try {
            OggOpus.packetSamples(packet)
        } catch (_: IllegalArgumentException) {
            return false
        }
        val start = scannedGranule
        scannedGranule += samples
        outAudio.add(CommittedOggPacket(packet, start, samples, pageEnd))
        packetCount++
        return true
    }

    private fun releaseCommitted(onPacket: (CommittedOggPacket) -> Unit): Int {
        val releasable = heldPages.size - trailingPagesMargin
        var lastBoundaryIndex = -1
        for (i in 0 until releasable) {
            if (heldPages[i].completeBoundary) lastBoundaryIndex = i
        }
        if (lastBoundaryIndex < 0) return 0
        var delivered = 0
        repeat(lastBoundaryIndex + 1) {
            val page = heldPages.removeFirst()
            committedHash.update(page.rawBytes)
            committedBytes = page.endOffset
            committedGranule = page.cumulativeGranule
            for (packet in page.completedAudio) {
                onPacket(packet)
                delivered++
            }
        }
        return delivered
    }

    private fun verifyCrc(page: ByteArray): Boolean {
        val expected = OggOpus.u32(page, CRC_OFFSET)
        page.copyInto(crcSaved, 0, CRC_OFFSET, CRC_OFFSET + CRC_BYTES)
        for (i in CRC_OFFSET until CRC_OFFSET + CRC_BYTES) page[i] = 0
        val actual = OggOpus.crc(page)
        crcSaved.copyInto(page, CRC_OFFSET)
        return actual == expected
    }

    private fun validOpusHead(packet: ByteArray): Boolean {
        if (packet.size < OPUS_HEAD_MIN_BYTES || !hasAsciiPrefix(packet, "OpusHead")) return false
        val version = packet[OPUS_VERSION_OFFSET].toInt() and BYTE_MASK
        val ch = packet[CHANNELS_OFFSET].toInt() and BYTE_MASK
        return version in 1..MAX_OPUS_VERSION && ch in 1..MAX_CHANNELS
    }

    private fun hasOggMagic(buffer: ByteArray): Boolean =
        buffer[MAGIC_BYTE_0] == 'O'.code.toByte() &&
            buffer[MAGIC_BYTE_1] == 'g'.code.toByte() &&
            buffer[MAGIC_BYTE_2] == 'g'.code.toByte() &&
            buffer[MAGIC_BYTE_3] == 'S'.code.toByte()

    private fun hasAsciiPrefix(bytes: ByteArray, prefix: String): Boolean =
        bytes.size >= prefix.length && String(bytes, 0, prefix.length, Charsets.US_ASCII) == prefix

    companion object {
        /** Hold back one complete non-EOS page behind the writer head by default. */
        const val DEFAULT_TRAILING_PAGES_MARGIN = 1

        /** Maximum Ogg pages scanned per incremental [poll] call to bound heap and latency on long backlogs. */
        const val DEFAULT_MAX_POLL_PAGES = 64

        /** Maximum Ogg container bytes scanned per incremental [poll] call (256 KiB). */
        const val DEFAULT_MAX_POLL_BYTES = 256L * 1024L

        private const val MAGIC_BYTE_0 = 0
        private const val MAGIC_BYTE_1 = 1
        private const val MAGIC_BYTE_2 = 2
        private const val MAGIC_BYTE_3 = 3
        private const val PAGE_HEADER_BYTES = 27
        private const val MAX_SEGMENTS = 255
        private const val MAX_LACING = 255
        private const val BYTE_MASK = 255
        private const val VERSION_OFFSET = 4
        private const val FLAGS_OFFSET = 5
        private const val SERIAL_OFFSET = 14
        private const val SEQUENCE_OFFSET = 18
        private const val CRC_OFFSET = 22
        private const val CRC_BYTES = 4
        private const val SEGMENT_COUNT_OFFSET = 26
        private const val FLAG_CONTINUATION = 1
        private const val FLAG_BOS = 2
        private const val FLAG_EOS = 4
        private const val HEADER_PACKETS = 2
        private const val MAX_AUDIO_PACKET_BYTES = 65_536
        private const val OPUS_HEAD_MIN_BYTES = 19
        private const val OPUS_VERSION_OFFSET = 8
        private const val MAX_OPUS_VERSION = 15
        private const val CHANNELS_OFFSET = 9
        private const val MAX_CHANNELS = 2
        private const val PRE_SKIP_OFFSET = 10
        private const val HASH_BUFFER_BYTES = 64 * 1024

        /** Whether [file] begins with a valid Ogg Opus BOS page header (`OggS` + `OpusHead`). */
        fun isOggOpusFile(file: File): Boolean {
            if (!file.isFile || file.length() < OggOpus.HEAD_BYTES) return false
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val head = ByteArray(OggOpus.HEAD_BYTES)
                    raf.readFully(head)
                    OggOpus.looksLikeOpus(head)
                }
            } catch (_: IOException) {
                false
            }
        }

        /**
         * Streaming lowercase hex SHA-256 over exact bytes `[0 ..< byteCount)` of [file].
         * Returns null when [file] is absent, unreadable, or shorter than [byteCount].
         */
        fun sha256Prefix(file: File, byteCount: Long): String? {
            if (byteCount <= 0L || !file.isFile || file.length() < byteCount) return null
            return try {
                RandomAccessFile(file, "r").use { raf -> hashPrefix(raf, byteCount) }
            } catch (_: IOException) {
                null
            }
        }

        private fun hashPrefix(raf: RandomAccessFile, byteCount: Long): String? {
            if (raf.length() < byteCount) return null
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_BYTES)
            var remaining = byteCount
            var eof = false
            while (remaining > 0L && !eof) {
                val want = minOf(buffer.size.toLong(), remaining).toInt()
                val got = raf.read(buffer, 0, want)
                if (got <= 0) {
                    eof = true
                } else {
                    digest.update(buffer, 0, got)
                    remaining -= got
                }
            }
            return if (eof) null else OggOpus.hex(digest.digest())
        }
    }
}
