@file:Suppress("FunctionNaming", "MagicNumber") // Approved native processing sheet, L-45.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.CassiniSheet
import io.codemyriad.cassini.ui.components.LinearTrack
import io.codemyriad.cassini.ui.components.ProcessingCardUi
import io.codemyriad.cassini.ui.components.SecondaryButton
import io.codemyriad.cassini.ui.components.SecondaryButtonSize
import io.codemyriad.cassini.ui.components.StatGrid
import io.codemyriad.cassini.ui.components.StatItem
import io.codemyriad.cassini.ui.components.TrackHeight
import io.codemyriad.cassini.ui.components.stageStates
import io.codemyriad.cassini.ui.format.BatteryUi
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.Progress
import io.codemyriad.cassini.ui.format.ProgressHeadline
import io.codemyriad.cassini.ui.format.StageState
import io.codemyriad.cassini.ui.format.StageUi
import io.codemyriad.cassini.ui.format.TimeLeftUi
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph
import kotlinx.collections.immutable.toImmutableList

/** Local presentation only; the caller supplies current processing state and the existing actions. */
@Composable
@Suppress("LongParameterList") // Current progress, busy/notification state and existing sheet actions.
internal fun TranscribingDetails(
    progress: ProcessingCardUi,
    stopBusy: Boolean,
    notificationsOff: Boolean,
    onStop: () -> Unit,
    onDownloadAction: (BannerAction) -> Unit,
    onDismiss: () -> Unit,
) {
    CassiniSheet(onDismissRequest = onDismiss, containerColor = CassiniTheme.colors.raised) {
        TranscribingDetailsContent(progress, stopBusy, notificationsOff, onStop, onDownloadAction)
    }
}

/** Also rendered inline by the screenshot boards; all figures remain supplied by the existing tracker. */
@Composable
internal fun TranscribingDetailsContent(
    progress: ProcessingCardUi,
    stopBusy: Boolean,
    notificationsOff: Boolean,
    onStop: () -> Unit,
    onDownloadAction: (BannerAction) -> Unit,
) {
    val c = CassiniTheme.colors
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp)
            .testTag("transcribing-details-content"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.ui_tx_heading),
                Modifier.weight(1f).semantics { heading() },
                style = CassiniTheme.type.noteTitle,
                color = c.ink,
            )
            Text(Counts.percent(progress.percent), style = CassiniTheme.type.monoTime, color = c.text2)
        }
        LinearTrack(
            progress.percent / 100f,
            height = TrackHeight.Thick,
            trackColor = c.neutralTint,
            moving = true,
            anchor = progress.anchor,
        )
        if (progress.headline == ProgressHeadline.WAITING_FOR_PREVIOUS) {
            Text(Progress.headline(progress.headline), style = CassiniTheme.type.caption, color = c.text2)
        }
        ProcessingStages(progress)
        HorizontalDivider(color = c.lineSoft)
        StatGrid(detailStats(progress))
        progress.download?.let { Banner(it.banner(), onAction = onDownloadAction) }
        if (progress.lowMemory) {
            Text(stringResource(R.string.ui_progress_low_memory), style = CassiniTheme.type.caption, color = c.amberInk)
        }
        val stop = stringResource(R.string.ui_tx_stop_keep)
        SecondaryButton(
            stop,
            onClick = onStop,
            modifier = Modifier.testTag("transcribing-details-stop").semantics { contentDescription = stop },
            size = SecondaryButtonSize.Block,
            busy = stopBusy,
        )
        Text(
            stringResource(
                if (notificationsOff) R.string.ui_progress_footnote_no_notifications else R.string.ui_progress_footnote,
            ),
            modifier = Modifier.fillMaxWidth(),
            style = CassiniTheme.type.caption12,
            color = c.text2,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ProcessingStages(progress: ProcessingCardUi) {
    val c = CassiniTheme.colors
    val labels = listOf(
        R.string.ui_tx_reading,
        R.string.ui_tx_preparing,
        R.string.ui_tx_words,
        R.string.ui_tx_matching,
        R.string.ui_tx_saving,
    )
    val states = stageStates(progress.stage, progress.skippedMatching)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        StageUi.entries.forEachIndexed { index, _ ->
            val state = states[index]
            val label = stringResource(labels[index])
            val spoken = stringResource(R.string.ui_stage_description, index + 1, labels.size, label)
            val status = Progress.stageState(state)
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics {
                    contentDescription = spoken
                    stateDescription = status
                },
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StageGlyph(state)
                Text(
                    label,
                    style = if (state == StageState.IN_PROGRESS) CassiniTheme.type.label14 else CassiniTheme.type.body14,
                    color = if (state == StageState.DONE || state == StageState.IN_PROGRESS) c.ink else c.text4,
                    textDecoration = if (state == StageState.SKIPPED) TextDecoration.LineThrough else null,
                )
            }
        }
    }
}

@Composable
private fun StageGlyph(state: StageState) {
    val c = CassiniTheme.colors
    when (state) {
        StageState.IN_PROGRESS -> CircularProgressIndicator(Modifier.size(18.dp), color = c.accent, strokeWidth = 2.dp)
        StageState.DONE -> Box(
            Modifier.size(18.dp).clip(CircleShape).background(c.accent),
            contentAlignment = Alignment.Center,
        ) {
            CassiniIcon(Glyph.Check, 12.dp, 2f, c.onAccentFill, null)
        }
        else -> Box(Modifier.size(18.dp).border(2.dp, c.line, CircleShape))
    }
}

@Composable
private fun detailStats(progress: ProcessingCardUi) = listOfNotNull(
    StatItem(
        stringResource(R.string.ui_tx_time_left),
        Progress.timeLeft(progress.timeLeft),
        pending = progress.timeLeft == TimeLeftUi.Calculating,
    ),
    StatItem(
        stringResource(R.string.ui_tx_audio_done),
        stringResource(
            R.string.ui_tx_audio_done_value,
            Durations.clock(progress.doneMs),
            Durations.clock(progress.totalMs),
        ),
        mono = true,
    ),
    StatItem(stringResource(R.string.ui_tx_words_so_far), Counts.number(progress.words.toLong())),
    Progress.battery(progress.battery)?.let {
        StatItem(stringResource(R.string.ui_tx_battery_finish), it, pending = progress.battery is BatteryUi.Measuring)
    },
).toImmutableList()
