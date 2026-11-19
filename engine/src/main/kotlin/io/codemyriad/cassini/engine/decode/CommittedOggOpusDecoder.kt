package io.codemyriad.cassini.engine.decode

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaFormat
import io.codemyriad.cassini.core.Failure
import io.codemyriad.cassini.core.audio.DecoderResampler
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.audio.WavDecoder
import io.codemyriad.cassini.core.format.CommittedOggPacket
import io.codemyriad.cassini.core.format.OggOpusPrefixScanner
import io.codemyriad.cassini.core.requireUser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Incremental read-only decoder over committed container prefix bytes of a growing recording (D-1124).
 *
 * Implementations never call `finish()` on the codec or resampler, so every emitted 16 kHz mono sample
 * matches the finalized post-Stop batch decode bit-for-bit.
 */
interface CommittedPrefixDecoder : AutoCloseable {
    /** Exact committed container byte length `[0 ..< committedBytes)` fed into the decoder so far. */
    val committedBytes: Long

    /** True once the underlying container exposed an end-of-stream page (recording finished). */
    val sawEos: Boolean

    /** True if container framing/magic became corrupt and precomputation must stop. */
    val corrupt: Boolean

    /** Lowercase hex SHA-256 over exact container bytes `[0 ..< committedBytes)`. */
    fun committedSha256(): String

    /**
     * Scans for newly committed container pages, decodes them through the batch decoder + resampler policy,
     * and emits 16 kHz mono float samples to [onSamples]. Returns the number of newly decoded packets.
     */
    fun poll(onSamples: (FloatArray, Int) -> Unit): Int
}

/**
 * Read-only committed Ogg Opus prefix decoder using the exact same `MediaCodec` (`audio/opus`) +
 * [DecoderResampler] policy as [AudioDecoder] (D-1124).
 *
 * Reads only complete, CRC32-valid, non-EOS Ogg Opus pages via [OggOpusPrefixScanner] (`RandomAccessFile(..., "r")`),
 * staying [trailingPagesMargin] complete pages behind the writer head so decoder lookahead and resampler history
 * remain inside committed bytes that never change at `LiveMuxer.finish(...)`.
 */
@Suppress("TooManyFunctions") // One helper per MediaCodec lifecycle and DecoderResampler step.
class CommittedOggOpusDecoder(
    file: File,
    private val included: Boolean,
    private val startSample: Long = 0L,
    trailingPagesMargin: Int = OggOpusPrefixScanner.DEFAULT_TRAILING_PAGES_MARGIN,
) : CommittedPrefixDecoder {
    private val scanner = OggOpusPrefixScanner(file, trailingPagesMargin)
    private val info = MediaCodec.BufferInfo()

    private var codec: MediaCodec? = null
    private var started = false
    private var closed = false
    private var sampleRate = OPUS_RATE
    private var channels = 1
    private var encoding = AudioFormat.ENCODING_PCM_16BIT
    private var resampler: DecoderResampler? = null
    private var mono = FloatArray(0)
    private var count = 0L
    private var next: Long? = null
    private var outputSamples = startSample

    override val committedBytes: Long
        get() = scanner.committedBytes

    override val sawEos: Boolean
        get() = scanner.sawEos

    override val corrupt: Boolean
        get() = scanner.corrupt

    override fun committedSha256(): String = scanner.committedSha256()

    override fun poll(onSamples: (FloatArray, Int) -> Unit): Int {
        check(!closed) { "CommittedOggOpusDecoder is closed" }
        val packets = scanner.poll()
        val decoder = if (packets.isNotEmpty()) ensureCodec() else null
        if (decoder == null) return 0
        for (packet in packets) {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            feedPacket(decoder, packet, onSamples)
        }
        drainOutputs(decoder, onSamples, timeoutUs = 0L)
        return packets.size
    }

    private fun ensureCodec(): MediaCodec? =
        codec ?: scanner.head?.let { head -> createAndStartCodec(head).also { codec = it } }

    // MediaCodec throws CodecException, IllegalStateException, or IllegalArgumentException on bad format/state.
    @Suppress("TooGenericExceptionCaught")
    private fun createAndStartCodec(head: ByteArray): MediaCodec {
        val ch = scanner.channels
        requireUser(ch in 1..MAX_CHANNELS, Failure.AUDIO)
        channels = ch
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, OPUS_RATE, ch).apply {
            setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            setByteBuffer("csd-0", ByteBuffer.wrap(head.copyOf()))
            val preSkipNs = scanner.preSkip.toLong() * NANOS_PER_SECOND / OPUS_RATE
            setByteBuffer("csd-1", nativeLongBuffer(preSkipNs))
            setByteBuffer("csd-2", nativeLongBuffer(SEEK_PRE_ROLL_NS))
        }
        val created = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        try {
            created.configure(format, null, null, 0)
            created.start()
            started = true
            return created
        } catch (error: RuntimeException) {
            releaseQuietly(created)
            throw error
        }
    }

    private fun feedPacket(decoder: MediaCodec, packet: CommittedOggPacket, onSamples: (FloatArray, Int) -> Unit) {
        val preSkip = scanner.preSkip.toLong()
        val ptsUs = (packet.granuleStart - preSkip).coerceAtLeast(0L) * MICROS_PER_SECOND / OPUS_RATE
        var queued = false
        var lastProgress = System.nanoTime()
        while (!queued) {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            requireUser(System.nanoTime() - lastProgress < STALL_TIMEOUT_NS, Failure.STALLED)
            val index = decoder.dequeueInputBuffer(0L)
            if (index >= 0) {
                val buffer = requireNotNull(decoder.getInputBuffer(index))
                buffer.clear()
                requireUser(packet.bytes.size <= buffer.remaining(), Failure.AUDIO)
                buffer.put(packet.bytes)
                decoder.queueInputBuffer(index, 0, packet.bytes.size, ptsUs, 0)
                queued = true
                lastProgress = System.nanoTime()
            }
            if (drainOutputs(decoder, onSamples, timeoutUs = if (queued) 0L else DRAIN_TIMEOUT_US)) {
                lastProgress = System.nanoTime()
            }
        }
    }

    private fun drainOutputs(
        decoder: MediaCodec,
        onSamples: (FloatArray, Int) -> Unit,
        timeoutUs: Long,
    ): Boolean {
        var progressed = false
        var waitUs = timeoutUs
        while (drainOneOutput(decoder, onSamples, waitUs)) {
            progressed = true
            waitUs = 0L
        }
        return progressed
    }

    private fun drainOneOutput(
        decoder: MediaCodec,
        onSamples: (FloatArray, Int) -> Unit,
        timeoutUs: Long,
    ): Boolean {
        val index = decoder.dequeueOutputBuffer(info, timeoutUs)
        return when {
            index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                applyOutputFormat(decoder.outputFormat)
                true
            }
            index >= 0 -> {
                try {
                    consumeOutputBuffer(decoder, index, onSamples)
                } finally {
                    decoder.releaseOutputBuffer(index, false)
                }
                true
            }
            else -> false
        }
    }

    private fun applyOutputFormat(output: MediaFormat) {
        val nextRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        requireUser(count == 0L || nextRate == sampleRate, Failure.AUDIO)
        sampleRate = nextRate
        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            output.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
    }

    private fun consumeOutputBuffer(
        decoder: MediaCodec,
        index: Int,
        onSamples: (FloatArray, Int) -> Unit,
    ) {
        requireUser(sampleRate in MIN_SAMPLE_RATE..MAX_SAMPLE_RATE && channels in 1..MAX_CHANNELS, Failure.AUDIO)
        requireUser(
            encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT,
            Failure.AUDIO,
        )
        if (info.size <= 0) return
        val buffer = requireNotNull(decoder.getOutputBuffer(index)).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val sampleBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) FLOAT_BYTES else SHORT_BYTES
        requireUser(info.size % (sampleBytes * channels) == 0, Failure.AUDIO)
        val frames = info.size / (sampleBytes * channels)
        requireUser(info.presentationTimeUs <= Limits.MAX_TRANSCRIBE_MS * MICROS_PER_MS + OPUS_MARGIN_US, Failure.LONG)

        val firstFrame = WavDecoder.framePosition(info.presentationTimeUs, sampleRate, next)
        next = firstFrame + frames
        val skip = (count - firstFrame).coerceIn(0L, frames.toLong()).toInt()
        if (frames <= skip) return

        val stream = resampler ?: DecoderResampler(sampleRate, count, startSample, included) { samples, size ->
            outputSamples += size
            requireUser(outputSamples <= Limits.MAX_SAMPLES, Failure.LONG)
            onSamples(samples, size)
        }.also { resampler = it }
        if (firstFrame > count) stream.silence(firstFrame - count)
        buffer.position(info.offset + skip * sampleBytes * channels)
        if (mono.size < frames) mono = FloatArray(frames)
        for (frame in 0 until frames - skip) {
            var sum = 0f
            repeat(channels) {
                val sample = if (sampleBytes == FLOAT_BYTES) buffer.float else buffer.short / PCM16_SCALE
                requireUser(sample.isFinite(), Failure.AUDIO)
                sum += sample
            }
            mono[frame] = sum / channels
        }
        stream.push(mono, 0, frames - skip)
        count = firstFrame + frames
    }

    override fun close() {
        if (closed) return
        closed = true
        val decoder = codec
        codec = null
        if (decoder != null) stopAndReleaseQuietly(decoder)
    }

    private fun stopAndReleaseQuietly(decoder: MediaCodec) {
        try {
            if (started) decoder.stop()
        } catch (
            @Suppress("TooGenericExceptionCaught") // Best-effort codec teardown.
            _: RuntimeException,
        ) {
            // Ignore stop failures during teardown.
        } finally {
            releaseQuietly(decoder)
        }
    }

    private fun releaseQuietly(decoder: MediaCodec) {
        try {
            decoder.release()
        } catch (
            @Suppress("TooGenericExceptionCaught") // Best-effort codec teardown.
            _: RuntimeException,
        ) {
            // Ignore release failures during teardown.
        }
    }

    private companion object {
        const val OPUS_RATE = 48_000
        const val MIN_SAMPLE_RATE = 8_000
        const val MAX_SAMPLE_RATE = 96_000
        const val MAX_CHANNELS = 2
        const val SHORT_BYTES = 2
        const val FLOAT_BYTES = 4
        const val PCM16_SCALE = 32_768f
        const val MICROS_PER_MS = 1_000L
        const val MICROS_PER_SECOND = 1_000_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val SEEK_PRE_ROLL_NS = 80_000_000L
        const val OPUS_MARGIN_US = 120_000L
        const val DRAIN_TIMEOUT_US = 10_000L
        const val STALL_TIMEOUT_NS = 30_000_000_000L

        fun nativeLongBuffer(value: Long): ByteBuffer =
            ByteBuffer.allocate(Long.SIZE_BYTES).order(ByteOrder.nativeOrder()).putLong(value).apply { flip() }
    }
}
