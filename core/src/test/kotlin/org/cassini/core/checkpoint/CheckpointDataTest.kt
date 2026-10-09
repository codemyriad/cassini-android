package org.cassini.core.checkpoint

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The binary encodings inside checkpoint.json. Changing any of them makes every saved checkpoint unreadable. */
class CheckpointDataTest {
    @Test fun bytesAreStandardBase64WithPadding() {
        assertEquals("AAEC/w==", CheckpointData.bytes(byteArrayOf(0, 1, 2, -1)))
        assertArrayEquals(byteArrayOf(0, 1, 2, -1), CheckpointData.bytes("AAEC/w=="))
        assertEquals("", CheckpointData.bytes(ByteArray(0)))
    }

    @Test fun floatsAreLittleEndianIeee754() {
        // 1.0f is 0x3F800000: bytes 00 00 80 3F.
        assertEquals("AACAPw==", CheckpointData.floats(floatArrayOf(1f)))
        assertArrayEquals(floatArrayOf(1f), CheckpointData.floats("AACAPw==", 1), 0f)
    }

    @Test fun floatsRoundTripBitForBit() {
        val values = floatArrayOf(-0f, Float.MIN_VALUE, Float.MAX_VALUE, -1.5f, Float.NaN, 0.1f)
        val decoded = CheckpointData.floats(CheckpointData.floats(values), values.size)
        assertArrayEquals(values.map { it.toRawBits() }.toIntArray(), decoded.map { it.toRawBits() }.toIntArray())
    }

    @Test fun floatsRejectPartialValuesAndMoreThanTheMaximum() {
        val partial = CheckpointData.bytes(ByteArray(6))
        assertThrows(IllegalArgumentException::class.java) { CheckpointData.floats(partial, 10) }
        val three = CheckpointData.floats(FloatArray(3))
        assertThrows(IllegalArgumentException::class.java) { CheckpointData.floats(three, 2) }
        assertEquals(2, CheckpointData.floats(CheckpointData.floats(FloatArray(2)), 2).size)
    }

    @Test fun bytesRejectTextLongerThanFourMebibytes() {
        val limit = 4 * 1024 * 1024
        assertEquals(limit / 4 * 3, CheckpointData.bytes("A".repeat(limit)).size)
        assertThrows(IllegalArgumentException::class.java) { CheckpointData.bytes("A".repeat(limit + 4)) }
    }

    @Test fun stringsReadEveryMember() {
        val json = JSONObject().put("diar_x_1", "Ada").put("diar_x_2", "")
        assertEquals(mapOf("diar_x_1" to "Ada", "diar_x_2" to ""), CheckpointData.strings(json))
        assertEquals(emptyMap<String, String>(), CheckpointData.strings(JSONObject()))
    }
}
