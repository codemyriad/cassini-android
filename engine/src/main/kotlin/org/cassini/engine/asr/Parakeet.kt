package org.cassini.engine.asr

import org.cassini.core.Failure
import org.cassini.core.asr.DecodePolicy
import org.cassini.core.asr.DecodeSession
import org.cassini.core.asr.Span
import org.cassini.core.asr.SpeechWindows
import org.cassini.core.asr.WordDecoder
import org.cassini.core.asr.WordGate
import org.cassini.core.audio.PcmAudio
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.requireUser
import org.cassini.engine.job.NativeBudget

/**
 * Parakeet decoding cut into pieces, as the desktop pipeline does on a stock sherpa runtime
 * (gocassini transcribe/stt.go), so progress and words exist before the whole recording is done.
 */
object Parakeet {
    /** How a recording is cut before decoding. */
    enum class Cutting(val provenance: String) {
        /** One decode of the whole recording: nothing is known until it returns. */
        WHOLE("whole recording"),
        /** The desktop's detector-free path: 15 s windows with 0.5 s overlap, reconciled at their seams. */
        FIXED("15 s windows, 0.5 s overlap, seams aligned by word"),
        /** No speech detector installed: the recording as one span, cut at its quiet points. */
        QUIET("pieces of 20 to 28 s cut at the quietest point with 1 s of recorded context, 0.5 s synthetic tail"),
        /** Speech spans found by the detector, each split by the [DecodePolicy]. */
        SPEECH("Silero VAD 0.18 speech spans, cut at the quietest point with 1 s of recorded context when longer than 28 s, 0.5 s synthetic tail"),
    }

    fun cutting(detectorModel: String?) = if (detectorModel == null) Cutting.QUIET else Cutting.SPEECH

    /** State after a decode: [doneMs] of [totalMs] of the recording, [elapsedMs] since decoding began, and the provisional words. */
    class Progress(val doneMs: Long, val totalMs: Long, val elapsedMs: Long, val words: List<Word>)

    /** A single decode's memory grows with its length; longer recordings are always cut. */
    const val WHOLE_MAX_MS = 60_000L
    private const val PROGRESS_INTERVAL_NS = 1_000_000_000L

    /**
     * Cuts by speech when [detectorModel] is given, otherwise at quiet points. [onProgress] runs on the
     * calling thread after each decode; its words are provisional, because seams can still drop a duplicate
     * and the energy gate runs once at the end. Loads and releases its own recognizer under [NativeBudget].
     */
    fun transcribe(audio: PcmAudio, models: ParakeetFiles, detectorModel: String?, policy: DecodePolicy = DecodePolicy.WHOLE_SPANS,
                   cutting: Cutting = cutting(detectorModel), onProgress: (Progress) -> Unit = {}): Transcript {
        requireUser(models.present(), Failure.MODEL)
        requireUser(!Thread.currentThread().isInterrupted, Failure.CANCELLED)
        return NativeBudget.holding {
            ParakeetDecoder(models).use { decoder -> transcribeWithDecoder(audio, decoder, detectorModel, policy, cutting, onProgress) }
        }
    }

    internal fun transcribeWithDecoder(audio: PcmAudio, decoder: WordDecoder, detectorModel: String?, policy: DecodePolicy,
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
            Cutting.SPEECH -> SileroDetector(requireNotNull(detectorModel)).use { detector ->
                paddedTailMs = detector.detect(audio) { span -> session.speech(span, report) }
            }
            Cutting.QUIET -> session.speech(Span(0, audio.samples.size), report)
            else -> session.fixed(report)
        }
        val transcript = Transcript(WordGate.finalizeTranscriptWords(audio, session.words, paddedTailMs))
        onProgress(Progress(audio.durationMs, audio.durationMs, (System.nanoTime() - began) / 1_000_000, transcript.words))
        return transcript
    }

    fun incrementalProvenance(detectorModel: String?) =
        "Incremental container decoding: 28 s PCM windows, 2 s recorded overlap, seams aligned by word; " + cutting(detectorModel).provenance
}
