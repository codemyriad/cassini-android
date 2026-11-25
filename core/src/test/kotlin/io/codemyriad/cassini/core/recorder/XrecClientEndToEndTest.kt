package io.codemyriad.cassini.core.recorder

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import kotlin.random.Random

/** [XrecClient] over piped streams against [FakeRecorder], which follows the device semantics of the spec. */
class XrecClientEndToEndTest {
    private val key = ByteArray(32) { (it * 3).toByte() }
    private val closeables = mutableListOf<AutoCloseable>()
    private val timeouts = XrecTimeouts(replyMillis = 2_000, frameMillis = 400, quietMillis = 100, drainMillis = 3_000)

    @After fun close() = closeables.reversed().forEach { it.close() }

    private fun device(fake: FakeRecorder = FakeRecorder(key = key)): FakeRecorder = fake.also { closeables += it }

    private fun connect(fake: FakeRecorder, timeouts: XrecTimeouts = this.timeouts): XrecClient {
        val input = StreamXrecInput(fake.clientInput).also { closeables += it }
        return XrecClient(input, fake.clientOutput, timeouts)
    }

    private fun authenticated(fake: FakeRecorder = device()): XrecClient = connect(fake).also { it.authenticate(key) }

    @Test fun firstBindingThenChallengeAuth() {
        val fake = device(FakeRecorder(pairingPin = "482913"))
        val client = connect(fake)
        val pong = client.ping()
        assertTrue(pong.pairingOpen)
        assertFalse(pong.bound)
        expect(XrecFailure.UNBOUND) { client.authenticate(key) }
        expect(XrecFailure.WRONG_PIN) { client.pair("000000", key) }
        client.pair("482913", key)
        assertTrue(fake.authenticated)
        assertArrayEquals(key, fake.key)
        val after = client.ping()
        assertTrue(after.bound && after.authenticated && !after.pairingOpen)
        expect(XrecFailure.PAIRING_CLOSED) { client.pair("482913", key) }
        client.authenticate(key)
    }

    @Test fun authGatesTheCommands() {
        val client = connect(device())
        expect(XrecFailure.LOCKED) { client.stat() }
        expect(XrecFailure.LOCKED) { client.list() }
        expect(XrecFailure.AUTH) { client.authenticate(ByteArray(32)) }
        client.authenticate(key)
        assertEquals(87, client.stat().batteryPercent)
    }

    @Test fun statusClockAndListing() {
        val fake = device(FakeRecorder(key = key, recording = true))
        fake.files["REC_0001.wav"] = ByteArray(10)
        fake.files["odd, name.wav"] = ByteArray(3)
        val client = authenticated(fake)
        assertTrue(client.stat().recording)
        assertEquals(LocalDateTime.of(2026, 11, 25, 10, 11, 12), client.rtc().time)
        client.setRtc(LocalDateTime.of(2026, 11, 25, 12, 0, 0))
        assertEquals("+XREC:RTC,2026,11,25,12,0,0,2", fake.commands.last())
        assertEquals(listOf(XrecReply.Entry(10, "REC_0001.wav"), XrecReply.Entry(3, "odd, name.wav")), client.list())
    }

    private fun gets(fake: FakeRecorder) = fake.commands.filter { it.startsWith("+XREC:GETS") }

    @Test fun downloadsAcrossSeveral64KiBWindows() {
        val fake = device()
        val data = Random(1).nextBytes(200_000)
        fake.files["REC_0001.wav"] = data
        val client = authenticated(fake)
        val progress = mutableListOf<Long>()
        val sink = ByteArrayOutputStream()
        assertEquals(200_000L, client.download("REC_0001.wav", 200_000, sink) { progress += it })
        assertArrayEquals(data, sink.toByteArray())
        // Progress for each frame, so the stall watchdog sees a long window move.
        assertEquals(200_000L, progress.last())
        assertTrue((listOf(0L) + progress).zipWithNext().all { (a, b) -> b - a in 1..640 })
        assertEquals(3 * 103 + 6, progress.size) // 102 frames of 640 and one of 256 for each full window
        assertEquals(
            listOf(
                "+XREC:GETS,0,65536,640,REC_0001.wav",
                "+XREC:GETS,65536,65536,640,REC_0001.wav",
                "+XREC:GETS,131072,65536,640,REC_0001.wav",
                "+XREC:GETS,196608,3392,640,REC_0001.wav",
            ),
            gets(fake),
        )
        assertEquals(XrecDownloadStats(200_000, client.lastDownload!!.millis, 0, 4, 65_536), client.lastDownload)
    }

    @Test fun theStockFirmwareKeepsTheSmallWindow() {
        val fake = device(FakeRecorder(key = key, firmware = "pen133_v2"))
        val data = Random(11).nextBytes(10_000)
        fake.files["a.wav"] = data
        val client = connect(fake)
        assertEquals(XrecLimits.GETS_WINDOW, client.getsWindow)
        client.ping()
        assertEquals(XrecLimits.STOCK_GETS_WINDOW, client.getsWindow)
        client.authenticate(key)
        val sink = ByteArrayOutputStream()
        client.download("a.wav", 10_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(
            listOf("+XREC:GETS,0,3840,640,a.wav", "+XREC:GETS,3840,3840,640,a.wav", "+XREC:GETS,7680,2320,640,a.wav"),
            gets(fake),
        )
    }

    @Test fun openFwKeepsTheLargeWindowAfterPing() {
        val fake = device()
        fake.files["a.wav"] = Random(12).nextBytes(10_000)
        val client = connect(fake)
        client.ping()
        client.authenticate(key)
        client.download("a.wav", 10_000, ByteArrayOutputStream())
        assertEquals(listOf("+XREC:GETS,0,10000,640,a.wav"), gets(fake))
    }

    @Test fun shortFramesFromASmallMtuAreNotTheEnd() {
        val fake = device(FakeRecorder(key = key, mtu = 300))
        val data = Random(2).nextBytes(150_000)
        fake.files["a.wav"] = data
        val client = authenticated(fake)
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        client.download("a.wav", 150_000, sink) { progress += it }
        assertArrayEquals(data, sink.toByteArray())
        // Frames of 288 bytes inside each 64 KiB window: the end comes from the size, not from a short frame.
        assertEquals(288L, progress.first())
        assertTrue(progress.zipWithNext().all { (a, b) -> b - a in 1..288 })
        assertEquals(
            listOf(
                "+XREC:GETS,0,65536,640,a.wav",
                "+XREC:GETS,65536,65536,640,a.wav",
                "+XREC:GETS,131072,18928,640,a.wav",
            ),
            gets(fake),
        )
        assertEquals(0, client.lastDownload!!.retries)
    }

    @Test fun resumesFromAnOffset() {
        val fake = device()
        val data = Random(3).nextBytes(4_000)
        fake.files["a.wav"] = data
        val sink = ByteArrayOutputStream().apply { write(data, 0, 1_234) }
        authenticated(fake).download("a.wav", 4_000, sink, resumeOffset = 1_234)
        assertArrayEquals(data, sink.toByteArray())
        assertEquals("+XREC:GETS,1234,2766,640,a.wav", fake.commands.first { it.startsWith("+XREC:GETS") })
    }

    @Test fun aDroppedFrameMidWindowIsFetchedAgainFromTheLastGoodOffset() {
        val fake = device()
        val data = Random(4).nextBytes(100_000)
        fake.files["a.wav"] = data
        fake.dropFrames += 32_000L // in the middle of the first window: the next frame has the wrong offset
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val client = authenticated(fake)
        client.download("a.wav", 100_000, sink) { progress += it }
        assertArrayEquals(data, sink.toByteArray())
        // The rest of the first window drained; the second asks from the dropped frame, without a gap or a repeat.
        assertEquals(
            listOf(
                "+XREC:GETS,0,65536,640,a.wav",
                "+XREC:GETS,32000,65536,640,a.wav",
                "+XREC:GETS,97536,2464,640,a.wav",
            ),
            gets(fake),
        )
        assertEquals(progress.distinct(), progress)
        assertTrue(progress.zipWithNext().all { (a, b) -> b - a in 1..640 })
        assertEquals(1, client.lastDownload!!.retries)
        // The link is quiet again: the next command gets its own reply.
        assertEquals(87, client.stat().batteryPercent)
    }

    @Test fun aDroppedLastFrameOfAWindowTimesOutAndIsFetchedAgain() {
        val fake = device()
        val data = Random(10).nextBytes(70_000)
        fake.files["a.wav"] = data
        fake.dropFrames += 65_280L // the short last frame of the first window: only the frame wait shows it
        val sink = ByteArrayOutputStream()
        val client = authenticated(fake)
        client.download("a.wav", 70_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(listOf("+XREC:GETS,0,65536,640,a.wav", "+XREC:GETS,65280,4720,640,a.wav"), gets(fake))
        assertEquals(1, client.lastDownload!!.retries)
    }

    @Test fun aCrcErrorMidWindowResumesWithoutDuplicatesOrGaps() {
        val fake = device()
        val data = Random(5).nextBytes(100_000)
        fake.files["a.wav"] = data
        fake.corruptFrames += 32_000L
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        val client = authenticated(fake)
        client.download("a.wav", 100_000, sink) { progress += it }
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(
            listOf(
                "+XREC:GETS,0,65536,640,a.wav",
                "+XREC:GETS,32000,65536,640,a.wav",
                "+XREC:GETS,97536,2464,640,a.wav",
            ),
            gets(fake),
        )
        assertEquals(progress.distinct(), progress)
        assertEquals(32_000L, progress[49])
        assertEquals(32_640L, progress[50])
        assertEquals(1, client.lastDownload!!.retries)
    }

    @Test fun aCrcErrorInAClippedFrameResumesAtItsOffset() {
        val fake = device(FakeRecorder(key = key, mtu = 300))
        val data = Random(13).nextBytes(80_000)
        fake.files["a.wav"] = data
        fake.corruptFrames += 288L * 100
        val sink = ByteArrayOutputStream()
        authenticated(fake).download("a.wav", 80_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        assertEquals("+XREC:GETS,28800,51200,640,a.wav", gets(fake)[1])
    }

    @Test fun aFileShorterThanItsListingEndsWithAnEmptyFrame() {
        val fake = device()
        val data = Random(14).nextBytes(70_000)
        fake.files["a.wav"] = data
        val sink = ByteArrayOutputStream()
        val client = authenticated(fake)
        expect(XrecFailure.SIZE_CHANGED) { client.download("a.wav", 80_000, sink) }
        // Every byte that exists arrived once: a short frame ended the window, the empty frame at 70 000 the file.
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(
            listOf(
                "+XREC:GETS,0,65536,640,a.wav",
                "+XREC:GETS,65536,14464,640,a.wav",
                "+XREC:GETS,70000,10000,640,a.wav",
            ),
            gets(fake),
        )
        assertEquals(87, client.stat().batteryPercent)
    }

    @Test fun aMissingFileOrAShorterFileFails() {
        val fake = device()
        fake.files["a.wav"] = ByteArray(100)
        val client = authenticated(fake)
        expect(XrecFailure.NOT_FOUND) { client.download("b.wav", 100, ByteArrayOutputStream()) }
        expect(XrecFailure.SIZE_CHANGED) { client.download("a.wav", 200, ByteArrayOutputStream()) }
        // The link stays usable.
        assertEquals(87, client.stat().batteryPercent)
    }

    @Test fun uploadsASidecarInChunks() {
        val fake = device()
        fake.files["REC_0001.wav"] = ByteArray(100)
        val bytes = Random(6).nextBytes(1_500)
        val client = authenticated(fake)
        val progress = mutableListOf<Int>()
        client.uploadSidecar("REC_0001.cwx", bytes) { progress += it }
        assertArrayEquals(bytes, fake.files["REC_0001.cwx"])
        // The first chunk is the short probe of the REC target.
        assertEquals(listOf(64, 664, 1_264, 1_500), progress)
        assertEquals(4, fake.putWrites)
        // A second upload replaces the file.
        client.uploadSidecar("REC_0001.cwx", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), fake.files["REC_0001.cwx"])
    }

    @Test fun aLostAckIsResentWithoutWritingTwice() {
        val fake = device()
        fake.files["a.wav"] = ByteArray(100)
        val bytes = Random(7).nextBytes(1_300)
        fake.dropPutAcks += 664L
        val client = connect(fake, timeouts.copy(replyMillis = 500)).also { it.authenticate(key) }
        client.uploadSidecar("a.cwx", bytes)
        assertArrayEquals(bytes, fake.files["a.cwx"])
        assertEquals(4, fake.putWrites)
        assertEquals(2, fake.commands.count { it.startsWith("+XREC:PUT,664,600,") })
        // The resync is a PING whose PONG ends the stale replies.
        assertTrue(fake.commands.contains("+XREC:PING"))
    }

    @Test fun aCorruptedChunkIsResent() {
        val fake = device()
        fake.files["a.wav"] = ByteArray(100)
        val bytes = Random(8).nextBytes(900)
        fake.corruptPutChunks += 0L
        authenticated(fake).uploadSidecar("a.cwx", bytes)
        assertArrayEquals(bytes, fake.files["a.cwx"])
        assertEquals(2, fake.commands.count { it.startsWith("+XREC:PUT,0,64,") })
    }

    @Test fun aLostPutEndReplyIsAskedAgainAndTheCommitHappensOnce() {
        val fake = device()
        fake.files["a.wav"] = ByteArray(100)
        fake.dropPutEndAck = true
        val bytes = Random(10).nextBytes(700)
        val client = connect(fake, timeouts.copy(replyMillis = 500)).also { it.authenticate(key) }
        client.uploadSidecar("a.cwx", bytes)
        assertArrayEquals(bytes, fake.files["a.cwx"])
        assertEquals(2, fake.commands.count { it == "+XREC:PUTEND" })
        assertEquals(87, client.stat().batteryPercent)
    }

    @Test fun aSidecarWithoutItsRecordingIsRefused() {
        val fake = device()
        val client = authenticated(fake)
        expect(XrecFailure.NOT_FOUND) { client.uploadSidecar("a.cwx", Random(11).nextBytes(700)) }
        assertNull(fake.files["a.cwx"])
        // The refused chunk was eaten whole: the link stays usable.
        assertEquals(87, client.stat().batteryPercent)

        // The recording is deleted during the upload: PUTEND refuses, and no orphan sidecar stays.
        fake.files["a.wav"] = ByteArray(100)
        fake.beforePutEnd = { fake.files.remove("a.wav") }
        expect(XrecFailure.NOT_FOUND) { client.uploadSidecar("a.cwx", Random(12).nextBytes(700)) }
        assertTrue(fake.files.isEmpty())
        assertEquals(87, client.stat().batteryPercent)
    }

    @Test fun namesAreCaseInsensitiveAsOnTheCard() {
        val fake = device()
        fake.files["REC_0001.WAV"] = ByteArray(100)
        val client = authenticated(fake)
        client.uploadSidecar("REC_0001.cwx", byteArrayOf(1, 2, 3))
        client.uploadSidecar("rec_0001.cwx", byteArrayOf(4, 5))
        assertEquals(setOf("REC_0001.WAV", "rec_0001.cwx"), fake.files.keys)
        // DEL of a recording deletes its sidecar too.
        client.delete("rec_0001.wav")
        assertTrue(fake.files.isEmpty())
    }

    @Test fun oldFirmwareWithoutTheRecTargetIsReportedAndTheLinkRecovers() {
        val fake = device(FakeRecorder(key = key, recordTarget = false))
        fake.files["a.wav"] = ByteArray(50)
        val client = authenticated(fake)
        // The payload, read as a line by the device, holds CRs and a command-like text.
        val payload = "\r+XREC:BOGUS\r".toByteArray() + Random(9).nextBytes(580)
        expect(XrecFailure.FIRMWARE_TOO_OLD) { client.uploadSidecar("a.cwx", payload) }
        assertNull(fake.files["a.cwx"])
        assertEquals(87, client.stat().batteryPercent)
        assertEquals(listOf(XrecReply.Entry(50, "a.wav")), client.list())
    }

    @Test fun anUploadNeedsAuthentication() {
        val fake = device()
        fake.files["a.wav"] = ByteArray(100)
        val client = connect(fake)
        expect(XrecFailure.LOCKED) { client.uploadSidecar("a.cwx", ByteArray(10)) }
        client.authenticate(key)
        client.uploadSidecar("a.cwx", ByteArray(10))
    }

    @Test fun deletesAFile() {
        val fake = device()
        fake.files["a.wav"] = ByteArray(1)
        val client = authenticated(fake)
        client.delete("a.wav")
        assertTrue(fake.files.isEmpty())
        expect(XrecFailure.DELETE_FAILED) { client.delete("a.wav") }
    }

    @Test fun aClosedLinkFailsTheOperation() {
        val fake = device()
        val client = authenticated(fake)
        fake.close()
        expect(XrecFailure.DISCONNECTED) { client.stat() }
    }

    @Test fun theCwxOfAnEncodedTranscriptSurvivesTheRoundTrip() {
        val fake = device()
        fake.files["REC_0007.wav"] = ByteArray(100)
        val client = authenticated(fake)
        val words = (0 until 300).map { CwxWord(it * 400L, it * 400L + 350, it / 20 % 3, "word$it") }
        val speakers = listOf(CwxSpeaker("A"), CwxSpeaker("B"), CwxSpeaker("C"))
        val cwx = CwxEncoder.encode(CwxTranscript("Round trip", 120_000, speakers, words))
        client.uploadSidecar("REC_0007.cwx", cwx)
        val reader = CwxReader(checkNotNull(fake.files["REC_0007.cwx"]))
        assertEquals(300L, reader.wordCount)
        assertEquals("word299", reader.wordsFrom(0).last().text)
    }

    private fun expect(failure: XrecFailure, block: () -> Unit) {
        try {
            block()
            fail("Expected $failure")
        } catch (e: XrecException) {
            assertEquals(e.message, failure, e.failure)
        }
    }
}
