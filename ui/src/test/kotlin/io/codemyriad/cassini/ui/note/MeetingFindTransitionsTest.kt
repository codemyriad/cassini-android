package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import io.codemyriad.cassini.ui.components.TranscriptFixtures
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class MeetingFindTransitionsTest {
    @get:Rule val rule = createComposeRule()
    private val turns = List(20) { index ->
        BoardFixtures.readGiulia.copy(
            id = "turn-$index",
            words = TranscriptFixtures.words(List(150) { "paragraph$index" }.joinToString(" "), index * 10_000L),
        )
    }.toImmutableList()
    private var state by mutableStateOf(
        BoardFixtures.readAndListen.copy(turns = turns, activeWord = null, playback = null),
    )

    @Test fun closingFindAtTheFirstTurnRestoresTheRestingHeader() {
        show()
        search()
        closeAtFirstTurn()
    }

    @Test fun openingFindPartwayThroughMetadataDoesNotClipTheFirstMatch() {
        show()
        rule.onNodeWithTag("transcript-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 40f) }
        rule.onNodeWithTag("meeting-scrolled-edge").assertIsDisplayed()
        search()
        val pane = rule.onNodeWithTag("transcript-list").getBoundsInRoot()
        val first = rule.onNodeWithTag("transcript-turn-turn-0").getUnclippedBoundsInRoot()
        assertTrue("The first match starts below Find", first.top >= pane.top)
        closeAtFirstTurn()
    }

    @Test fun aNewFirstMatchClearsAClippedTurnWhileFindIsAlreadyOpen() {
        show()
        search()
        rule.onNodeWithTag("transcript-list").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 40f) }
        rule.runOnIdle {
            state = state.copy(search = TranscriptSearchUi("paragraph0", persistentListOf(TranscriptMatch(turns[0].id, 0..9)), 0))
        }
        val pane = rule.onNodeWithTag("transcript-list").getBoundsInRoot()
        val first = rule.onNodeWithTag("transcript-turn-turn-0").getUnclippedBoundsInRoot()
        assertTrue("The first match is no longer clipped", first.top >= pane.top)
    }

    @Test fun closingADeepResultKeepsItsLocation() {
        show()
        search()
        rule.onNodeWithTag("transcript-list").performScrollToIndex(12)
        rule.onNodeWithTag("transcript-turn-turn-12").assertIsDisplayed()
        rule.runOnIdle { state = state.copy(search = null) }
        rule.onNodeWithTag("transcript-turn-turn-12").assertIsDisplayed()
        rule.onNodeWithTag("meeting-scrolled-edge").assertIsDisplayed()
    }

    @Test fun closingAnInitialLibrarySearchAtTheFirstTurnRestoresMetadata() {
        state = state.copy(search = TranscriptSearchUi("paragraph0", persistentListOf(TranscriptMatch(turns[0].id, 0..9)), 0))
        show()
        closeAtFirstTurn()
    }

    private fun show() {
        rule.setContent {
            CassiniTheme { ProvideFixedFormat(locale = Locale.ENGLISH) { NoteScreen(state, onEvent = {}) } }
        }
    }

    private fun search(match: Boolean = false) {
        rule.runOnIdle {
            state = state.copy(
                search = TranscriptSearchUi(
                    "paragraph0",
                    if (match) persistentListOf(TranscriptMatch(turns[0].id, 0..9)) else persistentListOf(),
                    0,
                ),
            )
        }
        rule.onNodeWithTag("meeting-header").assertDoesNotExist()
        rule.onNodeWithTag("transcript-turn-turn-0").assertIsDisplayed()
    }

    private fun closeAtFirstTurn() {
        rule.runOnIdle { state = state.copy(search = null) }
        rule.onNodeWithTag("meeting-date").assertIsDisplayed()
        rule.onNodeWithTag("meeting-scrolled-edge").assertDoesNotExist()
    }
}
