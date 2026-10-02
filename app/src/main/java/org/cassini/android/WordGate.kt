package org.cassini.android

/**
 * What happens to decoded words before they become transcript words: they move onto the
 * recording clock, synthetic padding is clipped off, and the energy gate decides against the
 * speaker's own samples both whether a word is real and how far it reaches. Samples are at the
 * recording's own rate, so every duration here is converted with that rate, never with 16 kHz.
 */
internal object WordGate {
    /** -60 dBFS peak, -80 dBFS RMS and 5 ms at or above -66 dBFS: rejects silence and lone clicks, keeps quiet acknowledgements. */
    const val MIN_PEAK = 0.001f
    const val MIN_RMS = 0.0001
    const val MIN_ACTIVE = 0.0005f
    const val MIN_ACTIVE_MS = 5
    /** Parakeet has placed a word up to 180 ms before its PCM; the margins absorb that jitter. */
    const val PRE_MARGIN_MS = 100L
    const val POST_MARGIN_MS = 200L
    /** One 10 ms feature frame, twice [MIN_ACTIVE_MS], so a single sample cannot win or lose a window. */
    const val SCAN_WINDOW_MS = 10L
    /** A silence shorter than this is a closure inside the word; a longer one is the next turn's pause. */
    const val GAP_TOLERANCE_MS = POST_MARGIN_MS

    /** Removes decoder head padding, then puts words and their caps on the recording clock. */
    fun offsetDecoderWords(words: List<TimedWord>, offsetMs: Long, headPaddingMs: Long): List<TimedWord> = words.map { timed ->
        var (word, cap) = timed
        if (headPaddingMs > 0) {
            word = word.copy(startMs = maxOf(0, word.startMs - headPaddingMs), endMs = maxOf(0, word.endMs - headPaddingMs))
            cap = maxOf(0, cap - headPaddingMs)
        }
        TimedWord(word.copy(startMs = word.startMs + offsetMs, endMs = word.endMs + offsetMs), cap + offsetMs)
    }

    /** Strict overlap, so a word ending exactly at the span start belongs to the previous span, not this one. */
    fun wordsOverlappingSpeech(words: List<TimedWord>, startMs: Long, endMs: Long): List<TimedWord> =
        words.filter { it.word.startMs < endMs && it.word.endMs > startMs }

    /**
     * Clips words straddling the real PCM end. A word stamped at the end or inside the [paddedTailMs]
     * of synthetic padding becomes zero-length at the end, so the gate can judge the real audio before
     * it; one starting past the padding, or reversed, is dropped. Neither ends nor caps reach into padding.
     */
    fun clampWordsToTimelineEnd(words: List<TimedWord>, endMs: Long, paddedTailMs: Long): List<TimedWord> {
        val paddedEndMs = (endMs + maxOf(0, paddedTailMs)).let { if (it < endMs) Long.MAX_VALUE else it }
        return words.mapNotNull { (word, cap) ->
            if (word.endMs < word.startMs || word.startMs > paddedEndMs) return@mapNotNull null
            val clamped = when {
                word.startMs >= endMs -> word.copy(startMs = endMs, endMs = endMs)
                word.endMs > endMs -> word.copy(endMs = endMs)
                else -> word
            }
            TimedWord(clamped, minOf(cap, endMs))
        }
    }

    fun finalizeTranscriptWords(samples: FloatArray, sampleRate: Int, words: List<TimedWord>, audioEndMs: Long, paddedTailMs: Long): List<Word> =
        filterWordsByEnergy(samples, sampleRate, clampWordsToTimelineEnd(words, audioEndMs, paddedTailMs))

    fun finalizeTranscriptWords(audio: PcmAudio, words: List<TimedWord>, paddedTailMs: Long): List<Word> =
        finalizeTranscriptWords(audio.samples, audio.sampleRate, words, audio.durationMs, paddedTailMs)

    /**
     * Drops words whose margin-widened interval is silence or a click, and extends each kept word over
     * its speaker's continuing audio up to its cap, because Parakeet's 320 ms duration head stops a long
     * word's last token short of the sound. The cap is consumed here: kept words come back plain.
     * Without samples or a sample rate nothing can be measured, so words pass through unchanged.
     */
    fun filterWordsByEnergy(samples: FloatArray, sampleRate: Int, words: List<TimedWord>): List<Word> {
        if (words.isEmpty() || samples.isEmpty() || sampleRate <= 0) return words.map { it.word }
        val audioEndMs = samples.size.toLong() * 1000 / sampleRate
        val minimumActiveSamples = (sampleRate * MIN_ACTIVE_MS + 999) / 1000
        return words.mapNotNull { timed ->
            val word = timed.word
            if (word.endMs < 0 || word.endMs < word.startMs || word.startMs > audioEndMs) return@mapNotNull null
            var startMs = maxOf(0, word.startMs)
            var endMs = minOf(audioEndMs, word.endMs)
            startMs = if (startMs > PRE_MARGIN_MS) startMs - PRE_MARGIN_MS else 0
            endMs = if (endMs < audioEndMs - POST_MARGIN_MS) endMs + POST_MARGIN_MS else audioEndMs
            val start = (startMs * sampleRate / 1000).toInt()
            val end = minOf(samples.size.toLong(), (endMs * sampleRate + 999) / 1000).toInt()
            if (end <= start) return@mapNotNull null
            var peak = 0f
            var squareSum = 0.0
            var active = 0
            for (i in start until end) {
                val sample = samples[i]
                squareSum += sample.toDouble() * sample
                val magnitude = if (sample < 0) -sample else sample
                if (magnitude >= MIN_ACTIVE) active++
                if (magnitude > peak) peak = magnitude
            }
            val meanSquare = squareSum / (end - start)
            if (peak >= MIN_PEAK && meanSquare >= MIN_RMS * MIN_RMS && active >= minimumActiveSamples) {
                word.copy(endMs = wordEndOverContinuingAudio(samples, sampleRate, timed, audioEndMs))
            } else null
        }
    }

    /**
     * Walks [SCAN_WINDOW_MS] windows from the word's end towards its cap while its speaker's audio
     * continues, bridging gaps shorter than [GAP_TOLERANCE_MS]. The end lands on the last active
     * window: never past the cap, so never past the punctuation-inclusive end, and never before the
     * word's own end, so no speech is cut. Silence right after the word leaves its end alone.
     */
    fun wordEndOverContinuingAudio(samples: FloatArray, sampleRate: Int, timed: TimedWord, audioEndMs: Long): Long {
        val end = timed.word.endMs
        val capMs = minOf(timed.extentCapMs, audioEndMs)
        if (capMs <= end || sampleRate <= 0) return end
        val windowSamples = ((sampleRate * SCAN_WINDOW_MS + 999) / 1000).toInt()
        if (windowSamples <= 0) return end
        val minimumActiveSamples = (sampleRate * MIN_ACTIVE_MS + 999) / 1000
        var lastActiveMs = -1L
        var at = end
        while (at < capMs) {
            val start = at * sampleRate / 1000
            val stop = minOf(start + windowSamples, samples.size.toLong())
            if (start < 0 || start >= stop) break
            var active = 0
            for (i in start.toInt() until stop.toInt()) {
                val sample = samples[i]
                if ((if (sample < 0) -sample else sample) >= MIN_ACTIVE) active++
            }
            if (active >= minimumActiveSamples) {
                lastActiveMs = at + SCAN_WINDOW_MS
            } else if (at - (if (lastActiveMs < 0) end else lastActiveMs) >= GAP_TOLERANCE_MS) {
                break
            }
            at += SCAN_WINDOW_MS
        }
        if (lastActiveMs < 0) return end
        return lastActiveMs.coerceAtMost(capMs).coerceAtLeast(end)
    }
}
