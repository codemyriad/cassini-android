package io.codemyriad.cassini.core.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class XrecPrimitivesTest {
    @Test fun crcMatchesTheSpecVectors() {
        assertEquals(0x29B1, Crc16CcittFalse.compute("123456789".toByteArray()))
        assertEquals(0xFFFF, Crc16CcittFalse.compute(ByteArray(0)))
    }

    @Test fun crcCoversOnlyTheGivenRange() {
        val padded = "xx123456789yy".toByteArray()
        assertEquals(0x29B1, Crc16CcittFalse.compute(padded, 2, 9))
    }

    @Test fun authRespondsWithTheHmacOfTheNonceBytes() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = XrecAuth.toHex(ByteArray(16) { (0xA0 + it).toByte() })
        assertEquals("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", nonce)
        assertEquals(
            "b1489727b2602a0e047cb237580a4c795aaf9023c5bc6b0b865c7fa17e0a2367",
            XrecAuth.response(key, nonce),
        )
        assertEquals(XrecAuth.response(key, nonce), XrecAuth.response(key, nonce.uppercase()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun authRejectsAShortNonce() {
        XrecAuth.response(ByteArray(32), "a0a1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun authRejectsAShortKey() {
        XrecAuth.response(ByteArray(16), "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf")
    }

    @Test fun hexRoundTrips() {
        val bytes = byteArrayOf(0, 1, 0x7F, -1, -128)
        assertEquals("00017fff80", XrecAuth.toHex(bytes))
        assertArrayEquals(bytes, XrecAuth.fromHex("00017FFF80"))
    }

    @Test fun newKeysHave32RandomBytes() {
        val random = SecureRandom()
        val first = XrecAuth.newKey(random)
        assertEquals(32, first.size)
        assertEquals(false, first.contentEquals(XrecAuth.newKey(random)))
    }

    @Test fun closingAFullStreamXrecInputEndsItsPumpThread() {
        val readOnce = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stream = object : InputStream() {
            private var sent = false

            override fun read(): Int = error("chunk reads only")

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (!sent) {
                    sent = true
                    val n = minOf(len, 128)
                    repeat(n) { b[off + it] = it.toByte() }
                    readOnce.countDown()
                    return n
                }
                closed.await(5, TimeUnit.SECONDS)
                return -1
            }

            override fun close() {
                closed.countDown()
            }
        }
        val threadName = "xrec-full-ring-test"
        val input = StreamXrecInput(stream, capacity = 64, threadName = threadName)
        assertTrue(readOnce.await(2, TimeUnit.SECONDS))
        val pump = Thread.getAllStackTraces().keys.first { it.name == threadName }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (pump.state != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5)
        }
        input.close()
        pump.join(1_000)
        assertFalse("Pump thread leaked in state ${pump.state}", pump.isAlive)
    }

    @Test fun closingStreamXrecInputWakesABlockedReaderAtOnce() {
        val closed = CountDownLatch(1)
        val stream = object : InputStream() {
            override fun read(): Int = error("chunk reads only")

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                // Simulates a stream whose close() does not unblock read() right away.
                Thread.sleep(2_000)
                return -1
            }

            override fun close() {
                closed.countDown()
            }
        }
        val input = StreamXrecInput(stream, capacity = 64, threadName = "xrec-close-wake-test")
        Thread {
            Thread.sleep(50)
            input.close()
        }.apply { isDaemon = true }.start()
        val started = System.nanoTime()
        assertEquals(-1, input.read(ByteArray(16), 0, 16, timeoutMillis = 5_000))
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        assertTrue("Reader waited $elapsedMillis ms after close()", elapsedMillis < 1_000)
        assertTrue(closed.await(1, TimeUnit.SECONDS))
    }
}
