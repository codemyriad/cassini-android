package org.cassini.core.checkpoint

import org.cassini.core.asr.IncrementalState
import org.cassini.core.asr.IncrementalTranscript
import org.cassini.core.format.Word
import org.cassini.core.models.ModelCatalog
import org.cassini.core.speakers.PrintWindow
import org.cassini.core.speakers.SpeakerTurn

/**
 * A synthetic long transcription for the size and growth tests: the words, turns and checkpoints that a job over
 * [hours] of continuous speech saves, window after window. The shapes follow a real job: a speaker id of the job's
 * form (`diar_<uuid>_N`), about [WORDS_PER_HOUR] words an hour (the measured rate of docs/architecture.md section
 * 3.3), a 28 s window that advances 26 s, a 2 s overlap kept in the checkpoint, and a Sortformer state of the size
 * the pinned model saves.
 */
internal class LongRun(private val wordsPerHour: Int = WORDS_PER_HOUR) {
    val prefix = "diar_0f8e2c3a_6b7d_4e1f_9a2b_3c4d5e6f7a8b_"

    /** The words that end at or before [endMs], in time order. */
    fun words(endMs: Long): List<Word> {
        val gap = HOUR_MS / wordsPerHour
        val count = (endMs / gap).toInt()
        return List(count) { i ->
            val start = i * gap
            Word("$prefix${1 + (i / TURN_WORDS) % SPEAKERS}", start, start + gap * 4 / 5, TEXT[i % TEXT.size])
        }.filter { it.endMs <= endMs }
    }

    /** One turn each [TURN_WORDS] words. */
    fun turns(endMs: Long): List<SpeakerTurn> {
        val length = HOUR_MS / wordsPerHour * TURN_WORDS
        return (0 until (endMs / length).toInt()).map { i ->
            SpeakerTurn(i * length, (i + 1) * length - 100, i % SPEAKERS)
        }
    }

    /** The checkpoint a job saves right after the window that ends at [doneMs]. */
    fun checkpoint(doneMs: Long, durationMs: Long, words: List<Word> = words(doneMs)): CheckpointState {
        val doneSamples = doneMs * RATE / 1000
        return CheckpointState(
            source = "capture.opus",
            signature = "${durationMs * 6}:1",
            model = ModelCatalog.PARAKEET_REVISION,
            speakers = ModelCatalog.diarizationModel.sha256,
            recognition = RecognitionState(
                doneMs / 10,
                IncrementalState(
                    doneSamples - OVERLAP_SAMPLES,
                    doneSamples,
                    FloatArray(OVERLAP_SAMPLES) { it / 1e5f },
                    words,
                ),
            ),
            encoder = null,
            diarizer = DiarizerState(FloatArray(DIARIZER_STATE) { it / 1e3f }, doneMs / 20, turns(doneMs)),
            speakerPrefix = prefix,
            speakerFailure = false,
            clipSamples = CLIP_SAMPLES,
            voiceClips = (1..SPEAKERS).associate { "$prefix$it" to listOf(PrintWindow((it - 1) * 16_000, it * 16_000)) },
            positionSamples = doneSamples,
            doneMs = doneMs,
            durationMs = durationMs,
            elapsedMs = doneMs / 8,
        )
    }

    /** The ends of the windows of a run over [durationMs]: one each 26 s after the first 28 s. */
    fun windowEnds(durationMs: Long): Sequence<Long> =
        generateSequence(WINDOW_MS) { it + STEP_MS }.takeWhile { it <= durationMs }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val WORDS_PER_HOUR = 9_500
        const val RATE = 16_000L
        const val WINDOW_MS = IncrementalTranscript.WINDOW_SAMPLES * 1000L / 16_000
        const val STEP_MS = WINDOW_MS - 2_000
        const val OVERLAP_SAMPLES = 32_000
        const val SPEAKERS = 4
        const val TURN_WORDS = 23

        /**
         * The Sortformer state of the pinned model: 7 header values, 188 cache frames and 188 FIFO frames of 512
         * values, and the cache probabilities of 4 speakers (scripts/streaming-diarization.patch, `SaveState`).
         */
        const val DIARIZER_STATE = 7 + 188 * 512 + 188 * 4 + 188 * 512
        const val CLIP_SAMPLES = SPEAKERS * 16_000

        /** Real word lengths: Italian and English words, some with the punctuation Parakeet attaches. */
        val TEXT = (
            "Allora, partiamo dal punto principale della riunione di oggi. The quarterly numbers look better than " +
                "expected, ma dobbiamo ancora verificare le consegne di giovedì e il budget del progetto. " +
                "Can everyone confirm their availability for next week? Perfetto, grazie mille a tutti."
            ).split(' ')
    }
}
