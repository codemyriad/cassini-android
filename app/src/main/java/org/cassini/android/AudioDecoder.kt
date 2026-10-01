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
    const val MAX_SECONDS = 180
    fun decode(context: Context, uri: Uri): PcmAudio {
        val local = File.createTempFile("audio-", ".input", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                val source = input ?: throw UserFacingException(Failure.OPEN)
                local.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                        val n = source.read(buffer)
                        if (n < 0) break
                        size += n
                        requireUser(size <= 64 * 1024 * 1024, Failure.LARGE)
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

    /**
     * Codec timestamps are whole microseconds, so a buffer that continues the previous one can
     * name a time just short of it: 48 kHz AAC frames last 21333.3 µs. Only a gap or overlap
     * beyond a millisecond moves the write position away from [next].
     */
    internal fun framePosition(presentationTimeUs: Long, sampleRate: Int, next: Int): Int {
        val nominal = (presentationTimeUs.coerceAtLeast(0) * sampleRate + 500_000) / 1_000_000
        return if (kotlin.math.abs(nominal - next) <= sampleRate / 1000) next else nominal.toInt()
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
            requireUser(offset + size <= file.length(), Failure.AUDIO)
            when (id) {
                "fmt " -> {
                    requireUser(size >= 16, Failure.AUDIO)
                    val format = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
                    channels = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
                    sampleRate = Integer.reverseBytes(file.readInt())
                    file.skipBytes(6)
                    val bits = java.lang.Short.reverseBytes(file.readShort()).toInt() and 0xffff
                    requireUser(format == 1 && bits == 16, Failure.WAV)
                }
                "data" -> { dataOffset = offset; dataSize = size }
            }
            file.seek(offset + size + (size and 1))
        }
        requireUser(sampleRate in 8000..96000 && channels in 1..2 && dataOffset >= 0, Failure.AUDIO)
        requireUser(dataSize % (2 * channels) == 0L, Failure.AUDIO)
        val frames = dataSize / (2 * channels)
        requireUser(frames in 1..(sampleRate.toLong() * MAX_SECONDS), Failure.LONG)
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
            // Android's Opus decoder removes pre-skip but can expose the final frame's padding.
            // Trust the independently verified Ogg EOS sample count, not codec buffer length.
            val opusSamples = try { OggOpus.read(file.readBytes()).also { it.digest() }.sampleCount } catch (_: Exception) { null }
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UserFacingException(Failure.AUDIO)
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                requireUser((opusSamples?.let { it * 1_000_000 / 48000 } ?: format.getLong(MediaFormat.KEY_DURATION)) <= MAX_SECONDS * 1_000_000L, Failure.LONG)
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
            var next = 0
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            var lastProgress = System.nanoTime()
            while (!outputEnded) {
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                requireUser(System.nanoTime() - lastProgress < 30_000_000_000L, Failure.STALLED)
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
                        requireUser(count == 0 || nextRate == sampleRate, Failure.AUDIO)
                        sampleRate = nextRate
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING))
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    else -> if (index >= 0) {
                        try {
                            requireUser(sampleRate in 8000..96000 && channels in 1..2, Failure.AUDIO)
                            requireUser(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT, Failure.AUDIO)
                            if (info.size > 0) {
                                val destination = pcm ?: FloatArray(sampleRate * MAX_SECONDS).also { pcm = it }
                                val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                requireUser(info.size % (sampleBytes * channels) == 0, Failure.AUDIO)
                                val frames = info.size / (sampleBytes * channels)
                                // Keep real container gaps as silence. The player and words share a zero origin.
                                requireUser(info.presentationTimeUs <= MAX_SECONDS * 1_000_000L + (if (opusSamples != null) 120_000 else 0), Failure.LONG)
                                val firstFrame = framePosition(info.presentationTimeUs, sampleRate, next)
                                next = firstFrame + frames
                                val playableFrames = opusSamples?.let { minOf(frames.toLong(), (it * sampleRate / 48000 - firstFrame).coerceAtLeast(0)).toInt() } ?: frames
                                requireUser(playableFrames == 0 || firstFrame.toLong() + playableFrames <= destination.size, Failure.LONG)
                                repeat(playableFrames) { frame ->
                                    var sum = 0f
                                    repeat(channels) {
                                        val sample = if (sampleBytes == 4) buffer.float else buffer.short / 32768f
                                        requireUser(sample.isFinite(), Failure.AUDIO)
                                        sum += sample
                                    }
                                    destination[firstFrame + frame] = sum / channels
                                }
                                if (playableFrames > 0) count = maxOf(count, firstFrame + playableFrames)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            lastProgress = System.nanoTime()
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
            }
            requireUser(count > 0, Failure.EMPTY)
            return PcmAudio(requireNotNull(pcm).copyOf(count), sampleRate)
        } finally {
            try { if (started) decoder?.stop() } finally {
                try { decoder?.release() } finally { extractor.release() }
            }
        }
    }
}
