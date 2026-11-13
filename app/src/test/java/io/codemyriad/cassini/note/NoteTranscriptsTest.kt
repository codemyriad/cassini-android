package io.codemyriad.cassini.note

import io.codemyriad.cassini.core.format.Transcript
import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.speakers.Match
import io.codemyriad.cassini.core.speakers.SpeakerPrint
import io.codemyriad.cassini.core.speakers.Voice
import io.codemyriad.cassini.data.db.SpeakerEntity
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.SuggestionUi
import io.codemyriad.cassini.ui.components.WordRef
import io.codemyriad.cassini.ui.components.turnText
import io.codemyriad.cassini.ui.note.WordStarts
import io.codemyriad.cassini.ui.theme.SpeakerTone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Turns, tints, badges, suggestion cards and the active word (features.md sections 3.1 and 3.3). */
class NoteTranscriptsTest {
    private val words = listOf(
        Word("a", 0, 400, "Hello"),
        Word("a", 500, 900, "everyone."),
        Word("b", 1_000, 1_400, "Morning"),
        Word("c", 2_000, 2_300, "Here"),
        Word("a", 3_000, 3_500, "Good,"),
        Word("a", 3_500, 3_900, "so"),
    )
    private val speakers = listOf(
        speaker("a", 0, "Giulia", placeholder = false),
        speaker("b", 1, "Speaker 2", placeholder = true, number = 2),
        speaker("c", 2, "Paolo Ferri", placeholder = false),
    )
    private val paolo = Voice("paolo", "Paolo Ferri", "m", FloatArray(1), 1, 10.0, 0, 0)
    private val luca = Voice("luca", "Luca", "m", FloatArray(1), 1, 10.0, 0, 0)

    @Test fun sealedTonesKeepWideCachedSlotsUntilPaletteReduction() {
        val cached = speakers.map { if (it.speakerId == "a") it.copy(colourIndex = 4294967296L) else it }
        val shown = NoteTranscripts.sealed(words, cached, emptyMap(), emptyList())
        assertEquals(SpeakerTone.Five, shown.speakerMetadata.getValue("a").tone)
    }

    @Test
    fun sealedTurnsFollowTheSpeakersWithTintsAndPlaceholders() {
        val transcript = NoteTranscripts.sealed(words, speakers, emptyMap(), emptyList())
        val turns = transcript.turns
        assertEquals(listOf("w0", "w2", "w3", "w4"), turns.map { it.id })
        assertEquals(listOf("a", "b", "c", "a"), turns.map { it.speakerId })
        assertEquals(SpeakerNameUi.Named("Giulia"), turns[0].name)
        assertEquals(SpeakerNameUi.Placeholder(2, "Speaker 2"), turns[1].name)
        // Unnamed hides slot 2; Paolo retains slot 3 before and after naming that speaker.
        val tones = listOf(SpeakerTone.One, SpeakerTone.Unnamed, SpeakerTone.Three, SpeakerTone.One)
        assertEquals(tones, turns.map { it.tone })
        assertEquals("Hello everyone.", turnText(turns[0].words))
        assertEquals(500L, turns[0].words[1].startMs)
        assertEquals(listOf("a", "b", "c"), transcript.speakers)
        assertEquals(transcript.speakers, transcript.meetingSpeakers.map { it.id })
        assertEquals(SpeakerNameUi.Placeholder(2, "Speaker 2"), transcript.meetingSpeakers[1].name)
        assertEquals(SpeakerTone.Unnamed, transcript.meetingSpeakers[1].tone)
    }

    @Test fun namingOrMergingDoesNotReassignOtherSpeakersStoredSlots() {
        val named = speakers.map { if (it.speakerId == "b") it.copy(label = "Ben", isPlaceholder = false) else it }
        val renamed = NoteTranscripts.sealed(words, named, emptyMap(), emptyList())
        assertEquals(SpeakerTone.Two, renamed.speakerMetadata.getValue("b").tone)
        assertEquals(SpeakerTone.Three, renamed.speakerMetadata.getValue("c").tone)
        val merged = words.map { if (it.speaker == "a") it.copy(speaker = "b") else it }
        val shown = NoteTranscripts.sealed(merged, named.reversed(), emptyMap(), emptyList())
        assertEquals(SpeakerTone.Two, shown.speakerMetadata.getValue("b").tone)
        assertEquals(SpeakerTone.Three, shown.speakerMetadata.getValue("c").tone)
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
        assertEquals("the viewer readable blocks, not Android word caps", listOf(130, 140, 140, 40), sizes)
        assertEquals(listOf("w0", "w130", "w270", "w410"), turns.map { it.id })
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
        assertEquals("so ends after 3.6 s", 1, turns[3].unsettledFrom)
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
        assertEquals("a stored placeholder stays amber", SpeakerTone.Unnamed, turns[1].tone)

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

    @Test fun acknowledgmentKeepsChipWordsSeekableAndSearchable() {
        val vector = fixture("acknowledgment")
        val transcript = NoteTranscripts.sealed(vector, vectorSpeakers, emptyMap(), emptyList())
        val row = transcript.turns.single()
        assertEquals(
            "The release candidate is ready for testing, (Ben: Sounds good.) " +
                "and I will send the download link after this call.",
            turnText(row.words),
        )
        assertEquals("", row.over)
        assertEquals(listOf("ada", "ben"), transcript.speakers)
        assertEquals(SpeakerNameUi.Named("Ben"), transcript.speakerMetadata.getValue("ben").name)
        assertEquals(row.words[7].interjectionTone, transcript.speakerMetadata.getValue("ben").tone)
        val active = transcript.activeWord(3200)!!
        assertEquals(WordRef(row.id, 7), active)
        assertEquals("Sounds", row.words[active.index].text)
        assertEquals(3050L, transcript.wordStart(active))
        assertEquals(1, TranscriptSearch.matches(transcript.turns, "Sounds good.").size)
    }

    @Test fun competingWordsKeepCanonicalSeekIndicesAndWholeSentences() {
        val vector = fixture("competing")
        val transcript = NoteTranscripts.sealed(vector, vectorSpeakers, emptyMap(), emptyList())
        assertEquals(2, transcript.turns.size)
        assertEquals(listOf("(over Ben)", "(over Ada)"), transcript.turns.map { it.over })
        assertTrue(transcript.turns.none { it.continuation })
        assertEquals("I can check the Linux build and report any problems today.", turnText(transcript.turns[0].words))
        assertEquals(1, TranscriptSearch.matches(transcript.turns, "Linux build").size)
        for ((canonical, word) in vector.withIndex()) {
            val ref = transcript.activeWord(word.startMs)!!
            val atom = transcript.turns.first { it.id == ref.turnId }.words[ref.index]
            assertEquals(canonical, atom.canonicalIndex)
            assertEquals(word.startMs, transcript.wordStart(ref))
        }
    }

    @Test fun draftUnsettledFlagsAndSuggestionFollowChipIndices() {
        val vector = fixture("acknowledgment")
        val draft = NoteTranscripts.draft(
            vector,
            mapOf("ada" to "Ada"),
            emptySet(),
            mapOf("ben" to SuggestionUi("voice", "Ben")),
            3400,
        )
        val row = draft.turns.single()
        assertFalse(row.words[7].unsettled)
        assertTrue(row.words[8].unsettled)
        assertEquals("ben", row.inlineSuggestions.single().speakerId)
        assertTrue("overlap-only speaker remains in the meeting header", draft.meetingSpeakers.any { it.id == "ben" })
        assertEquals(WordRef(row.id, 8), draft.activeWord(3400))
    }

    @Test fun automaticOverlapOnlySpeakersKeepTheirHeaderMetadataInSealedAndDraftViews() {
        val vector = fixture("acknowledgment")
        val ben = Voice("ben-voice", "Ben", "m", FloatArray(1), 1, 10.0, 0, 0)
        val prints = mapOf("ben" to SpeakerPrint(FloatArray(1), 5.0, Match(ben.id, 0.8f, Match.State.AUTO)))
        val sealed = NoteTranscripts.sealed(vector, vectorSpeakers, prints, listOf(ben))
        val draft = NoteTranscripts.draft(vector, mapOf("ada" to "Ada", "ben" to "Ben"), setOf("ben"), emptyMap(), null)
        for (shown in listOf(sealed, draft)) {
            assertTrue("Ben occurs only in an inline chip", shown.turns.none { it.speakerId == "ben" })
            assertTrue(shown.speakerMetadata.getValue("ben").auto)
            val header = shown.meetingSpeakers.single { it.id == "ben" }
            assertTrue(header.auto)
            assertEquals(vector.filter { it.speaker == "ben" }.minOf { it.startMs }, header.startMs)
        }
    }

    @Test fun canonicalOrderMayDifferFromPlaybackOrder() {
        val transcript = NoteTranscripts.sealed(
            listOf(Word("a", 1000, 1100, "later"), Word("b", 0, 100, "earlier")),
            emptyList(),
            emptyMap(),
            emptyList(),
        )
        assertEquals(0L, transcript.wordStart(transcript.activeWord(50)!!))
        assertEquals(1000L, transcript.wordStart(transcript.activeWord(1000)!!))
    }

    @Test fun measuredWordEndsAreNotRepairedInTheNote() {
        val words = listOf(Word("a", 0, 4000, "held."))
        val transcript = NoteTranscripts.sealed(words, emptyList(), emptyMap(), emptyList(), endsBoundedByAudio = true)
        assertEquals(4000L, transcript.turns.single().words.single().endMs)
    }

    @Test fun everyViewerVectorKeepsTheSameSealedAndDraftRowMapping() {
        val labels = mapOf("ada" to "Ada", "ben" to "Ben", "cy" to "Cy")
        val speakers = vectorSpeakers + speaker("cy", 2, "Cy", false)
        val fixtures = listOf(
            "acknowledgment",
            "competing",
            "monologue",
            "overlapping-turns",
            "brief-reply",
            "seam-gap",
        )
        for (name in fixtures) {
            val words = fixture(name)
            val expected = JSONObject(vectorFile("$name.expected.json")).getJSONArray("rows")
            val sealed = NoteTranscripts.sealed(words, speakers, emptyMap(), emptyList())
            val draft = NoteTranscripts.draft(words, labels, emptySet(), emptyMap(), null)
            for (transcript in listOf(sealed, draft)) {
                val frame = WordStarts(transcript.turns)
                for (word in words) {
                    assertEquals(name, transcript.activeWord(word.startMs), frame.ref(frame.indexAt(word.startMs)))
                }
                assertEquals(name, expected.length(), transcript.turns.size)
                transcript.turns.forEachIndexed { index, turn ->
                    val row = expected.getJSONObject(index)
                    assertEquals(name, row.getString("speaker"), turn.speakerId)
                    assertEquals(name, row.getLong("startMs"), turn.startMs)
                    assertEquals(name, row.getBoolean("continuation"), turn.continuation)
                    assertEquals(name, row.getBoolean("crosstalk"), turn.crosstalk)
                    val members = row.getJSONArray("members")
                    val canonical = (0 until members.length()).flatMap { member ->
                        val indices = members.getJSONObject(member).getJSONArray("wordIndices")
                        (0 until indices.length()).map(indices::getInt)
                    }
                    assertEquals(name, canonical, turn.words.map { it.canonicalIndex })
                    assertEquals(name, canonical.map { words[it].text }, turn.words.map { it.text })
                }
            }
        }
    }

    private val vectorSpeakers = listOf(speaker("ada", 0, "Ada", false), speaker("ben", 1, "Ben", false))
    private fun vectorFile(name: String): String =
        java.io.File(checkNotNull(System.getProperty("cassini.interjections")), name).readText()
    private fun fixture(name: String): List<Word> = Transcript.fromJson(
        vectorFile("$name.words.json"),
    ).words

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
