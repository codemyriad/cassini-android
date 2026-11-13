package io.codemyriad.cassini.ui.note

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import io.codemyriad.cassini.ui.components.TurnMode
import io.codemyriad.cassini.ui.components.TurnParagraph
import io.codemyriad.cassini.ui.components.WordUi
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-rUS-w390dp-h844dp-xhdpi")
class TranscribingDraftInkTest {
    @get:Rule val rule = createComposeRule()

    @Test fun stableWordsUseInkAndOnlyTheCurrentUnsettledWordsStayLighter() {
        var words by mutableStateOf(persistentListOf(WordUi("Stable", 0), WordUi("changing", 1, unsettled = true)))
        var boundary by mutableStateOf<Int?>(0)
        var ink = Color.Unspecified
        var lighter = Color.Unspecified
        rule.setContent {
            CassiniTheme {
                ink = CassiniTheme.colors.ink
                lighter = CassiniTheme.colors.text4
                // Explicit unsettled flags take precedence over this legacy boundary.
                TurnParagraph(words, null, {}, Modifier.testTag("draft-ink"), TurnMode.Draft, unsettledFrom = boundary)
            }
        }
        fun layout(): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithTag("draft-ink").fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
            return results.single()
        }
        val before = layout().layoutInput
        assertEquals(ink, before.style.color)
        assertEquals(lighter, before.text.spanStyles.single().item.color)
        assertEquals("changing", before.text.substring(before.text.spanStyles.single().start))
        rule.runOnIdle {
            words = persistentListOf(WordUi("Stable", 0), WordUi("settled", 1))
            boundary = null
        }
        val after = layout().layoutInput
        assertEquals(ink, after.style.color)
        assertTrue(after.text.spanStyles.isEmpty())
        rule.runOnIdle { boundary = 1 }
        val legacy = layout().layoutInput.text
        assertEquals(lighter, legacy.spanStyles.single().item.color)
        assertEquals("settled", legacy.substring(legacy.spanStyles.single().start))
    }
}
