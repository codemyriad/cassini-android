@file:Suppress("MagicNumber") // The colour table of the speaker tints.

package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxSpeaker
import io.codemyriad.cassini.core.recorder.CwxTranscript
import io.codemyriad.cassini.core.recorder.CwxWord
import io.codemyriad.cassini.core.speakers.DefaultLabels
import io.codemyriad.cassini.core.speakers.SpeakerTint
import io.codemyriad.cassini.data.db.SpeakerEntity
import io.codemyriad.cassini.data.db.WordEntity

/**
 * The recorder's transcript of one note variant, with the speakers as the Note screen shows them: the stored label
 * (a placeholder without one gets [placeholderLabel] with its number) and the colour of its [SpeakerTint]. The
 * recorder shows light text on a dark screen, so the colours are those of the app's dark theme (`:ui`
 * BrandTokenValues, `speaker-0` to `speaker-6`).
 */
object RecorderTranscripts {
    /** The dark-theme colour of each tint (0xRRGGBB). */
    private val TINT_RGB = mapOf(
        SpeakerTint.ONE to 0x679CFF,
        SpeakerTint.TWO to 0xE96CAD,
        SpeakerTint.THREE to 0x09B4BB,
        SpeakerTint.FOUR to 0xEB7C33,
        SpeakerTint.FIVE to 0xB082F7,
        SpeakerTint.SIX to 0x7FA3C2,
        SpeakerTint.UNNAMED to 0xB0B4BC,
    )

    /**
     * [words] in canonical order and the [speakers] of their variant. The speakers are listed in order of their first
     * word; a word's speaker without a row shows as a numbered placeholder. [durationMs] 0 (unknown) takes the end of
     * the last word.
     */
    fun build(
        title: String,
        durationMs: Long,
        words: List<WordEntity>,
        speakers: List<SpeakerEntity>,
        placeholderLabel: (Int) -> String,
    ): CwxTranscript {
        val byId = speakers.associateBy { it.speakerId }
        val order = words.map { it.speaker }.distinct()
        val tints = SpeakerTint.assign(order, byId.mapValues { it.value.colourIndex }) { id ->
            byId[id]?.isPlaceholder ?: true
        }
        val cwxSpeakers = order.mapIndexed { index, id ->
            CwxSpeaker(label(byId[id], index, placeholderLabel), TINT_RGB.getValue(tints.getValue(id)))
        }
        val indexOf = order.withIndex().associate { (index, id) -> id to index }
        val cwxWords = words.map { CwxWord(it.startMs, it.endMs, indexOf.getValue(it.speaker), it.text) }
        val duration = if (durationMs > 0) durationMs else words.maxOfOrNull { it.endMs } ?: 0L
        return CwxTranscript(title, duration, cwxSpeakers, cwxWords)
    }

    private fun label(speaker: SpeakerEntity?, index: Int, placeholderLabel: (Int) -> String): String = when {
        speaker == null -> placeholderLabel(index + 1)
        !speaker.isPlaceholder -> speaker.label
        else -> DefaultLabels.stored(speaker.speakerId, speaker.label)
            ?: placeholderLabel(speaker.placeholderNumber ?: (speaker.ordinal + 1))
    }
}
