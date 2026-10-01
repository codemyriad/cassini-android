package org.cassini.android

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    val durationMs: Long get() = samples.size * 1000L / sampleRate
}

/** Bounded, whole-utterance import. Reject long clips rather than silently dropping audio. */
object AudioDecoder {
    const val MAX_SECONDS = 30
    fun decode(context: Context, uri: Uri): PcmAudio {
        val local = File.createTempFile("audio-", ".input", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Cannot open recording." }
                local.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        check(!Thread.currentThread().isInterrupted) { "Cancelled" }
                        val n = input.read(buffer)
                        if (n < 0) break
                        size += n
                        require(size <= 64 * 1024 * 1024) { "Prototype import limit is 64 MB." }
                        output.write(buffer, 0, n)
                    }
                }
            }
            RandomAccessFile(local, "r").use { file ->
                val header = ByteArray(12)
                if (file.length() >= 12) {
                    file.readFully(header)
                    if (String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(header, 8, 4, Charsets.US_ASCII) == "WAVE") return decodeWav(file)
                }
            }
            return decodeCompressed(local)
        } finally {
            local.delete()
        }
    }

    private fun decodeWav(file: RandomAccessFile): PcmAudio {
        var sampleRate = 0
        var channels = 0
        var dataOffset = -1L
        var dataSize = 0L
        while (file.filePointer + 8 <= file.length()) {
            val id = ByteArray(4).also(file::readFully).toString(Charsets.US_ASCII)
            val size = Integer.reverseBytes(file.readInt()).toLong() and 0xffffffffL
            val offset = file.filePointer
            require(offset + size <= file.length()) { "Truncated WAV chunk." }
            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV format." }
                    val format = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
                    channels = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
                    sampleRate = Integer.reverseBytes(file.readInt())
                    file.skipBytes(6)
                    val bits = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
                    require(format == 1 && bits == 16) { "Use a 16-bit PCM WAV, or MP3/M4A/FLAC/Opus." }
                }
                "data" -> { dataOffset = offset; dataSize = size }
            }
            file.seek(offset + size + (size and 1))
        }
        require(sampleRate in 8000..96000 && channels in 1..2 && dataOffset >= 0) { "Invalid WAV audio." }
        require(dataSize % (2 * channels) == 0L) { "Partial WAV sample." }
        val frames = dataSize / (2 * channels)
        require(frames in 1..(sampleRate.toLong() * MAX_SECONDS)) { "Choose a clip of 30 seconds or less." }
        file.seek(dataOffset)
        val pcm = ByteArray(dataSize.toInt()).also(file::readFully)
        val samples = FloatArray(frames.toInt())
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in samples.indices) {
            var sum = 0f
            repeat(channels) { sum += buffer.short / 32768f }
            samples[i] = sum / channels
        }
        return PcmAudio(samples, sampleRate)
    }

    private fun decodeCompressed(file: File): PcmAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No supported audio track in recording.")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                require(format.getLong(MediaFormat.KEY_DURATION) <= MAX_SECONDS * 1_000_000L) {
                    "Choose a clip of 30 seconds or less."
                }
            }
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var pcm: FloatArray? = null
            var count = 0
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            var lastProgress = System.nanoTime()
            while (!outputEnded) {
                check(!Thread.currentThread().isInterrupted) { "Cancelled" }
                check(System.nanoTime() - lastProgress < 30_000_000_000L) { "Audio decoder stalled." }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                        lastProgress = System.nanoTime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val output = codec.outputFormat
                        val nextRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        require(count == 0 || nextRate == sampleRate) { "Changing sample rates are unsupported." }
                        sampleRate = nextRate
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    else -> if (index >= 0) {
                        try {
                            require(sampleRate in 8000..96000 && channels in 1..2) { "Only mono/stereo audio is supported." }
                            require(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                "Unsupported decoded PCM format."
                            }
                            if (info.size > 0) {
                                val destination = pcm ?: FloatArray(sampleRate * MAX_SECONDS).also { pcm = it }
                                val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                require(info.size % (sampleBytes * channels) == 0) { "Partial decoded PCM frame." }
                                val frames = info.size / (sampleBytes * channels)
                                // Keep real container gaps as silence. The player and words share a zero origin.
                                require(info.presentationTimeUs <= MAX_SECONDS * 1_000_000L) {
                                    "Choose a clip of 30 seconds or less."
                                }
                                val firstFrame = (info.presentationTimeUs.coerceAtLeast(0) * sampleRate / 1_000_000).toInt()
                                require(firstFrame.toLong() + frames <= destination.size) {
                                    "Choose a clip of 30 seconds or less."
                                }
                                repeat(frames) { frame ->
                                    var sum = 0f
                                    repeat(channels) {
                                        val sample = if (sampleBytes == 4) buffer.float else buffer.short / 32768f
                                        require(sample.isFinite()) { "Invalid audio sample." }
                                        sum += sample
                                    }
                                    destination[firstFrame + frame] = sum / channels
                                }
                                count = maxOf(count, firstFrame + frames)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = System.nanoTime()
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            require(count > 0) { "Recording contains no audio samples." }
            return PcmAudio(requireNotNull(pcm).copyOf(count), sampleRate)
        } finally {
            try { if (started) decoder?.stop() } finally {
                try { decoder?.release() } finally { extractor.release() }
            }
        }
    }
}
