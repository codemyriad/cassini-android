package io.codemyriad.cassini.ui.recording

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.DarkColors
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
 * Phase 1 WP4 recording controls, semantics and night ground across all six app locales (L-08, L-48, L-49).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecordingLocalesTest {
    @get:Rule val rule = createComposeRule()

    @Test
    @Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
    fun english() = assertRecordingLocale("en-US")

    @Test
    @Config(qualifiers = "it-rIT-w390dp-h844dp-xhdpi")
    fun italian() = assertRecordingLocale("it-IT")

    @Test
    @Config(qualifiers = "fr-rFR-w390dp-h844dp-xhdpi")
    fun french() = assertRecordingLocale("fr-FR")

    @Test
    @Config(qualifiers = "es-rES-w390dp-h844dp-xhdpi")
    fun spanish() = assertRecordingLocale("es-ES")

    @Test
    @Config(qualifiers = "pt-rBR-w390dp-h844dp-xhdpi")
    fun brazilianPortuguese() = assertRecordingLocale("pt-BR")

    @Test
    @Config(qualifiers = "pt-rPT-w390dp-h844dp-xhdpi")
    fun europeanPortuguese() = assertRecordingLocale("pt-PT")

    private fun assertRecordingLocale(tag: String) {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val locale = Locale.forLanguageTag(tag)
        assertEquals(tag, resources.configuration.locales[0].toLanguageTag())

        var phase by mutableStateOf(RecordingPhase.Recording)
        val events = mutableListOf<RecordingEvent>()
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = locale) {
                    RecordingScreen(
                        state = BoardFixtures.recording.copy(phase = phase),
                        onEvent = { events += it },
                        modifier = Modifier.testTag(VIEWPORT_TAG),
                    )
                }
            }
        }

        assertNightGroundAndEditableTitle(resources)
        exerciseActiveAndPausedControls(resources, tag) { next -> rule.runOnIdle { phase = next } }
        rule.runOnIdle {
            assertEquals(
                listOf(
                    RecordingEvent.Minimise,
                    RecordingEvent.OpenLibrary,
                    RecordingEvent.Finish,
                    RecordingEvent.PauseResume,
                    RecordingEvent.PauseResume,
                ),
                events,
            )
        }
    }

    private fun assertNightGroundAndEditableTitle(resources: Resources) {
        val pixels = rule.onRoot().captureToImage().toPixelMap()
        assertEquals(DarkColors.bg, pixels[0, 0])
        val titleLabel = resources.getString(R.string.ui_title_label)
        rule.onNodeWithText(titleLabel).assertDoesNotExist()
        val titleBounds = rule.onNodeWithContentDescription(titleLabel)
            .assert(hasSetTextAction())
            .getUnclippedBoundsInRoot()
        assertTrue(titleBounds.bottom - titleBounds.top >= 48.dp)
    }

    private fun exerciseActiveAndPausedControls(
        resources: Resources,
        tag: String,
        setPhase: (RecordingPhase) -> Unit,
    ) {
        assertControlTargets(resources, paused = false)
        assertEquals("Texts that do not fit ($tag, active)", emptyList<String>(), cutTexts())
        rule.onNodeWithContentDescription(resources.getString(R.string.ui_recording_minimise))
            .assertIsDisplayed()
            .performClick()
        rule.onNodeWithText(resources.getString(R.string.ui_recording_library)).performClick()
        rule.onNodeWithText(resources.getString(R.string.ui_recording_finish)).performClick()
        rule.onNodeWithContentDescription(resources.getString(R.string.ui_recording_pause))
            .assert(hasText(resources.getString(R.string.ui_recording_pause_label)))
            .performClick()

        setPhase(RecordingPhase.Paused)
        assertControlTargets(resources, paused = true)
        assertEquals("Texts that do not fit ($tag, paused)", emptyList<String>(), cutTexts())
        rule.onNodeWithContentDescription(resources.getString(R.string.ui_recording_resume))
            .assert(hasText(resources.getString(R.string.ui_recording_resume_label)))
            .performClick()
    }

    private fun assertControlTargets(resources: Resources, paused: Boolean) {
        val library = rule.onNodeWithText(resources.getString(R.string.ui_recording_library))
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val stop = rule.onNodeWithText(resources.getString(R.string.ui_recording_finish))
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        val spoken = resources.getString(if (paused) R.string.ui_recording_resume else R.string.ui_recording_pause)
        val toggle = rule.onNodeWithContentDescription(spoken)
            .assertIsDisplayed()
            .getUnclippedBoundsInRoot()
        assertTrue(library.right <= stop.left)
        assertTrue(stop.right <= toggle.left)
        val window = rule.onNodeWithTag(VIEWPORT_TAG).getBoundsInRoot()
        assertEquals((window.left.value + window.right.value) / 2, (stop.left.value + stop.right.value) / 2, 1f)
        for (index in 0..1) {
            val circle = rule.onAllNodesWithTag(SIDE_CIRCLE_TAG, useUnmergedTree = true)[index]
                .getUnclippedBoundsInRoot()
            assertEquals((circle.top.value + circle.bottom.value) / 2, (stop.top.value + stop.bottom.value) / 2, 1f)
        }
        for (bounds in listOf(library, stop, toggle)) {
            assertTrue(bounds.top >= window.top && bounds.bottom <= window.bottom)
            assertTrue(bounds.left >= window.left && bounds.right <= window.right)
            assertTrue(bounds.right - bounds.left >= 48.dp)
            assertTrue(bounds.bottom - bounds.top >= 48.dp)
        }
    }

    private fun cutTexts(): List<String> = rule.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().mapNotNull(::overflowDescription)

    private fun overflowDescription(node: SemanticsNode): String? {
        val results = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val layout = results.firstOrNull() ?: return null
        val lines = 0 until layout.lineCount
        val how = when {
            lines.any(layout::isLineEllipsized) -> "ellipsized"
            layout.multiParagraph.didExceedMaxLines -> "more lines than maxLines"
            layout.multiParagraph.height > layout.size.height + 1 -> "cut at the bottom"
            lines.any { layout.getLineRight(it) - layout.getLineLeft(it) > node.size.width + 1 } -> "cut at the side"
            else -> null
        }
        return how?.let { "${layout.layoutInput.text} ($it)" }
    }

    private companion object {
        const val VIEWPORT_TAG = "recording-viewport"
        const val SIDE_CIRCLE_TAG = "recording-side-circle"
    }
}
