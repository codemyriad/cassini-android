package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.display
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.format.Spoken
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.SpeakerTone
import kotlinx.collections.immutable.persistentListOf
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
@Config(qualifiers = "en-rUS-w360dp-h844dp-xhdpi")
class MeetingSpeakerControlsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun shortAndAutomaticLongNamesKeepTheirTouchTargetsAndSpokenActions() = controls(1f)

    @Test fun largerTextKeepsTheAutomaticBadgeVisibleBesideALongName() = controls(2f)

    @Test fun overviewNamingAndTranscriptTurnsHaveDistinctFullSpokenDescriptions() {
        val base = BoardFixtures.readAndListen.copy(activeWord = null, playback = null)
        val descriptions = mutableMapOf<String, String>()
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) {
                    base.turns.forEach { descriptions[it.speakerId] = Spoken.turn(it.name.display(), it.startMs, it.auto) }
                    NoteScreen(base, onEvent = {})
                }
            }
        }
        rule.runOnIdle { assertEquals(base.turns.size, descriptions.size) }
        for ((id, spoken) in descriptions) {
            rule.onAllNodes(hasContentDescription(spoken)).assertCountEquals(1)
            rule.onNodeWithTag("meeting-speaker-$id")
                .assert(hasContentDescription("Name this speaker · $spoken"))
        }
    }

    private fun controls(scale: Float) {
        val events = mutableListOf<NoteEvent>()
        val speakers = persistentListOf(
            MeetingSpeakerUi("short", SpeakerNameUi.Named("Li"), SpeakerTone.One, startMs = 13_000),
            MeetingSpeakerUi(
                "long",
                SpeakerNameUi.Named("Alexandra Very Long Recognised Speaker ".repeat(2).take(60)),
                SpeakerTone.Two,
                auto = true,
                startMs = 17_000,
            ),
        )
        rule.setContent {
            CassiniTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                    ProvideFixedFormat(locale = Locale.ENGLISH) {
                        NoteScreen(
                            BoardFixtures.readAndListen.copy(
                                meetingSpeakers = speakers,
                                activeWord = null,
                                playback = null,
                            ),
                            onEvent = { events += it },
                        )
                    }
                }
            }
        }
        for (id in listOf("short", "long")) {
            val button = rule.onNodeWithTag("meeting-speaker-$id").assertIsDisplayed()
            val bounds = button.getBoundsInRoot()
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
            assertEquals("Name this speaker", button.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            button.performClick()
        }
        rule.onNodeWithTag("meeting-speaker-long")
            .assert(hasContentDescription("named automatically", substring = true))
        rule.onNodeWithTag("meeting-speaker-short").assert(hasContentDescription("13 seconds", substring = true))
        val badge = rule.onNodeWithTag(
            "meeting-auto-long",
            useUnmergedTree = true,
        ).assertIsDisplayed().getBoundsInRoot()
        assertTrue(badge.right - badge.left > 20.dp)
        rule.runOnIdle { assertEquals(listOf(NoteEvent.SpeakerTap("short"), NoteEvent.SpeakerTap("long")), events) }
    }
}
