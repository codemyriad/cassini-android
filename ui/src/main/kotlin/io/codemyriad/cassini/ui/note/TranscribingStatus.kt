@file:Suppress("FunctionNaming", "MagicNumber") // Phase 1 compact status and native 48dp actions.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.LinearTrack
import io.codemyriad.cassini.ui.components.ProcessingCardUi
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Progress
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** The compact status stays above the draft; details and Stop are separate native touch targets. */
@Composable
internal fun TranscribingStatus(
    progress: ProcessingCardUi,
    stopBusy: Boolean,
    onDetails: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CassiniTheme.colors
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp).testTag("transcribing-status"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StatusDetails(progress, onDetails, Modifier.weight(1f))
            StatusStop(stopBusy, onStop)
        }
        LinearTrack(
            progress = progress.percent / 100f,
            modifier = Modifier.padding(bottom = 12.dp),
            trackColor = c.ink.copy(alpha = 0.10f),
            moving = true,
            anchor = progress.anchor,
        )
    }
}

@Composable
private fun StatusDetails(progress: ProcessingCardUi, onDetails: () -> Unit, modifier: Modifier) {
    val c = CassiniTheme.colors
    val heading = stringResource(R.string.ui_tx_heading)
    val eta = Progress.timeLeftLine(progress.timeLeft)
    val label = stringResource(R.string.ui_join_dot, heading, eta)
    val details = stringResource(R.string.ui_tx_details)
    val percentage = Counts.percent(progress.percent / 5 * 5)
    Row(
        modifier.heightIn(min = 48.dp).testTag("transcribing-details")
            .clickable(role = Role.Button, onClickLabel = details, onClick = onDetails)
            .clearAndSetSemantics {
                contentDescription = label
                stateDescription = percentage
                liveRegion = LiveRegionMode.Polite
                role = Role.Button
                onClick(label = details) {
                    onDetails()
                    true
                }
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
        val text = buildAnnotatedString {
            append(label)
            addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), 0, heading.length)
        }
        Text(text, Modifier.weight(1f), style = CassiniTheme.type.body14, color = c.ink)
        CassiniIcon(Glyph.ChevronRight, 14.dp, 1.8f, c.text2, null)
    }
}

@Composable
private fun StatusStop(stopBusy: Boolean, onStop: () -> Unit) {
    val c = CassiniTheme.colors
    val stop = stringResource(R.string.ui_tx_stop_keep)
    TextButton(
        onClick = onStop,
        enabled = !stopBusy,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = stop }
            .testTag("transcribing-stop"),
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.ui_tx_stop),
                Modifier.alpha(if (stopBusy) 0f else 1f),
                style = CassiniTheme.type.label14,
                color = c.accent,
            )
            if (stopBusy) CircularProgressIndicator(Modifier.size(16.dp), color = c.accent, strokeWidth = 2.dp)
        }
    }
}
