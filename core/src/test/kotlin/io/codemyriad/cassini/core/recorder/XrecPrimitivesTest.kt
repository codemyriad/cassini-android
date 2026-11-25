package io.codemyriad.cassini.core.recorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

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
}
