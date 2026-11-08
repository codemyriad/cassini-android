package io.codemyriad.cassini.engine.whistle

import io.codemyriad.cassini.core.asr.SileroInputFilter
import io.codemyriad.cassini.core.asr.whistle.WhistleSpeechGate
import io.codemyriad.cassini.core.asr.whistle.WhistleSpeechSegment
import io.codemyriad.cassini.core.audio.Limits
import io.codemyriad.cassini.core.audio.whistle.WhistleResampler
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** One track in the dedicated worker. Bounded raw PCM remains separate from VAD-only high-pass samples. */
class WhistleStream(private val rate: Int, private val segment: (WhistleSpeechSegment, ByteArray, String) -> Unit) {
    private val raw = FloatArray(RING_SIZE)
    private var count = 0L
    val positionSamples: Long get() = count
    private val filter = SileroInputFilter()
    private var frames = 0
    private var voiced = 0
    private var maximum = 0f
    private var finished = false
    private var rawHashStart = 0L
    private var vadHashStart = 0L
    private val rawHash = MessageDigest.getInstance("SHA-256")
    private val vadHash = MessageDigest.getInstance("SHA-256")
    private val gate = WhistleSpeechGate({ frame ->
        val filtered = filter.process(frame)
        updateHash(vadHash, filtered, filtered.size)
        WhistleNative.probability(filtered).also { value ->
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

    /** Bounded semantic state. Hash diagnostics restart at explicit sample indices after a restore. */
    @Suppress("LongParameterList") // One member per independent pipeline state component.
    data class State(
        val rate: Int,
        val count: Long,
        val rawFirst: Long,
        val raw: FloatArray,
        val filter: DoubleArray,
        val gate: WhistleSpeechGate.State,
        val resampler: WhistleResampler.State,
        val vad: FloatArray,
        val frames: Int,
        val voiced: Int,
        val maximum: Float,
    )

    fun snapshot(): State {
        check(!finished)
        val saved = gate.snapshot()
        val first = if (saved.active) saved.start else saved.position - saved.pre.toLong() * FRAME
        check(first >= maxOf(0L, count - RING_SIZE) && count - first <= RING_SIZE)
        val retained = FloatArray((count - first).toInt()) { raw[((first + it) and RING_MASK).toInt()] }
        return State(
            rate, count, first, retained, filter.state(), saved, resampler.snapshot(),
            WhistleNative.vadState(), frames, voiced, maximum,
        )
    }

    /** The caller validates engine/model/pipeline identity and loads a fresh native session first. */
    fun restore(state: State) {
        check(!finished && count == 0L && frames == 0)
        validate(state)
        require(state.rate == rate)
        for (i in state.raw.indices) raw[((state.rawFirst + i) and RING_MASK).toInt()] = state.raw[i]
        filter.restore(state.filter)
        resampler.restore(state.resampler)
        gate.restore(state.gate)
        WhistleNative.restoreVad(state.vad)
        count = state.count
        frames = state.frames
        voiced = state.voiced
        maximum = state.maximum
        rawHash.reset()
        vadHash.reset()
        rawHashStart = state.count
        vadHashStart = state.gate.position
    }

    fun accept(samples: FloatArray) {
        check(!finished)
        require(samples.size <= INPUT_BLOCK)
        require(samples.all { it.isFinite() })
        resampler.accept(samples)
    }

    @Suppress("LongParameterList") // Hash scopes are required when a checkpoint omits a provider-private digest state.
    data class Summary(
        val rawSha256: String,
        val vadSha256: String,
        val frames: Int,
        val voiced: Int,
        val maximum: Float,
        val rawHashStartSample: Long,
        val vadHashStartSample: Long,
    )

    fun finish(): Summary {
        check(!finished)
        finished = true
        resampler.finish()
        gate.finish()
        return Summary(hex(rawHash.digest()), hex(vadHash.digest()), frames, voiced, maximum, rawHashStart, vadHashStart)
    }

    private fun recognize(range: WhistleSpeechSegment) {
        val length = range.endSample - range.startSample
        check(length in 1..MAX_SEGMENT && range.startSample >= count - RING_SIZE && range.endSample <= count)
        val samples = FloatArray(length.toInt()) { raw[((range.startSample + it) and RING_MASK).toInt()] }
        val digest = MessageDigest.getInstance("SHA-256")
        updateHash(digest, samples, samples.size)
        segment(range, WhistleNative.transcribe(samples), hex(digest.digest()))
    }

    companion object {
        const val INPUT_BLOCK = 8192
        private const val SOURCE_RATE = 48000
        private const val FRAME = 512
        private const val VAD_STATE = 320
        private const val FILTER_STATE = 4
        private const val VAD_ATTACK = 0.5f
        private const val RING_SIZE = 262144
        private const val RING_MASK = 262143L
        private const val MAX_SEGMENT = 240512L
        /** CPU-only validation, also used before any neural model is loaded on a checkpoint OPEN. */
        fun validate(state: State) {
            require(state.rate == Limits.ASR_RATE || state.rate == SOURCE_RATE)
            require(state.count in 0..Limits.MAX_SAMPLES && state.rawFirst in 0..state.count)
            require(state.raw.size <= RING_SIZE && state.raw.size.toLong() == state.count - state.rawFirst)
            require(state.raw.all { it.isFinite() })
            require(state.filter.size == FILTER_STATE && state.filter.all { it.isFinite() })
            require(state.vad.size == VAD_STATE && state.vad.all { it.isFinite() })
            require(state.frames >= 0 && state.frames.toLong() == state.gate.position / FRAME)
            require(state.voiced in 0..state.frames && state.maximum.isFinite() && state.maximum in 0f..1f)
            WhistleSpeechGate({ 0f }, {}).restore(state.gate)
            WhistleResampler(state.rate) { _, _ -> }.restore(state.resampler)
            require(state.resampler.rate == state.rate)
            require(state.gate.position + state.gate.used == state.count)
            val first = if (state.gate.active) state.gate.start else state.gate.position - state.gate.pre.toLong() * FRAME
            require(state.rawFirst <= first)
            if (state.rate == SOURCE_RATE) require(state.resampler.next == state.count)
        }

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        private fun updateHash(digest: MessageDigest, samples: FloatArray, count: Int) {
            val bytes = ByteBuffer.allocate(count * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) bytes.putFloat(samples[i])
            digest.update(bytes.array())
        }
    }
}
