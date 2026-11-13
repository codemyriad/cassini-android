package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.theme.DarkColors
import io.codemyriad.cassini.ui.theme.LightColors
import org.junit.Assert.assertEquals

/** Check the actual meeting paragraphs, including the unnamed speaker, rather than a bare default paragraph. */
internal fun BoardTest.assertSevenSpeakerWordsInk(dark: Boolean) {
    val words = rule.onAllNodesWithText("Ready.", useUnmergedTree = true).fetchSemanticsNodes()
    assertEquals(7, words.size)
    val ink = if (dark) DarkColors.ink else LightColors.ink
    for (node in words) {
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals(ink, layouts.single().layoutInput.style.color)
    }
}
