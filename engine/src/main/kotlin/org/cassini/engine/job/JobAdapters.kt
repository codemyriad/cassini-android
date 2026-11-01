package org.cassini.engine.job

import android.net.Uri
import org.cassini.core.audio.PcmAudio
import org.cassini.core.audio.PcmSource
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.checkpoint.DiarizerState
import org.cassini.core.checkpoint.EncoderState
import org.cassini.core.checkpoint.RecognitionState
import org.cassini.core.format.Transcript
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.PrintWindow
import org.cassini.core.speakers.SpeakerTurn
import org.cassini.engine.asr.IncrementalRecognizer
import org.cassini.engine.asr.Parakeet
import org.cassini.engine.asr.ParakeetFiles
import org.cassini.engine.decode.AudioDecoder
import org.cassini.engine.opus.StreamingOpusEncoder
import org.cassini.engine.speakers.SortformerStream
import org.cassini.engine.speakers.VoiceprintExtractor
import java.io.File

// Thin adapters from the job seams (JobSeams.kt) to the native classes. They convert between the typed checkpoint
// members of :core and the JSON the native classes read and write, and add nothing else. :app builds them in AppGraph
// from the installed model paths; JVM tests use fakes instead.

/**
 * [Recognizer]s over `IncrementalRecognizer`. [parakeet] and [detector] are read at each [open], so a detector
 * installed by the job's own download is used; [detector] returns null when the speech detector is not installed.
 */
class ParakeetRecognizers(
    private val parakeet: () -> ParakeetFiles,
    private val detector: () -> String?,
) : RecognizerFactory {
    override fun open(restored: RecognitionState?, callbacks: Recognizer.Callbacks): Recognizer {
        val detectorModel = detector()
        val recognizer = IncrementalRecognizer(
            parakeet(),
            detectorModel,
            restored,
            callbacks.onChunk,
            callbacks.onStage,
            callbacks.onProgress,
        )
        return IncrementalRecognizerSeam(recognizer, Parakeet.incrementalProvenance(detectorModel))
    }
}

private class IncrementalRecognizerSeam(
    private val recognizer: IncrementalRecognizer,
    override val segmentation: String,
) : Recognizer {
    override var totalMs: Long
        get() = recognizer.totalMs
        set(value) {
            recognizer.totalMs = value
        }
    override val inferenceMs: Long get() = recognizer.inferenceMs
    override val positionSamples: Long get() = recognizer.positionSamples
    override val doneMs: Long get() = recognizer.doneMs

    /** Parakeet recognizes 25 languages without saying which one it heard. */
    override val language: String = Transcript.UNDETERMINED

    override fun accept(samples: FloatArray, count: Int) = recognizer.accept(samples, count)

    override fun finish(durationMs: Long): Transcript = recognizer.finish(durationMs).copy(language = language)

    override fun checkpoint(): RecognitionState = recognizer.checkpoint()

    override fun close() = recognizer.close()
}

/** [StreamingDiarizer]s over `SortformerStream`, with the installed diarizer model at [model]. */
class SortformerDiarizers(private val model: () -> String) : DiarizerFactory {
    override fun open(restored: DiarizerState?): StreamingDiarizer =
        SortformerSeam(SortformerStream(model(), restored?.let(CheckpointState::json)))
}

private class SortformerSeam(private val stream: SortformerStream) : StreamingDiarizer {
    override val turns: List<SpeakerTurn> get() = stream.turns
    override val elapsedMs: Long get() = stream.elapsedMs

    override fun process(audio: PcmAudio, startMs: Long): List<SpeakerTurn> = stream.process(audio, startMs)

    override fun checkpoint(): DiarizerState = CheckpointState.diarizer(stream.checkpoint())

    override fun close() = stream.close()
}

/**
 * [PrintExtractor] over the `VoiceprintExtractor` object with the installed CAM++ model at [model]. Every print it
 * returns has [DIMENSION] values; a print of another length (another model file) fails the call with an
 * IllegalStateException, which the job treats as "no names" (best effort). [extract] stands in for the native call in
 * tests.
 */
class VoiceprintPrints(
    private val model: () -> File,
    private val extract: (File, PcmSource, Map<String, List<PrintWindow>>, () -> Unit) -> Map<String, ExtractedPrint> =
        { file, audio, windows, active -> VoiceprintExtractor.compute(file, audio, windows, active) },
) : PrintExtractor {
    override fun compute(
        audio: PcmSource,
        windows: Map<String, List<PrintWindow>>,
        active: () -> Unit,
    ): Map<String, ExtractedPrint> {
        val prints = extract(model(), audio, windows, active)
        prints.forEach { (speaker, print) ->
            val size = print.first.size
            check(size == DIMENSION) { "Voiceprint of $speaker has $size values, expected $DIMENSION" }
        }
        return prints
    }

    companion object {
        /**
         * The length of a print of `3dspeaker_speech_campplus_sv_en_voxceleb_16k` ([ModelCatalog.voiceprintModel]):
         * the CAM++ embedding layer has 512 outputs. Saved voices store it as `dim`; `ModelCatalog.VOICEPRINT_DIM`
         * must agree (VoiceprintDimensionTest).
         */
        const val DIMENSION = 512
    }
}

/** [AudioSource] over `AudioDecoder.stream` for files in app storage (a file uri is read in place). */
class DecoderAudioSource(private val decoder: AudioDecoder) : AudioSource {
    override fun stream(
        file: File,
        startSample: Long,
        onSamples: (FloatArray, Int) -> Unit,
        onProgress: (Long, Long?) -> Unit,
    ): Long = decoder.stream(Uri.fromFile(file), onSamples, startSample, onProgress)
}

/** [OpusSink]s over `StreamingOpusEncoder`. */
class OpusEncoders : OpusSinkFactory {
    override fun open(file: File, restored: EncoderState?): OpusSink =
        OpusEncoderSeam(StreamingOpusEncoder(file, restored?.let(CheckpointState::json)))
}

private class OpusEncoderSeam(private val encoder: StreamingOpusEncoder) : OpusSink {
    override fun accept(samples: FloatArray, count: Int) = encoder.accept(samples, count)

    override fun checkpoint(): EncoderState = CheckpointState.encoder(encoder.checkpoint())

    override fun finish() = encoder.finish()

    override fun close() = encoder.close()
}
