package io.codemyriad.cassini.core.audio

import io.codemyriad.cassini.core.audio.whistle.WhistleResampler

/**
 * Explicit job decoder policy. Parakeet keeps its existing seek/kernel behavior. Included decodes from the origin:
 * 48 kHz uses the desktop FIR; 16 kHz is unchanged; other supported rates use the existing sinc conversion.
 * Resume skips output only after resampling, preserving phase/history without retaining the skipped prefix.
 */
class DecoderResampler(
    rate: Int,
    initialFrame: Long,
    firstOutput: Long,
    included: Boolean,
    sink: (FloatArray, Int) -> Unit,
) {
    private var skip = if (included) firstOutput else 0L
    private val output: (FloatArray, Int) -> Unit = { samples, size ->
        val drop = minOf(skip, size.toLong()).toInt()
        skip -= drop
        if (drop == 0) sink(samples, size) else if (drop < size) sink(samples.copyOfRange(drop, size), size - drop)
    }
    private val fir = if (included && rate == SOURCE_RATE) WhistleResampler(rate, output) else null
    private val legacy = if (fir == null) {
        StreamingResampler(rate, if (included) 0 else initialFrame, if (included) 0 else firstOutput, output)
    } else null

    init { require(!included || initialFrame == 0L) }

    fun push(samples: FloatArray, offset: Int, count: Int) {
        if (fir != null) fir.accept(samples.copyOfRange(offset, offset + count)) else legacy!!.push(samples, offset, count)
    }

    fun silence(count: Long) {
        val zeros = FloatArray(SILENCE_BLOCK)
        var remaining = count
        while (remaining > 0) {
            val size = minOf(remaining, zeros.size.toLong()).toInt()
            push(zeros, 0, size)
            remaining -= size
        }
    }

    fun finish() {
        if (fir != null) fir.finish() else legacy!!.finish()
    }

    companion object {
        private const val SOURCE_RATE = 48000
        private const val SILENCE_BLOCK = 4096
    }
}
