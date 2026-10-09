// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
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
 * "not started" or "skipped".
 */
@Composable
fun StageTracker(current: StageUi, skippedMatching: Boolean, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    val states = stageStates(current, skippedMatching)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                Text(
                    text = Progress.stage(stage),
                    modifier = Modifier.fillMaxWidth(),
                    style = if (state == StageState.IN_PROGRESS) t.stageCurrent else t.stage,
                    color = if (state == StageState.IN_PROGRESS) c.ink else c.text2,
                    textAlign = TextAlign.Center,
                    textDecoration = if (state == StageState.SKIPPED) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

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
