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
    private val sendTimes = mutableListOf<Long>()
    private val sent = object : ByteArrayOutputStream() {
        override fun flush() {
            sendTimes += now
        }
    }
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
    private val fallbacks = mutableListOf<Triple<String, Long, Int>>()
    private val client = XrecClient(input, sent, timeouts, clock = { now }, maxRetries = 2).apply {
        onFallback = { name, offset, window -> fallbacks += Triple(name, offset, window) }
    }

    private fun receive(text: String) = steps.add(Step.Bytes(text.toByteArray()))

    private companion object {
        const val PONG = "+XREC:PONG,openfw_v5,v5,bound=1,auth=1,pair=0\r\n"
        const val RESYNC = "\r+XREC:PING\r"
    }

    /** The end of a download that recovered: the drain ends in silence, then the PONG of the resync. */
    private fun settle() {
        steps.add(Step.Silence)
        receive(PONG)
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
        settle() // The frame at 640 may have been late: the repeat of the window can still stream.
        val sink = ByteArrayOutputStream()
        assertEquals(1000L, client.download("a.wav", 1000, sink))
        assertArrayEquals(data, sink.toByteArray())
        assertEquals("+XREC:GETS,0,1000,640,a.wav\r+XREC:GETS,640,360,640,a.wav\r$RESYNC", sent.toString())
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
        settle()
        val sink = ByteArrayOutputStream()
        val progress = mutableListOf<Long>()
        assertEquals(3_000L, client.download("a.wav", 3_000, sink) { progress += it })
        assertArrayEquals(data, sink.toByteArray())
        // Each skipped frame repeats the offset: a heartbeat for the stall watchdog.
        assertEquals(listOf(640L, 640L, 640L, 1280L, 1920L, 2560L, 3000L), progress)
        assertEquals("+XREC:GETS,0,3000,640,a.wav\r+XREC:GETS,640,2360,640,a.wav\r$RESYNC", sent.toString())
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
        settle()
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
        settle()
        val sink = ByteArrayOutputStream()
        client.download("a.wav", 2_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        assertEquals("+XREC:GETS,0,2000,640,a.wav\r+XREC:GETS,640,1360,640,a.wav\r$RESYNC", sent.toString())
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
        settle()
        val sink = ByteArrayOutputStream()
        client.download("a.wav", 5_000, sink)
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(
            "+XREC:GETS,0,5000,640,a.wav\r+XREC:GETS,0,5000,640,a.wav\r" +
                "+XREC:GETS,0,3840,640,a.wav\r+XREC:GETS,3840,1160,640,a.wav\r$RESYNC",
            sent.toString(),
        )
        assertEquals(XrecLimits.STOCK_GETS_WINDOW, client.getsWindow)
        assertEquals(listOf(Triple("a.wav", 0L, XrecLimits.STOCK_GETS_WINDOW)), fallbacks)
        // Two windows of 700 ms frame wait and 50 ms drain, then the 50 ms drain before the resync.
        assertEquals(XrecDownloadStats(5_000, 2 * (700 + 50) + 50, 2, 4, 3_840), client.lastDownload)

        // The fallback is for that file only: the next download asks for the full window again.
        sent.reset()
        steps.add(Step.Bytes(frame(0, data.copyOfRange(0, 640))))
        client.download("b.wav", 640, ByteArrayOutputStream())
        assertEquals("+XREC:GETS,0,640,640,b.wav\r", sent.toString())
        assertEquals(XrecLimits.GETS_WINDOW, client.getsWindow)
    }

    @Test fun lateOldFramesThatCompleteTheFileLeaveNoBytesForTheNextCommand() {
        // Silent PCM: the repeat of the window holds far more than the 4096-byte reply buffer without a line end.
        val data = ByteArray(12_800)
        fun at(offset: Int) = frame(offset.toLong(), data.copyOfRange(offset, minOf(offset + 640, data.size)))
        fun frames(from: Int) = (from until data.size step 640).map(::at).reduce(ByteArray::plus)
        steps.add(Step.Bytes(at(0)))
        steps.add(Step.Silence) // the frame at 640 is late: the frame wait times out
        steps.add(Step.Silence) // and the drain ends
        // The late old frames complete the file, and the repeat of the new window streams after them.
        steps.add(Step.Bytes(frames(640)))
        steps.add(Step.Bytes(frames(640).copyOfRange(0, 5_000)))
        steps.add(Step.Silence) // a pause in the repeat ends the drain
        steps.add(Step.Bytes(frames(640).copyOfRange(5_000, 12_000))) // its rest comes before the PONG
        receive(PONG)
        val sink = ByteArrayOutputStream()
        assertEquals(12_800L, client.download("a.wav", 12_800, sink))
        assertArrayEquals(data, sink.toByteArray())
        assertEquals(true, sent.toString().endsWith(RESYNC))

        // The next command and the next download see only their own bytes.
        receive("+XREC:STAT,87,3990,0,0,33,4,1,2\r\n")
        assertEquals(true, client.stat().recording)
        val other = ByteArray(640) { 7 }
        steps.add(Step.Bytes(frame(0, other)))
        val next = ByteArrayOutputStream()
        client.download("b.wav", 640, next)
        assertArrayEquals(other, next.toByteArray())
    }

    @Test fun theDrainOfALargeWindowGoesOnWhileBytesFlowUpToItsLimit() {
        val size = XrecLimits.GETS_WINDOW
        val data = ByteArray(size) { (it * 11).toByte() }
        fun at(offset: Int) = frame(offset.toLong(), data.copyOfRange(offset, minOf(offset + 640, size)))
        steps.add(Step.Bytes(at(0)))
        steps.add(Step.Silence) // the frame wait times out at 700 ms
        // A slow link: the rest of the old window keeps coming, a frame a second, for longer than the drain may wait.
        repeat(12) {
            steps.add(Step.Elapse(1_000))
            steps.add(Step.Bytes(at(0)))
        }
        // The new window after the old frames that the drain left: they lie before the offset and are skipped.
        steps.add(Step.Bytes((640 until size step 640).map(::at).reduce(ByteArray::plus)))
        settle()
        val progress = mutableListOf<Long>()
        val sink = ByteArrayOutputStream()
        client.download("a.wav", size.toLong(), sink) { progress += it }
        assertArrayEquals(data, sink.toByteArray())
        // The limit is drainMillis + 64 KiB at 8 KiB/s = 8500 ms after 700: the read that ends at 9700 ms is the last.
        val limit = timeouts.drainMillis + size * 1_000L / XrecClient.DRAIN_BYTES_PER_SECOND
        assertEquals(8_500L, limit)
        assertEquals(listOf(0L, 9_700L), sendTimes.take(2))
        // Each drained read and each skipped frame is a heartbeat at 640.
        assertEquals(1 + 9 + 3, progress.count { it == 640L })
    }

    @Test fun oldFramesBeyondTheEndOfTheBudgetFailTheWindow() {
        val data = ByteArray(1_280) { (it * 3).toByte() }
        val old = frame(0, data.copyOfRange(0, 640))
        val budget = (XrecClient.STALE_BUDGET / old.size).toInt()
        steps.add(Step.Bytes(old))
        steps.add(Step.Silence) // the frame at 640 never comes
        steps.add(Step.Silence) // drain
        repeat(2) {
            // More old frames than one window may skip: the window fails on the first one past the budget.
            steps.add(Step.Bytes(ByteArray(0).let { (0..budget).fold(it) { all, _ -> all + old } }))
            steps.add(Step.Silence) // drain
        }
        try {
            client.download("a.wav", 1_280, ByteArrayOutputStream())
            fail("Expected INTEGRITY")
        } catch (e: XrecException) {
            assertEquals(XrecFailure.INTEGRITY, e.failure)
            assertEquals("Frame at 0, expected 640", e.cause?.message)
        }
        assertEquals(3, sent.toString().split("GETS").size - 1)
    }

    @Test fun oldFramesThatCarryAFallbackWindowToItsEndDoNotEndTheRecovery() {
        val size = 70_000
        val data = ByteArray(size) { (it * 7 + it / 251).toByte() }

        // The frames the device sends for one GETS: 640 bytes each, the last one short at the window's end.
        fun window(from: Int, total: Int) = (from until from + total step 640)
            .map { frame(it.toLong(), data.copyOfRange(it, minOf(it + 640, from + total))) }
            .reduce(ByteArray::plus)
        val big = XrecLimits.GETS_WINDOW
        val small = XrecLimits.STOCK_GETS_WINDOW
        repeat(2) {
            steps.add(Step.Silence) // no frame in time
            steps.add(Step.Silence) // drain
        }
        // Both failed 64 KiB windows stream after all, then each fallback window the client asks for. The late frames
        // of the first fill the fallback windows up to 65536, past the ends the windows asked for; the second and the
        // repeats follow, behind the offset, and the short last frame of the 64 KiB window leaves the fallback window
        // at 65280 to continue from the middle of a frame.
        var stream = window(0, big) + window(0, big)
        for (from in 0 until size step small) stream += window(from, minOf(small, size - from))
        steps.add(Step.Bytes(stream))
        val sink = ByteArrayOutputStream()
        client.download("a.wav", size.toLong(), sink)
        assertArrayEquals(data, sink.toByteArray())
        val stats = client.lastDownload!!
        assertEquals(2, stats.retries)
        assertEquals(2 + (size + small - 1) / small, stats.requests)
        // The frames of the last windows came after every old frame: the recovery ended, no resync was needed.
        assertEquals(false, sent.toString().endsWith(RESYNC))
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
