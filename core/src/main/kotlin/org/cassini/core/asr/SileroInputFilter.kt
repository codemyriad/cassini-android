package org.cassini.core.asr

/**
 * The high-pass on the speech detector's input (coordination D26.1, stage [STAGE]): WebRTC APM's capture high-pass,
 * a 2nd-order Butterworth at 100 Hz for 16 kHz audio, as freedesktop webrtc-audio-processing `high_pass_filter.cc`
 * has it. Silero VAD has no DC handling of its own, so a microphone with a constant ADC offset hides quiet speech from
 * it and the recording gets no transcript. Only the samples Silero sees are filtered: the recognizer, the diarizer,
 * the voiceprint windows and the stored audio stay as they are.
 *
 * Direct Form I with binary64 state and arithmetic, evaluated left to right (the JVM never fuses a multiply-add), and
 * the denormal guard of libopus `dc_reject`. The coefficients are exact literals; b0 + b1 + b2 is exactly 0, a true
 * double zero at DC. The state starts at zero and is carried across frames and recognition windows for the whole
 * track ([state], [restore]); a zero-state start never produced a segment, so no priming is needed.
 */
class SileroInputFilter {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    /** Samples filtered since the start or the last [restore]. */
    private var processed = 0L
    private var mark: Long = -1
    private var onMark: ((DoubleArray) -> Unit)? = null

    /** Filters one frame for Silero into a new array; the frame itself is not changed. */
    fun process(frame: FloatArray): FloatArray {
        val out = FloatArray(frame.size)
        for (i in frame.indices) {
            reachMark()
            val x = frame[i].toDouble()
            var y = B0 * x + B1 * x1 + B2 * x2 - A1 * y1 - A2 * y2
            if (y > -DENORMAL && y < DENORMAL) y = 0.0
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            out[i] = y.toFloat()
            processed++
        }
        reachMark()
        return out
    }

    private fun reachMark() {
        if (processed != mark) return
        mark = -1
        onMark?.invoke(state())
    }

    /** x1, x2, y1, y2: the state before the next sample. */
    fun state(): DoubleArray = DoubleArray(STATE_SIZE).also {
        it[X1] = x1
        it[X2] = x2
        it[Y1] = y1
        it[Y2] = y2
    }

    fun restore(state: DoubleArray) {
        require(state.size == STATE_SIZE)
        x1 = state[X1]
        x2 = state[X2]
        y1 = state[Y1]
        y2 = state[Y2]
    }

    /** Calls [record] with the state once [samples] samples have been filtered: where the next window begins. */
    fun markAt(samples: Long, record: (DoubleArray) -> Unit) {
        mark = samples
        onMark = record
    }

    companion object {
        /** The version of this stage, for the provenance. */
        const val STAGE = "silero-input-hpf-butter2-100hz-v1"

        // butter(2, 100/8000, 'high'), as exact binary64 literals: never recomputed at run time.
        const val B0 = 0.9726138984998437
        const val B1 = -1.9452277969996874
        const val B2 = 0.9726138984998437
        const val A1 = -1.9444776577670935
        const val A2 = 0.9459779362322813

        private const val DENORMAL = 1e-25
        private const val STATE_SIZE = 4
        private const val X1 = 0
        private const val X2 = 1
        private const val Y1 = 2
        private const val Y2 = 3
    }
}

/**
 * One [SileroInputFilter] state for a whole track that is recognized in overlapping windows, each with its own
 * detector: a window starts from the state at its first sample, which the window before recorded where the next one
 * begins. The first window, and the first one after a resume from a checkpoint, start from zero.
 */
class TrackSileroInput {
    private var carried: Pair<Long, DoubleArray>? = null

    /**
     * The filter of the window that starts at track sample [start]; [next] is where the following window starts,
     * relative to this one (null for the last window).
     */
    fun window(start: Long, next: Int?): SileroInputFilter {
        val filter = SileroInputFilter()
        carried?.takeIf { it.first == start }?.let { filter.restore(it.second) }
        carried = null
        if (next != null) filter.markAt(next.toLong()) { state -> carried = (start + next) to state }
        return filter
    }
}
