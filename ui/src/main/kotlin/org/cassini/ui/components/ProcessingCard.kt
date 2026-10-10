// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.toImmutableList
import org.cassini.ui.R
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.format.BatteryUi
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Durations
import org.cassini.ui.format.Progress
import org.cassini.ui.format.ProgressHeadline
import org.cassini.ui.format.StageUi
import org.cassini.ui.format.TimeLeftUi
import org.cassini.ui.theme.CassiniTheme

/** The card's spoken percentage moves in steps of 5, so the live region does not chatter. */
private const val ANNOUNCE_STEP = 5

/**
 * The Transcribing card (board 02): surface, 1 dp line, radius 20, padding 16, 12 dp between parts. The header row
 * (bottom-aligned) holds the headline (`caption` text2) over the window range (`monoTime` text3, 2 dp below; hidden
 * at 0–0) and the percentage ("62" in `percent`, "%" in `percentSign`). Then the 6 dp track, the [StageTracker], the
 * [StatGrid] (the [ProcessingCardUi.download] banner replaces it while models download; its Cancel calls
 * [onDownloadAction]), the amber low-memory hint, "Stop and keep progress" ([onStop]) and the footnote (`caption12`
 * text2; [notificationsOff] picks the "only here" variant).
 *
 * The header is a polite live region whose text changes only when the headline changes or the percentage crosses a
 * 5 % step. The screen gives the margins (top 8, horizontal 16).
 */
@Composable
@Suppress("LongParameterList", "LongMethod") // The card anatomy of ui.md section 6, one parameter per variable part.
fun ProcessingCard(
    state: ProcessingCardUi,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    notificationsOff: Boolean = false,
    stopBusy: Boolean = false,
    onDownloadAction: (BannerAction) -> Unit = {},
) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    val shape = CassiniTheme.shapes.progressCard
    val headline = Progress.headline(state.headline)
    val step = state.percent / ANNOUNCE_STEP * ANNOUNCE_STEP
    val stepText = Counts.percent(step)
    val announced = remember(headline, stepText) { "$headline, $stepText" }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            // The board's 16 dp padding sits inside its 1 dp CSS border.
            .padding(17.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = announced
                    liveRegion = LiveRegionMode.Polite
                },
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(text = headline, style = t.caption, color = c.text2)
                if (state.rangeStartMs != 0L || state.rangeEndMs != 0L) {
                    Text(
                        text = Durations.clockRange(state.rangeStartMs, state.rangeEndMs),
                        modifier = Modifier.padding(top = 2.dp),
                        style = t.monoTime,
                        color = c.text3,
                    )
                }
            }
            // The text measures the font's full height (48.8 sp at 40 sp); the board's line box is 40 sp.
            Text(
                text = percentText(state.percent),
                modifier = Modifier
                    .height(with(LocalDensity.current) { t.percent.lineHeight.toDp() })
                    .wrapContentHeight(unbounded = true),
                style = t.percent,
                color = c.ink,
            )
        }
        Spacer(Modifier.height(12.dp))
        LinearTrack(state.percent / 100f, height = TrackHeight.Thick)
        Spacer(Modifier.height(12.dp))
        StageTracker(state.stage, state.skippedMatching)
        Spacer(Modifier.height(12.dp))
        val download = state.download
        if (download != null) Banner(download.banner(), onAction = onDownloadAction) else StatGrid(stats(state))
        if (state.lowMemory) {
            Spacer(Modifier.height(12.dp))
            Text(text = stringResource(R.string.ui_progress_low_memory), style = t.caption, color = c.amberInk)
        }
        // The block button is 44 dp drawn in a 48 dp layout: 2 dp less on each side keeps the board's 12 dp gaps.
        Spacer(Modifier.height(10.dp))
        SecondaryButton(
            text = stringResource(R.string.ui_action_stop_keep_progress),
            onClick = onStop,
            size = SecondaryButtonSize.Block,
            busy = stopBusy,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(
                if (notificationsOff) R.string.ui_progress_footnote_no_notifications else R.string.ui_progress_footnote,
            ),
            style = t.caption12,
            color = c.text2,
        )
    }
}

/** "62%" with the "%" in the smaller `percentSign` style. */
@Composable
private fun percentText(percent: Int): AnnotatedString {
    val text = Counts.percent(percent)
    val sign = CassiniTheme.type.percentSign.toSpanStyle()
    return buildAnnotatedString {
        append(text)
        text.forEachIndexed { index, char -> if (char == '%') addStyle(sign, index, index + 1) }
    }
}

/** Time left, Processed, Battery at finish (when there is a reading) and Words so far. */
@Composable
private fun stats(state: ProcessingCardUi) =
    listOfNotNull(
        StatItem(
            label = stringResource(R.string.ui_stat_time_left),
            value = Progress.timeLeft(state.timeLeft),
            pending = state.timeLeft == TimeLeftUi.Calculating,
        ),
        StatItem(
            label = stringResource(R.string.ui_stat_processed),
            value = Durations.processed(state.doneMs, state.totalMs),
            mono = true,
        ),
        Progress.battery(state.battery)?.let {
            StatItem(
                label = stringResource(R.string.ui_stat_battery),
                value = it,
                pending = state.battery is BatteryUi.Measuring,
            )
        },
        StatItem(label = stringResource(R.string.ui_stat_words), value = Counts.number(state.words.toLong())),
    ).toImmutableList()

@Preview
@Composable
internal fun ProcessingCardPreview() {
    CassiniTheme {
        Box(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
        ) {
            ProcessingCard(
                state = ProcessingCardUi(
                    headline = ProgressHeadline.FINDING_WORDS_AND_SPEAKERS,
                    rangeStartMs = 1_740_000,
                    rangeEndMs = 1_785_000,
                    percent = 62,
                    stage = StageUi.WORDS_AND_VOICES,
                    skippedMatching = false,
                    timeLeft = TimeLeftUi.Range(360_000, 540_000),
                    doneMs = 1_792_000,
                    totalMs = 2_893_000,
                    battery = BatteryUi.Projected(54, 41),
                    words = 4_812,
                    lowMemory = false,
                ),
                onStop = {},
            )
        }
    }
}
