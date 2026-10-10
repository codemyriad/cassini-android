// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Durations
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/** The times row is drawn 4 dp higher than its layout slot, so the 48 dp slider keeps the board's 28 dp spacing. */
private val TimesLift = 4.dp

/**
 * The Read & listen player (board 03): surface with a 1 dp line top border; padding 16 horizontal and 22 at the
 * bottom (the 48 dp slider absorbs the board's top 10). The [CassiniSlider]; the times row (`monoSmall` text2:
 * [positionMs] and [durationMs]); then the controls centred 28 dp apart: back 10 s, play/pause ([playing]) and
 * forward 10 s. The bar is one accessibility traversal group.
 */
@Composable
@Suppress("LongParameterList") // ui.md section 6: the position, the play state and four callbacks.
fun PlaybackBar(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier,
    onSeekFinished: (() -> Unit)? = null,
) {
    val c = CassiniTheme.colors
    val group = stringResource(R.string.ui_player_group)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(c.surface)
            .drawBehind { drawRect(c.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }
            .semantics {
                isTraversalGroup = true
                contentDescription = group
            }
            .padding(start = 16.dp, end = 16.dp, top = 1.dp, bottom = 22.dp),
    ) {
        CassiniSlider(positionMs, durationMs, onSeek, onSeekFinished = onSeekFinished)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    val lift = TimesLift.roundToPx()
                    layout(placeable.width, placeable.height - lift) { placeable.place(0, -lift) }
                }
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = Durations.clock(positionMs), style = CassiniTheme.type.monoSmall, color = c.text2)
            Text(text = Durations.clock(durationMs), style = CassiniTheme.type.monoSmall, color = c.text2)
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SkipButton(forward = false, onClick = onSkipBack)
            PlayPauseButton(playing = playing, onClick = onPlayPause)
            SkipButton(forward = true, onClick = onSkipForward)
        }
    }
}

/**
 * A 52 dp transparent round button with Back10 or Fwd10 (28/1.6 ink) and "10" in `skip`, its 12 sp line centred as
 * on board 03 (ui.md says 2 dp below the centre; the board's span is centred). Described "Back 10 seconds" /
 * "Forward 10 seconds".
 */
@Composable
fun SkipButton(forward: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val description = stringResource(if (forward) R.string.ui_player_forward_10 else R.string.ui_player_back_10)
    Box(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CassiniIcon(if (forward) Glyph.Fwd10 else Glyph.Back10, 28.dp, 1.6f, c.ink, null)
        Text(
            text = stringResource(R.string.ui_skip_seconds),
            style = CassiniTheme.type.skip,
            color = c.ink,
        )
    }
}

/**
 * The 64 dp inkFill play/pause button: Pause 26 while [playing], else Play 26, in onInkFill. Described "Pause" or
 * "Play" with the state "Playing" or "Paused".
 */
@Composable
fun PlayPauseButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val description = stringResource(if (playing) R.string.ui_player_pause else R.string.ui_player_play)
    val state = stringResource(if (playing) R.string.ui_player_playing else R.string.ui_player_paused)
    Box(
        modifier = modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(c.inkFill)
            .clickable(
                interactionSource = null,
                indication = ripple(color = c.onInkFill),
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                contentDescription = description
                stateDescription = state
            },
        contentAlignment = Alignment.Center,
    ) {
        CassiniIcon(if (playing) Glyph.Pause else Glyph.Play, 26.dp, 0f, c.onInkFill, null)
    }
}

@Preview
@Composable
internal fun PlaybackBarPreview() {
    CassiniTheme {
        Box(Modifier.width(390.dp)) {
            PlaybackBar(
                positionMs = 704_000,
                durationMs = 1_430_000,
                playing = true,
                onSeek = {},
                onSkipBack = {},
                onSkipForward = {},
                onPlayPause = {},
            )
        }
    }
}
