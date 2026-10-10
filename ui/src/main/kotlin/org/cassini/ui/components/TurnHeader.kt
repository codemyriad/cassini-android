// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Durations
import org.cassini.ui.format.Spoken
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.SpeakerTone
import org.cassini.ui.theme.colors

/** The height of a turn header row: the 48 dp touch target. */
internal val TurnHeaderHeight = 48.dp

/**
 * A transcript turn's speaker label (boards 02 and 03), the same in Read and Draft: a full-width 48 dp row whose text
 * line sits 26 dp from the top with 6 dp below it (the board's 4 dp padding plus the paragraph's 2 dp margin), so
 * turns stack with no gap. The line: an 8 dp dot, [name] in `speaker` in the tone's ink colour, the start time in
 * `monoTime` text2 and, when [auto], the "auto" badge.
 *
 * With [onClick] (renaming allowed) the row is a button with the click label "Name this speaker"; its description is
 * "Giulia, named automatically, at 11 minutes 41 seconds".
 */
@Composable
@Suppress("LongParameterList") // ui.md section 6: name, tone, time, auto and the click.
fun TurnHeader(
    name: String,
    tone: SpeakerTone,
    timeMs: Long,
    auto: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(TurnHeaderHeight)
            .turnHeaderSemantics(
                description = Spoken.turn(name, timeMs, auto),
                clickLabel = stringResource(R.string.ui_turn_name_action),
                onClick = onClick,
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        TurnHeaderLine(name, tone, timeMs, auto, Modifier.padding(bottom = 6.dp))
    }
}

/**
 * The button and [description] of a turn header ([clickLabel] is "Name this speaker"); a header that cannot be
 * renamed ([onClick] null) is a plain described node.
 */
internal fun Modifier.turnHeaderSemantics(description: String, clickLabel: String, onClick: (() -> Unit)?): Modifier =
    this
        .then(
            if (onClick != null) {
                Modifier.clickable(onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
            } else {
                Modifier
            },
        )
        .semantics(mergeDescendants = true) { contentDescription = description }

/** The header's text line, without semantics: the node around it describes it. */
@Composable
internal fun TurnHeaderLine(
    name: String,
    tone: SpeakerTone,
    timeMs: Long,
    auto: Boolean,
    modifier: Modifier = Modifier,
) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    val speaker = tone.colors
    Row(
        modifier = modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(speaker.dot),
        )
        Text(
            text = name,
            modifier = Modifier.weight(1f, fill = false),
            style = t.speaker,
            color = speaker.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = Durations.clock(timeMs),
            style = t.monoTime.copy(lineHeight = t.speaker.lineHeight),
            color = c.text2,
            maxLines = 1,
        )
        if (auto) AutoBadge(stringResource(R.string.ui_turn_auto))
    }
}

@Preview
@Composable
internal fun TurnHeaderPreview() {
    CassiniTheme {
        Column(
            Modifier
                .width(350.dp)
                .background(CassiniTheme.colors.surface),
        ) {
            TurnHeader("Giulia", SpeakerTone.Blue, 692_000, auto = false, onClick = {})
            TurnHeader("Paolo Ferri", SpeakerTone.Teal, 701_000, auto = true, onClick = {})
            TurnHeader("Speaker 3", SpeakerTone.Amber, 740_000, auto = false, onClick = null)
        }
    }
}
