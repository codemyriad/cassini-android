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

    @Test fun downloadsAcrossSeveralBursts() {
        val fake = device()
        val data = Random(1).nextBytes(10_000)
        fake.files["REC_0001.wav"] = data
        val client = authenticated(fake)
        val progress = mutableListOf<Long>()
        val sink = ByteArrayOutputStream()
        assertEquals(10_000L, client.download("REC_0001.wav", 10_000, sink) { progress += it })
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(10_000L, progress.last())
        assertEquals(3, fake.commands.count { it.startsWith("+XREC:GETS,") })
    }

    @Test fun shortFramesFromASmallMtuAreNotTheEnd() {
        val fake = device(FakeRecorder(key = key, mtu = 300))
        val data = Random(2).nextBytes(5_000)
        fake.files["a.wav"] = data
        val client = authenticated(fake)
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        client.download("a.wav", 5_000, sink) { progress += it }
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(288L, progress.first())
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

    @Test fun aDroppedFrameIsFetchedAgainFromTheLastGoodOffset() {
        val fake = device()
        val data = Random(4).nextBytes(8_000)
        fake.files["a.wav"] = data
        fake.dropFrames += 1_280L // in the middle of the first burst: the next frame has the wrong offset
        val sink = ByteArrayOutputStream()
        authenticated(fake).download("a.wav", 8_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        val gets = fake.commands.filter { it.startsWith("+XREC:GETS") }
        assertEquals("+XREC:GETS,1280,3840,640,a.wav", gets[1])
    }

    @Test fun aDroppedLastFrameOfABurstTimesOutAndIsFetchedAgain() {
        val fake = device()
        val data = Random(10).nextBytes(5_000)
        fake.files["a.wav"] = data
        fake.dropFrames += 3_200L // only the frame wait shows it
        val sink = ByteArrayOutputStream()
        authenticated(fake).download("a.wav", 5_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        val gets = fake.commands.filter { it.startsWith("+XREC:GETS") }
        assertEquals(listOf("+XREC:GETS,0,3840,640,a.wav", "+XREC:GETS,3200,1800,640,a.wav"), gets)
    }

    @Test fun aCrcErrorIsRetried() {
        val fake = device()
        val data = Random(5).nextBytes(2_000)
        fake.files["a.wav"] = data
        fake.corruptFrames += 640L
        val sink = ByteArrayOutputStream()
        authenticated(fake).download("a.wav", 2_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        assertTrue(fake.commands.contains("+XREC:GETS,640,1360,640,a.wav"))
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
        val bytes = Random(6).nextBytes(1_500)
        val client = authenticated(fake)
        val progress = mutableListOf<Int>()
        client.uploadSidecar("REC_0001.cwx", bytes) { progress += it }
        assertArrayEquals(bytes, fake.files["REC_0001.cwx"])
        assertEquals(listOf(600, 1_200, 1_500), progress)
        assertEquals(3, fake.putWrites)
        // A second upload replaces the file.
        client.uploadSidecar("REC_0001.cwx", byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), fake.files["REC_0001.cwx"])
    }

    @Test fun aLostAckIsResentWithoutWritingTwice() {
        val fake = device()
        val bytes = Random(7).nextBytes(1_300)
        fake.dropPutAcks += 600L
        val client = connect(fake, timeouts.copy(replyMillis = 500)).also { it.authenticate(key) }
        client.uploadSidecar("a.cwx", bytes)
        assertArrayEquals(bytes, fake.files["a.cwx"])
        assertEquals(3, fake.putWrites)
        assertEquals(2, fake.commands.count { it.startsWith("+XREC:PUT,600,600,") })
    }

    @Test fun aCorruptedChunkIsResent() {
        val fake = device()
        val bytes = Random(8).nextBytes(900)
        fake.corruptPutChunks += 0L
        authenticated(fake).uploadSidecar("a.cwx", bytes)
        assertArrayEquals(bytes, fake.files["a.cwx"])
        assertEquals(2, fake.commands.count { it.startsWith("+XREC:PUT,0,600,") })
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
        val client = connect(device())
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
