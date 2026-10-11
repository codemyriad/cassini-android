package org.cassini.core.audio

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp3GaplessTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun theFleursFixtureHasTheLengthOfItsWav() {
        // The first 201 bytes of fixtures/audio/fleurs-it-short.mp3: an ID3v2 tag (TSSE Lavf62.3.100), then the
        // MPEG-2 Layer III mono frame with the Info tag and the Lavc62.11 extension (delay 576, padding 576).
        val file = folder.newFile("fleurs.mp3").apply { writeBytes(hex(FLEURS_HEAD)) }
        val gapless = Mp3Gapless.scan(file)
        assertEquals(Mp3Gapless(16000, 576, 442, 576, 576), gapless)
        // fleurs-it-short.wav: 253440 samples at 16 kHz (15840 ms).
        assertEquals(253_440L, gapless!!.samples)
    }

    @Test fun anMpeg1StereoLameTagWithAllFields() {
        val gapless = Mp3Gapless.parse(Frame(frames = 1000).bytes())
        assertEquals(Mp3Gapless(44100, 1152, 1000, 576, 1260), gapless)
        assertEquals(1000L * 1152 - 576 - 1260, gapless!!.samples)
    }

    @Test fun onlyTheFrameCountFlagMovesTheExtension() {
        val frame = Frame(mpeg1 = false, mono = true, magic = "Info", flags = 1, encoder = "Lavf58.76")
        assertEquals(Mp3Gapless(22050, 576, 10, 576, 1260), Mp3Gapless.parse(frame.bytes()))
    }

    @Test fun anIdTagWithItsFooterIsSkipped() {
        val frame = Frame(mono = true, magic = "Info", frames = 50, encoder = "LAME3.99r").bytes()
        // ID3v2.4 with the footer flag: 10 header bytes, 20 tag bytes (synchsafe size), 10 footer bytes.
        val id3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0x10, 0, 0, 0, 20) + ByteArray(20 + 10)
        val file = folder.newFile("footer.mp3").apply { writeBytes(id3 + frame) }
        assertEquals(Mp3Gapless(44100, 1152, 50, 576, 1260), Mp3Gapless.scan(file))
    }

    @Test fun filesWithoutTheExtensionGiveNull() {
        assertNull(Mp3Gapless.parse(Frame(encoder = "Unknown00").bytes()))
        assertNull(Mp3Gapless.parse(Frame(flags = 14).bytes())) // no frame count
        assertNull(Mp3Gapless.parse(Frame(magic = "VBRI").bytes()))
        // More delay and padding than audio.
        assertNull(Mp3Gapless.parse(Frame(frames = 1).bytes()))
        assertNull(Mp3Gapless.parse(ByteArray(300)))
        assertNull(Mp3Gapless.scan(folder.newFile("empty.mp3")))
        val wav = folder.newFile("audio.wav").apply { writeBytes("RIFF".toByteArray() + ByteArray(400)) }
        assertNull(Mp3Gapless.scan(wav))
    }

    @Test fun aLayerTwoFrameIsNotMp3() {
        val bytes = Frame().bytes()
        bytes[1] = (bytes[1].toInt() and 0xf9 or 0x04).toByte() // layer bits 10: Layer II
        assertNull(Mp3Gapless.parse(bytes))
    }

    /** A first frame (44.1 kHz for MPEG-1, 22.05 kHz for MPEG-2) with a tag; the extension has delay 576, padding 1260. */
    private data class Frame(
        val mpeg1: Boolean = true,
        val mono: Boolean = false,
        val magic: String = "Xing",
        val flags: Int = 15,
        val frames: Int = 10,
        val encoder: String = "LAME3.100",
    ) {
        fun bytes(): ByteArray {
            val version = if (mpeg1) 0xfb else 0xf3
            val header = byteArrayOf(0xff.toByte(), version.toByte(), 0x90.toByte(), if (mono) 0xc0.toByte() else 0x40)
            val side = ByteArray(if (mpeg1) (if (mono) 17 else 32) else (if (mono) 9 else 17))
            val fields = buildList {
                add(int32(frames))
                if (flags and 2 != 0) add(int32(12345))
                if (flags and 4 != 0) add(ByteArray(100))
                if (flags and 8 != 0) add(int32(50))
            }.fold(ByteArray(0)) { all, part -> all + part }
            val extension = encoder.toByteArray(Charsets.ISO_8859_1) + ByteArray(12) + byteArrayOf(0x24, 0x04, 0xec.toByte())
            return header + side + magic.toByteArray(Charsets.ISO_8859_1) + int32(flags) + fields + extension + ByteArray(64)
        }

        private fun int32(value: Int) =
            byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
    }

    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val FLEURS_HEAD =
            "49443304000000000022545353450000000e0000034c61766636322e332e3130300000000000000000000000fff3a8c0" +
                "000000000000000000496e666f0000000f000001ba0002eb90000406080a0e101315171b1d1f2224272a2c2e31343639" +
                "3b3e414345484b4e505254585a5d5f616567696c6f717476787c7e808385888b8d8f9295979a9ca0a2a4a7a9acafb1b3" +
                "b6b9bbbec0c2c6c8cacdd0d2d5d7d9dddfe1e4e6eaeceef0f3f6f9fbfd000000004c61766336322e3131000000000000" +
                "000000000000240240"
    }
}
