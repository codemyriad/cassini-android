package org.cassini.core.asr

import org.cassini.core.audio.PcmAudio
import org.cassini.core.format.TimedWord
import org.cassini.core.format.Word

/** One recognizer call over [length] samples of [audio] from [start], between [headPad] and [tailPad] zero samples. */
fun interface WordDecoder {
    fun decode(audio: PcmAudio, start: Int, length: Int, headPad: Int, tailPad: Int): List<TimedWord>
}

/** The decodes of one recording on one recognizer. Fed span by span to report batch progress. */
class DecodeSession(private val decoder: WordDecoder, private val audio: PcmAudio, private val policy: DecodePolicy) {
    private val rate = audio.sampleRate
    private val total = audio.samples.size
    private var previousEnd = 0
    private var settled = emptyList<TimedWord>()
    /** The span being decoded: its windows so far, shown before the span is settled. */
    private var pending = emptyList<TimedWord>()
    val words: List<TimedWord> get() = settled + pending

    private fun ms(samples: Int) = SpeechWindows.floorMs(samples, rate)

    /** One decoder call over [length] recording samples from [start], between [headPad] and [tailPad] zeros. */
    private fun decode(start: Int, length: Int, headPad: Int = 0, tailPad: Int = 0) =
        decoder.decode(audio, start, length, headPad, tailPad)

    fun whole(): List<Word> = decode(0, total).map { it.word }

    /** The windows of one source span, each decoded, placed on the recording clock and reconciled at its seam. */
    private fun segment(span: Span, detected: Boolean, onDecoded: (Int) -> Unit = {}): List<TimedWord> {
        var merged = emptyList<TimedWord>()
        SpeechWindows.windows(span.length, rate, detected, policy).forEachIndexed { index, window ->
            val windowOffsetMs = ms(span.start) + ms(window.span.start)
            var decoded = emptyList<TimedWord>()
            for (chunk in window.chunks) {
                val placed = WordGate.offsetDecoderWords(decode(span.start + chunk.span.start, chunk.span.length, chunk.headPad, chunk.tailPad),
                    windowOffsetMs + ms(chunk.span.start - window.span.start), ms(chunk.headPad))
                // A final token can be stamped inside the synthetic tail: keep it at the real boundary for the gate to judge.
                decoded = decoded + WordGate.clampWordsToTimelineEnd(placed,
                    windowOffsetMs + ms(chunk.span.end - window.span.start), SpeechWindows.ceilMs(chunk.tailPad, rate))
                pending = SeamMerge.merge(merged, decoded, index == 0, windowOffsetMs, ms(window.overlap)) { it.word }
                onDecoded(span.start + chunk.span.end)
            }
            merged = SeamMerge.merge(merged, decoded, index == 0, windowOffsetMs, ms(window.overlap)) { it.word }
            pending = merged
            onDecoded(span.start + window.span.end)
        }
        return merged
    }

    /**
     * A span of speech. When the policy keeps spans whole, a long one is first cut at quiet points so no
     * decode runs long. Neighbouring pieces are decoded with recorded context past the cut and reconciled there.
     */
    fun speech(span: Span, onDecoded: (Int) -> Unit) {
        val pieces = if (policy.preserveSpan) SpeechWindows.quietPieces(audio.samples, span, rate) else listOf(span)
        if (pieces.size == 1) return piece(span, onDecoded)
        val context = SpeechWindows.samples(SpeechWindows.CUT_CONTEXT_MS, rate)
        var previous: Span? = null
        pieces.forEachIndexed { index, cut ->
            val source = Span(if (index == 0) cut.start else cut.start - context, if (index == pieces.lastIndex) cut.end else cut.end + context)
            val found = segment(source, true, onDecoded)
            pending = emptyList()
            settled = previous?.let {
                SeamMerge.splice(settled, found, ms(source.start), ms(it.end - source.start)) { timed -> timed.word }
            } ?: (settled + found)
            previous = source
            // Shows the joined words at once; until here the piece was listed after the words it overlaps.
            onDecoded(source.end)
        }
        previousEnd = span.end
        onDecoded(span.end)
    }

    /** One piece of speech, with recorded context when the policy asks for it. */
    private fun piece(span: Span, onDecoded: (Int) -> Unit) {
        var source = if (policy.contextMs > 0) SpeechWindows.context(span.start, span.end, total, rate, policy.contextMs) else span
        var found = segment(source, true, onDecoded)
        // A tight crop can make an utterance-normalised decode emit only blanks. Retry once with real context,
        // keeping only words that overlap the detected speech.
        if (found.isEmpty() && policy.preserveSpan) {
            val retry = SpeechWindows.context(span.start, span.end, total, rate, RETRY_CONTEXT_MS)
            if (retry.start < source.start || retry.end > source.end) {
                found = WordGate.wordsOverlappingSpeech(segment(retry, true), ms(span.start), ms(minOf(total, span.end)))
                source = retry
            }
        }
        pending = emptyList()
        settled = if (policy.contextMs > 0 && previousEnd > source.start) {
            SeamMerge.merge(settled, found, false, ms(source.start), ms(previousEnd - source.start)) { it.word }
        } else settled + found
        previousEnd = source.end
        onDecoded(span.end)
    }

    /** Dense audio without a detector: when two overlapping windows disagree entirely, each instant keeps one owner. */
    fun fixed(onDecoded: (Int) -> Unit) {
        var previous: Span? = null
        for (span in SpeechWindows.fixed(total, rate)) {
            val found = segment(span, false, onDecoded)
            pending = emptyList()
            settled = SeamMerge.merge(settled, found, previous == null, ms(span.start),
                ms(SpeechWindows.overlap(previous, span)), midpointOnDisagreement = true) { it.word }
            previous = span
            onDecoded(span.end)
        }
    }

    companion object {
        /** Recorded context for retrying a span that decoded to nothing: the detector's silence decision interval. */
        const val RETRY_CONTEXT_MS = 500
    }
}
