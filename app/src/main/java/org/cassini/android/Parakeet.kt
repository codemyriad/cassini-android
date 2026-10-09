package org.cassini.android

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import org.cassini.core.Failure
import org.cassini.core.asr.DecodePolicy
import org.cassini.core.asr.DecodeSession
import org.cassini.core.asr.IncrementalTranscript
import org.cassini.core.asr.Span
import org.cassini.core.asr.SpeechWindows
import org.cassini.core.asr.TokenWords
import org.cassini.core.asr.WordDecoder
import org.cassini.core.asr.WordGate
import org.cassini.core.audio.PcmAudio
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.format.TimedWord
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.requireUser

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
            val transcript = Transcript(DecodeSession(decoder, audio, policy).whole())
            onProgress(Progress(audio.durationMs, audio.durationMs, (System.nanoTime() - began) / 1_000_000, transcript.words))
            return transcript
        }
        // The clock starts once the model is loaded, so speed and remaining time describe decoding alone.
        val began = System.nanoTime()
        val session = DecodeSession(decoder, audio, policy)
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
        private val chunks = IncrementalTranscript(restored?.getJSONObject("chunks")?.let(CheckpointState::incremental)) { audio, startMs ->
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
        fun checkpoint() = org.json.JSONObject().put("inferenceMs", inferenceMs).put("chunks", CheckpointState.json(chunks.checkpoint()))
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
    internal class Decoder(models: ModelStore, private val ownsLease: Boolean = true) : WordDecoder, AutoCloseable {
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
        override fun decode(audio: PcmAudio, start: Int, length: Int, headPad: Int, tailPad: Int): List<TimedWord> {
            requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
            val stream = recognizer.createStream()
            try {
                // The decoder delivers the feature rate, so sherpa does not resample again.
                stream.acceptWaveform(if (headPad == 0 && tailPad == 0 && start == 0 && length == audio.samples.size) audio.samples
                    else FloatArray(headPad + length + tailPad).also { audio.samples.copyInto(it, headPad, start, start + length) }, audio.sampleRate)
                recognizer.decode(stream)
                val result = recognizer.getResult(stream)
                requireUser(result.text.isBlank() || result.tokens.isNotEmpty(), Failure.TIMINGS)
                return TokenWords.timedWordsFromTokens(result.tokens, result.timestamps, result.durations)
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
}
