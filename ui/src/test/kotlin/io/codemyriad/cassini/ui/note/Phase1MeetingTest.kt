package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.TranscriptFixtures
import io.codemyriad.cassini.ui.components.WordRef
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.junit.Assert.assertEquals
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
class Phase1MeetingTest {
    @get:Rule val rule = createComposeRule()

    private val turns = (0 until 20).map { index ->
        BoardFixtures.readGiulia.copy(
            id = "turn-$index",
            startMs = index * 10_000L,
            words = TranscriptFixtures.words(List(150) { "paragraph$index" }.joinToString(" "), index * 10_000L),
        )
    }.toImmutableList()

    @Test fun metadataScrollsAwayWhileTheNativeTitleAndActionsStayPinned() {
        val state = BoardFixtures.readAndListen.copy(turns = turns, playback = null)
        show(state)
        rule.onNodeWithTag("meeting-date").assertIsDisplayed()
        val bar = rule.onNodeWithTag("meeting-app-bar").getBoundsInRoot()
        rule.onNodeWithTag("transcript-list").performScrollToIndex(15)
        rule.onNodeWithTag("meeting-date").assertIsNotDisplayed()
        assertEquals(bar, rule.onNodeWithTag("meeting-app-bar").getBoundsInRoot())
        rule.onNodeWithTag("meeting-scrolled-edge").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back to all meetings").assertIsDisplayed()
        rule.onNodeWithContentDescription("Search in transcript").assertIsDisplayed()
        rule.onNodeWithContentDescription("Share Cassini document").assertIsDisplayed()
    }

    @Test fun inlineNamesAndAllTopActionsKeepTheirExistingEvents() {
        val base = BoardFixtures.readAndListen
        val events = mutableListOf<NoteEvent>()
        show(base.copy(playback = null), onEvent = { events += it })
        val speaker = base.turns.first()
        rule.onNodeWithTag("meeting-speaker-${speaker.speakerId}").performClick()
        rule.onNodeWithContentDescription("Share Cassini document").performClick()
        rule.onNodeWithContentDescription("Search in transcript").performClick()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    NoteEvent.SpeakerTap(speaker.speakerId),
                    NoteEvent.SheetAction(NoteAction.SHARE_DOCUMENT),
                    NoteEvent.OpenSearch,
                    NoteEvent.More,
                ),
                events,
            )
        }
    }

    @Test fun aStoredUnnamedLabelWinsInTheHeaderAndKeepsItsNamingTarget() {
        val base = BoardFixtures.readAndListen
        val unnamed = base.turns.last()
        val speakers = base.turns.map {
            MeetingSpeakerUi(
                it.speakerId,
                if (it == unnamed) SpeakerNameUi.Placeholder(3, "Voce originale") else it.name,
                it.tone,
                it.auto,
            )
        }.toImmutableList()
        val events = mutableListOf<NoteEvent>()
        show(base.copy(meetingSpeakers = speakers, playback = null), onEvent = { events += it })
        rule.onNodeWithTag("meeting-speaker-${unnamed.speakerId}").assert(hasText("Voce originale")).performClick()
        rule.runOnIdle { assertEquals(listOf(NoteEvent.SpeakerTap(unnamed.speakerId)), events) }
    }

    @Test fun searchJumpsPastTheHeaderToTheActualMatchedTurn() = jump(searching = true)

    @Test fun playbackFollowJumpsPastTheHeaderToTheActualActiveTurn() = jump(searching = false)

    private fun show(state: NoteUiState, onEvent: (NoteEvent) -> Unit = {}) {
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) { NoteScreen(state, onEvent) }
            }
        }
    }

    private fun jump(searching: Boolean) {
        lateinit var list: LazyListState
        var word by mutableStateOf<WordRef?>(null)
        var search by mutableStateOf<TranscriptSearchUi?>(null)
        rule.setContent {
            CassiniTheme {
                list = rememberLazyListState()
                Box(Modifier.height(240.dp)) {
                    TranscriptPane(
                        turns = turns,
                        activeWord = word,
                        search = search,
                        following = !searching,
                        followPaused = false,
                        listState = list,
                        onEvent = {},
                        header = { Box(Modifier.height(180.dp)) },
                    )
                }
            }
        }
        rule.runOnIdle {
            if (searching) {
                search = TranscriptSearchUi("paragraph19", persistentListOf(TranscriptMatch(turns.last().id, 0..10)), 0)
            } else {
                word = WordRef(turns.last().id, 0)
            }
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(
                "actual target turn must be in view",
                list.layoutInfo.visibleItemsInfo.any { it.key == turns.last().id },
            )
        }
    }
}
