package io.codemyriad.cassini.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FlacStructureTest {
    @get:Rule val folder = TemporaryFolder()
    private fun fixture() = File("../fixtures/audio/fleurs-it-short.flac").readBytes()
    private fun validate(bytes: ByteArray): FlacStructure.Info? =
        FlacStructure.validated(folder.newFile().apply { writeBytes(bytes) })

    @Test fun completeNativeFlacAndOptionalTagPass() {
        val bytes = fixture()
        val info = requireNotNull(validate(bytes))
        assertEquals(16000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(253440L, info.totalSamples)
        assertNotNull(validate(bytes + "TAG".toByteArray() + ByteArray(125)))
        val unknownTotal = bytes.copyOf().apply {
            this[21] = (this[21].toInt() and 0xf0).toByte()
            for (i in 22..25) this[i] = 0
        }
        assertNotNull(validate(unknownTotal))
    }

    @Test fun metadataOnlyAndTruncatedFinalFramesFail() {
        val bytes = fixture()
        for (cut in listOf(0, 4, 41, 42, 8286, bytes.size / 2, 256999, bytes.size - 1)) {
            assertNull("cut $cut", validate(bytes.copyOf(cut)))
        }
        assertNull(validate(bytes.copyOf(42).apply { this[4] = 0x80.toByte() }))
        assertNull(validate(bytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }))
        // Metadata length exceeds the available file.
        assertNull(validate(bytes.copyOf().apply { this[43] = 0x7f }))
        // Invalid first frame header, while the complete final frame is retained.
        assertNull(validate(bytes.copyOf().apply { this[8286] = 0 }))
    }

    @Test fun unsupportedFormatsAndWrongTotalsFail() {
        val bytes = fixture()
        val highRate = bytes.copyOf().apply {
            this[18] = 0x2e
            this[19] = 0xe0.toByte()
        }
        assertNull(validate(highRate))
        assertNull(validate(bytes.copyOf().apply { this[20] = (this[20].toInt() or 10).toByte() }))
        assertNull(validate(bytes.copyOf().apply { this[25] = (this[25].toInt() xor 1).toByte() }))
    }

    @Test fun decodedFrameCountMustReachTheDeclaredEndEvenAfterResume() {
        val info = requireNotNull(validate(fixture()))
        assertTrue(info.decodedCompletely(0, info.totalSamples))
        assertTrue(info.decodedCompletely(16000, info.totalSamples - 16000))
        assertFalse(info.decodedCompletely(0, info.totalSamples / 2))
        assertFalse(info.decodedCompletely(16000, info.totalSamples - 16001))
        assertFalse(info.decodedCompletely(0, info.totalSamples + 1))
    }
}
