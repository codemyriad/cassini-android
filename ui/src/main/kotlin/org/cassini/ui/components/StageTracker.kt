// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.platform.LocalDensity
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
 * free to wrap. At large font scales, when the labels do not fit five columns, the steps take more rows (3 + 2,
 * then pairs) with the same 4 dp gaps; no board. The current step is `stageCurrent` (600) in ink; a skipped step is
 * struck through (no board). Each step is one accessibility node, "Step 3 of 5: Words and voices", with the state
 * "done", "in progress", "not started" or "skipped". The stepper is a traversal group labelled "Stages", as the
 * board's aria-label.
 */
@Composable
fun StageTracker(current: StageUi, skippedMatching: Boolean, modifier: Modifier = Modifier) {
    val states = stageStates(current, skippedMatching)
    val label = stringResource(R.string.ui_stages)
    val labels = StageUi.entries.map { Progress.stage(it) }
    val style = CassiniTheme.type.stageCurrent
    val measurer = rememberTextMeasurer()
    val fontScale = LocalDensity.current.fontScale
    val minScale = if (fontScale <= DEFAULT_SCALE_LIMIT) MIN_LABEL_SCALE else MIN_LARGE_LABEL_SCALE
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = label
                isTraversalGroup = true
            },
    ) {
        val gapPx = with(LocalDensity.current) { StageGap.roundToPx() }
        val widthPx = constraints.maxWidth
        val perRow = remember(labels, style, widthPx, minScale, measurer) {
            stagesPerRow(widestWord(measurer, labels, style), widthPx, gapPx, minScale)
        }
        Column(verticalArrangement = Arrangement.spacedBy(StageGap)) {
            StageUi.entries.withIndex().chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(StageGap)) {
                    row.forEach { (index, stage) -> Stage(stage, states[index], minScale) }
                    // Keep the columns of a short last row as wide as the others.
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** One step: the 2 dp top rule, 4 dp padding and the label, as one accessibility node. */
@Composable
private fun RowScope.Stage(stage: StageUi, state: StageState, minScale: Float) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
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
            minScale = minScale,
        )
    }
}

/** The width in pixels of the widest word of [labels] in [style], on one line. */
internal fun widestWord(measurer: TextMeasurer, labels: List<String>, style: TextStyle): Int =
    labels.flatMap { label -> label.split(' ').filter { it.isNotEmpty() } }
        .maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0

/**
 * How many steps share a row: all five when the widest label word ([widestPx]) fits its column at [minScale] or
 * more, else 3, 2 or 1 per row. At large font scales the labels so keep most of their size (features.md section 14).
 */
internal fun stagesPerRow(widestPx: Int, widthPx: Int, gapPx: Int, minScale: Float): Int {
    if (widestPx <= 0 || widthPx <= 0) return StageUi.entries.size
    return ROW_SIZES.firstOrNull { n -> widestPx * minScale <= (widthPx - gapPx * (n - 1)) / n } ?: 1
}

private val StageGap = 4.dp

/** The row sizes to try, widest first: five steps, then 3 + 2, then pairs. */
private val ROW_SIZES = listOf(5, 3, 2)

/** Up to this font scale, the labels may shrink to [MIN_LABEL_SCALE] (long Italian words at the default size). */
private const val DEFAULT_SCALE_LIMIT = 1.15f

/**
 * A stage label, centred and free to wrap between words. A column is about 66 dp wide, so a long word (Italian
 * "Preparazione") would break inside the word; then the label shrinks until its longest word fits
 * ([fitLongestWord]), down to [minScale]. At large font scales [stagesPerRow] gives the columns more room first.
 */
@Composable
private fun StageLabel(text: String, style: TextStyle, color: Color, decoration: TextDecoration?, minScale: Float) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxWidthPx = constraints.maxWidth
        val fitted = remember(text, style, maxWidthPx, measurer, minScale) {
            fitLongestWord(measurer, text, style, maxWidthPx, minScale)
        }
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
internal fun fitLongestWord(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    maxWidthPx: Int,
    minScale: Float = MIN_LABEL_SCALE,
): TextStyle {
    val words = text.split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty() || maxWidthPx <= 0) return style
    fun widest(candidate: TextStyle): Int =
        words.maxOf { measurer.measure(it, candidate, softWrap = false, maxLines = 1).size.width }
    var fitted = style
    var factor = 1f
    while (widest(fitted) > maxWidthPx && factor > minScale) {
        factor -= LABEL_SCALE_STEP
        fitted = style.copy(
            fontSize = style.fontSize * factor,
            lineHeight = if (style.lineHeight.isSp) style.lineHeight * factor else style.lineHeight,
        )
    }
    return fitted
}

private const val LABEL_SCALE_STEP = 0.05f

/** The smallest label scale at the default font size; below it a word may break. */
private const val MIN_LABEL_SCALE = 0.5f

/** The smallest label scale at large font scales: the stepper takes more rows before the labels shrink further. */
private const val MIN_LARGE_LABEL_SCALE = 0.85f

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
