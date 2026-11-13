package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.format.TimeLeftUi
import io.codemyriad.cassini.ui.theme.CassiniTheme
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
class TranscribingStatusTest {
    @get:Rule val rule = createComposeRule()
    private val card = (BoardFixtures.transcribing.mode as NoteMode.Processing).card

    @Test fun detailsAndStopKeepSeparateCallbacksAndTheBusyStopIsDisabled() {
        var busy by mutableStateOf(false)
        var details = 0
        var stops = 0
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) {
                    TranscribingStatus(card, busy, { details++ }, { stops++ })
                }
            }
        }
        rule.onNodeWithTag("transcribing-details").performClick()
        rule.onNodeWithTag("transcribing-stop").performClick()
        rule.runOnIdle {
            assertEquals(1, details)
            assertEquals(1, stops)
            busy = true
        }
        rule.onNodeWithTag("transcribing-stop").assertIsNotEnabled()
        rule.onNodeWithTag("transcribing-details").performClick()
        rule.runOnIdle { assertEquals(2, details) }
    }

    @Test fun aLiveEtaUpdateIsVisibleWithoutReopeningTheDetailsAction() {
        var progress by mutableStateOf(card.copy(timeLeft = TimeLeftUi.Calculating))
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = Locale.ENGLISH) { TranscribingStatus(progress, false, {}, {}) }
            }
        }
        rule.onNodeWithTag("transcribing-details")
            .assertContentDescriptionContains("calculating time left", substring = true)
        rule.runOnIdle { progress = progress.copy(timeLeft = TimeLeftUi.Range(240_000, 240_000)) }
        rule.onNodeWithTag("transcribing-details").assertContentDescriptionContains("4", substring = true)
    }

    @Test
    @Config(qualifiers = "fr-rFR-w390dp-h844dp-xhdpi")
    fun frenchRangeKeepsTheCompactStatusOnOneLine() = compactLine(Locale.FRENCH, "Transcription")

    @Test
    @Config(qualifiers = "it-rIT-w390dp-h844dp-xhdpi")
    fun italianRangeKeepsTheCompactStatusOnOneLine() = compactLine(Locale.ITALIAN, "In trascrizione")

    private fun compactLine(locale: Locale, heading: String) {
        rule.setContent {
            CassiniTheme {
                ProvideFixedFormat(locale = locale) { TranscribingStatus(card, false, {}, {}) }
            }
        }
        val nodes = rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val layouts = nodes.flatMap { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            results
        }
        val status = layouts.single { it.layoutInput.text.text.startsWith(heading) }
        assertEquals(status.layoutInput.text.text, 1, status.lineCount)
    }

    @Test fun largeTextWrapsWithoutCoveringEitherNativeTouchTarget() {
        rule.setContent {
            CassiniTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    ProvideFixedFormat(locale = Locale.ENGLISH) { TranscribingStatus(card, false, {}, {}) }
                }
            }
        }
        val details = rule.onNodeWithTag("transcribing-details").assertIsDisplayed().getUnclippedBoundsInRoot()
        val stop = rule.onNodeWithTag("transcribing-stop").assertIsDisplayed().getUnclippedBoundsInRoot()
        assertTrue(details.right <= stop.left)
        for (bounds in listOf(details, stop)) {
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
    }
}
