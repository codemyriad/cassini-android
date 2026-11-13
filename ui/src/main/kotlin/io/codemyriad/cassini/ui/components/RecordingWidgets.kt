// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.Spoken
import io.codemyriad.cassini.ui.theme.CassiniTheme

/**
 * The Recording timer (board 01): `timer` (56 sp system sans, tabular) in rec.text, centred. Its description is spoken
 * ("24 minutes 7 seconds"); it is not a live region, so TalkBack does not read every second.
 */
@Composable
fun RecordingTimer(elapsedMs: Long, modifier: Modifier = Modifier) {
    val description = Spoken.duration(elapsedMs)
    Text(
        text = Durations.clock(elapsedMs),
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
        style = CassiniTheme.type.timer,
        color = CassiniTheme.recording.text,
        textAlign = TextAlign.Center,
    )
}

/**
 * The Recording header status (board 01): the 10 dp live dot (paused: a hollow ring, 1.5 dp rec.text2) and, 8 dp
 * after it, "Recording" (14/600 rec.status) or "Paused" (rec.text2). TalkBack reads "Recording status" with the
 * word as its state description; a polite live region announces the change.
 */
@Composable
fun RecordingStatus(paused: Boolean, modifier: Modifier = Modifier) {
    val rec = CassiniTheme.recording
    val label = stringResource(R.string.ui_recording_status_label)
    val state = stringResource(
        if (paused) R.string.ui_recording_status_paused else R.string.ui_recording_status_recording,
    )
    Row(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = label
            stateDescription = state
            liveRegion = LiveRegionMode.Polite
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RecordingDot(paused)
        Text(
            text = state,
            style = CassiniTheme.type.label14,
            color = if (paused) rec.text2 else rec.status,
        )
    }
}

@Preview
@Composable
internal fun RecordingWidgetsPreview() {
    CassiniTheme {
        Column(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.recording.bg)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            RecordingStatus(paused = false)
            RecordingTimer(1_447_000)
            RecordingControls(paused = false, onLibrary = {}, onPauseResume = {}, onFinish = {})
        }
    }
}
