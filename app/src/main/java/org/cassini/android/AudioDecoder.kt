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

/**
 * Decode to 16 kHz mono, either as borrowed streaming blocks or a bounded retained recording.
 * Reject long recordings rather than silently dropping audio.
 */
object AudioDecoder {
    /**
     * [onSamples] receives borrowed 16 kHz mono buffers on the calling thread; consume or copy
     * them before returning. [onProgress] reports source milliseconds read and optional duration.
     * The returned PCM remains available for speaker identification and document creation.
     */
    fun decode(context: Context, uri: Uri, onSamples: (FloatArray, Int) -> Unit = { _, _ -> },
               onProgress: (Long, Long?) -> Unit = { _, _ -> }): PcmAudio =
        decodeInternal(context, uri, true, onSamples, onProgress)

    /** Streams 16 kHz PCM without allocating a whole-recording array. Returns actual decoded duration. */
    fun stream(context: Context, uri: Uri, onSamples: (FloatArray, Int) -> Unit, startSample: Long = 0,
               onProgress: (Long, Long?) -> Unit = { _, _ -> }): Long {
        require(startSample in 0..Limits.MAX_SAMPLES)
        var count = startSample
        decodeInternal(context, uri, false, { samples, size ->
            count += size
            requireUser(count <= Limits.MAX_SAMPLES, Failure.LONG)
            onSamples(samples, size)
        }, onProgress, startSample)
        return count * 1000 / Limits.ASR_RATE
    }

    private fun decodeInternal(context: Context, uri: Uri, retainPcm: Boolean, onSamples: (FloatArray, Int) -> Unit,
                               onProgress: (Long, Long?) -> Unit, startSample: Long = 0): PcmAudio {
        val owned = uri.takeIf { it.scheme == "file" }?.path?.let(::File)
        val local = owned ?: File.createTempFile("audio-", ".input", context.cacheDir)
        try {
            requireUser(local.length() <= Limits.MAX_IMPORT_BYTES, Failure.LARGE)
            if (owned == null) context.contentResolver.openInputStream(uri).use { input ->
                val source = input ?: throw UserFacingException(Failure.OPEN)
                local.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var size = 0L
                    while (true) {
                        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                        val n = source.read(buffer)
                        if (n < 0) break
                        if (size / (16 shl 20) != (size + n) / (16 shl 20))
                            requireUser(context.cacheDir.usableSpace >= Limits.MIN_FREE_BYTES, Failure.SPACE)
                        size += n
                        requireUser(size <= Limits.MAX_IMPORT_BYTES, Failure.LARGE)
                        output.write(buffer, 0, n)
                    }
                }
            }
            RandomAccessFile(local, "r").use { file ->
                val header = ByteArray(OggOpus.HEAD_BYTES)
                if (file.length() >= 12) {
                    file.readFully(header, 0, minOf(file.length(), header.size.toLong()).toInt())
                    if (String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                        String(header, 8, 4, Charsets.US_ASCII) == "WAVE") return decodeWav(file, onSamples, retainPcm, startSample, onProgress)
                }
            }
            return decodeCompressed(local, onSamples, onProgress, retainPcm, startSample)
        } finally {
            if (owned == null) local.delete()
        }
    }

    /**
     * Codec timestamps are whole microseconds, so a buffer that continues the previous one can
     * name a time just short of it: 48 kHz AAC frames last 21333.3 µs. Only a gap or overlap
     * beyond a millisecond moves the write position away from [next]. The first buffer has no
     * predecessor and keeps its own time.
     */
    internal fun framePosition(presentationTimeUs: Long, sampleRate: Int, next: Int?): Int {
        val nominal = (presentationTimeUs.coerceAtLeast(0) * sampleRate + 500_000) / 1_000_000
        return if (next != null && kotlin.math.abs(nominal - next) <= sampleRate / 1000) next else nominal.toInt()
    }

    internal fun decodeWav(file: RandomAccessFile, onSamples: (FloatArray, Int) -> Unit = { _, _ -> }, retainPcm: Boolean = true, startSample: Long = 0, onProgress: (Long, Long?) -> Unit = { _, _ -> }): PcmAudio {
        var sampleRate = 0
        var channels = 0
        var dataOffset = -1L
        var dataSize = 0L
        file.seek(12)
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
        requireUser(frames >= 1, Failure.LONG)
        Limits.requireDuration(frames * 1000 / sampleRate, retainPcm)
        val out = if (retainPcm) PcmBuilder(((frames * Limits.ASR_RATE + sampleRate / 2) / sampleRate).toInt()) else null
        val initialFrame = (startSample * sampleRate / Limits.ASR_RATE - sampleRate).coerceAtLeast(0)
        require(initialFrame < frames)
        val resampler = StreamingResampler(sampleRate, initialFrame, startSample) { samples, size ->
            out?.append(samples, size)
            onSamples(samples, size)
        }
        file.seek(dataOffset + initialFrame * 2 * channels)
        val block = ByteArray(64 * 1024)
        val mono = FloatArray(block.size / 2)
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        var remaining = dataSize - initialFrame * 2 * channels
        val durationMs = frames * 1000 / sampleRate
        onProgress(startSample * 1000 / Limits.ASR_RATE, durationMs)
        while (remaining > 0) {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val n = minOf(block.size.toLong(), remaining).toInt()
            file.readFully(block, 0, n)
            remaining -= n
            buffer.clear()
            val count = n / (2 * channels)
            for (i in 0 until count) {
                var sum = 0f
                repeat(channels) { sum += buffer.short / 32768f }
                mono[i] = sum / channels
            }
            resampler.push(mono, 0, count)
            onProgress((dataSize - remaining) / (2 * channels) * 1000 / sampleRate, durationMs)
        }
        resampler.finish()
        return PcmAudio(out?.build() ?: FloatArray(0), Limits.ASR_RATE)
    }

    private fun decodeCompressed(file: File, onSamples: (FloatArray, Int) -> Unit, onProgress: (Long, Long?) -> Unit, retainPcm: Boolean, startSample: Long): PcmAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var started = false
        try {
            // Android's Opus decoder removes pre-skip but can expose the final frame's padding.
            // Trust the independently verified Ogg EOS sample count, not codec buffer length.
            val opusSamples = try { OggOpus.scan(file).also { it.digest() }.sampleCount } catch (_: Exception) { null }
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UserFacingException(Failure.AUDIO)
            extractor.selectTrack(track)
            if (startSample > 0) extractor.seekTo((startSample * 1_000_000 / Limits.ASR_RATE - 1_000_000).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val format = extractor.getTrackFormat(track)
            val durationUs = opusSamples?.let { it * 1_000_000 / 48000 }
                ?: if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
            durationUs?.let { Limits.requireDuration(it / 1000, retainPcm) }
            onProgress(startSample * 1000 / Limits.ASR_RATE, durationUs?.div(1000))
            // Presized exactly for Ogg Opus; a container estimate that is wrong costs one trim or growth.
            val out = if (retainPcm) PcmBuilder(opusSamples?.let { (it * Limits.ASR_RATE + 24000) / 48000 }?.toInt()
                ?: durationUs?.let { (it * Limits.ASR_RATE / 1_000_000).toInt() } ?: (Limits.ASR_RATE * 60)) else null
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            if (startSample > 0 && mime == MediaFormat.MIMETYPE_AUDIO_OPUS) {
                // Opus pre-skip belongs only to the beginning of the stream. A fresh decoder
                // at a seek must not trim that delay a second time from the middle of the recording.
                format.setByteBuffer("csd-1", ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0).apply { flip() })
            }
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: StreamingResampler? = null
            var mono = FloatArray(0)
            var count = 0
            var next: Int? = null
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            var lastProgress = System.nanoTime()
            while (!outputEnded) {
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                requireUser(System.nanoTime() - lastProgress < 30_000_000_000L, Failure.STALLED)
                // Block only when neither side moved: a fixed wait per packet made an hour of Opus take minutes.
                var fed = false
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        fed = true
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
                when (val index = codec.dequeueOutputBuffer(info, if (fed) 0 else 10_000)) {
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
                                val buffer = requireNotNull(codec.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                                requireUser(info.size % (sampleBytes * channels) == 0, Failure.AUDIO)
                                val frames = info.size / (sampleBytes * channels)
                                requireUser(info.presentationTimeUs <= Limits.MAX_RECORDING_MS * 1000 + (if (opusSamples != null) 120_000 else 0), Failure.LONG)
                                val firstFrame = framePosition(info.presentationTimeUs, sampleRate, next)
                                if (startSample > 0 && next == null) count = firstFrame
                                next = firstFrame + frames
                                val playableFrames = opusSamples?.let { minOf(frames.toLong(), (it * sampleRate / 48000 - firstFrame).coerceAtLeast(0)).toInt() } ?: frames
                                // Keep real container gaps as silence. The player and words share a zero origin.
                                // An overlap keeps the audio already passed on: the resampler only moves forward.
                                val skip = (count - firstFrame).coerceIn(0, playableFrames)
                                if (playableFrames > skip) {
                                    val stream = resampler ?: StreamingResampler(sampleRate, count.toLong(), startSample) { samples, size ->
                                        out?.append(samples, size)
                                        onSamples(samples, size)
                                    }.also { resampler = it }
                                    if (firstFrame > count) stream.silence((firstFrame - count).toLong())
                                    buffer.position(info.offset + skip * sampleBytes * channels)
                                    if (mono.size < frames) mono = FloatArray(frames)
                                    for (frame in 0 until playableFrames - skip) {
                                        var sum = 0f
                                        repeat(channels) {
                                            val sample = if (sampleBytes == 4) buffer.float else buffer.short / 32768f
                                            requireUser(sample.isFinite(), Failure.AUDIO)
                                            sum += sample
                                        }
                                        mono[frame] = sum / channels
                                    }
                                    stream.push(mono, 0, playableFrames - skip)
                                    count = firstFrame + playableFrames
                                    onProgress(count * 1000L / sampleRate, durationUs?.div(1000))
                                }
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
            requireNotNull(resampler).finish()
            return PcmAudio(out?.build() ?: FloatArray(0), Limits.ASR_RATE)
        } finally {
            try { if (started) decoder?.stop() } finally {
                try { decoder?.release() } finally { extractor.release() }
            }
        }
    }
}
