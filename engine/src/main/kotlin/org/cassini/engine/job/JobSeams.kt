package org.cassini.engine.job

import org.cassini.core.audio.PcmAudio
import org.cassini.core.audio.PcmSource
import org.cassini.core.checkpoint.DiarizerState
import org.cassini.core.checkpoint.EncoderState
import org.cassini.core.checkpoint.RecognitionState
import org.cassini.core.format.Transcript
import org.cassini.core.speakers.PrintWindow
import org.cassini.core.speakers.SpeakerTurn
import org.cassini.engine.asr.Parakeet
import java.io.File

// The native seams of TranscriptionJob (architecture section 2). Each interface covers what the job uses of one
// native class, so JVM tests drive the job with fakes; JobAdapters.kt has the thin adapters over the real classes.
// Every method runs on the inference thread (InferenceDispatcher) unless its KDoc says otherwise, and blocks.
// A method that sees the thread interrupted ends in UserFacingException(Failure.CANCELLED), as the native classes do.

/**
 * Incremental speech recognition over 28 s windows, as `IncrementalRecognizer`. The caller holds [NativeBudget] from
 * [RecognizerFactory.open] until after [close]. The model loads with the first window.
 */
interface Recognizer : AutoCloseable {
    /** The recording's length when known (0 = unknown); progress reports use it. Set by the decode loop. */
    var totalMs: Long

    /** Milliseconds spent in recognition, the restored amount included (`x-inferenceMs`). */
    val inferenceMs: Long

    /** Samples accepted so far, the restored ones included: where a resumed decoder starts. */
    val positionSamples: Long

    /** Recording time covered by decoded windows. */
    val doneMs: Long

    /** How the recording is cut, for the `x-segmentation` provenance. */
    val segmentation: String

    /** The BCP-47 tag of the language this run recognizes (D53.2): [Transcript.UNDETERMINED] for Parakeet. */
    val language: String

    /** Takes [count] borrowed 16 kHz samples; decodes every window they complete, calling the [Callbacks]. */
    fun accept(samples: FloatArray, count: Int)

    /** Decodes the last partial window and returns the merged transcript of the whole [durationMs], in [language]. */
    fun finish(durationMs: Long): Transcript

    /** The `recognition` member of checkpoint.json for the windows decoded so far. */
    fun checkpoint(): RecognitionState

    /** Releases the native recognizer. Safe to call twice. */
    override fun close()

    /**
     * What a recognizer reports, on the inference thread. [onChunk] receives each decoded window (its audio, its start
     * on the recording clock and its words) and returns the words to keep, with speakers. [onStage] reports a window's
     * span and whether the model is still loading for it. [onProgress] follows each window with every word so far.
     */
    class Callbacks(
        val onChunk: (PcmAudio, Long, Transcript) -> Transcript,
        val onStage: (Long, Long, Boolean) -> Unit,
        val onProgress: (Parakeet.Progress) -> Unit,
    )
}

/** Opens a [Recognizer] over the installed Parakeet (and the speech detector, when installed). */
fun interface RecognizerFactory {
    /** [restored] continues a checkpoint's windows. The caller already holds [NativeBudget]. */
    fun open(restored: RecognitionState?, callbacks: Recognizer.Callbacks): Recognizer
}

/**
 * Streaming Sortformer diarization, as `SortformerStream`: one instance keeps speaker ids across windows. The caller
 * holds [NativeBudget] from [DiarizerFactory.open] until after [close].
 */
interface StreamingDiarizer : AutoCloseable {
    /** Every turn found so far, on the recording clock. */
    val turns: List<SpeakerTurn>

    /** Milliseconds spent in native diarization (`SpeakerAttribution.record`). */
    val elapsedMs: Long

    /** Turns in one 16 kHz window of at most 28 s starting at [startMs]; times are relative to the window. */
    fun process(audio: PcmAudio, startMs: Long): List<SpeakerTurn>

    /** The `diarizer` member of checkpoint.json. */
    fun checkpoint(): DiarizerState

    override fun close()
}

/** Opens a [StreamingDiarizer] over the installed diarizer model. */
fun interface DiarizerFactory {
    /** [restored] continues a checkpoint's speaker cache and turns. The caller already holds [NativeBudget]. */
    fun open(restored: DiarizerState?): StreamingDiarizer
}

/**
 * CAM++ voiceprints, as the `VoiceprintExtractor` object. Each call loads and releases the model; the caller holds
 * [NativeBudget] around it.
 */
interface PrintExtractor {
    /**
     * The L2-normalised print and the seconds of speech behind it, per speaker, from the [windows] of [audio]. A
     * speaker without usable speech has no entry. [active] is called between native calls and throws to stop.
     */
    fun compute(
        audio: PcmSource,
        windows: Map<String, List<PrintWindow>>,
        active: () -> Unit,
    ): Map<String, ExtractedPrint>
}

/** One speaker's L2-normalised print and the seconds of speech behind it, as `VoiceprintExtractor` returns them. */
typealias ExtractedPrint = Pair<FloatArray, Double>

/** Decodes a note's audio file to 16 kHz mono, as `AudioDecoder.stream`. */
interface AudioSource {
    /**
     * Streams [file] from [startSample] on: [onSamples] receives borrowed blocks (consume or copy them before
     * returning) and [onProgress] the source milliseconds read and the duration when the container tells it. Returns
     * the decoded duration in milliseconds. Throws UserFacingException for unreadable, empty or too long audio.
     */
    fun stream(
        file: File,
        startSample: Long,
        onSamples: (FloatArray, Int) -> Unit,
        onProgress: (Long, Long?) -> Unit,
    ): Long
}

/**
 * The 24 kb/s libopus encoder of a non-Opus source, as `StreamingOpusEncoder`, writing `work/audio.opus.part`. Not
 * native-budgeted (libopus is small).
 */
interface OpusSink : AutoCloseable {
    fun accept(samples: FloatArray, count: Int)

    /** Flushes and fsyncs the file, then returns the `encoder` member of checkpoint.json. */
    fun checkpoint(): EncoderState

    /** Pads the last frame, ends the Ogg stream and fsyncs. Throws Failure.EMPTY without samples. */
    fun finish()

    override fun close()
}

/** Opens an [OpusSink]. */
fun interface OpusSinkFactory {
    /** A fresh stream into [file], or the checkpointed one when [restored] is set (the file is truncated to it). */
    fun open(file: File, restored: EncoderState?): OpusSink
}
