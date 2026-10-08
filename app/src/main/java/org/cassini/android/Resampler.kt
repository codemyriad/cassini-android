package org.cassini.android

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Silero VAD's and Parakeet's feature rate. Decoded audio is resampled to it once, so ASR, VAD and diarization share one array. */
internal const val VAD_SAMPLE_RATE = 16000

/** Cutoff as a fraction of min(rate, 16 kHz): 7 kHz for inputs at 16 kHz or above. */
private const val CUTOFF_FRACTION = 0.4375
/** Kernel half-width in zero crossings of the cutoff sinc. */
private const val ZERO_CROSSINGS = 12
private const val TABLE_STEPS = 1024
private val kernel = FloatArray(ZERO_CROSSINGS * TABLE_STEPS + 2) { index ->
    val z = index.toDouble() / TABLE_STEPS
    if (z >= ZERO_CROSSINGS) 0f
    else ((if (z == 0.0) 1.0 else sin(PI * z) / (PI * z)) * 0.5 * (1 + cos(PI * z / ZERO_CROSSINGS))).toFloat()
}

/**
 * Converts mono audio at [rate] (8..96 kHz) to 16 kHz. Each output
 * sample is a band-limited interpolation through a Hann-windowed sinc low-pass with cutoff 7/16 of
 * min(rate, 16 kHz), 12 zero crossings each side, normalised to unit gain at every output position.
 * That keeps the speech band flat to about 6 kHz and attenuates content above 8 kHz, which would
 * otherwise alias into it, by more than 35 dB: enough for a detector, not a mastering resampler.
 * Output sample j sits at time j / 16000 s, so it maps back to recording sample j * rate / 16000.
 * The output has round(n * 16000 / rate) samples; 16 kHz input is returned as is.
 */
internal fun resampleTo16k(samples: FloatArray, rate: Int): FloatArray {
    require(rate in 8000..96000) { "Unsupported sample rate $rate" }
    if (rate == VAD_SAMPLE_RATE) return samples
    val output = FloatArray(((samples.size.toLong() * VAD_SAMPLE_RATE + rate / 2) / rate).toInt())
    val step = rate.toDouble() / VAD_SAMPLE_RATE
    // Cutoff in cycles per input sample; one zero crossing of the sinc spans 1 / (2 * cutoff) input samples.
    val crossingsPerSample = 2 * CUTOFF_FRACTION * min(rate, VAD_SAMPLE_RATE) / rate
    val halfWidth = ZERO_CROSSINGS / crossingsPerSample
    for (index in output.indices) {
        val time = index * step
        var sum = 0.0
        var weight = 0.0
        for (k in max(0, ceil(time - halfWidth).toInt())..min(samples.size - 1, floor(time + halfWidth).toInt())) {
            val position = abs(time - k) * crossingsPerSample * TABLE_STEPS
            val cell = position.toInt()
            val fraction = (position - cell).toFloat()
            val tap = kernel[cell] + (kernel[cell + 1] - kernel[cell]) * fraction
            sum += tap * samples[k]
            weight += tap
        }
        output[index] = if (weight > 0) (sum / weight).toFloat() else 0f
    }
    return output
}

/**
 * [resampleTo16k] over audio that arrives in blocks: the same kernel and output positions, with the
 * input history kept across block edges, so the output equals the whole-array conversion. [sink]
 * receives a reused buffer and its valid length.
 */
internal class StreamingResampler(private val rate: Int, private val sink: (FloatArray, Int) -> Unit) {
    init { require(rate in 8000..96000) { "Unsupported sample rate $rate" } }
    private val step = rate.toDouble() / VAD_SAMPLE_RATE
    private val crossingsPerSample = 2 * CUTOFF_FRACTION * min(rate, VAD_SAMPLE_RATE) / rate
    private val halfWidth = ZERO_CROSSINGS / crossingsPerSample
    /** Whole-multiple rates put every output on an input sample, so the taps are the same each time. */
    private val fixed = rate % VAD_SAMPLE_RATE == 0
    private val first = ceil(-halfWidth).toInt()
    private val taps = if (!fixed) FloatArray(0) else FloatArray(floor(halfWidth).toInt() - first + 1) { i ->
        val position = abs((first + i).toDouble()) * crossingsPerSample * TABLE_STEPS
        val cell = position.toInt()
        kernel[cell] + (kernel[cell + 1] - kernel[cell]) * (position - cell).toFloat()
    }
    private val tapWeight = taps.fold(0.0) { sum, tap -> sum + tap }
    private var history = FloatArray(8192)
    private var base = 0L
    private var size = 0
    private val out = FloatArray(4096)
    private var pending = 0
    private var finished = false
    var produced = 0L
        private set
    val consumed get() = base + size

    fun push(samples: FloatArray, offset: Int = 0, length: Int = samples.size - offset) {
        check(!finished)
        if (rate == VAD_SAMPLE_RATE) {
            var at = offset
            while (at < offset + length) {
                val n = min(out.size - pending, offset + length - at)
                samples.copyInto(out, pending, at, at + n); pending += n; at += n
                if (pending == out.size) flush()
            }
            base += length; produced += length
            return
        }
        if (size + length > history.size) history = history.copyOf(max(history.size * 2, size + length))
        samples.copyInto(history, size, offset, offset + length)
        size += length
        drain(null)
    }

    fun silence(count: Long) {
        val zeros = FloatArray(4096)
        var left = count
        while (left > 0) { val n = min(left, zeros.size.toLong()).toInt(); push(zeros, 0, n); left -= n }
    }

    /** Emits the outputs that need samples past the end, and returns the output length: round(n * 16000 / rate). */
    fun finish(): Long {
        check(!finished)
        finished = true
        if (rate != VAD_SAMPLE_RATE) drain((consumed * VAD_SAMPLE_RATE + rate / 2) / rate)
        flush()
        return produced
    }

    private fun flush() { if (pending > 0) sink(out, pending); pending = 0 }

    private fun drain(target: Long?) {
        val end = base + size
        while (target == null || produced < target) {
            val time = produced * step
            val last = floor(time + halfWidth).toLong()
            if (target == null && last >= end) break
            val start = produced * (rate / VAD_SAMPLE_RATE) + first
            if (fixed && start >= 0 && last < end) {
                var sum = 0.0
                val at = (start - base).toInt()
                for (i in taps.indices) sum += taps[i] * history[at + i]
                out[pending++] = (sum / tapWeight).toFloat()
                if (pending == out.size) flush()
                produced++
                continue
            }
            var sum = 0.0
            var weight = 0.0
            for (k in max(0L, ceil(time - halfWidth).toLong())..min(end - 1, last)) {
                val position = abs(time - k) * crossingsPerSample * TABLE_STEPS
                val cell = position.toInt()
                val fraction = (position - cell).toFloat()
                val tap = kernel[cell] + (kernel[cell + 1] - kernel[cell]) * fraction
                sum += tap * history[(k - base).toInt()]
                weight += tap
            }
            out[pending++] = if (weight > 0) (sum / weight).toFloat() else 0f
            if (pending == out.size) flush()
            produced++
        }
        val keep = max(0L, ceil(produced * step - halfWidth).toLong()).coerceAtMost(end)
        val drop = (keep - base).toInt()
        if (drop > 0) { history.copyInto(history, 0, drop, size); size -= drop; base = keep }
    }
}
