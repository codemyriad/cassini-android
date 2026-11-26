// Composables are nouns in PascalCase; the numbers are the board values (Feature-BackgroundVoices 2F).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.TurnUi
import io.codemyriad.cassini.ui.components.WordRef
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/**
 * Where a turn stands in a run of consecutive background turns (cassini-ux D112 / L-86): the run's [key] (its first
 * turn's id), whether this turn is the [first] (it carries the folded row), and the number of distinct background
 * [voices] in the run.
 */
@Immutable
internal data class BackgroundRun(val key: String, val first: Boolean, val voices: Int)

/** The run of every background turn of [turns], by turn id; a turn of the conversation has none. */
internal fun backgroundRuns(turns: List<TurnUi>): Map<String, BackgroundRun> {
    val runs = HashMap<String, BackgroundRun>()
    var start = 0
    while (start < turns.size) {
        if (!turns[start].background) {
            start++
            continue
        }
        var end = start
        while (end + 1 < turns.size && turns[end + 1].background) end++
        val run = turns.subList(start, end + 1)
        val voices = run.mapTo(HashSet()) { it.speakerId }.size
        run.forEachIndexed { i, turn -> runs[turn.id] = BackgroundRun(run.first().id, i == 0, voices) }
        start = end + 1
    }
    return runs
}

/** [word], unless it is in a folded run of background turns: it plays without moving the list, with no line to follow. */
internal fun followed(word: WordRef?, runs: Map<String, BackgroundRun>, open: List<String>): WordRef? =
    word?.takeIf { runs[it.turnId]?.key?.let(open::contains) != false }

/**
 * The folded row of a run of background turns: "2 background voices hidden", text only, in the tertiary text colour
 * at 13 sp, starting on the paragraph edge, with a trailing chevron that points down ([open] up). The row toggles the
 * run; it keeps a 48 dp touch height.
 */
@Composable
internal fun BackgroundFold(run: BackgroundRun, open: Boolean, onToggle: () -> Unit) {
    val c = CassiniTheme.colors
    val text = Counts.plural(R.plurals.ui_bg_hidden, run.voices)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag("background-fold-${run.key}")
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                // TalkBack offers "Expand" or "Collapse", in the system language.
                if (open) {
                    collapse {
                        onToggle()
                        true
                    }
                } else {
                    expand {
                        onToggle()
                        true
                    }
                }
            },
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = CassiniTheme.type.caption, color = c.text3)
        CassiniIcon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, 14.dp, 1.8f, c.text3, null)
    }
}
