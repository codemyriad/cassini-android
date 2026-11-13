package io.codemyriad.cassini.ui.screens

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import io.codemyriad.cassini.ui.BoardTest
import io.codemyriad.cassini.ui.theme.DarkColors
import io.codemyriad.cassini.ui.theme.LightColors
import org.junit.Assert.assertEquals

/** Check every actual paragraph, including those virtualised out of the initial meeting viewport. */
internal fun BoardTest.assertSevenSpeakerWordsInk(dark: Boolean) = rule.assertSevenSpeakerWordsInk(dark)

internal fun androidx.compose.ui.test.junit4.ComposeContentTestRule.assertSevenSpeakerWordsInk(dark: Boolean) {
    val paused = !mainClock.autoAdvance
    mainClock.autoAdvance = true
    try {
        val ink = if (dark) DarkColors.ink else LightColors.ink
        for (index in 0 until 7) {
            val turn = androidx.compose.ui.test.hasTestTag("transcript-turn-seven-$index")
            onNodeWithTag("transcript-list").performScrollToNode(turn)
            val word = onNode(
                androidx.compose.ui.test.hasText("Ready.") and androidx.compose.ui.test.hasAnyAncestor(turn),
                useUnmergedTree = true,
            ).fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            word.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            assertEquals("speaker $index paragraph keeps neutral ink", ink, layouts.single().layoutInput.style.color)
        }
    } finally {
        mainClock.autoAdvance = !paused
    }
}
