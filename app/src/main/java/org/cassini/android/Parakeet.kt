package org.cassini.android

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

/**
 * Parakeet decoding cut into pieces, as the desktop pipeline does on a stock sherpa runtime
 * (gocassini transcribe/stt.go), so progress and words exist before the whole recording is done.
 */
object Parakeet {
    /** How a recording is cut before decoding. */
    internal enum class Cutting(val provenance: String) {
        /** One decode of the whole recording: nothing is known until it returns. */
        WHOLE("whole recording"),
        /** The desktop's detector-free path: 15 s windows with 0.5 s overlap, reconciled at their seams. */
        FIXED("15 s windows, 0.5 s overlap, seams aligned by word"),
        /** No speech detector installed: the recording as one span, cut at its quiet points. */
        QUIET("pieces of 20 to 28 s cut at the quietest point with 1 s of recorded context, 0.5 s synthetic tail"),
        /** Speech spans found by the detector, each split by the [DecodePolicy]. */
        SPEECH("Silero VAD 0.18 speech spans, cut at the quietest point with 1 s of recorded context when longer than 28 s, 0.5 s synthetic tail"),
    }

    internal fun cutting(detectorModel: String?) = if (detectorModel == null) Cutting.QUIET else Cutting.SPEECH

    /** State after a decode: [doneMs] of [totalMs] of the recording, [elapsedMs] since decoding began, and the provisional words. */
    internal class Progress(val doneMs: Long, val totalMs: Long, val elapsedMs: Long, val words: List<Word>)

    /** What turns samples into timed words, so the cutting can be checked on the JVM with a fake. */
    internal interface Recognizer {
        fun decode(samples: FloatArray, sampleRate: Int): List<TimedWord>
    }

    // A cancelled native call finishes before another screen loads a second copy of the model.
    private val decoderLease = NativeInference.lease

    /** Recorded context for retrying a span that decoded to nothing: the detector's silence decision interval. */
    private const val RETRY_CONTEXT_MS = 500
    /** A single decode's memory grows with its length; longer recordings are always cut. */
    internal const val WHOLE_MAX_MS = 60_000L
    private const val PROGRESS_INTERVAL_NS = 1_000_000_000L
    /** Recorded audio decoded again before a resume point, so the first new decode hears the words it joins. */
    internal const val RESUME_OVERLAP_MS = 1_000

    /**
     * Cuts by speech when [detectorModel] is given, otherwise at quiet points. [onProgress] runs on the
     * calling thread after each decode; its words are provisional, because seams can still drop a duplicate
     * and the energy gate runs once at the end.
     */
    internal fun transcribe(audio: PcmAudio, models: ModelStore, detectorModel: String?, policy: DecodePolicy = DecodePolicy.WHOLE_SPANS,
                            cutting: Cutting = cutting(detectorModel), onProgress: (Progress) -> Unit = {}): Transcript =
        transcribe(ArraySource(audio), models, detectorModel, policy, cutting, onProgress = onProgress)

    /**
     * [transcribe] over a [source] that may still be arriving. [resume] continues a journaled run:
     * its words are kept and decoding starts [RESUME_OVERLAP_MS] before its end, joined at that seam.
     * [onSettled] receives words in order, each once, as soon as no later decode can change them,
     * with the sample up to which everything is final.
     */
    internal fun transcribe(source: AudioSource, models: ModelStore, detectorModel: String?, policy: DecodePolicy = DecodePolicy.WHOLE_SPANS,
                            cutting: Cutting = cutting(detectorModel), resume: TranscriptJournal.State? = null,
                            onSettled: (List<TimedWord>, Long) -> Unit = { _, _ -> }, onProgress: (Progress) -> Unit = {}): Transcript {
        requireUser(models.ready(), Failure.MODEL)
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        val decoder = Decoder(models)
        try {
            val detector = if (cutting == Cutting.SPEECH) SpeechDetector(requireNotNull(detectorModel)) else null
            try {
                return run(source, decoder, detector, policy, cutting, resume, onSettled, onProgress)
            } finally { detector?.close() }
        } finally {
            decoder.close()
        }
    }

    /** The cutting itself, over any [recognizer] and speech [detector]. */
    internal fun run(source: AudioSource, recognizer: Recognizer, detector: SpeechDetector?, policy: DecodePolicy, cutting: Cutting,
                     resume: TranscriptJournal.State?, onSettled: (List<TimedWord>, Long) -> Unit, onProgress: (Progress) -> Unit): Transcript =
        runCutting(source, recognizer, detector?.let { d -> { s: AudioSource, from: Int, onSpan: (Span) -> Unit ->
            if (s is ArraySource && s.sampleRate != VAD_SAMPLE_RATE) { require(from == 0); d.detect(s.audio, onSpan) } else d.detect(s, from, onSpan)
        } }, policy, cutting, resume, onSettled, onProgress)

    internal fun runCutting(source: AudioSource, recognizer: Recognizer, detect: ((AudioSource, Int, (Span) -> Unit) -> Long)?, policy: DecodePolicy,
                     cutting: Cutting, resume: TranscriptJournal.State?, onSettled: (List<TimedWord>, Long) -> Unit,
                     onProgress: (Progress) -> Unit): Transcript {
        val rate = source.sampleRate
        val totalMs = { SpeechWindows.floorMs(if (source.ended) source.size() else source.expectedSize, rate) }
        if (cutting == Cutting.WHOLE) {
            val size = source.size()
            requireUser(size.toLong() * 1000 / rate <= WHOLE_MAX_MS, Failure.LONG)
            val began = System.nanoTime()
            val transcript = Transcript(Session(recognizer, source, policy, null) { _, _ -> }.whole())
            onProgress(Progress(totalMs(), totalMs(), (System.nanoTime() - began) / 1_000_000, transcript.words))
            return transcript
        }
        // A fixed grid and a single decode have no seam a resume could join at.
        val continued = resume?.takeIf { cutting != Cutting.FIXED && it.settledEnd > 0 }
        // The clock starts once the model is loaded, so speed and remaining time describe decoding alone.
        val began = System.nanoTime()
        val session = Session(recognizer, source, policy, continued, onSettled)
        // Recorded context makes a decode end past its speech span, so the position only ever moves forward.
        var reached = continued?.settledEnd?.toInt() ?: 0
        var reported = 0L
        val report = { done: Int ->
            reached = maxOf(reached, done)
            val now = System.nanoTime()
            // Each report copies every word so far: at most one a second keeps a long recording linear.
            if (reported == 0L || now - reported >= PROGRESS_INTERVAL_NS) {
                reported = now
                onProgress(Progress(SpeechWindows.floorMs(reached, rate).coerceAtMost(totalMs()), totalMs(),
                    (now - began) / 1_000_000, session.words.map { it.word }))
            }
        }
        report(reached)
        val from = continued?.let { state ->
            val back = state.settledEnd - SpeechWindows.samples(RESUME_OVERLAP_MS, rate)
            (back / SpeechDetector.WINDOW * SpeechDetector.WINDOW).coerceAtLeast(0).toInt()
        } ?: 0
        val after = continued?.settledEnd ?: 0
        var paddedTailMs = 0L
        when (cutting) {
            Cutting.SPEECH -> paddedTailMs = requireNotNull(detect)(source, from) { span ->
                // Spans the journal already holds whole are skipped; one across its end is decoded and joined.
                if (span.end > after) session.speech(span, report)
            }
            Cutting.QUIET -> session.quiet(from, report)
            else -> session.fixed(report)
        }
        val size = source.size()
        session.settleAll(size)
        val transcript = Transcript(WordGate.finalizeTranscriptWords(
            if (source is ArraySource) ArrayReader(source.audio.samples) else BlockReader(source, size), rate, session.words, paddedTailMs))
        onProgress(Progress(totalMs(), totalMs(), (System.nanoTime() - began) / 1_000_000, transcript.words))
        return transcript
    }

    /** One native recognizer, owned and closed by the decoding thread. */
    internal class Decoder(models: ModelStore) : Recognizer, AutoCloseable {
        private val recognizer: OfflineRecognizer
        private var closed = false
        init {
            NativeInference.acquire()
            try {
                requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
                recognizer = OfflineRecognizer(config = OfflineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 128, dither = 0f),
                    modelConfig = OfflineModelConfig(
                        transducer = OfflineTransducerModelConfig(
                            encoder = models.modelPath("encoder"),
                            decoder = models.modelPath("decoder"),
                            joiner = models.modelPath("joiner"),
                        ),
                        tokens = models.path("tokens.txt"),
                        modelType = "nemo_transducer",
                        provider = "cpu", numThreads = 2,
                    ),
                    decodingMethod = "greedy_search",
                ))
            } catch (error: Throwable) {
                decoderLease.release()
                throw error
            }
        }
        override fun decode(samples: FloatArray, sampleRate: Int): List<TimedWord> {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val stream = recognizer.createStream()
            try {
                // The decoder delivers the feature rate, so sherpa does not resample again.
                stream.acceptWaveform(samples, sampleRate)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                requireUser(result.text.isBlank() || result.tokens.isNotEmpty(), Failure.TIMINGS)
                return Transcript.timedWordsFromTokens(result.tokens, result.timestamps, result.durations)
            } finally {
                stream.release()
            }
        }

        override fun close() {
            if (closed) return
            closed = true
            try { recognizer.release() } finally { decoderLease.release() }
        }
    }

    /**
     * The decodes of one recording on one recognizer. Fed span by span to report batch progress. Words
     * reach [onSettled] once a margin behind the last decode, where no later seam can touch them.
     */
    private class Session(private val recognizer: Recognizer, private val source: AudioSource, private val policy: DecodePolicy,
                          resume: TranscriptJournal.State?, private val onSettled: (List<TimedWord>, Long) -> Unit) {
        private val rate = source.sampleRate
        private var previousEnd = resume?.settledEnd?.toInt() ?: 0
        private var settled: List<TimedWord> = resume?.words ?: emptyList()
        /** The span being decoded: its windows so far, shown before the span is settled. */
        private var pending = emptyList<TimedWord>()
        val words: List<TimedWord> get() = settled + pending
        private var emitted = settled.size
        private var lastEmitted = settled.lastOrNull()
        private var finalEnd = resume?.settledEnd ?: 0L
        /** A later decode starts at most this far before the end of the last one, and a seam moves a word at most the tolerance. */
        private val margin = SpeechWindows.samples(2 * SpeechWindows.CUT_CONTEXT_MS + 2 * policy.contextMs, rate) +
            SpeechWindows.samples(SeamMerge.DUPLICATE_TOLERANCE_MS.toInt(), rate)

        private fun ms(samples: Int) = SpeechWindows.floorMs(samples, rate)

        /** One decoder call over [length] recording samples from [start], between [headPad] and [tailPad] zeros. */
        private fun decode(start: Int, length: Int, headPad: Int = 0, tailPad: Int = 0): List<TimedWord> {
            source.await(start + length)
            val samples = FloatArray(headPad + length + tailPad)
            source.read(start, length, samples, headPad)
            return recognizer.decode(samples, rate)
        }

        fun whole(): List<Word> = decode(0, source.size()).map { it.word }

        /** Hands on the words that end before [decodedEnd] less the margin; the final call passes everything. */
        private fun settle(decodedEnd: Int, all: Boolean = false) {
            // A seam never reaches this far back, but if it ever did, continue after the last word handed on.
            val last = lastEmitted
            if (emitted > 0 && last != null && settled.getOrNull(emitted - 1) != last) {
                val at = settled.lastIndexOf(last)
                emitted = if (at >= 0) at + 1 else settled.count { it.word.startMs <= last.word.startMs }
            }
            val stableMs = ms(decodedEnd - margin)
            var end = emitted
            while (end < settled.size && (all || settled[end].word.endMs <= stableMs)) end++
            val finalTo = if (all) decodedEnd.toLong() else minOf(decodedEnd - margin.toLong(),
                settled.getOrNull(end)?.word?.startMs?.let { it * rate / 1000 } ?: Long.MAX_VALUE)
            if (end > emitted || all) {
                finalEnd = maxOf(finalEnd, finalTo)
                onSettled(settled.subList(emitted, end).toList(), finalEnd)
                emitted = end
                lastEmitted = settled.getOrNull(end - 1)
            }
        }

        fun settleAll(total: Int) = settle(total, all = true)

        /**
         * Joins a first decode to words settled before it: only a resume overlaps them. Words already handed
         * on are final, so a decode never replaces them: of its words, only those centred after them are added.
         */
        private fun join(found: List<TimedWord>, start: Int): List<TimedWord> {
            if (previousEnd <= start) return settled + found
            val final = settled.subList(0, emitted.coerceAtMost(settled.size))
            val open = settled.subList(final.size, settled.size)
            val finalEndMs = if (final.isEmpty()) Long.MIN_VALUE else maxOf(ms(finalEnd.toInt()), final.last().word.endMs)
            val fresh = found.filter { (it.word.startMs + it.word.endMs) / 2 >= finalEndMs }
            return final + if (open.isEmpty()) fresh else SeamMerge.merge(open, fresh, false, ms(start), ms(previousEnd - start)) { it.word }
        }

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
        fun speech(span: Span, onDecoded: (Int) -> Unit) =
            pieces(if (policy.preserveSpan) SpeechWindows.quietPieces(source, span.start, span.end) else sequenceOf(span), onDecoded)

        /** No detector: everything from [from] to the end of the input, as it arrives, cut at its quiet points. */
        fun quiet(from: Int, onDecoded: (Int) -> Unit) = pieces(SpeechWindows.quietPieces(source, from, null), onDecoded)

        private fun pieces(cuts: Sequence<Span>, onDecoded: (Int) -> Unit) {
            val iterator = cuts.iterator()
            if (!iterator.hasNext()) return
            var cut = iterator.next()
            // Peek one ahead: only the last piece goes without recorded context past its end.
            var next = if (iterator.hasNext()) iterator.next() else null
            if (next == null) return piece(cut, onDecoded)
            val context = SpeechWindows.samples(SpeechWindows.CUT_CONTEXT_MS, rate)
            var previous: Span? = null
            while (true) {
                val source = Span(if (previous == null) cut.start else cut.start - context, if (next == null) cut.end else cut.end + context)
                val found = segment(source, true, onDecoded)
                pending = emptyList()
                settled = previous?.let {
                    SeamMerge.splice(settled, found, ms(source.start), ms(it.end - source.start)) { timed -> timed.word }
                } ?: join(found, source.start)
                previous = source
                settle(source.end)
                // Shows the joined words at once; until here the piece was listed after the words it overlaps.
                onDecoded(source.end)
                cut = next ?: break
                next = if (iterator.hasNext()) iterator.next() else null
            }
            previousEnd = cut.end
            onDecoded(cut.end)
        }

        /** One piece of speech, with recorded context when the policy asks for it. */
        private fun piece(span: Span, onDecoded: (Int) -> Unit) {
            var source = if (policy.contextMs > 0) context(span, policy.contextMs) else span
            var found = segment(source, true, onDecoded)
            // A tight crop can make an utterance-normalised decode emit only blanks. Retry once with real context,
            // keeping only words that overlap the detected speech.
            if (found.isEmpty() && policy.preserveSpan) {
                val retry = context(span, RETRY_CONTEXT_MS)
                if (retry.start < source.start || retry.end > source.end) {
                    found = WordGate.wordsOverlappingSpeech(segment(retry, true), ms(span.start), ms(minOf(retry.end, span.end)))
                    source = retry
                }
            }
            pending = emptyList()
            settled = join(found, source.start)
            previousEnd = source.end
            settle(source.end)
            onDecoded(span.end)
        }

        /** [span] widened by [contextMs] of real recording on each side, clipped to what the input holds. */
        private fun context(span: Span, contextMs: Int): Span {
            val wanted = span.end + SpeechWindows.samples(contextMs, rate)
            return SpeechWindows.context(span.start, span.end, source.await(wanted), rate, contextMs)
        }

        /** Dense audio without a detector: when two overlapping windows disagree entirely, each instant keeps one owner. */
        fun fixed(onDecoded: (Int) -> Unit) {
            var previous: Span? = null
            for (span in SpeechWindows.fixed(source.size(), rate)) {
                val found = segment(span, false, onDecoded)
                pending = emptyList()
                settled = SeamMerge.merge(settled, found, previous == null, ms(span.start),
                    ms(SpeechWindows.overlap(previous, span)), midpointOnDisagreement = true) { it.word }
                previous = span
                settle(span.end)
                onDecoded(span.end)
            }
        }
    }
}
