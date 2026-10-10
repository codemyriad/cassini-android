// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Durations
import org.cassini.ui.format.Spoken
import org.cassini.ui.theme.CassiniTheme

/**
 * The Library card of an active recording (no board): rec.bg, radius 16, padding 12/14, gap 10. A 10 dp rec.dot
 * (a 1.5 dp rec.text2 ring when [paused]), "Recording · <title>" (or "Recording paused · <title>") in `label14`
 * rec.text on one line over "Tap to return" (`caption`, rec.text2), and the elapsed time (`monoTime`, rec.text2).
 * A button with the click label "Return to recording"; TalkBack reads the elapsed time in words ("24 minutes
 * 7 seconds"). The screen places it before the progress card, 8 dp apart.
 */
@Composable
fun RecordingBannerCard(
    title: String,
    elapsedMs: Long,
    paused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rec = CassiniTheme.recording
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(CassiniTheme.shapes.card)
            .background(rec.bg)
            .clickable(
                interactionSource = null,
                // rec.bg is dark in both themes: the theme's ink ripple would not show.
                indication = ripple(color = rec.text),
                onClickLabel = stringResource(R.string.ui_library_return_to_recording),
                role = Role.Button,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingDot(paused)
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(
                    if (paused) R.string.ui_library_recording_paused_card else R.string.ui_library_recording_card,
                    title,
                ),
                style = CassiniTheme.type.label14,
                color = rec.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.ui_library_recording_tap),
                style = CassiniTheme.type.caption,
                color = rec.text2,
            )
        }
        val spoken = Spoken.duration(elapsedMs)
        Text(
            text = Durations.clock(elapsedMs),
            modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
            style = CassiniTheme.type.monoTime,
            color = rec.text2,
        )
    }
}

/** The 10 dp live dot of the Recording palette; paused, a hollow ring with a 1.5 dp rec.text2 stroke. */
@Composable
internal fun RecordingDot(paused: Boolean, modifier: Modifier = Modifier) {
    val rec = CassiniTheme.recording
    Box(
        modifier
            .size(10.dp)
            .clip(CircleShape)
            .then(if (paused) Modifier.border(1.5.dp, rec.text2, CircleShape) else Modifier.background(rec.dot)),
    )
}

@Preview
@Composable
internal fun RecordingBannerCardPreview() {
    CassiniTheme {
        Box(
            Modifier
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
        ) {
            RecordingBannerCard("Weekly product sync", 1_447_000, paused = false, onClick = {})
        }
    }
}
