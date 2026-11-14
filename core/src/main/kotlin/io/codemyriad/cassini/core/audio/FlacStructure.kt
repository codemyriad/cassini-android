package io.codemyriad.cassini.core.audio

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Bounded native FLAC checks, using RFC 9639 STREAMINFO and frame headers/CRCs. */
// Binary format fields and reserved codes.
@Suppress(
    "MagicNumber",
    "ReturnCount",
    "ComplexMethod",
    "ComplexCondition",
    "TooManyFunctions",
)
object FlacStructure {
    data class Info(
        val sampleRate: Int,
        val channels: Int,
        val totalSamples: Long,
        val maxBlock: Int,
        val maxFrame: Int,
    ) {
        val durationMs: Long get() = totalSamples * 1000 / sampleRate

        /** Count actual PCM frames, before resampling; timestamps cannot stand in for missing samples. */
        fun decodedCompletely(firstFrame: Long, decodedFrames: Long): Boolean =
            totalSamples == 0L || firstFrame >= 0 && firstFrame + decodedFrames == totalSamples
    }

    /** Null for a non-native stream. Invalid native streams throw before any decoder output can be sealed. */
    fun read(file: File): Info? = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 4 || input.readInt() != 0x664c6143) return@use null
        val head = ByteArray(42)
        input.seek(0)
        input.readFully(head)
        require(ImportTypes.flacHeaderValidated(head))
        val packed = unsigned(head, 18, 8)
        Info(
            (packed ushr 44).toInt(),
            ((packed ushr 41) and 7).toInt() + 1,
            packed and 0xfffffffffL,
            unsigned(head, 10, 2).toInt(),
            unsigned(head, 15, 3).toInt(),
        ).also { info ->
            require(info.sampleRate in 8000..96000 && info.channels in 1..2 && info.maxBlock in 16..65535)
            val audio = audioOffset(input)
            val first = ByteArray(minOf(32L, input.length() - audio).toInt())
            input.seek(audio)
            input.readFully(first)
            val initial = requireNotNull(frame(first, 0, info))
            require(initial.number == 0L)
            checkTail(input, audio, info, initial.variable)
        }
    }

    /** Import rejection helper; decoder callers retain the exception to fail the operation. */
    fun validated(file: File): Info? = try {
        read(file)
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun audioOffset(input: RandomAccessFile): Long {
        input.seek(4)
        do {
            val tag = input.readUnsignedByte()
            val size = (input.readUnsignedByte() shl 16) or (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
            require(tag and 0x7f != 127 && input.filePointer + size <= input.length())
            input.seek(input.filePointer + size)
        } while (tag and 0x80 == 0)
        require(input.filePointer < input.length())
        return input.filePointer
    }

    private fun checkTail(input: RandomAccessFile, audio: Long, info: Info, variable: Boolean) {
        var end = input.length()
        if (end - audio >= 128) {
            input.seek(end - 128)
            if (input.readUnsignedByte() == 84 && input.readUnsignedByte() == 65 && input.readUnsignedByte() == 71) end -= 128
        }
        val window = maxOf(info.maxFrame, info.maxBlock * info.channels * 4 + 64, 65536)
        val start = maxOf(audio, end - window)
        val tail = ByteArray((end - start).toInt())
        input.seek(start)
        input.readFully(tail)
        // Reverse the CRC recurrence once. Each candidate then costs only a bounded header parse,
        // even for a hostile tail full of sync bytes (no quadratic per-candidate whole-frame CRC).
        var crc = 0
        var found = false
        for (offset in tail.lastIndex downTo 0) {
            repeat(8) { crc = if (crc and 1 == 0) crc ushr 1 else ((crc xor 0x8005) ushr 1) or 0x8000 }
            crc = crc xor (u8(tail, offset) shl 8)
            val last = if (crc == 0 && tail.size - offset >= 8) frame(tail, offset, info) else null
            if (last != null) {
                val sample = if (last.variable) last.number else last.number * info.maxBlock
                if (last.variable == variable && (info.totalSamples == 0L || sample + last.block == info.totalSamples)) found = true
            }
        }
        require(found) { "Incomplete FLAC final frame" }
    }

    private data class Frame(val number: Long, val block: Int, val variable: Boolean)

    private fun frame(bytes: ByteArray, offset: Int, info: Info): Frame? {
        if (offset + 6 > bytes.size || u8(bytes, offset) != 255 || u8(bytes, offset + 1) and 0xfe != 0xf8) return null
        val blockCode = u8(bytes, offset + 2) ushr 4
        val rateCode = u8(bytes, offset + 2) and 15
        val channelCode = u8(bytes, offset + 3) ushr 4
        val depthCode = (u8(bytes, offset + 3) ushr 1) and 7
        if (blockCode == 0 || rateCode == 15 || channelCode > 10 || depthCode == 3 || u8(bytes, offset + 3) and 1 != 0) return null
        if ((if (channelCode < 8) channelCode + 1 else 2) != info.channels) return null
        val cursor = Cursor(bytes, offset + 4)
        val number = cursor.number() ?: return null
        val variable = u8(bytes, offset + 1) and 1 != 0
        if (!variable && number > 0x7fffffffL) return null
        val block = when (blockCode) {
            1 -> 192
            in 2..5 -> 576 shl (blockCode - 2)
            6 -> cursor.take() + 1
            7 -> cursor.word() + 1
            else -> 256 shl (blockCode - 8)
        }
        val rate = when (rateCode) {
            0 -> info.sampleRate
            12 -> cursor.take() * 1000
            13 -> cursor.word()
            14 -> cursor.word() * 10
            else -> intArrayOf(0, 88200, 176400, 192000, 8000, 16000, 22050, 24000, 32000, 44100, 48000, 96000)[rateCode]
        }
        if (block !in 1..info.maxBlock || rate != info.sampleRate || cursor.position >= bytes.size || cursor.invalid) return null
        var crc = 0
        for (at in offset until cursor.position) {
            crc = crc xor u8(bytes, at)
            repeat(8) { crc = ((crc shl 1) xor (if (crc and 128 != 0) 7 else 0)) and 255 }
        }
        return if (crc == cursor.take()) Frame(number, block, variable) else null
    }

    private class Cursor(val bytes: ByteArray, var position: Int) {
        var invalid = false
        fun take(): Int = if (position < bytes.size) u8(bytes, position++) else 0.also { invalid = true }
        fun word(): Int = (take() shl 8) or take()
        fun number(): Long? {
            val first = take()
            if (first < 128) return first.toLong()
            val length = Integer.numberOfLeadingZeros(first.inv() and 255) - 24
            if (length !in 2..7) return null
            var value = (first and (127 shr length)).toLong()
            repeat(length - 1) {
                val next = take()
                if (next and 192 != 128) return null
                value = (value shl 6) or (next and 63).toLong()
            }
            val minimum = longArrayOf(0, 0, 128, 2048, 65536, 2097152, 67108864, 2147483648)[length]
            return value.takeIf { !invalid && it >= minimum && it <= 0xfffffffffL }
        }
    }

    private fun u8(bytes: ByteArray, at: Int): Int = bytes[at].toInt() and 255
    private fun unsigned(bytes: ByteArray, at: Int, count: Int): Long {
        var value = 0L
        repeat(count) { value = (value shl 8) or u8(bytes, at + it).toLong() }
        return value
    }
}
