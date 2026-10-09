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

    // A cancelled native call finishes before another screen loads a second copy of the model.
    private val decoderLease = NativeInference.lease

    /** Recorded context for retrying a span that decoded to nothing: the detector's silence decision interval. */
    private const val RETRY_CONTEXT_MS = 500
    /** A single decode's memory grows with its length; longer recordings are always cut. */
    internal const val WHOLE_MAX_MS = 60_000L
    private const val PROGRESS_INTERVAL_NS = 1_000_000_000L

    /**
     * Cuts by speech when [detectorModel] is given, otherwise at quiet points. [onProgress] runs on the
     * calling thread after each decode; its words are provisional, because seams can still drop a duplicate
     * and the energy gate runs once at the end.
     */
    internal fun transcribe(audio: PcmAudio, models: ModelStore, detectorModel: String?, policy: DecodePolicy = DecodePolicy.WHOLE_SPANS,
                            cutting: Cutting = cutting(detectorModel), onProgress: (Progress) -> Unit = {}): Transcript {
        requireUser(models.ready(), Failure.MODEL)
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        return Decoder(models).use { decoder -> transcribeWithDecoder(audio, decoder, detectorModel, policy, cutting, onProgress) }
    }

    private fun transcribeWithDecoder(audio: PcmAudio, decoder: Decoder, detectorModel: String?, policy: DecodePolicy,
                                     cutting: Cutting, onProgress: (Progress) -> Unit): Transcript {
        if (cutting == Cutting.WHOLE) {
            requireUser(audio.durationMs <= WHOLE_MAX_MS, Failure.LONG)
            val began = System.nanoTime()
            val transcript = Transcript(Session(decoder, audio, policy).whole())
            onProgress(Progress(audio.durationMs, audio.durationMs, (System.nanoTime() - began) / 1_000_000, transcript.words))
            return transcript
        }
        // The clock starts once the model is loaded, so speed and remaining time describe decoding alone.
        val began = System.nanoTime()
        val session = Session(decoder, audio, policy)
        // Recorded context makes a decode end past its speech span, so the position only ever moves forward.
        var reached = 0
        var reported = 0L
        val report = { done: Int ->
            reached = maxOf(reached, minOf(done, audio.samples.size))
            val now = System.nanoTime()
            // Each report copies every word so far: at most one a second keeps a long recording linear.
            if (reported == 0L || now - reported >= PROGRESS_INTERVAL_NS) {
                reported = now
                onProgress(Progress(SpeechWindows.floorMs(reached, audio.sampleRate), audio.durationMs,
                    (now - began) / 1_000_000, session.words.map { it.word }))
            }
        }
        report(0)
        var paddedTailMs = 0L
        when (cutting) {
            Cutting.SPEECH -> SpeechDetector(requireNotNull(detectorModel)).use { detector ->
                paddedTailMs = detector.detect(audio) { span -> session.speech(span, report) }
            }
            Cutting.QUIET -> session.speech(Span(0, audio.samples.size), report)
            else -> session.fixed(report)
        }
        val transcript = Transcript(WordGate.finalizeTranscriptWords(audio, session.words, paddedTailMs))
        onProgress(Progress(audio.durationMs, audio.durationMs, (System.nanoTime() - began) / 1_000_000, transcript.words))
        return transcript
    }

    /** One recognizer reused while the container decoder supplies short PCM blocks. */
    internal class Incremental(private val models: ModelStore, private val detectorModel: String?,
                               private val leaseHeld: Boolean = false,
                               restored: org.json.JSONObject? = null,
                               private val onChunk: (PcmAudio, Long, Transcript) -> Transcript = { _, _, transcript -> transcript },
                               private val onStage: (Long, Long, Boolean) -> Unit = { _, _, _ -> },
                               private val onProgress: (Progress) -> Unit) : AutoCloseable {
        private var decoder: Decoder? = null
        private val began = System.nanoTime()
        var totalMs = 0L
        var inferenceMs = restored?.optLong("inferenceMs") ?: 0L
            private set
        private val chunks = IncrementalTranscript(restored?.getJSONObject("chunks")) { audio, startMs ->
            onStage(startMs, startMs + audio.durationMs, decoder == null)
            val recognizer = decoder ?: Decoder(models, ownsLease = !leaseHeld).also { decoder = it }
            onStage(startMs, startMs + audio.durationMs, false)
            val started = System.nanoTime()
            val transcript = transcribeWithDecoder(audio, recognizer, detectorModel, DecodePolicy.WHOLE_SPANS, cutting(detectorModel)) {}
            inferenceMs += (System.nanoTime() - started) / 1_000_000
            onChunk(audio, startMs, transcript)
        }

        val positionSamples get() = chunks.positionSamples
        val doneMs get() = chunks.doneMs
        fun checkpoint() = org.json.JSONObject().put("inferenceMs", inferenceMs).put("chunks", chunks.checkpoint())
        fun accept(samples: FloatArray, count: Int) {
            chunks.accept(samples, count) { doneMs, words ->
                onProgress(Progress(doneMs, totalMs.takeIf { it > 0 }?.let { maxOf(it, doneMs) } ?: 0, (System.nanoTime() - began) / 1_000_000, words))
            }
        }

        fun finish(durationMs: Long): Transcript {
            val transcript = chunks.finish()
            onProgress(Progress(durationMs, durationMs, (System.nanoTime() - began) / 1_000_000, transcript.words))
            return transcript
        }

        override fun close() { decoder?.close() }
    }

    internal fun incrementalProvenance(detectorModel: String?) =
        "Incremental container decoding: 28 s PCM windows, 2 s recorded overlap, seams aligned by word; " + cutting(detectorModel).provenance

    /** One native recognizer, owned and closed by the decoding thread. */
    internal class Decoder(models: ModelStore, private val ownsLease: Boolean = true) : AutoCloseable {
        private val recognizer: OfflineRecognizer
        private var closed = false
        init {
            if (ownsLease) NativeInference.acquire()
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
                if (ownsLease) decoderLease.release()
                throw error
            }
        }
        fun decode(audio: PcmAudio, start: Int = 0, length: Int = audio.samples.size, headPad: Int = 0, tailPad: Int = 0): List<TimedWord> {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val stream = recognizer.createStream()
            try {
                // The decoder delivers the feature rate, so sherpa does not resample again.
                stream.acceptWaveform(if (headPad == 0 && tailPad == 0 && start == 0 && length == audio.samples.size) audio.samples
                    else FloatArray(headPad + length + tailPad).also { audio.samples.copyInto(it, headPad, start, start + length) }, audio.sampleRate)
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
            try { recognizer.release() } finally { if (ownsLease) decoderLease.release() }
        }
    }

    /** The decodes of one recording on one recognizer. Fed span by span to report batch progress. */
    private class Session(private val decoder: Decoder, private val audio: PcmAudio, private val policy: DecodePolicy) {
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
    }
}
