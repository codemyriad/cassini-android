package org.cassini.android

/** Samples [start, end) at the recording's own rate. */
internal data class Span(val start: Int, val end: Int) {
    val length: Int get() = end - start
    fun shift(by: Int) = Span(start + by, end + by)
}

/**
 * Boundary choices for one speech span, in milliseconds so they hold at any sample rate.
 * The defaults are the desktop pipeline's policy for a stock sherpa runtime: 10 s windows with
 * 0.5 s overlap, spans up to 10.5 s kept whole, a terminal window of at least 5 s, and 0.5 s of
 * synthetic silence after each decode. [PARAKEET_V3_REFERENCE] needs a patched native frontend.
 */
internal data class DecodePolicy(
    val windowMs: Int = 10_000, val overlapMs: Int = 500, val graceMs: Int = 500, val minTerminalMs: Int = 5_000,
    val tailPaddingMs: Int = 500, val headPaddingMs: Int = 0, val contextMs: Int = 0,
    val preserveSpan: Boolean = false, val syntheticPadding: Boolean = true,
) {
    companion object {
        /** Whole detected utterance plus 30 ms of recorded context, and no synthetic silence: the reference frontend normalises over the entire input. */
        val PARAKEET_V3_REFERENCE = DecodePolicy(preserveSpan = true, contextMs = 30, tailPaddingMs = 0, syntheticPadding = false)
        /**
         * What this app uses: each detected span decoded whole with the stock 0.5 s synthetic tail, long spans
         * first cut by [SpeechWindows.quietPieces]. On a Pixel 8 with the stock frontend, 10 s windows left
         * duplicated words at their seams; see docs/pixel8-results.md.
         */
        val WHOLE_SPANS = DecodePolicy(preserveSpan = true)
    }
}

/** One decoder call: [span] of the source with [headPad] zero samples before it and [tailPad] after it. */
internal data class Chunk(val span: Span, val headPad: Int, val tailPad: Int)

/**
 * One decode window. Its first [overlap] samples were also decoded by the previous window, so words
 * there are de-duplicated against it (0 for the first window). [chunks] cover the window back to back.
 */
internal data class Window(val span: Span, val overlap: Int, val chunks: List<Chunk>)

/**
 * Window arithmetic of the desktop decoder. Every duration is converted with the recording's
 * actual sample rate, because Android hands sherpa the samples unresampled.
 */
internal object SpeechWindows {
    /** Emergency split for silence-free speech: an ONNX-safe decode length of 55 s of audio. */
    const val MAX_SAFE_MS = 55_000
    /** Non-VAD decodes shorter than this get 0.5 s of synthetic tail silence. */
    const val TAIL_PAD_MIN_SECONDS = 10

    /** A span up to this long is decoded whole. The detector's 25 s limit is a hint: spans close a little after it. */
    const val WHOLE_SPAN_MS = 28_000
    /** A longer span is cut where it is quietest in this stretch after the previous cut. */
    const val CUT_FROM_MS = 20_000
    const val CUT_TO_MS = 25_000
    /** Recorded audio decoded on each side of a forced cut, so a word the cut runs through is heard whole by both decodes. */
    const val CUT_CONTEXT_MS = 1_000
    private const val QUIET_FRAME_MS = 30
    private const val QUIET_STEP_MS = 10

    fun samples(ms: Int, sampleRate: Int): Int = (ms.toLong() * sampleRate / 1000).toInt()

    /**
     * Sherpa's detector does not close a span at its maximum duration; it only becomes stricter, and over
     * noise one span can run for minutes. A [span] longer than [WHOLE_SPAN_MS] is cut at the quietest
     * 30 ms between [CUT_FROM_MS] and [CUT_TO_MS] after the previous cut, so each decode stays short,
     * progress keeps moving, and a cut falls between words where the recording allows it. The quietest
     * frame can still be a closure inside a word, so the caller decodes [CUT_CONTEXT_MS] of the recording
     * past each cut on both sides and reconciles the two readings. The pieces cover the span back to back.
     */
    fun quietPieces(samples: FloatArray, span: Span, sampleRate: Int): List<Span> {
        require(sampleRate > 0)
        val whole = samples(WHOLE_SPAN_MS, sampleRate)
        val out = ArrayList<Span>()
        var start = span.start
        while (span.end - start > whole) {
            val cut = quietest(samples, start + samples(CUT_FROM_MS, sampleRate), start + samples(CUT_TO_MS, sampleRate), sampleRate)
            out += Span(start, cut)
            start = cut
        }
        if (span.end > start) out += Span(start, span.end)
        return out
    }

    /** Centre of the lowest-energy frame inside [from, to), the earliest on a tie. */
    private fun quietest(samples: FloatArray, from: Int, to: Int, sampleRate: Int): Int {
        val frame = maxOf(1, samples(QUIET_FRAME_MS, sampleRate))
        val step = maxOf(1, samples(QUIET_STEP_MS, sampleRate))
        var best = from
        var least = Double.MAX_VALUE
        var at = from
        while (at + frame <= to) {
            var energy = 0.0
            for (i in at until at + frame) energy += samples[i].toDouble() * samples[i]
            if (energy < least) { least = energy; best = at + frame / 2 }
            at += step
        }
        return best
    }
    fun maxSafeSamples(sampleRate: Int): Int = samples(MAX_SAFE_MS, sampleRate)
    /** Floor, as the decoder places source offsets on the timeline. */
    fun floorMs(samples: Int, sampleRate: Int): Long = samples.toLong() * 1000 / sampleRate
    /** Ceiling, so a timeline clamp tolerates every padded sample. */
    fun ceilMs(samples: Int, sampleRate: Int): Long =
        if (samples <= 0 || sampleRate <= 0) 0 else (samples.toLong() * 1000 + sampleRate - 1) / sampleRate

    /** Samples [next] shares with [previous]; 0 for the first window or when they do not meet. */
    fun overlap(previous: Span?, next: Span): Int = previous?.let { maxOf(0, it.end - next.start) } ?: 0

    /** A VAD span widened by [contextMs] of real recording on each side, clipped to the recording. */
    fun context(start: Int, end: Int, total: Int, sampleRate: Int, contextMs: Int): Span {
        val context = samples(contextMs, sampleRate)
        return Span(maxOf(0, start - context), minOf(total, end + context))
    }

    /**
     * Decode windows for a VAD span of [total] samples, relative to its start. Long merged spans make
     * Parakeet emit only a prefix, so they are cut into overlapping windows; a span at most one window
     * plus grace stays whole, and a short terminal window is moved back to [DecodePolicy.minTerminalMs]
     * so it carries useful context, keeping the exact overlap and gap-free coverage.
     */
    fun split(total: Int, sampleRate: Int, policy: DecodePolicy = DecodePolicy()): List<Span> {
        require(sampleRate > 0)
        if (total <= 0) return emptyList()
        if (policy.preserveSpan) return listOf(Span(0, total))
        val window = samples(policy.windowMs, sampleRate)
        val overlap = samples(policy.overlapMs, sampleRate)
        if (total <= window + samples(policy.graceMs, sampleRate)) return listOf(Span(0, total))
        val bounds = slide(total, window, overlap).toMutableList()
        val last = bounds.lastIndex
        val minTerminal = samples(policy.minTerminalMs, sampleRate)
        if (last > 0 && bounds[last].length < minTerminal) {
            bounds[last] = Span(total - minTerminal, total)
            bounds[last - 1] = bounds[last - 1].copy(end = bounds[last].start + overlap)
        }
        return bounds
    }

    /** Fixed sliding windows for dense audio decoded without VAD; only the last may be shorter. */
    fun fixed(total: Int, sampleRate: Int, windowMs: Int = 15_000, overlapMs: Int = 500): List<Span> {
        require(sampleRate > 0)
        return slide(total, samples(windowMs, sampleRate), samples(overlapMs, sampleRate))
    }

    /**
     * Synthetic tail silence for a decode when the policy does not set it: every VAD span ends at a
     * detected boundary with its closing silence removed, and a short non-VAD decode needs context.
     */
    fun tailPadSamples(chunkSamples: Int, sampleRate: Int, vadSegment: Boolean): Int = when {
        chunkSamples <= 0 || sampleRate <= 0 -> 0
        vadSegment || chunkSamples.toLong() < TAIL_PAD_MIN_SECONDS.toLong() * sampleRate -> sampleRate / 2
        else -> 0
    }

    /**
     * The decoder calls for one source span of [total] samples, relative to its start. A VAD span is
     * split into windows first; every window is then cut at [maxSafeSamples] without overlap and
     * padded by the policy. Without synthetic padding a chunk under 20 ms is skipped, because the
     * reference frontend needs two 10 ms frames and aborts in native code on none.
     */
    fun windows(total: Int, sampleRate: Int, vadSegment: Boolean, policy: DecodePolicy = DecodePolicy()): List<Window> {
        require(sampleRate > 0)
        if (total <= 0) return emptyList()
        val spans = if (vadSegment) split(total, sampleRate, policy) else emptyList()
        if (spans.size <= 1) return listOf(Window(Span(0, total), 0, chunks(total, sampleRate, vadSegment, policy)))
        val out = ArrayList<Window>()
        for (span in spans) for (child in windows(span.length, sampleRate, true, policy)) {
            val shifted = child.span.shift(span.start)
            out += Window(shifted, overlap(out.lastOrNull()?.span, shifted), child.chunks.map { it.copy(span = it.span.shift(span.start)) })
        }
        return out
    }

    private fun chunks(total: Int, sampleRate: Int, vadSegment: Boolean, policy: DecodePolicy): List<Chunk> {
        val limit = maxSafeSamples(sampleRate)
        val out = ArrayList<Chunk>()
        var start = 0
        while (start < total) {
            val end = minOf(total.toLong(), start.toLong() + limit).toInt()
            val length = end - start
            if (policy.syntheticPadding || length.toLong() * 1000 >= sampleRate.toLong() * 20) {
                val explicit = vadSegment || !policy.syntheticPadding
                out += Chunk(Span(start, end),
                    if (explicit) samples(policy.headPaddingMs, sampleRate) else 0,
                    if (explicit) samples(policy.tailPaddingMs, sampleRate) else tailPadSamples(length, sampleRate, false))
            }
            start = end
        }
        return out
    }

    /** Windows of [window] samples starting [overlap] before the previous end; a degenerate window covers everything. */
    private fun slide(total: Int, window: Int, overlap: Int): List<Span> {
        if (total <= 0) return emptyList()
        if (window <= 0 || window >= total) return listOf(Span(0, total))
        val stride = (window - overlap).takeIf { it > 0 } ?: window
        val out = ArrayList<Span>()
        var start = 0
        while (start < total) {
            if (start.toLong() + window >= total) { out += Span(start, total); break }
            out += Span(start, start + window)
            start += stride
        }
        return out
    }
}
