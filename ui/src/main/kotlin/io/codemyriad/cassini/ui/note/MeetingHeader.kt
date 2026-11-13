@file:Suppress("FunctionNaming", "MagicNumber") // Approved Meeting-Android dimensions.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.AutoBadge
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.display
import io.codemyriad.cassini.ui.format.Dates
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.colors

/** Metadata scrolls with the transcript; speaker names keep the transcript's stored identity and colour. */
@Composable
internal fun MeetingHeader(state: NoteUiState, onEvent: (NoteEvent) -> Unit) {
    val c = CassiniTheme.colors
    val speakers = remember(state.meetingSpeakers, state.turns) {
        state.meetingSpeakers.ifEmpty {
            state.turns.distinctBy { it.speakerId }.map { MeetingSpeakerUi(it.speakerId, it.name, it.tone, it.auto) }
        }
    }
    Column(Modifier.fillMaxWidth().background(c.bg).testTag("meeting-header")) {
        if (state.mode == NoteMode.Reading) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                state.subtitle?.let {
                    Text(
                        stringResource(
                            R.string.ui_join_dot,
                            it.recordedLabel ?: Dates.dayTime(it.day, it.time),
                            Durations.clock(it.durationMs),
                        ),
                        style = CassiniTheme.type.monoTime,
                        color = c.text2,
                        modifier = Modifier.testTag("meeting-date"),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    speakers.forEach { speaker -> InlineSpeaker(speaker) { onEvent(NoteEvent.SpeakerTap(speaker.id)) } }
                }
            }
        }
        // Existing trust/version controls stay available and scroll with the rest of the metadata.
        state.chips?.let { NoteChipsRow(it, onEvent) }
        HorizontalDivider(color = c.line)
    }
}

@Composable
private fun InlineSpeaker(speaker: MeetingSpeakerUi, onClick: () -> Unit) {
    val c = CassiniTheme.colors
    val unnamed = speaker.name is SpeakerNameUi.Placeholder
    Row(
        Modifier.heightIn(min = 48.dp).testTag("meeting-speaker-${speaker.id}")
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(speaker.tone.colors.ink, CircleShape))
        Text(
            speaker.name.display(),
            style = CassiniTheme.type.body14Plain.copy(fontSize = 15.sp),
            color = c.ink,
            modifier = Modifier.drawBehind {
                if (unnamed) {
                    drawLine(
                        c.text2.copy(alpha = 0.55f),
                        Offset(0f, size.height),
                        Offset(size.width, size.height),
                        1.dp.toPx(),
                    )
                }
            },
        )
        if (speaker.auto) AutoBadge(stringResource(R.string.ui_turn_auto))
    }
}
