package io.codemyriad.cassini.engine.whistle

import io.codemyriad.cassini.core.asr.SileroInputFilter
import io.codemyriad.cassini.core.asr.whistle.WhistleSpeechGate
import io.codemyriad.cassini.core.asr.whistle.WhistleSpeechSegment
import io.codemyriad.cassini.core.audio.whistle.WhistleResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** One track in the dedicated worker. Bounded raw PCM remains separate from VAD-only high-pass samples. */
class WhistleStream internal constructor(
    rate: Int,
    private val probability: (FloatArray) -> Float,
    private val transcribe: (FloatArray) -> ByteArray,
    private val segment: (WhistleSpeechSegment, ByteArray, String) -> Unit,
) {
    constructor(rate: Int, segment: (WhistleSpeechSegment, ByteArray, String) -> Unit) :
        this(rate, WhistleNative::probability, WhistleNative::transcribe, segment)

    private val raw = FloatArray(RING_SIZE)
    private var count = 0L
    private val filter = SileroInputFilter()
    private var frames = 0
    private var voiced = 0
    private var maximum = 0f
    private val rawHash = MessageDigest.getInstance("SHA-256")
    private val vadHash = MessageDigest.getInstance("SHA-256")
    private val gate = WhistleSpeechGate({ frame ->
        val filtered = filter.process(frame)
        updateHash(vadHash, filtered, filtered.size)
        probability(filtered).also { value ->
            frames++
            if (value >= VAD_ATTACK) voiced++
            maximum = maxOf(maximum, value)
        }
    }, ::recognize)
    private val resampler = WhistleResampler(rate) { samples, size ->
        updateHash(rawHash, samples, size)
        for (i in 0 until size) raw[((count + i) and RING_MASK).toInt()] = samples[i]
        count += size
        gate.accept(samples, size)
    }

    fun accept(samples: FloatArray) {
        require(samples.size <= INPUT_BLOCK)
        require(samples.all { it.isFinite() })
        resampler.accept(samples)
    }

    data class Summary(val rawSha256: String, val vadSha256: String, val frames: Int, val voiced: Int, val maximum: Float)

    fun finish(): Summary {
        resampler.finish()
        gate.finish()
        return Summary(hex(rawHash.digest()), hex(vadHash.digest()), frames, voiced, maximum)
    }

    private fun recognize(range: WhistleSpeechSegment) {
        val length = range.endSample - range.startSample
        check(length in 1..MAX_SEGMENT && range.startSample >= count - RING_SIZE && range.endSample <= count)
        val samples = FloatArray(length.toInt()) { raw[((range.startSample + it) and RING_MASK).toInt()] }
        val digest = MessageDigest.getInstance("SHA-256")
        updateHash(digest, samples, samples.size)
        segment(range, transcribe(samples), hex(digest.digest()))
    }

    companion object {
        const val INPUT_BLOCK = 8192
        private const val VAD_ATTACK = 0.5f
        private const val RING_SIZE = 262144
        private const val RING_MASK = 262143L
        private const val MAX_SEGMENT = 240512L
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun updateHash(digest: MessageDigest, samples: FloatArray, count: Int) {
            val bytes = ByteBuffer.allocate(count * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) bytes.putFloat(samples[i])
            digest.update(bytes.array())
        }
    }
}
