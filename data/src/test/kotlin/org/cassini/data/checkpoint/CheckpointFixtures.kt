package org.cassini.data.checkpoint

import org.cassini.core.asr.IncrementalState
import org.cassini.core.checkpoint.CheckpointState
import org.cassini.core.checkpoint.EncoderState
import org.cassini.core.checkpoint.RecognitionState
import org.cassini.core.format.Word
import org.cassini.core.models.ModelCatalog
import org.cassini.data.notes.NotesHarness
import java.io.File
import java.util.UUID

/** A note id: a canonical UUID v4 string built from [n]. */
fun noteId(n: Int): String = UUID(0x5eedL, n.toLong()).toString().replaceRange(14, 15, "4")

/** Words "uno due tre …" with the speakers [speakers] in turn, one word per 500 ms. */
fun draftWords(vararg speakers: String): List<Word> =
    speakers.mapIndexed { index, speaker -> Word(speaker, index * 500L, index * 500L + 400, "w$index") }

/**
 * A checkpoint of [noteId]'s audio file [source] that validates against the file as it is now, with [clipSamples]
 * saved clip samples and, when [encoderLength] is given, an encoder that committed that many bytes of
 * `audio.opus.part`. [windowStartMs] is the start of the window that fills; words that end 10 s before it go to the
 * word log.
 */
@Suppress("LongParameterList") // Test fixture: one parameter per member a test varies.
fun NotesHarness.checkpoint(
    noteId: String,
    source: String = "capture.opus",
    words: List<Word> = draftWords("S1", "S1", "S2"),
    doneMs: Long = 28_000,
    clipSamples: Int = 0,
    encoderLength: Long? = null,
    model: String = ModelCatalog.PARAKEET_REVISION,
    windowStartMs: Long = 0,
): CheckpointState = CheckpointState(
    source = source,
    signature = CheckpointState.signature(File(dir(noteId), source)),
    model = model,
    speakers = ModelCatalog.diarizationModel.sha256,
    recognition = RecognitionState(
        1_200,
        IncrementalState(windowStartMs * 16, doneMs * 16, FloatArray(4) { it / 4f }, words),
    ),
    encoder = encoderLength?.let {
        EncoderState(null, FloatArray(CheckpointState.FRAME_FLOATS), 640, 0, it, 7, 3, 960, null)
    },
    diarizer = null,
    speakerPrefix = "S",
    speakerFailure = false,
    clipSamples = clipSamples,
    voiceClips = null,
    positionSamples = doneMs * 16,
    doneMs = doneMs,
    durationMs = 60_000,
    elapsedMs = 9_000,
)
