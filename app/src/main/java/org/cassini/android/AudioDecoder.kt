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
 * Bounded whole-recording import, decoded straight to 16 kHz mono so long audio fits the heap.
 * Reject long recordings rather than silently dropping audio.
 */
object AudioDecoder {
    /**
     * Where decoded 16 kHz mono goes. [begin] comes once, before any samples, with the expected
     * sample count. [inputUs] is the container position the decoder has read up to.
     */
    interface Sink {
        fun begin(expectedSamples: Long) {}
        fun push(samples: FloatArray, count: Int, inputUs: Long)
    }

    /**
     * Writes output from sample [start] on. A resumed decode seeks the input and continues the
     * resampler at [start]; only a fallback replay from zero passes earlier samples, which are dropped.
     * The input position handed to the sink is the time of the next output sample: output j is at j / 16000 s.
     */
    private class Skipping(private val sink: Sink, private val start: Long, var total: Long = 0) {
        fun push(samples: FloatArray, count: Int) {
            val skip = (start - total).coerceIn(0, count.toLong()).toInt()
            total += count
            if (skip == count) return
            val inputUs = total * 1_000_000 / Limits.ASR_RATE
            if (skip > 0) sink.push(samples.copyOfRange(skip, count), count - skip, inputUs)
            else sink.push(samples, count, inputUs)
        }
    }

    fun decode(context: Context, uri: Uri): PcmAudio {
        var out: PcmBuilder? = null
        stream(context, uri, keyed = false) { file, _ ->
            decodeTo(file, object : Sink {
                override fun begin(expectedSamples: Long) { out = PcmBuilder(expectedSamples.toInt()) }
                override fun push(samples: FloatArray, count: Int, inputUs: Long) = checkNotNull(out).append(samples, count)
            }, 0)
        }
        return PcmAudio(checkNotNull(out).build(), Limits.ASR_RATE)
    }

    /**
     * Decodes [uri] once into a [PcmCache] under [directory], continuing a decode a kill
     * interrupted. A complete cache is returned without decoding. The cache key is the Ogg Opus
     * audio digest, or the SHA-256 of the bytes of any other format.
     */
    fun decodeToCache(context: Context, uri: Uri, directory: File, onOpen: (PcmCache) -> Unit = {}): PcmCache {
        var result: PcmCache? = null
        stream(context, uri, keyed = true) { file, key ->
            val cache = PcmCache.open(directory, requireNotNull(key))
            result = cache
            PcmCache.trim(directory, 2, except = key)
            onOpen(cache)
            if (cache.state.complete) return@stream
            try {
                decodeTo(file, object : Sink {
                    override fun begin(expectedSamples: Long) = cache.openWriter(expectedSamples)
                    override fun push(samples: FloatArray, count: Int, inputUs: Long) = cache.append(samples, count, inputUs)
                }, cache.state.decodedSamples)
                cache.finish()
            } catch (error: Throwable) {
                cache.abandon()
                throw error
            }
        }
        return checkNotNull(result)
    }

    /** Decodes a local copy of any supported file into [sink], skipping the first [skip] samples. */
    internal fun decodeTo(file: File, sink: Sink, skip: Long) {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(OggOpus.HEAD_BYTES)
            if (input.length() >= 12) {
                input.readFully(header, 0, minOf(input.length(), header.size.toLong()).toInt())
                if (String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                    String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { decodeWav(input, sink, skip); return }
            }
        }
        decodeCompressed(file, sink, skip)
    }

    private fun stream(context: Context, uri: Uri, keyed: Boolean, body: (File, String?) -> Unit) {
        val local = File.createTempFile("audio-", ".input", context.cacheDir)
        try {
            val hash = if (keyed) java.security.MessageDigest.getInstance("SHA-256") else null
            context.contentResolver.openInputStream(uri).use { input ->
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
                        hash?.update(buffer, 0, n)
                    }
                }
            }
            val key = hash?.let { bytes ->
                val opus = try { OggOpus.scan(local).digest() } catch (_: Exception) { null }
                opus?.let { "opus-$it" } ?: "file-${OggOpus.hex(bytes.digest())}"
            }
            body(local, key)
        } finally {
            local.delete()
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

    internal fun decodeWav(file: RandomAccessFile): PcmAudio {
        var out: PcmBuilder? = null
        decodeWav(file, object : Sink {
            override fun begin(expectedSamples: Long) { out = PcmBuilder(expectedSamples.toInt()) }
            override fun push(samples: FloatArray, count: Int, inputUs: Long) = checkNotNull(out).append(samples, count)
        }, 0)
        return PcmAudio(checkNotNull(out).build(), Limits.ASR_RATE)
    }

    internal fun decodeWav(file: RandomAccessFile, sink: Sink, skip: Long) {
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
        Limits.requireDuration(frames * 1000 / sampleRate)
        sink.begin((frames * Limits.ASR_RATE + sampleRate / 2) / sampleRate)
        // Resume by seeking: the resampler continues at [skip] from the input frames it depends on.
        val out = Skipping(sink, skip, skip)
        val resampler = StreamingResampler(sampleRate, skip, out::push)
        val firstFrame = minOf(resampler.resumeInput(skip), frames)
        file.seek(dataOffset + firstFrame * 2 * channels)
        val block = ByteArray(64 * 1024)
        val mono = FloatArray(block.size / 2)
        val buffer = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN)
        var remaining = dataSize - firstFrame * 2 * channels
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
        }
        resampler.finish()
    }

    private fun decodeCompressed(file: File, sink: Sink, resumeAt: Long) {
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
            val format = extractor.getTrackFormat(track)
            val durationUs = opusSamples?.let { it * 1_000_000 / 48000 }
                ?: if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else null
            durationUs?.let { Limits.requireDuration(it / 1000) }
            // Presized exactly for Ogg Opus; a container estimate that is wrong costs one trim or growth.
            sink.begin(opusSamples?.let { (it * Limits.ASR_RATE + 24000) / 48000 }
                ?: durationUs?.let { it * Limits.ASR_RATE / 1_000_000 } ?: (Limits.ASR_RATE * 60L))
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            // Resume by seeking to the input the resampler needs for output [resumeAt], less the
            // Opus 80 ms pre-roll so the decoder has settled; frames before it are dropped by time.
            // A container that cannot seek there falls back to replaying from zero.
            var resumeFrame = 0L
            if (resumeAt > 0 && sampleRate in 8000..96000) {
                val frame = StreamingResampler(sampleRate, resumeAt) { _, _ -> }.resumeInput(resumeAt)
                val frameUs = frame * 1_000_000 / sampleRate
                extractor.seekTo((frameUs - 80_000).coerceAtLeast(0), MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val landed = extractor.sampleTime
                if (landed in 0..frameUs) resumeFrame = frame
                else extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val out = if (resumeFrame > 0) Skipping(sink, resumeAt, resumeAt) else Skipping(sink, resumeAt)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            val codec = MediaCodec.createDecoderByType(mime)
            decoder = codec
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            codec.configure(format, null, null, 0)
            codec.start()
            started = true
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var resampler: StreamingResampler? = null
            var mono = FloatArray(0)
            var count = resumeFrame.toInt()
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
                        // A resumed decode planned its seek at the track rate, so the rate may not change.
                        requireUser((count == 0 && resumeFrame == 0L) || nextRate == sampleRate, Failure.AUDIO)
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
                                next = firstFrame + frames
                                val playableFrames = opusSamples?.let { minOf(frames.toLong(), (it * sampleRate / 48000 - firstFrame).coerceAtLeast(0)).toInt() } ?: frames
                                // Keep real container gaps as silence. The player and words share a zero origin.
                                // An overlap keeps the audio already passed on: the resampler only moves forward.
                                val skip = (count - firstFrame).coerceIn(0, playableFrames)
                                if (playableFrames > skip) {
                                    val stream = resampler ?: (if (resumeFrame > 0) StreamingResampler(sampleRate, resumeAt, out::push)
                                        else StreamingResampler(sampleRate, out::push)).also { resampler = it }
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
        } finally {
            try { if (started) decoder?.stop() } finally {
                try { decoder?.release() } finally { extractor.release() }
            }
        }
    }
}
