package io.codemyriad.cassini.recorder

import io.codemyriad.cassini.core.recorder.CwxEncoder
import io.codemyriad.cassini.core.recorder.CwxReader
import io.codemyriad.cassini.data.db.SpeakerEntity
import io.codemyriad.cassini.data.db.WordEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class RecorderTranscriptsTest {
    private fun word(index: Int, speaker: String, text: String) =
        WordEntity("n", "v", index, index * 1_000L, index * 1_000L + 800, speaker, text)

    private fun named(id: String, ordinal: Int, label: String, colour: Long) =
        SpeakerEntity("n", "v", id, ordinal, label, isPlaceholder = false, colourIndex = colour)

    private fun placeholder(id: String, ordinal: Int, label: String, number: Int) =
        SpeakerEntity("n", "v", id, ordinal, label, isPlaceholder = true, placeholderNumber = number)

    @Test fun speakersHaveTheLabelsAndColoursOfTheNoteScreen() {
        val words = listOf(word(0, "s2", "Hi"), word(1, "s1", "hello"), word(2, "s2", "again"), word(3, "s3", "who?"))
        val speakers = listOf(
            placeholder("s1", 1, "s1", number = 2),
            named("s2", 0, "Ada", colour = 1),
        )
        val transcript = RecorderTranscripts.build("Standup", 0, words, speakers) { "Speaker $it" }

        // In order of the first word: Ada (named, colour slot 1), the placeholder s1 (number 2, stone), and s3
        // without a row (third to speak).
        assertEquals(listOf("Ada", "Speaker 2", "Speaker 3"), transcript.speakers.map { it.label })
        assertEquals(listOf(0xE96CAD, 0xB0B4BC, 0xB0B4BC), transcript.speakers.map { it.rgb })
        assertEquals(listOf(0, 1, 0, 2), transcript.words.map { it.speakerIndex })
        assertEquals(3_800L, transcript.durationMs)
        assertEquals("Standup", transcript.title)

        val reader = CwxReader(CwxEncoder.encode(transcript))
        assertEquals(listOf("Hi", "hello", "again", "who?"), reader.wordsFrom(0).map { it.text })
    }

    @Test fun aStoredPlaceholderLabelIsShownVerbatim() {
        val transcript = RecorderTranscripts.build(
            "Call",
            5_000,
            listOf(word(0, "s1", "Ciao")),
            listOf(placeholder("s1", 0, "Voce 1", number = 1)),
        ) { "Speaker $it" }
        assertEquals(listOf("Voce 1"), transcript.speakers.map { it.label })
        assertEquals(5_000L, transcript.durationMs)
    }
}
