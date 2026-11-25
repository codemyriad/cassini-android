package io.codemyriad.cassini.core.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/** [XrecClient] against a scripted receive side and a fake clock: timeouts without waiting. */
class XrecClientScriptedTest {
    private sealed interface Step {
        class Bytes(val data: ByteArray) : Step

        /** Nothing arrives within the requested wait; the fake clock moves on by that wait. */
        data object Silence : Step

        data object Closed : Step

        /** The fake clock moves on by [millis] before the next step: the time a transfer takes. */
        class Elapse(val millis: Long) : Step
    }

    private var now = 0L
    private val waits = mutableListOf<Long>()
    private val steps = ArrayDeque<Step>()
    private val sent = ByteArrayOutputStream()
    private val input = XrecInput { buffer, offset, length, timeout ->
        waits += timeout
        var next = steps.pollFirst() ?: Step.Silence
        while (next is Step.Elapse) {
            now += next.millis
            next = steps.pollFirst() ?: Step.Silence
        }
        when (val step = next) {
            is Step.Bytes -> {
                val n = minOf(length, step.data.size)
                step.data.copyInto(buffer, offset, 0, n)
                if (n < step.data.size) steps.addFirst(Step.Bytes(step.data.copyOfRange(n, step.data.size)))
                n
            }
            Step.Silence -> {
                now += timeout
                0
            }
            Step.Closed -> -1
            is Step.Elapse -> error("Elapse is consumed above")
        }
    }
    private val timeouts = XrecTimeouts(replyMillis = 1000, frameMillis = 700, quietMillis = 50, drainMillis = 500)
    private val client = XrecClient(input, sent, timeouts, clock = { now }, maxRetries = 2)

    private fun receive(text: String) = steps.add(Step.Bytes(text.toByteArray()))

    private companion object {
        const val PONG = "+XREC:PONG,openfw_v5,v5,bound=1,auth=1,pair=0\r\n"
    }

    @Test fun skipsLinesThatAreNotReplies() {
        receive("garbage\r\n\r\nOK\r\n+XREC:PONG,openfw_v5,v5,bound=0,auth=0,pair=1\r\n")
        assertEquals(true, client.ping().pairingOpen)
        assertEquals("+XREC:PING\r", sent.toString())
        assertEquals(1000L, waits.first())
    }

    @Test fun aReplySplitAcrossReadsIsJoined() {
        "+XREC:STAT,87,3990,0,0,33,4,1,2\r\n".chunked(3).forEach(::receive)
        assertEquals(true, client.stat().recording)
    }

    @Test fun noReplyTimesOut() {
        steps.add(Step.Silence)
        expect(XrecFailure.TIMEOUT) { client.stat() }
        assertEquals(1000L, now)
    }

    @Test fun aClosedLinkIsReported() {
        steps.add(Step.Closed)
        expect(XrecFailure.DISCONNECTED) { client.ping() }
    }

    @Test fun deviceErrorsAreTyped() {
        receive("+XREC:ERR,LOCKED\r\n")
        expect(XrecFailure.LOCKED) { client.list() }
        receive("+XREC:ERR,PAIRCLOSED\r\n")
        expect(XrecFailure.PAIRING_CLOSED) { client.pair("123456", ByteArray(32)) }
    }

    @Test fun anUnexpectedReplyIsAProtocolError() {
        receive("+XREC:OK,PUT,0,1\r\n")
        expect(XrecFailure.PROTOCOL) { client.ping() }
    }

    @Test fun authenticatesWithTheChallenge() {
        val key = ByteArray(32) { it.toByte() }
        receive("+XREC:CHAL,a0a1a2a3a4a5a6a7a8a9aaabacadaeaf\r\n+XREC:OK,AUTH\r\n")
        client.authenticate(key)
        assertEquals(
            "+XREC:CHAL\r+XREC:AUTH,b1489727b2602a0e047cb237580a4c795aaf9023c5bc6b0b865c7fa17e0a2367\r",
            sent.toString(),
        )
    }

    @Test fun aLostFrameTimesOutAndTheDownloadResumesFromTheLastGoodOffset() {
        val data = ByteArray(1000) { it.toByte() }
        // The first burst brings 640 bytes, then the frame at 640 never arrives.
        steps.add(Step.Bytes(frame(0, data.copyOfRange(0, 640))))
        steps.add(Step.Silence) // frame wait
        steps.add(Step.Silence) // drain ends in silence
        steps.add(Step.Bytes(frame(640, data.copyOfRange(640, 1000))))
        val sink = ByteArrayOutputStream()
        assertEquals(1000L, client.download("a.wav", 1000, sink))
        assertArrayEquals(data, sink.toByteArray())
        assertEquals("+XREC:GETS,0,1000,640,a.wav\r+XREC:GETS,640,360,640,a.wav\r", sent.toString())
    }

    @Test fun framesOfTheFailedWindowAfterTheNewGetsAreSkipped() {
        val data = ByteArray(3_000) { (it * 13).toByte() }
        fun at(offset: Int) = frame(offset.toLong(), data.copyOfRange(offset, minOf(offset + 640, data.size)))
        steps.add(Step.Bytes(at(0)))
        val bad = at(640).also { it[20] = (it[20] + 1).toByte() } // a CRC error
        steps.add(Step.Bytes(bad))
        steps.add(Step.Silence) // the drain ends in silence, before the rest of the old window came
        // After the new GETS: the late rest of the old window, then the new window from 640.
        steps.add(Step.Bytes(at(1280) + at(1920)))
        steps.add(Step.Bytes(at(640) + at(1280) + at(1920) + at(2560)))
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        assertEquals(3_000L, client.download("a.wav", 3_000, sink) { progress += it })
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(listOf(640L, 1280L, 1920L, 2560L, 3000L), progress)
        assertEquals("+XREC:GETS,0,3000,640,a.wav\r+XREC:GETS,640,2360,640,a.wav\r", sent.toString())
        assertEquals(1, client.lastDownload!!.retries)
    }

    @Test fun aLateOldFrameAtTheExpectedOffsetIsTakenAndItsRepeatSkipped() {
        val data = ByteArray(2_000) { (it * 5).toByte() }
        fun at(offset: Int) = frame(offset.toLong(), data.copyOfRange(offset, minOf(offset + 640, data.size)))
        steps.add(Step.Bytes(at(0)))
        steps.add(Step.Silence) // the frame at 640 is late: the frame wait times out
        steps.add(Step.Silence) // and the drain ends
        // Part of the old window comes after all (the same bytes), then the new window repeats them.
        steps.add(Step.Bytes(at(640) + at(1280) + at(640) + at(1280) + at(1920)))
        val sink = ByteArrayOutputStream()
        assertEquals(2_000L, client.download("a.wav", 2_000, sink))
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(2, sent.toString().split("GETS").size - 1)
    }

    @Test fun aGapInAHealthyWindowIsAnErrorNotASkip() {
        val data = ByteArray(2_000) { it.toByte() }
        fun at(offset: Int) = frame(offset.toLong(), data.copyOfRange(offset, minOf(offset + 640, data.size)))
        steps.add(Step.Bytes(at(0) + at(1280))) // the frame at 640 is missing
        steps.add(Step.Silence) // drain
        steps.add(Step.Bytes(at(640) + at(1280) + at(1920)))
        val sink = ByteArrayOutputStream()
        client.download("a.wav", 2_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        assertEquals("+XREC:GETS,0,2000,640,a.wav\r+XREC:GETS,640,1360,640,a.wav\r", sent.toString())
    }

    @Test fun repeatedFailuresFallBackToTheStockWindow() {
        val data = ByteArray(5_000) { (it * 3).toByte() }
        fun at(offset: Int) = frame(offset.toLong(), data.copyOfRange(offset, minOf(offset + 640, data.size)))
        repeat(2) {
            steps.add(Step.Silence) // no frame
            steps.add(Step.Silence) // drain
        }
        steps.add(Step.Bytes((0 until 3_840 step 640).map(::at).reduce(ByteArray::plus)))
        steps.add(Step.Bytes((3_840 until 5_000 step 640).map(::at).reduce(ByteArray::plus)))
        val sink = ByteArrayOutputStream()
        client.download("a.wav", 5_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(
            "+XREC:GETS,0,5000,640,a.wav\r+XREC:GETS,0,5000,640,a.wav\r" +
                "+XREC:GETS,0,3840,640,a.wav\r+XREC:GETS,3840,1160,640,a.wav\r",
            sent.toString(),
        )
        assertEquals(XrecLimits.STOCK_GETS_WINDOW, client.getsWindow)
        assertEquals(XrecDownloadStats(5_000, 2 * (700 + 50), 2, 4, 3_840), client.lastDownload)
    }

    @Test fun theDownloadSpeedIsMeasured() {
        val data = ByteArray(2_560) { it.toByte() }
        steps.add(Step.Elapse(10))
        steps.add(Step.Bytes(frame(1_280, data.copyOfRange(1_280, 1_920))))
        steps.add(Step.Elapse(10))
        steps.add(Step.Bytes(frame(1_920, data.copyOfRange(1_920, 2_560))))
        val sink = ByteArrayOutputStream().apply { write(data, 0, 1_280) }
        assertEquals(2_560L, client.download("a.wav", 2_560, sink, resumeOffset = 1_280))
        assertArrayEquals(data, sink.toByteArray())
        val stats = client.lastDownload!!
        // The resumed bytes do not count: 1280 bytes in 20 ms.
        assertEquals(XrecDownloadStats(1_280, 20, 0, 1, XrecLimits.GETS_WINDOW), stats)
        assertEquals(64_000L, stats.bytesPerSecond)
    }

    @Test fun anErrLineInsteadOfAFrameFails() {
        receive("+XREC:ERR,NOENT\r\n")
        expect(XrecFailure.NOT_FOUND) { client.download("gone.wav", 10, ByteArrayOutputStream()) }
    }

    @Test fun aDownloadWithoutProgressGivesUpAfterTheRetries() {
        expect(XrecFailure.INTEGRITY) { client.download("a.wav", 10, ByteArrayOutputStream()) }
        assertEquals(3, sent.toString().split("GETS").size - 1)
    }

    @Test fun nothingIsSentForAnEmptyOrCompleteFile() {
        assertEquals(0L, client.download("a.wav", 0, ByteArrayOutputStream()))
        assertEquals(10L, client.download("a.wav", 10, ByteArrayOutputStream(), resumeOffset = 10))
        assertEquals("", sent.toString())
    }

    @Test fun aLostAckIsResentAfterAResync() {
        val bytes = ByteArray(700) { (it * 7).toByte() }
        steps.add(Step.Silence) // no ack for chunk 0
        // The late ack and a stray error come before the PONG of the resync: all are dropped.
        receive("+XREC:OK,PUT,0,64\r\n+XREC:ERR,LINE\r\n$PONG")
        // A late duplicate ack of chunk 0 before the ack of chunk 1 is dropped too.
        receive("+XREC:OK,PUT,0,64\r\n+XREC:OK,PUT,0,64\r\n+XREC:OK,PUT,64,600\r\n+XREC:OK,PUT,664,36\r\n")
        receive("+XREC:OK,PUTEND,700\r\n")
        client.uploadSidecar("a.cwx", bytes)
        val crc0 = Crc16CcittFalse.compute(bytes, 0, 64)
        val text = sent.toString(Charsets.ISO_8859_1)
        assertEquals(2, text.split("+XREC:PUT,0,64,$crc0,REC,a.cwx:").size - 1)
        assertEquals(1, text.split("\r+XREC:PING\r").size - 1)
        assertEquals(true, text.endsWith("+XREC:PUTEND\r"))
    }

    @Test fun aResyncWithoutPongTimesOut() {
        expect(XrecFailure.TIMEOUT) { client.resync() }
        assertEquals(500L, now)
    }

    @Test fun aLostPutEndReplyIsAskedAgain() {
        receive("+XREC:OK,PUT,0,5\r\n")
        steps.add(Step.Silence) // no reply to PUTEND
        receive(PONG + "+XREC:OK,PUTEND,5\r\n")
        client.uploadSidecar("a.cwx", ByteArray(5))
        assertEquals(2, sent.toString(Charsets.ISO_8859_1).split("+XREC:PUTEND\r").size - 1)
    }

    @Test fun olderFirmwareConfirmsARepeatedPutEndWithTheListing() {
        receive("+XREC:OK,PUT,0,5\r\n")
        steps.add(Step.Silence)
        receive(PONG + "+XREC:ERR,NOPUT\r\n+XREC:F,100,a.wav\r\n+XREC:F,5,A.CWX\r\n+XREC:END\r\n")
        client.uploadSidecar("a.cwx", ByteArray(5))
        assertEquals(true, sent.toString(Charsets.ISO_8859_1).endsWith("+XREC:PUTEND\r+XREC:LS\r"))

        // Not in the listing: the commit is not confirmed.
        receive("+XREC:OK,PUT,0,5\r\n")
        steps.add(Step.Silence)
        receive(PONG + "+XREC:ERR,NOPUT\r\n+XREC:F,4,a.cwx\r\n+XREC:END\r\n")
        expect(XrecFailure.INTEGRITY) { client.uploadSidecar("a.cwx", ByteArray(5)) }
    }

    @Test fun aFirstPutEndThatFindsNoUploadIsAnError() {
        receive("+XREC:OK,PUT,0,5\r\n+XREC:ERR,NOPUT\r\n")
        expect(XrecFailure.NO_UPLOAD) { client.uploadSidecar("a.cwx", ByteArray(5)) }
    }

    @Test fun aWrongPutEndTotalIsAProtocolError() {
        receive("+XREC:OK,PUT,0,5\r\n+XREC:OK,PUTEND,4\r\n")
        expect(XrecFailure.PROTOCOL) { client.uploadSidecar("a.cwx", ByteArray(5)) }
    }

    private fun frame(offset: Long, payload: ByteArray): ByteArray {
        val crc = Crc16CcittFalse.compute(payload)
        val header = byteArrayOf(
            'X'.code.toByte(), 'R'.code.toByte(), 'E'.code.toByte(), 'C'.code.toByte(),
            offset.toByte(), (offset shr 8).toByte(), (offset shr 16).toByte(), (offset shr 24).toByte(),
            payload.size.toByte(), (payload.size shr 8).toByte(), crc.toByte(), (crc shr 8).toByte(),
        )
        return header + payload
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
