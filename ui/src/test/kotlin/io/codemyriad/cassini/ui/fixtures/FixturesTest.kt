package io.codemyriad.cassini.ui.fixtures

import io.codemyriad.cassini.ui.banner.BannerGroup
import io.codemyriad.cassini.ui.components.turnText
import io.codemyriad.cassini.ui.format.Sizes
import io.codemyriad.cassini.ui.note.NoteSheet
import io.codemyriad.cassini.ui.note.NoteUiState
import io.codemyriad.cassini.ui.note.SaveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fixtures agree with the boards (ui.md section 10.2) and with themselves (plain JVM). */
class FixturesTest {
    private val b = BoardFixtures

    @Test
    fun everyBoardHasAFixture() {
        val slugs = (0..10).map { "%02d".format(it) }
        assertEquals(slugs, b.boards.keys.map { it.take(2) })
    }

    @Test
    fun readAndListenPlaysQuindiAtTheBoardPosition() {
        val state = b.readAndListen
        val metadata = requireNotNull(state.subtitle)
        assertEquals(b.supplier.time, metadata.time)
        assertEquals(b.supplier.durationMs, metadata.durationMs)
        val active = requireNotNull(state.activeWord)
        val word = state.turns.single { it.id == active.turnId }.words[active.index]
        assertEquals("so", word.text)
        assertEquals(requireNotNull(state.playback).positionMs, word.startMs)
    }

    @Test
    fun libraryPreviewIsTheExcerptAtThePlayingWord() {
        val preview = requireNotNull(b.supplier.preview)
        val paolo = turnText(b.readPaolo.words)
        val start = paolo.indexOf("so")
        assertTrue(paolo.substring(start).startsWith(preview.text))
        assertTrue(preview.leadingEllipsis && preview.trailingEllipsis)
    }

    @Test
    fun nameSheetHasTheQuestionOpenAndSaveLooksEnabled() {
        assertEquals(SaveState.BLOCKED_BY_QUESTION, b.nameSpeaker.save)
        assertEquals("Anna Ricci", b.nameSpeaker.samePerson)
        assertTrue(b.nameSpeaker.nameFieldFocused)
    }

    @Test
    fun trashIsOldestDeletionFirstWithTheFixtureSnackbar() {
        assertEquals(b.trash.items.sortedBy { it.daysLeft }, b.trash.items)
        assertNotNull(b.trash.snackbar)
    }

    @Test
    fun modelPartsRoundToTheBoardFigures() {
        assertEquals(listOf(640L, 62L, 28L), b.modelDownload.parts.map { Sizes.mebibytes(it.bytes) })
    }

    @Test
    fun statesColumnsFollowTheBoard() {
        assertEquals(BannerGroup.entries.toList(), b.states.keys.toList())
        b.states.forEach { (group, banners) ->
            assertEquals(6, banners.size)
            assertTrue(banners.all { it.kind.group == group && !it.dismissible })
        }
    }

    @Test
    fun variantNamesAreUnique() {
        val names = VariantFixtures.all.flatMap { it.keys }
        assertEquals(names.groupBy { it }.filterValues { it.size > 1 }.keys, emptySet<String>())
    }

    @Test
    fun noteReferencesStayInsideTheirTurns() {
        val notes = listOf(b.transcribing, b.readAndListen) +
            VariantFixtures.all.flatMap { it.values }.filterIsInstance<NoteUiState>()
        notes.forEach { note ->
            note.turns.forEach { turn ->
                turn.unsettledFrom?.let { assertTrue(turn.id, it in turn.words.indices) }
            }
            note.activeWord?.let { ref ->
                assertTrue(ref.index in note.turns.single { it.id == ref.turnId }.words.indices)
            }
            note.search?.let { search ->
                assertTrue(search.total == 0 || search.current in search.matches.indices)
                search.matches.forEach { match ->
                    val text = turnText(note.turns.single { it.id == match.turnId }.words)
                    assertEquals(search.query.lowercase(), text.substring(match.range).lowercase())
                }
            }
            (note.sheet as? NoteSheet.Versions)?.ui?.let { assertTrue(it.selected in it.rows.indices) }
        }
    }
}
