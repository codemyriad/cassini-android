// The numbers are the byte layout of the MPEG audio frame header, the Xing/Info tag and the LAME extension.
@file:Suppress("MagicNumber")

package org.cassini.core.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * The gapless information of an MP3 file: the LAME extension of the Xing or Info tag in the first frame. An MP3
 * decoder outputs [frames] x [samplesPerFrame] samples. The encoder put [delay] samples before the audio and [padding]
 * samples after it, so the audio is [samples] long. LAME, and FFmpeg (`Lavf`, `Lavc`), write the extension.
 *
 * The values count the samples of a decoder that already removed its own 529-sample delay, as the Android
 * decoders do (`SoftMP3`, `c2.android.mp3.decoder`): the audio starts [delay] samples into that output.
 */
data class Mp3Gapless(
    val sampleRate: Int,
    val samplesPerFrame: Int,
    val frames: Long,
    val delay: Int,
    val padding: Int,
) {
    /** The audio without the encoder delay and padding, in samples at [sampleRate]. */
    val samples: Long get() = frames * samplesPerFrame - delay - padding

    companion object {
        private const val ID3_HEADER = 10
        private const val SEARCH_BYTES = 4096
        private val tags = listOf("Xing", "Info")
        private val encoders = listOf("LAME", "Lavf", "Lavc")

        /** The gapless information of [file], or null when it is not an MP3 file with a LAME extension. */
        fun scan(file: File): Mp3Gapless? = try {
            RandomAccessFile(file, "r").use { input ->
                val start = audioStart(input)
                if (start >= input.length()) return@use null
                val head = ByteArray(minOf(SEARCH_BYTES.toLong(), input.length() - start).toInt())
                input.seek(start)
                input.readFully(head)
                parse(head)
            }
        } catch (_: IOException) {
            null
        }

        /** The offset after the ID3v2 tag (and its footer) at the start of [input], or 0 when there is none. */
        private fun audioStart(input: RandomAccessFile): Long {
            val id3 = ByteArray(minOf(input.length(), ID3_HEADER.toLong()).toInt()).also(input::readFully)
            if (id3.size < ID3_HEADER || String(id3, 0, 3, Charsets.ISO_8859_1) != "ID3") return 0
            // The tag size is synchsafe: 7 bits in each of 4 bytes. It counts neither the header nor the footer.
            val size = (6 until 10).fold(0L) { sum, i -> (sum shl 7) or (id3[i].toLong() and 0x7f) }
            val footer = if (id3[5].toInt() and 0x10 != 0) ID3_HEADER else 0
            return ID3_HEADER + size + footer
        }

        /**
         * Reads the tag from [bytes], which start at or a little before the first frame header (after any ID3v2
         * tag). Gives null when the first frame is not MPEG audio Layer III, has no Xing or Info tag with a frame
         * count, or has no LAME extension.
         */
        fun parse(bytes: ByteArray): Mp3Gapless? {
            val frame = (0..bytes.size - 4).firstNotNullOfOrNull { header(bytes, it) }
            val xing = frame?.let { xing(bytes, it.tag) }
            val lame = xing?.let { lame(bytes, it.second) }
            if (frame == null || xing == null || lame == null) return null
            return Mp3Gapless(frame.sampleRate, frame.samplesPerFrame, xing.first, lame.first, lame.second)
                .takeIf { it.samples > 0 }
        }

        /** A Layer III frame header: [tag] is where a Xing or Info tag starts, after the side information. */
        private class Header(val sampleRate: Int, val samplesPerFrame: Int, val tag: Int)

        /** The Layer III frame header at [at], or null when there is none. */
        private fun header(bytes: ByteArray, at: Int): Header? {
            val b1 = bytes[at + 1].toInt() and 0xff
            val b2 = bytes[at + 2].toInt() and 0xff
            val version = (b1 shr 3) and 3 // 3: MPEG-1, 2: MPEG-2, 0: MPEG-2.5, 1: reserved
            val rateIndex = (b2 shr 2) and 3
            val sync = bytes[at].toInt() and 0xff == 0xff && b1 and 0xe0 == 0xe0
            val layer3 = (b1 shr 1) and 3 == 1 && version != 1
            val rates = b2 shr 4 in 1..14 && rateIndex != 3
            if (!(sync && layer3 && rates)) return null
            val mpeg1 = version == 3
            val mono = (bytes[at + 3].toInt() and 0xff) shr 6 == 3
            val sideInfo = when {
                mpeg1 -> if (mono) 17 else 32
                else -> if (mono) 9 else 17
            }
            val sampleRate = intArrayOf(44100, 48000, 32000)[rateIndex] shr (3 - version).coerceAtMost(2)
            return Header(sampleRate, if (mpeg1) 1152 else 576, at + 4 + sideInfo)
        }

        /** The frame count of the Xing or Info tag at [at] and the offset after its fields, or null. */
        private fun xing(bytes: ByteArray, at: Int): Pair<Long, Int>? {
            val tagged = at + 12 <= bytes.size && String(bytes, at, 4, Charsets.ISO_8859_1) in tags
            val flags = if (tagged) u32(bytes, at + 4) else 0L
            if (flags and 1L == 0L) return null
            val bytesField = if (flags and 2L != 0L) 4 else 0
            val toc = if (flags and 4L != 0L) 100 else 0
            val quality = if (flags and 8L != 0L) 4 else 0
            return u32(bytes, at + 8) to at + 12 + bytesField + toc + quality
        }

        /**
         * The encoder delay and padding of the LAME extension at [at]: the encoder (9 bytes), 12 bytes of other
         * fields, then the two 12-bit values in 3 bytes. Null when no known encoder wrote it.
         */
        private fun lame(bytes: ByteArray, at: Int): Pair<Int, Int>? {
            if (at + 24 > bytes.size || String(bytes, at, 4, Charsets.ISO_8859_1) !in encoders) return null
            val b0 = bytes[at + 21].toInt() and 0xff
            val b1 = bytes[at + 22].toInt() and 0xff
            val b2 = bytes[at + 23].toInt() and 0xff
            return ((b0 shl 4) or (b1 shr 4)) to (((b1 and 0x0f) shl 8) or b2)
        }

        private fun u32(bytes: ByteArray, at: Int): Long =
            (0 until 4).fold(0L) { sum, i -> (sum shl 8) or (bytes[at + i].toLong() and 0xff) }
    }
}
