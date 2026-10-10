// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Progress
import org.cassini.ui.format.StageState
import org.cassini.ui.format.StageUi
import org.cassini.ui.theme.CassiniTheme

/**
 * The state of each stage when [current] is running: stages before it are done, later ones not started; Matching is
 * skipped when [skippedMatching] (no saved voices to match), wherever [current] is.
 */
fun stageStates(current: StageUi, skippedMatching: Boolean): List<StageState> =
    StageUi.entries.map { stage ->
        when {
            stage == StageUi.MATCHING && skippedMatching && stage != current -> StageState.SKIPPED
            stage < current -> StageState.DONE
            stage == current -> StageState.IN_PROGRESS
            else -> StageState.NOT_STARTED
        }
    }

/**
 * The five-step stepper of the Transcribing card (board 02): equal columns with a 4 dp gap; each has a 2 dp top
 * rule (accent when done or in progress, line2 otherwise), 4 dp padding and its label in `stage` text2, centred and
 * free to wrap. The current step is `stageCurrent` (600) in ink; a skipped step is struck through (no board). Each
 * step is one accessibility node, "Step 3 of 5: Words and voices", with the state "done", "in progress",
 * "not started" or "skipped". The row is a traversal group labelled "Stages", as the board's aria-label.
 */
@Composable
fun StageTracker(current: StageUi, skippedMatching: Boolean, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    val states = stageStates(current, skippedMatching)
    val label = stringResource(R.string.ui_stages)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                isTraversalGroup = true
            },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StageUi.entries.forEachIndexed { index, stage ->
            val state = states[index]
            val rule = if (state == StageState.DONE || state == StageState.IN_PROGRESS) c.accent else c.line2
            val description = Progress.stageDescription(stage)
            val stateText = Progress.stageState(state)
            Column(
                Modifier
                    .weight(1f)
                    .drawBehind { drawRect(rule, Offset.Zero, Size(size.width, 2.dp.toPx())) }
                    .padding(top = 6.dp, bottom = 4.dp)
                    .clearAndSetSemantics {
                        contentDescription = description
                        stateDescription = stateText
                    },
            ) {
                StageLabel(
                    text = Progress.stage(stage),
                    style = if (state == StageState.IN_PROGRESS) t.stageCurrent else t.stage,
                    color = if (state == StageState.IN_PROGRESS) c.ink else c.text2,
                    decoration = if (state == StageState.SKIPPED) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

/**
 * A stage label, centred and free to wrap between words. A column is about 66 dp wide, so a long word (Italian
 * "Preparazione", or any label at a large font scale) would break inside the word; then the label shrinks until its
 * longest word fits ([fitLongestWord]).
 */
@Composable
private fun StageLabel(text: String, style: TextStyle, color: Color, decoration: TextDecoration?) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val fitted = remember(text, style, maxWidthPx, measurer) { fitLongestWord(measurer, text, style, maxWidthPx) }
        Text(
            text = text,
            modifier = Modifier.fillMaxWidth(),
            style = fitted,
            color = color,
            textAlign = TextAlign.Center,
            textDecoration = decoration,
        )
    }
}

/**
 * [style], or a smaller copy of it (font size and line height scaled by the same factor, in 5 % steps) in which
 * the longest word of [text] fits in [maxWidthPx] on one line.
 */
internal fun fitLongestWord(measurer: TextMeasurer, text: String, style: TextStyle, maxWidthPx: Int): TextStyle {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty() || maxWidthPx <= 0) return style
    fun widest(candidate: TextStyle): Int =
        words.maxOf { measurer.measure(it, candidate, softWrap = false, maxLines = 1).size.width }
    var fitted = style
    var factor = 1f
    while (widest(fitted) > maxWidthPx && factor > MIN_LABEL_SCALE) {
        factor -= LABEL_SCALE_STEP
        fitted = style.copy(
            fontSize = style.fontSize * factor,
            lineHeight = if (style.lineHeight.isSp) style.lineHeight * factor else style.lineHeight,
        )
    }
    return fitted
}

private const val LABEL_SCALE_STEP = 0.05f

/** The smallest label scale; below it a word may break, as before. */
private const val MIN_LABEL_SCALE = 0.5f

@Preview
@Composable
internal fun StageTrackerPreview() {
    CassiniTheme {
        Column(
            Modifier
                .width(356.dp)
                .background(CassiniTheme.colors.surface)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StageTracker(StageUi.WORDS_AND_VOICES, skippedMatching = false)
            StageTracker(StageUi.SAVING, skippedMatching = true)
        }
    }
}
