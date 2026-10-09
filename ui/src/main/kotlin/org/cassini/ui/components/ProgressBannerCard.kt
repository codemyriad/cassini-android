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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Counts
import org.cassini.ui.theme.CassiniTheme

/** The Library banner's spoken percentage moves in steps of 5, so the live region does not chatter. */
private const val ANNOUNCE_STEP = 5

/**
 * The Library transcription banner (board 00): accentTint, radius 16, padding 12/14, gap 8. Row 1 is [title]
 * (`label14`, one line) baseline-aligned with [percent] (`monoTime`); then a 4 dp track filled to [progress]
 * (hidden when null) and [line] (`caption`, text3). A button with the click label "Open note" and a polite live
 * region. Its description changes only when [title] changes (a new stage or note) or [percent] crosses a 5 % step;
 * [line] is read with the description of that moment. The screen gives the margins (top 14, horizontal 16).
 */
@Composable
@Suppress("LongParameterList") // ui.md section 6: title, percent, progress, line and the click.
fun ProgressBannerCard(
    title: String,
    percent: Int?,
    progress: Float?,
    line: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CassiniTheme.colors
    val step = percent?.let { it / ANNOUNCE_STEP * ANNOUNCE_STEP }
    val stepText = step?.let { Counts.percent(it) }
    val announcedLine = remember(title, step) { line }
    val description = listOfNotNull(title, stepText, announcedLine).joinToString(", ")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(CassiniTheme.shapes.card)
            .background(c.accentTint)
            .clickable(
                onClickLabel = stringResource(R.string.ui_action_open_note),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                modifier = Modifier
                    .weight(1f)
                    .alignByBaseline(),
                style = CassiniTheme.type.label14,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (percent != null) {
                Text(
                    text = Counts.percent(percent),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .alignByBaseline(),
                    style = CassiniTheme.type.monoTime,
                    color = c.ink,
                )
            }
        }
        if (progress != null) LinearTrack(progress)
        Text(text = line, style = CassiniTheme.type.caption, color = c.text3)
    }
}

@Preview
@Composable
internal fun ProgressBannerCardPreview() {
    CassiniTheme {
        Box(
            Modifier
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
        ) {
            ProgressBannerCard(
                title = "Transcribing Weekly product sync",
                percent = 62,
                progress = 0.62f,
                line = "About 6–9 min left · 1 more in queue",
                onClick = {},
            )
        }
    }
}
