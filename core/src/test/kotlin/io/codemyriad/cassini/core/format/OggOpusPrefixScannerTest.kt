package io.codemyriad.cassini.core.format

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/** Tests [OggOpusPrefixScanner] on growing LiveMuxer streams, partial pages, bad CRCs, EOS, and rewrites. */
class OggOpusPrefixScannerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun opusHead(preSkip: Int = 312, channels: Int = 1): ByteArray = ByteArray(19).also { head ->
        "OpusHead".toByteArray(Charsets.US_ASCII).copyInto(head, 0)
        head[8] = 1
        head[9] = channels.toByte()
        head[10] = (preSkip and 0xff).toByte()
        head[11] = ((preSkip ushr 8) and 0xff).toByte()
        // 48000 Hz input sample rate
        OggOpus.put32(head, 12, 48_000)
    }

    /** Valid 20 ms (960-sample) SILK/CELT Opus packet with TOC 0xFC (CELT 20 ms, 1 frame). */
    private fun fakeOpusPacket(seed: Int, size: Int = 64): ByteArray = ByteArray(size) { i ->
        if (i == 0) 0xfc.toByte() else ((seed * 31 + i) and 0xff).toByte()
    }

    @Test fun scansGrowingLiveMuxerPrefixAndMatchesFinalizedFilePrefixSha256() {
        val file = temp.newFile("capture.opus")
        val scanner = OggOpusPrefixScanner(file, trailingPagesMargin = 1)
        val out = FileOutputStream(file)
        val muxer = OggOpus.LiveMuxer(out, opusHead(), CassiniDocument.tagsPacket(emptyList()), serial = 42)
        out.flush()

        // Only OpusHead + OpusTags exist so far (2 pages); with margin=1 only OpusHead commits, no audio packets yet.
        assertTrue(scanner.poll().isEmpty())
        assertNotNull(scanner.head)
        assertNotNull(scanner.tags)
        assertEquals(312, scanner.preSkip)
        assertEquals(1, scanner.channels)

        // Feed 10 audio packets; LiveMuxer holds 1 pending packet so 9 audio pages are written to disk.
        val expectedPackets = (0 until 10).map { fakeOpusPacket(it) }
        expectedPackets.forEach(muxer::add)
        out.flush()

        val firstBatch = scanner.poll()
        // 9 audio pages written, trailingPagesMargin = 1 holds back the last page -> 8 committed audio packets!
        assertEquals(8, firstBatch.size)
        for (i in firstBatch.indices) {
            assertArrayEquals(expectedPackets[i], firstBatch[i].bytes)
            assertEquals(i * 960L, firstBatch[i].granuleStart)
            assertEquals(960, firstBatch[i].sampleCount)
        }
        val committedBytes = scanner.committedBytes
        val committedSha = scanner.committedSha256()
        assertTrue(committedBytes > 0L && committedBytes < file.length())
        assertEquals(committedSha, OggOpusPrefixScanner.sha256Prefix(file, committedBytes))

        // Append 5 more packets and finalize with EOS (simulating Stop).
        (10 until 15).forEach { muxer.add(fakeOpusPacket(it)) }
        muxer.finish(312L + 15 * 960L)
        out.flush()
        out.close()

        // Finalized file is longer, yet bytes [0 ..< committedBytes) are byte-for-byte identical!
        assertTrue(file.length() > committedBytes)
        assertTrue(OggOpusPrefixScanner.isOggOpusFile(file))
        assertEquals(committedSha, OggOpusPrefixScanner.sha256Prefix(file, committedBytes))

        // Polling after finish stops cleanly before the EOS page and never commits EOS.
        val secondBatch = scanner.poll()
        assertTrue(secondBatch.isNotEmpty())
        assertTrue(scanner.sawEos)
        assertFalse(scanner.corrupt)
        assertTrue(scanner.committedBytes < file.length())
        assertEquals(scanner.committedSha256(), OggOpusPrefixScanner.sha256Prefix(file, scanner.committedBytes))
    }

    @Test fun ignoresPartialTrailingPageAndBadCrcUntilComplete() {
        val raw = ByteArrayOutputStream()
        val muxer = OggOpus.LiveMuxer(raw, opusHead(), CassiniDocument.tagsPacket(emptyList()), serial = 7)
        repeat(6) { muxer.add(fakeOpusPacket(it)) }
        val fullNonEos = raw.toByteArray()

        val file = temp.newFile("growing.opus")
        // Write all but the last 10 bytes (truncated final page).
        file.writeBytes(fullNonEos.copyOf(fullNonEos.size - 10))
        val scanner = OggOpusPrefixScanner(file, trailingPagesMargin = 0)
        val before = scanner.poll()
        // 5 audio pages were in fullNonEos; truncating the 5th leaves 4 complete audio packets.
        assertEquals(4, before.size)
        val committedBefore = scanner.committedBytes

        // Corrupt a byte in the truncated trailing page while padding to full length: CRC fails, so it must NOT advance.
        val badCrc = fullNonEos.copyOf()
        badCrc[badCrc.lastIndex] = (badCrc[badCrc.lastIndex].toInt() xor 0x55).toByte()
        file.writeBytes(badCrc)
        assertTrue(scanner.poll().isEmpty())
        assertEquals(committedBefore, scanner.committedBytes)

        // Now write the valid complete bytes: scanner advances across the 5th page cleanly.
        file.writeBytes(fullNonEos)
        val after = scanner.poll()
        assertEquals(1, after.size)
        assertEquals(fullNonEos.size.toLong(), scanner.committedBytes)
        assertEquals(scanner.committedSha256(), OggOpusPrefixScanner.sha256Prefix(file, scanner.committedBytes))
    }

    @Test fun rewrittenHeaderOrAacFileNeverMatchesPrefix() {
        val opus = temp.newFile("a.opus")
        FileOutputStream(opus).use { out ->
            val muxer = OggOpus.LiveMuxer(out, opusHead(), CassiniDocument.tagsPacket(emptyList()), serial = 1)
            repeat(5) { muxer.add(fakeOpusPacket(it)) }
            muxer.finish()
        }
        val scanner = OggOpusPrefixScanner(opus, trailingPagesMargin = 1)
        scanner.poll()
        val committedBytes = scanner.committedBytes
        val committedSha = scanner.committedSha256()

        // Rewriting tags with a new serial/tags invalidates prefix SHA-256.
        val rewritten = temp.newFile("rewritten.opus")
        OggOpus.rewrite(opus, CassiniDocument.tagsPacket(listOf("TITLE=Changed")), rewritten, serial = 99)
        assertNotEquals(committedSha, OggOpusPrefixScanner.sha256Prefix(rewritten, committedBytes))

        // Non-Opus AAC/M4A file is rejected by isOggOpusFile.
        val m4a = temp.newFile("capture.m4a").apply {
            writeBytes(ByteArray(128) { if (it in 4..7) "ftyp"[it - 4].code.toByte() else 0 })
        }
        assertFalse(OggOpusPrefixScanner.isOggOpusFile(m4a))
        assertNull(OggOpusPrefixScanner.sha256Prefix(File(temp.root, "missing.opus"), 16))
    }

    @Test fun boundsMemoryAndHonorsPageByteLimitsAndCancellationOnLongStream() {
        val file = temp.newFile("long-capture.opus")
        val totalPackets = 7_200
        FileOutputStream(file).use { out ->
            val muxer = OggOpus.LiveMuxer(out, opusHead(), CassiniDocument.tagsPacket(emptyList()), serial = 55)
            repeat(totalPackets) { i -> muxer.add(fakeOpusPacket(i, size = 1024)) }
            out.flush()
        }

        // Cancellation between pages stops scanning immediately without reading the backlog.
        val cancelScanner = OggOpusPrefixScanner(file, trailingPagesMargin = 1)
        var cancelledAfter = 0
        val deliveredBeforeCancel = cancelScanner.poll(
            maxPages = 64,
            maxBytes = 256L * 1024L,
            shouldContinue = { cancelledAfter < 5 },
        ) {
            cancelledAfter++
        }
        assertEquals(5, deliveredBeforeCancel)
        assertTrue(cancelScanner.committedBytes in 1L until (file.length() / 10L))
        assertTrue(cancelScanner.heldPageCount <= 2)

        // Incremental bounded polling scans the entire long capture with constant held-page memory.
        val scanner = OggOpusPrefixScanner(file, trailingPagesMargin = 1)
        val maxPagesPerPoll = 32
        val maxBytesPerPoll = 64L * 1024L
        var totalDelivered = 0
        var polls = 0
        var maxHeldSeen = 0
        while (true) {
            var pollDelivered = 0
            val count = scanner.poll(maxPages = maxPagesPerPoll, maxBytes = maxBytesPerPoll) {
                pollDelivered++
                maxHeldSeen = maxOf(maxHeldSeen, scanner.heldPageCount)
            }
            assertEquals(pollDelivered, count)
            assertTrue("Poll must respect maxPages=$maxPagesPerPoll", count <= maxPagesPerPoll)
            maxHeldSeen = maxOf(maxHeldSeen, scanner.heldPageCount)
            if (count == 0) break
            totalDelivered += count
            polls++
        }

        // LiveMuxer holds 1 pending packet (7199 written) and margin=1 holds 1 trailing page -> 7198 committed packets.
        assertEquals(totalPackets - 2, totalDelivered)
        assertTrue("Long backlog must require multiple bounded polls", polls > 100)
        assertTrue("Held pages must stay O(margin), saw $maxHeldSeen", maxHeldSeen <= 2)
        assertFalse(scanner.corrupt)
        assertEquals(scanner.committedSha256(), OggOpusPrefixScanner.sha256Prefix(file, scanner.committedBytes))
    }
}
