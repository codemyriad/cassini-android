package org.cassini.android

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Silero VAD's rate. Only the detector's copy uses it; ASR keeps the recording's own samples. */
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
 * Converts mono audio at [rate] (8..96 kHz) to 16 kHz for the speech detector only. Each output
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
