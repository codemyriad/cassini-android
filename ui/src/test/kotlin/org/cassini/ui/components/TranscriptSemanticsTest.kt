package org.cassini.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.format.FIXED_ZONE
import org.cassini.ui.format.ProvideFixedFormat
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.SpeakerTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

/**
 * Behaviour and semantics of the transcript and the player (docs/design/ui.md sections 6 and 11): word taps, the
 * recomposition scope of the active word, speaker labels as buttons, the suggestion card's header, the slider's
 * description and steps, and the player buttons.
 *
 * Accessibility Test Framework checks (`enableAccessibilityChecks()`) do not run under Robolectric (P2 finding, see
 * ScaffoldScreenshotTest); the P10 device flow tests run them on the emulator. Each transcript word is a link node
 * with a click action (no click label: see TurnParagraph). Native graphics give the text
 * its real geometry, which the word taps need.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w390dp-h844dp-xhdpi")
class TranscriptSemanticsTest {
    @get:Rule val rule = createComposeRule()

    private fun setThemed(content: @Composable () -> Unit) {
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = Locale.ENGLISH, is24Hour = true, zone = FIXED_ZONE, content = content)
            }
        }
    }

    @Test fun aTapOnAWordReportsItsIndex() {
        val taps = mutableListOf<Int>()
        val words = TranscriptFixtures.paolo
        setThemed {
            Box(Modifier.width(350.dp)) {
                TurnParagraph(
                    words = words,
                    activeIndex = TranscriptFixtures.PAOLO_ACTIVE,
                    onWordTap = { taps += it },
                    modifier = Modifier.testTag(PARAGRAPH),
                )
            }
        }
        val paragraph = rule.onNodeWithTag(PARAGRAPH)
        val layout = paragraph.textLayout()
        val starts = wordStarts(words)
        val tapped = listOf(0, TranscriptFixtures.PAOLO_ACTIVE, 12, words.lastIndex)
        for (index in tapped) {
            val centre = layout.getBoundingBox(starts[index] + words[index].text.length / 2).center
            paragraph.performTouchInput { click(centre) }
        }
        rule.runOnIdle { assertEquals(tapped, taps) }
        // A tap on the space after word 3 reaches one of its two neighbours.
        val space = layout.getBoundingBox(starts[3] + words[3].text.length).center
        paragraph.performTouchInput { click(space) }
        rule.runOnIdle {
            assertEquals(tapped.size + 1, taps.size)
            assertTrue("space tap went to ${taps.last()}", taps.last() in 3..4)
        }
        // Each word is its own link node.
        assertEquals(words.size, paragraph.linkCount())
    }

    /** The acceptance walk reads [ActiveWordStart] to see that the playing word is highlighted. */
    @Test fun onlyTheReadParagraphWithTheActiveWordReportsItsStart() {
        val words = TranscriptFixtures.paolo
        var active by mutableStateOf<Int?>(TranscriptFixtures.PAOLO_ACTIVE)
        setThemed {
            Column {
                TurnParagraph(words, active, onWordTap = {}, modifier = Modifier.testTag(PARAGRAPH))
                TurnParagraph(words, null, onWordTap = {}, modifier = Modifier.testTag(OTHER))
                TurnParagraph(words, 2, onWordTap = {}, modifier = Modifier.testTag(DRAFT), mode = TurnMode.Draft)
            }
        }
        fun start(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrNull(ActiveWordStart)
        assertEquals(words[TranscriptFixtures.PAOLO_ACTIVE].startMs, start(PARAGRAPH))
        assertEquals(null, start(OTHER))
        assertEquals(null, start(DRAFT))
        active = 3
        rule.waitForIdle()
        assertEquals(words[3].startMs, start(PARAGRAPH))
        active = null
        rule.waitForIdle()
        assertEquals(null, start(PARAGRAPH))
    }

    @Test fun aDraftParagraphHasNoLinks() {
        setThemed {
            TurnParagraph(
                words = TranscriptFixtures.draftAfterCard,
                activeIndex = null,
                onWordTap = {},
                modifier = Modifier.testTag(PARAGRAPH),
                mode = TurnMode.Draft,
                unsettledFrom = TranscriptFixtures.DRAFT_UNSETTLED_FROM,
            )
        }
        assertEquals(0, rule.onNodeWithTag(PARAGRAPH).linkCount())
    }

    @Test fun movingTheActiveWordRecomposesOnlyTheTurnsThatHoldIt() {
        val turns = listOf(TranscriptFixtures.giulia, TranscriptFixtures.paolo, TranscriptFixtures.speaker3)
        val counts = IntArray(turns.size)
        var active by mutableStateOf(0 to 2)
        val onTap: (Int) -> Unit = {}
        setThemed {
            LazyColumn {
                items(turns.size, key = { it }) { index ->
                    val probe: () -> Unit = remember(index) { { counts[index] += 1 } }
                    CompositionLocalProvider(LocalTurnParagraphProbe provides probe) {
                        TurnParagraph(turns[index], if (active.first == index) active.second else null, onTap)
                    }
                }
            }
        }
        rule.runOnIdle {
            assertEquals(listOf(1, 1, 1), counts.toList())
            counts.fill(0)
            active = 1 to TranscriptFixtures.PAOLO_ACTIVE
        }
        rule.runOnIdle {
            assertEquals("old and new turn only", listOf(1, 1, 0), counts.toList())
            counts.fill(0)
            active = 1 to TranscriptFixtures.PAOLO_ACTIVE + 1
        }
        rule.runOnIdle { assertEquals("the same turn only", listOf(0, 1, 0), counts.toList()) }
    }

    @Test fun aTurnHeaderIsAButtonNamedForTheSpeaker() {
        var clicks = 0
        setThemed {
            Column(Modifier.width(350.dp)) {
                TurnHeader("Paolo Ferri", SpeakerTone.Teal, 701_000, auto = true, onClick = { clicks++ })
                TurnHeader("Speaker 3", SpeakerTone.Amber, 740_000, auto = false, onClick = null)
            }
        }
        val paolo = rule.onNode(hasContentDescription("Paolo Ferri, named automatically, at 11 minutes 41 seconds"))
        paolo.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("Name this speaker", paolo.clickLabel())
        paolo.assertTouchAtLeast48().performClick()
        rule.runOnIdle { assertEquals(1, clicks) }
        // Renaming not allowed: still described, but not a button.
        rule.onNode(hasContentDescription("Speaker 3, at 12 minutes 20 seconds")).assertHasNoClickAction()
        // The visible line adds no second description.
        rule.onAllNodesWithContentDescription("Paolo Ferri", substring = true).assertCountEquals(1)
    }

    @Test fun theSuggestionHeaderIsA48dpButtonAboveTheQuestion() {
        val events = mutableListOf<String>()
        setThemed {
            Box(Modifier.width(350.dp)) {
                SuggestionCard(
                    speaker = "Speaker 2",
                    tone = SpeakerTone.Amber,
                    timeMs = 1_742_000,
                    name = "Luca",
                    onYes = { events += "yes" },
                    onNo = { events += "no" },
                    onSpeaker = { events += "speaker" },
                )
            }
        }
        val header = rule.onNode(hasContentDescription("Speaker 2, at 29 minutes 2 seconds"))
        header.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertEquals("Name this speaker", header.clickLabel())
        header.assertTouchAtLeast48()
        // A tap on the visible speaker line, 18 dp from the card's top, opens the Name sheet.
        header.performTouchInput { click(Offset(40.dp.toPx(), 18.dp.toPx())) }
        rule.onNodeWithText("Yes, Luca").assertTouchAtLeast48().performClick()
        rule.onNodeWithText("Not Luca").assertTouchAtLeast48().performClick()
        rule.runOnIdle { assertEquals(listOf("speaker", "yes", "no"), events) }
        rule.onNodeWithText("Is this Luca?").assertExists()
    }

    @Test fun theSliderIsPositionWithTenSecondSteps() {
        val seeks = mutableListOf<Long>()
        setThemed { CassiniSlider(704_000, 1_430_000, onSeek = { seeks += it }) }
        val slider = rule.onNodeWithContentDescription("Position")
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "11:44 of 23:50"))
        slider.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(704_000f, 0f..1_430_000f, steps = 142),
            ),
        )
        slider.assertTouchAtLeast48()
        val setProgress = slider.fetchSemanticsNode().config[SemanticsActions.SetProgress]
        rule.runOnIdle { setProgress.action?.invoke(714_000f) }
        assertEquals(listOf(714_000L), seeks)
    }

    @Test fun thePlayerButtonsAreDescribedAndBigEnough() {
        val events = mutableListOf<String>()
        var playing by mutableStateOf(true)
        setThemed {
            PlaybackBar(
                positionMs = 704_000,
                durationMs = 1_430_000,
                playing = playing,
                onSeek = {},
                onSkipBack = { events += "back" },
                onSkipForward = { events += "forward" },
                onPlayPause = { events += "play" },
            )
        }
        rule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.IsTraversalGroup, true))
            .assert(hasContentDescription("Playback"))
        rule.onNodeWithContentDescription("Pause")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Playing"))
            .assertTouchAtLeast48()
            .performClick()
        rule.onNodeWithContentDescription("Back 10 seconds")
            .assertHasClickAction()
            .assertTouchAtLeast48()
            .performClick()
        rule.onNodeWithContentDescription("Forward 10 seconds").assertTouchAtLeast48().performClick()
        rule.runOnIdle {
            assertEquals(listOf("play", "back", "forward"), events)
            playing = false
        }
        rule.onNodeWithContentDescription("Play")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Paused"))
    }

    @Test fun searchMatchesKeepTheWordsTappable() {
        val taps = mutableListOf<Int>()
        val words = TranscriptFixtures.paolo
        val match = TranscriptFixtures.matchesOf(words, "consegna").first()
        setThemed {
            TurnParagraph(
                words = words,
                activeIndex = null,
                onWordTap = { taps += it },
                modifier = Modifier.testTag(PARAGRAPH),
                matches = TurnMatches(persistentListOf(match), current = 0),
            )
        }
        val paragraph = rule.onNodeWithTag(PARAGRAPH)
        val middle = (match.first + match.last) / 2
        val centre = paragraph.textLayout().getBoundingBox(middle).center
        paragraph.performTouchInput { click(centre) }
        rule.runOnIdle { assertEquals(listOf(wordAt(words, middle)), taps) }
    }

    private fun wordAt(words: List<WordUi>, offset: Int): Int = wordStarts(words).indexOfLast { it <= offset }

    private fun SemanticsNodeInteraction.textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun SemanticsNodeInteraction.linkCount(): Int =
        fetchSemanticsNode().children.count { it.config.contains(SemanticsProperties.LinkTestMarker) }

    private fun SemanticsNodeInteraction.clickLabel(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsActions.OnClick)?.label

    private fun SemanticsNodeInteraction.assertTouchAtLeast48() = assertTouchAtLeast48(rule.density)

    private companion object {
        const val PARAGRAPH = "paragraph"
        const val OTHER = "other"
        const val DRAFT = "draft"
    }
}
