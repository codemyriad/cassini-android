package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.SuggestionUi
import io.codemyriad.cassini.ui.components.TurnUi
import io.codemyriad.cassini.ui.components.WordUi
import io.codemyriad.cassini.ui.format.FIXED_ZONE
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.note.NoteEvent
import io.codemyriad.cassini.ui.note.NoteMode
import io.codemyriad.cassini.ui.note.NoteScreen
import io.codemyriad.cassini.ui.note.NoteUiState
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.SpeakerTone
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/** Actual Note branches on the JVM: no application, device, screenshot or saved Library. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class ReadingSuggestionHeaderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun readerSuggestionKeepsTheReadingHeaderAndNamingActions() = check(NoteMode.Reading)

    @Test fun draftSuggestionKeepsTheReadingHeaderAndNamingActions() =
        check(NoteMode.Paused(doneMs = 1000, totalMs = 2000, hasCheckpoint = true))

    private fun check(mode: NoteMode) {
        val events = ArrayList<NoteEvent>()
        val turn = TurnUi(
            id = "turn",
            speakerId = "ben",
            name = SpeakerNameUi.Placeholder(2, "Speaker 2"),
            tone = SpeakerTone.Two,
            startMs = 0,
            words = persistentListOf(WordUi("Agreed.", 0)),
            suggestion = SuggestionUi("voice", "Benjamin"),
            continuation = true,
            over = "(over Ada)",
            crosstalk = true,
        )
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE) {
                    NoteScreen(
                        NoteUiState("note", "Test transcript", mode = mode, turns = persistentListOf(turn)),
                        onEvent = { events += it },
                    )
                }
            }
        }
        rule.onAllNodes(hasContentDescription("(over Ada)", substring = true)).assertCountEquals(1)
        rule.onAllNodes(hasContentDescription("Probably crosstalk", substring = true)).assertCountEquals(1)
        val inTurn = hasAnyAncestor(hasTestTag("transcript-turn-${turn.id}"))
        rule.onAllNodes(hasText("Speaker 2") and inTurn, useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithText("Speaker 2", useUnmergedTree = true)
            .assertCountEquals(if (mode == NoteMode.Reading) 1 else 0)
        rule.onNodeWithText("(over Ada)", useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Yes, Benjamin").performClick()
        rule.runOnIdle { assertEquals(listOf(NoteEvent.AcceptSuggestion("ben", "voice")), events) }
    }
}
