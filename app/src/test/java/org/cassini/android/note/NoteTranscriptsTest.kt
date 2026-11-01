package org.cassini.android.note

import org.cassini.core.format.Word
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.Voice
import org.cassini.data.db.SpeakerEntity
import org.cassini.ui.components.SpeakerNameUi
import org.cassini.ui.components.SuggestionUi
import org.cassini.ui.components.WordRef
import org.cassini.ui.components.turnText
import org.cassini.ui.theme.SpeakerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turns, tints, badges, suggestion cards and the active word (features.md sections 3.1 and 3.3). */
class NoteTranscriptsTest {
    private val words = listOf(
        Word("a", 0, 400, "Ciao"),
        Word("a", 500, 900, "a tutti."),
        Word("b", 1_000, 1_400, "Buongiorno"),
        Word("c", 2_000, 2_300, "Eccomi"),
        Word("a", 3_000, 3_500, "Bene,"),
        Word("a", 3_500, 3_900, "quindi"),
    )
    private val speakers = listOf(
        speaker("a", 0, "Giulia", placeholder = false),
        speaker("b", 1, "Speaker 2", placeholder = true, number = 2),
        speaker("c", 2, "Paolo Ferri", placeholder = false),
    )
    private val paolo = Voice("paolo", "Paolo Ferri", "m", FloatArray(1), 1, 10.0, 0, 0)
    private val luca = Voice("luca", "Luca", "m", FloatArray(1), 1, 10.0, 0, 0)

    @Test
    fun sealedTurnsFollowTheSpeakersWithTintsAndPlaceholders() {
        val transcript = NoteTranscripts.sealed(words, speakers, emptyMap(), emptyList())
        val turns = transcript.turns
        assertEquals(listOf("w0", "w2", "w3", "w4"), turns.map { it.id })
        assertEquals(listOf("a", "b", "c", "a"), turns.map { it.speakerId })
        assertEquals(SpeakerNameUi.Named("Giulia"), turns[0].name)
        assertEquals(SpeakerNameUi.Placeholder(2, "Speaker 2"), turns[1].name)
        // Placeholders are amber and take no slot: Giulia blue, Paolo teal.
        val tones = listOf(SpeakerTone.Blue, SpeakerTone.Amber, SpeakerTone.Teal, SpeakerTone.Blue)
        assertEquals(tones, turns.map { it.tone })
        assertEquals("Ciao a tutti.", turnText(turns[0].words))
        assertEquals(500L, turns[0].words[1].startMs)
        assertEquals(listOf("a", "b", "c"), transcript.speakers)
    }

    @Test
    fun autoBadgeAndSuggestionComeFromTheMatches() {
        val prints = mapOf(
            "c" to SpeakerPrint(FloatArray(1), 5.0, Match("paolo", 0.8f, Match.State.AUTO)),
            "b" to SpeakerPrint(FloatArray(1), 5.0, Match("luca", 0.6f, Match.State.SUGGESTED)),
        )
        val turns = NoteTranscripts.sealed(words, speakers, prints, listOf(paolo, luca)).turns
        assertTrue("Paolo's label equals his AUTO voice", turns[2].auto)
        assertFalse(turns[0].auto)
        assertEquals(SuggestionUi("luca", "Luca"), turns[1].suggestion)
        assertTrue(turns.filter { it.speakerId != "b" }.all { it.suggestion == null })
    }

    @Test
    fun aSuggestionToAForgottenVoiceIsNotShown() {
        val prints = mapOf("b" to SpeakerPrint(FloatArray(1), 5.0, Match("gone", 0.6f, Match.State.SUGGESTED)))
        assertNull(NoteTranscripts.sealed(words, speakers, prints, emptyList()).turns[1].suggestion)
    }

    @Test
    fun theActiveWordIsTheLastWordThatStarted() {
        val transcript = NoteTranscripts.sealed(words, speakers, emptyMap(), emptyList())
        assertEquals(WordRef("w0", 0), transcript.activeWord(0))
        assertEquals(WordRef("w0", 0), transcript.activeWord(450))
        assertEquals(WordRef("w0", 1), transcript.activeWord(500))
        assertEquals(WordRef("w3", 0), transcript.activeWord(2_900))
        assertEquals("the word that starts at the position plays", WordRef("w4", 1), transcript.activeWord(3_500))
        assertEquals(WordRef("w4", 1), transcript.activeWord(99_000))
        val late = NoteTranscripts.sealed(listOf(Word("a", 100, 200, "x")), speakers, emptyMap(), emptyList())
        assertNull(late.activeWord(50))
        assertEquals(3_000L, transcript.turnStart("w4"))
        assertEquals(3_500L, transcript.wordStart(WordRef("w4", 1)))
    }

    @Test
    fun oneLongSpeakerIsSplitIntoParagraphs() {
        val long = (0 until 450).map { i -> Word("a", i * 100L, i * 100L + 90, if (i == 129) "end." else "w$i") }
        val turns = NoteTranscripts.sealed(long, emptyList(), emptyMap(), emptyList()).turns
        val sizes = turns.map { it.words.size }
        assertEquals("a sentence end after 120 words, then at most 200", listOf(130, 200, 120), sizes)
        assertEquals(listOf("w0", "w130", "w330"), turns.map { it.id })
        assertTrue(turns.all { it.speakerId == "a" })
    }

    @Test
    fun theDraftNumbersPlaceholdersByFirstWordAndMarksUnsettledWords() {
        val draft = NoteTranscripts.draft(
            words = words,
            labels = mapOf("a" to "Speaker 1", "c" to "Paolo Ferri"),
            automatic = setOf("c"),
            suggestions = mapOf("a" to SuggestionUi("luca", "Luca")),
            tentativeFromMs = 3_600,
        )
        val turns = draft.turns
        assertEquals(SpeakerNameUi.Placeholder(1, "Speaker 1"), turns[0].name)
        assertEquals("no label yet: Speaker N by first word", SpeakerNameUi.Placeholder(2), turns[1].name)
        assertEquals(SpeakerNameUi.Named("Paolo Ferri"), turns[2].name)
        assertTrue(turns[2].auto)
        assertNull("the card sits at the speaker's most recent turn", turns[0].suggestion)
        assertEquals(SuggestionUi("luca", "Luca"), turns[3].suggestion)
        assertEquals("quindi ends after 3.6 s", 1, turns[3].unsettledFrom)
        assertNull(turns[0].unsettledFrom)
        assertEquals(WordRef("w4", 0), draft.activeWord(3_100))
    }

    @Test
    fun aStoredPlaceholderShowsVerbatimInAnyUiLanguage() {
        // D47: an Italian writer stored "Voce 2"; an English UI still shows "Voce 2", never "Speaker 2".
        val italian = listOf(
            speaker("a", 0, "Giulia", placeholder = false),
            speaker("b", 1, "Voce 2", placeholder = true, number = 2),
            speaker("c", 2, "Hablante 7", placeholder = true, number = 7),
        )
        val turns = NoteTranscripts.sealed(words, italian, emptyMap(), emptyList()).turns
        assertEquals(SpeakerNameUi.Placeholder(2, "Voce 2"), turns[1].name)
        val spanish = SpeakerNameUi.Placeholder(7, "Hablante 7")
        assertEquals("the number comes from the stored label", spanish, turns[2].name)
        assertEquals("a stored placeholder stays amber", SpeakerTone.Amber, turns[1].tone)

        val draft = NoteTranscripts.draft(words, mapOf("a" to "Voce 1"), emptySet(), emptyMap(), null).turns
        assertEquals(SpeakerNameUi.Placeholder(1, "Voce 1"), draft[0].name)
    }

    @Test
    fun aSpeakerWithoutAStoredLabelIsSpeakerNByFirstSpeakingOrderNeverItsId() {
        // D53.3: a missing, blank or raw-id label falls back to "Speaker N"; the id is never shown.
        val unlabelled = listOf(
            speaker("a", 0, "a", placeholder = true),
            speaker("b", 1, " ", placeholder = true),
        )
        val turns = NoteTranscripts.sealed(words, unlabelled, emptyMap(), emptyList()).turns
        assertEquals(SpeakerNameUi.Placeholder(1), turns[0].name)
        assertEquals(SpeakerNameUi.Placeholder(2), turns[1].name)
        assertEquals("not in speakers[]: numbered by first word", SpeakerNameUi.Placeholder(3), turns[2].name)
    }

    @Test
    fun searchIgnoresCaseAndAccents() {
        val turns = NoteTranscripts.sealed(
            listOf(Word("a", 0, 1, "Giovedì"), Word("a", 1, 2, "CONSEGNA"), Word("b", 2, 3, "giovedi consegna")),
            emptyList(),
            emptyMap(),
            emptyList(),
        ).turns
        val giovedi = TranscriptSearch.matches(turns, "giovedi")
        assertEquals(2, giovedi.size)
        assertEquals("w0", giovedi[0].turnId)
        assertEquals(0..6, giovedi[0].range)
        assertEquals(0..6, giovedi[1].range)
        assertEquals(listOf(8..15, 8..15), TranscriptSearch.matches(turns, "Consegna").map { it.range })
        assertTrue(TranscriptSearch.matches(turns, "  ").isEmpty())
        assertTrue(TranscriptSearch.matches(turns, "domani").isEmpty())
    }

    @Test
    fun aDecomposedAccentKeepsTheRangeOnTheSourceText() {
        val text = "Café aperto"
        val turns = NoteTranscripts.sealed(listOf(Word("a", 0, 1, text)), emptyList(), emptyMap(), emptyList()).turns
        val match = TranscriptSearch.matches(turns, "café").single()
        assertEquals("the combining mark belongs to the match", 0..4, match.range)
    }

    private fun speaker(id: String, ordinal: Int, label: String, placeholder: Boolean, number: Int? = null) =
        SpeakerEntity(
            noteId = "n",
            variantId = "v1",
            speakerId = id,
            ordinal = ordinal,
            label = label,
            isPlaceholder = placeholder,
            placeholderNumber = number,
        )
}
