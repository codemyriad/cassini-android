package io.codemyriad.cassini.ui.note

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.fixtures.BoardFixtures
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.ProvideFixedFormat
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

/**
 * Phase 1 WP6 Name a speaker sheet copy, semantics, duplicate-notice bold span and native controls across all six app
 * locales (L-14, L-41, L-46).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NameSpeakerLocalesTest {
    @get:Rule val rule = createComposeRule()

    @Test
    @Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
    fun english() = assertNameSpeakerLocale("en-US")

    @Test
    @Config(qualifiers = "it-rIT-w390dp-h844dp-xhdpi")
    fun italian() = assertNameSpeakerLocale("it-IT")

    @Test
    @Config(qualifiers = "fr-rFR-w390dp-h844dp-xhdpi")
    fun french() = assertNameSpeakerLocale("fr-FR")

    @Test
    @Config(qualifiers = "es-rES-w390dp-h844dp-xhdpi")
    fun spanish() = assertNameSpeakerLocale("es-ES")

    @Test
    @Config(qualifiers = "pt-rBR-w390dp-h844dp-xhdpi")
    fun brazilianPortuguese() = assertNameSpeakerLocale("pt-BR")

    @Test
    @Config(qualifiers = "pt-rPT-w390dp-h844dp-xhdpi")
    fun europeanPortuguese() = assertNameSpeakerLocale("pt-PT")

    private fun assertNameSpeakerLocale(tag: String) {
        val resources = ApplicationProvider.getApplicationContext<Context>().resources
        val locale = Locale.forLanguageTag(tag)
        assertEquals(tag, resources.configuration.locales[0].toLanguageTag())

        var ui by mutableStateOf(BoardFixtures.nameSpeaker)
        val events = mutableListOf<NameSheetEvent>()
        rule.setContent {
            CassiniTheme(darkTheme = false) {
                ProvideFixedFormat(locale = locale) {
                    NameSpeakerSheet(ui = ui, onEvent = { events += it }, onDismiss = {})
                }
            }
        }
        rule.waitForIdle()

        assertWindowHeaderQuoteAndNotice(resources, ui, tag)
        assertVoicesRememberAndFooter(resources, tag, events) { next -> rule.runOnIdle { ui = next } }
    }

    private fun assertWindowHeaderQuoteAndNotice(resources: Resources, ui: NameSheetUi, tag: String) {
        val view = (rule.onNodeWithTag("remember-voice").fetchSemanticsNode().root as ViewRootForTest).view
        val window = checkNotNull(
            generateSequence(view.parent) { it.parent }.filterIsInstance<DialogWindowProvider>().firstOrNull()?.window,
        )
        assertEquals(resources.getString(R.string.ui_sp_dialog_a11y), window.attributes.title.toString())
        assertEquals("Texts that do not fit ($tag, default)", emptyList<String>(), cutTexts())

        val heading = rule.onNodeWithText(resources.getString(R.string.ui_speaker_label, 3)).fetchSemanticsNode()
        assertTrue(SemanticsProperties.Heading in heading.config)
        rule.onNodeWithText(Durations.speaksFor(resources, ui.speakingMs)).assertExists()
        val playBounds = rule.onNodeWithContentDescription(resources.getString(R.string.ui_sp_play_sample))
            .assertIsDisplayed()
            .performClick()
            .getUnclippedBoundsInRoot()
        assertTrue(playBounds.right - playBounds.left >= 48.dp && playBounds.bottom - playBounds.top >= 48.dp)

        rule.onNodeWithText(resources.getString(R.string.ui_sp_quote, ui.quote)).assertExists()
        rule.onNodeWithText(resources.getString(R.string.ui_sp_name)).assertExists()
        rule.onNode(hasSetTextAction()).assertHeightIsAtLeast(48.dp)

        val same = requireNotNull(ui.samePerson)
        val notice = resources.getString(R.string.ui_sp_duplicate_notice, same)
        val annotated = rule.onNodeWithText(notice).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        val span = annotated.spanStyles.single()
        val expectedStart = resources.getString(R.string.ui_sp_duplicate_notice, "\u0000").indexOf('\u0000')
        assertEquals(FontWeight.SemiBold, span.item.fontWeight)
        assertEquals(expectedStart, span.start)
        assertEquals(expectedStart + same.length, span.end)
        rule.onNodeWithText(resources.getString(R.string.ui_sp_same_person)).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText(resources.getString(R.string.ui_sp_someone_else)).assertHeightIsAtLeast(48.dp).performClick()
    }

    private fun assertVoicesRememberAndFooter(
        resources: Resources,
        tag: String,
        events: List<NameSheetEvent>,
        update: (NameSheetUi) -> Unit,
    ) {
        rule.onNodeWithText(resources.getString(R.string.ui_sp_voices_ranked)).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.ui_sp_likely), useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Annalisa B.").performScrollTo().assertHeightIsAtLeast(56.dp).performClick()

        val switchMatcher = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        rule.onAllNodes(switchMatcher).assertCountEquals(1)
        val namedCopy = resources.getString(R.string.ui_sp_remember_body, "Anna")
        assertTrue(namedCopy.contains('\n'))
        rule.onNodeWithText(namedCopy, substring = true).assertExists()
        rule.onNode(switchMatcher).performScrollTo().assertIsOn().assertIsEnabled().assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.onNodeWithText(resources.getString(R.string.ui_sp_merge)).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText(resources.getString(R.string.ui_action_cancel)).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithText(resources.getString(R.string.ui_sp_save)).assertHeightIsAtLeast(48.dp)

        val base = BoardFixtures.nameSpeaker
        update(
            base.copy(name = "", ranked = false, samePerson = "a", rememberEnabled = false, rememberReason = RememberReason.NO_MODEL),
        )
        rule.waitForIdle()
        assertEquals("Texts that do not fit ($tag, variants)", emptyList<String>(), cutTexts())
        rule.onNodeWithText(resources.getString(R.string.ui_sp_voices)).performScrollTo().assertExists()
        rule.onNodeWithTag("remember-voice").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText(resources.getString(R.string.ui_sp_remember_models), substring = true).assertExists()
        val shortSpan = rule.onNodeWithText(resources.getString(R.string.ui_sp_duplicate_notice, "a"))
            .fetchSemanticsNode().config[SemanticsProperties.Text].single().spanStyles.single()
        val shortStart = resources.getString(R.string.ui_sp_duplicate_notice, "\u0000").indexOf('\u0000')
        assertEquals(shortStart to (shortStart + 1), shortSpan.start to shortSpan.end)

        update(base.copy(name = "", samePerson = null))
        rule.waitForIdle()
        val unnamedCopy = resources.getString(R.string.ui_sp_remember_unnamed)
        assertTrue(unnamedCopy.contains('\n'))
        rule.onNodeWithText(unnamedCopy, substring = true).assertExists()

        update(base.copy(samePerson = null, rememberEnabled = false, rememberReason = RememberReason.TOO_LITTLE_SPEECH))
        rule.waitForIdle()
        rule.onNodeWithText(resources.getString(R.string.ui_sp_remember_too_short), substring = true).assertExists()
        rule.runOnIdle {
            assertEquals(
                listOf(
                    NameSheetEvent.PlaySample,
                    NameSheetEvent.SamePerson,
                    NameSheetEvent.SomeoneElse,
                    NameSheetEvent.PickVoice("annalisa"),
                    NameSheetEvent.RememberChanged(false),
                    NameSheetEvent.MergeInto,
                    NameSheetEvent.Cancel,
                ),
                events,
            )
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
}
