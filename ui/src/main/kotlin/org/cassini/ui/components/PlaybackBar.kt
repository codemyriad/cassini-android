// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.format.Durations
import org.cassini.ui.format.format
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph
import java.text.NumberFormat

/** The times row is drawn 4 dp higher than its layout slot, so the 48 dp slider keeps the board's 28 dp spacing. */
private val TimesLift = 4.dp

/**
 * The Read & listen player (board 03): surface with a 1 dp line top border; padding 16 horizontal and 22 at the
 * bottom (the 48 dp slider absorbs the board's top 10). The [CassiniSlider]; the times row (`monoSmall` text2:
 * [positionMs] and [durationMs]); then the controls centred 28 dp apart: back 10 s, play/pause ([playing]) and
 * forward 10 s. The bar is one accessibility traversal group. The surface reaches the bottom of the window: the
 * navigation-bar inset is padding inside the surface (zero in the screenshot tests). In a short window where the
 * slider keeps [COMPACT_MIN_SLIDER], the player is one row instead ([CompactPlaybackBar]).
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
    speed: SpeedControl? = null,
) {
    val windowHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() }
    val fixedWidth = compactFixedWidth(durationMs, speed != null)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (windowHeight < COMPACT_HEIGHT && maxWidth - fixedWidth >= COMPACT_MIN_SLIDER) {
            CompactPlaybackBar(
                positionMs,
                durationMs,
                playing,
                onSeek,
                onSkipBack,
                onSkipForward,
                onPlayPause,
                onSeekFinished,
                speed,
            )
        } else {
            TwoRowPlaybackBar(
                positionMs,
                durationMs,
                playing,
                onSeek,
                onSkipBack,
                onSkipForward,
                onPlayPause,
                onSeekFinished,
                speed,
            )
        }
    }
}

/** The player of board 03: the slider, the times row, then the controls. */
@Composable
@Suppress("LongParameterList") // The parameters of PlaybackBar.
private fun TwoRowPlaybackBar(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekFinished: (() -> Unit)?,
    speed: SpeedControl?,
) {
    val c = CassiniTheme.colors
    val group = stringResource(R.string.ui_player_group)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .drawBehind { drawRect(c.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }
            .semantics {
                isTraversalGroup = true
                contentDescription = group
            }
            .navigationBarsPadding()
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
            Text(
                text = Durations.clock(positionMs),
                style = CassiniTheme.type.monoSmall,
                color = c.text2,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                text = Durations.clock(durationMs),
                style = CassiniTheme.type.monoSmall,
                color = c.text2,
                maxLines = 1,
                softWrap = false,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkipButton(forward = false, onClick = onSkipBack)
                PlayPauseButton(playing = playing, onClick = onPlayPause)
                SkipButton(forward = true, onClick = onSkipForward)
            }
            // At the end, so the three controls stay centred as on board 03.
            if (speed != null) SpeedButton(speed, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/**
 * Below this window height the player is one row, so the transcript keeps the height. A phone in landscape is 410 to
 * 490 dp tall, depending on its display size; a phone upright is over 700 dp.
 */
private val COMPACT_HEIGHT = 600.dp

/**
 * The one row needs at least this much width left for the slider; else the player keeps its two rows. A narrow short
 * window (split screen upright, a small phone in landscape, large text, a note over an hour) would crush the slider
 * below its 48 dp touch target.
 */
private val COMPACT_MIN_SLIDER = 96.dp

private val CompactPadding = 16.dp
private val CompactGap = 12.dp
private val SkipSize = 52.dp
private val PlayPauseSize = 64.dp

/** Test tags of the two times of the one-row player, which TalkBack does not read: the slider says both. */
internal const val COMPACT_POSITION_TAG = "player-position"
internal const val COMPACT_LENGTH_TAG = "player-length"

/**
 * The width of everything but the slider in [CompactPlaybackBar]: the padding, the side navigation bar, the five gaps,
 * the three buttons and the two times at the current font scale. Both times are measured as the length, which is
 * never shorter than the position, so the layout does not change while the note plays.
 */
@Composable
private fun compactFixedWidth(durationMs: Long, speed: Boolean): Dp {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val insets = WindowInsets.navigationBars
    val sides = insets.getLeft(density, direction) + insets.getRight(density, direction)
    val measurer = rememberTextMeasurer()
    val style = CassiniTheme.type.monoSmall
    val time = remember(durationMs, style, density) {
        measurer.measure(Durations.clock(durationMs), style, maxLines = 1, softWrap = false).size.width
    }
    val speedWidth = if (speed) CompactGap + SkipSize else 0.dp
    return with(density) { (2 * time + sides).toDp() } + CompactPadding * 2 + CompactGap * 5 + SkipSize * 2 +
        PlayPauseSize + speedWidth
}

/**
 * The player in one row for a short window: back 10 s, play/pause, forward 10 s, the position, the slider and the
 * length. The same controls, semantics and callbacks as [PlaybackBar]; 8 dp padding above and below. The times stay on
 * one line; [PlaybackBar] chooses this row only when the slider keeps [COMPACT_MIN_SLIDER].
 */
@Composable
@Suppress("LongParameterList") // The parameters of PlaybackBar.
private fun CompactPlaybackBar(
    positionMs: Long,
    durationMs: Long,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekFinished: (() -> Unit)?,
    speed: SpeedControl?,
) {
    val c = CassiniTheme.colors
    val group = stringResource(R.string.ui_player_group)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(c.surface)
            .drawBehind { drawRect(c.line, Offset.Zero, Size(size.width, 1.dp.toPx())) }
            .semantics {
                isTraversalGroup = true
                contentDescription = group
            }
            .navigationBarsPadding()
            .padding(horizontal = CompactPadding, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CompactGap),
    ) {
        SkipButton(forward = false, onClick = onSkipBack)
        PlayPauseButton(playing = playing, onClick = onPlayPause)
        SkipButton(forward = true, onClick = onSkipForward)
        if (speed != null) SpeedButton(speed)
        Text(
            text = Durations.clock(positionMs),
            style = CassiniTheme.type.monoSmall,
            color = c.text2,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics { testTag = COMPACT_POSITION_TAG },
        )
        Box(Modifier.weight(1f)) { CassiniSlider(positionMs, durationMs, onSeek, onSeekFinished = onSeekFinished) }
        Text(
            text = Durations.clock(durationMs),
            style = CassiniTheme.type.monoSmall,
            color = c.text2,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.clearAndSetSemantics { testTag = COMPACT_LENGTH_TAG },
        )
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
            .size(SkipSize)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        CassiniIcon(if (forward) Glyph.Fwd10 else Glyph.Back10, 28.dp, 1.6f, c.ink, null)
        // The "10" is part of the 28 dp icon, so it does not follow the font scale (at 2.0 it covered the arrow).
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1f)) {
            Text(
                text = stringResource(R.string.ui_skip_seconds),
                style = CassiniTheme.type.skip,
                color = c.ink,
            )
        }
    }
}

/**
 * The speed button: a 52 dp tall rounded button with the speed ("1×", "1.25×", the number in the formatting locale) in
 * `label14` ink, at least 52 dp wide. Described with the catalog's "Playback speed, 1.25×" (`player.speed.a11y`); a
 * tap goes to the next speed.
 */
@Composable
fun SpeedButton(speed: SpeedControl, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val rate = stringResource(R.string.ui_speed_rate, speedNumber(speed.speed))
    val description = stringResource(R.string.ui_player_speed, rate)
    Box(
        modifier = modifier
            .heightIn(min = SkipSize)
            .widthIn(min = SkipSize)
            .clip(CassiniTheme.shapes.row)
            .clickable(role = Role.Button, onClick = speed.onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = rate, style = CassiniTheme.type.label14, color = c.ink, maxLines = 1, softWrap = false)
    }
}

/** 1, 1.25, 0.75 in the formatting locale ("1,25" in Italian), without trailing zeros. */
@Composable
private fun speedNumber(speed: Float): String {
    val locale = format().locale
    return remember(speed, locale) {
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }.format(speed.toDouble())
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
            .size(PlayPauseSize)
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
