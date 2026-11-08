package io.codemyriad.cassini.core.audio.whistle

/**
 * Desktop cassini-fir-48k-16k-v1. Accepts mono 48 kHz or 16 kHz PCM; other rates need an explicit decoder policy.
 * The 97 fixed float32 taps accumulate in ascending order in binary64, without FMA, then round once to float32.
 * Only the true track edges are zero-padded. Call [finish] exactly once to emit the centred filter's delayed tail.
 *
 * Buffers are bounded independently of the track and decoder block sizes. [sink] borrows its array only during
 * the call and must copy any samples it retains. A sink failure aborts this instance; construct a new one to retry.
 * This stage never high-passes recognizer PCM and does not change the legacy Parakeet resampler.
 */
class WhistleResampler(private val rate: Int, private val sink: (FloatArray, Int) -> Unit) {
    init { require(rate == INPUT_RATE || rate == OUTPUT_RATE) { "Whistle requires mono 16 or 48 kHz PCM" } }

    private val history = FloatArray(HISTORY_SIZE)
    private val output = FloatArray(OUTPUT_BLOCK)
    private var count = 0L
    private var next = 0L
    private var used = 0
    private var finished = false
    private var failed = false

    fun accept(samples: FloatArray, size: Int = samples.size) {
        check(!finished && !failed) { "Whistle resampler is closed" }
        require(size in 0..samples.size)
        failed = true
        if (rate == OUTPUT_RATE) {
            if (size > 0) sink(samples, size)
        } else {
            for (i in 0 until size) {
                history[(count and HISTORY_MASK).toInt()] = samples[i]
                count++
                if (next * DECIMATION + HALF_TAPS < count) emit()
            }
            flushOutput()
        }
        failed = false
    }

    fun finish() {
        check(!finished && !failed) { "Whistle resampler is closed" }
        finished = true
        if (rate == INPUT_RATE) {
            val limit = (count + DECIMATION - 1) / DECIMATION
            while (next < limit) emit()
            flushOutput()
        }
    }

    private fun emit() {
        val center = next * DECIMATION + HALF_TAPS
        var value = 0.0
        for (n in coefficients.indices) {
            val index = center - n
            if (index >= 0 && index < count) {
                val product = coefficients[n].toDouble() * history[(index and HISTORY_MASK).toInt()].toDouble()
                value += product
            }
        }
        output[used++] = value.toFloat()
        next++
        if (used == output.size) flushOutput()
    }

    private fun flushOutput() {
        if (used == 0) return
        sink(output, used)
        used = 0
    }

    companion object {
        const val STAGE = "cassini-fir-48k-16k-v1"
        private const val INPUT_RATE = 48000
        private const val OUTPUT_RATE = 16000
        private const val DECIMATION = 3
        private const val HALF_TAPS = 48
        private const val HISTORY_SIZE = 128
        private const val HISTORY_MASK = 127L
        private const val OUTPUT_BLOCK = 1024

        // Exact float32 bit patterns from the published coefficient JSON (SHA-256 52423b16...204e74).
        // Do not recompute these with a platform libm. The test resource preserves the full source metadata.
        @Suppress("MagicNumber") // Pinned IEEE-754 coefficient bits, not calculated tuning parameters.
        private val coefficients = intArrayOf(
            923598676, 917747916, -1215253444, -1203777234, -1205448332, 938994545, 956507424,
            957722229, -1597423569, -1182901171, -1178704366, -1189377304, 968350128, 977298407,
            973723268, -1179701235, -1163808081, -1163666469, 571785603, 988036575, 991757453,
            980265136, -1157430602, -1149196826, -1154619186, 986810901, 1001925174, 1001550607,
            -1563469169, -1142559135, -1139212606, -1151282751, 1005686704, 1013455039, 1008346943,
            -1145611144, -1130369888, -1130712804, 590655647, 1019944099, 1024094180, 1012083874,
            -1125137491, -1116107867, -1120254107, 1023685049, 1041885781, 1048813202, 1050253817,
            1048813202, 1041885781, 1023685049, -1120254107, -1116107867, -1125137491, 1012083874,
            1024094180, 1019944099, 590655647, -1130712804, -1130369888, -1145611144, 1008346943,
            1013455039, 1005686704, -1151282751, -1139212606, -1142559135, -1563469169, 1001550607,
            1001925174, 986810901, -1154619186, -1149196826, -1157430602, 980265136, 991757453,
            988036575, 571785603, -1163666469, -1163808081, -1179701235, 973723268, 977298407,
            968350128, -1189377304, -1178704366, -1182901171, -1597423569, 957722229, 956507424,
            938994545, -1205448332, -1203777234, -1215253444, 917747916, 923598676,
        ).map { Float.fromBits(it) }.toFloatArray()
    }
}
