@file:Suppress("FunctionNaming", "MagicNumber") // Approved Meeting-Android dimensions.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.AutoBadge
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.display
import io.codemyriad.cassini.ui.components.turnHeaderSemantics
import io.codemyriad.cassini.ui.format.Spoken
import io.codemyriad.cassini.ui.theme.CassiniSpacing
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.colors
import kotlin.math.max
import kotlin.math.min

/**
 * Metadata scrolls with the transcript; speaker names keep the transcript's stored identity and colour. Reading shows the
 * full meeting title as a heading first (QA 25 Nov: the top bar no longer cuts it), then the date, then the speaker
 * names just under it; when the names wrap, their lines stay 8 dp apart so they read as one group.
 */
@Composable
internal fun MeetingHeader(state: NoteUiState, onEvent: (NoteEvent) -> Unit) {
    val c = CassiniTheme.colors
    val speakers = remember(state.meetingSpeakers, state.turns) {
        state.meetingSpeakers.ifEmpty {
            state.turns.distinctBy { it.speakerId }.map {
                MeetingSpeakerUi(it.speakerId, it.name, it.tone, it.auto, it.startMs)
            }
        }
    }
    Column(Modifier.fillMaxWidth().background(c.bg).testTag("meeting-header")) {
        if (state.mode == NoteMode.Reading) {
            // The bottom padding holds the last line's 48 dp touch targets, which reach past the names: TalkBack and the
            // accessibility checks see a node only inside its parents' bounds.
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = TouchReach)) {
                Text(
                    state.title,
                    style = CassiniTheme.type.screenTitle,
                    color = c.ink,
                    modifier = Modifier.padding(bottom = 4.dp).semantics { heading() }.testTag("meeting-title"),
                )
                state.subtitle?.let {
                    Text(
                        subtitleText(it),
                        style = CassiniTheme.type.monoTime,
                        color = c.text2,
                        modifier = Modifier.testTag("meeting-date"),
                    )
                }
                FlowRow(
                    Modifier.padding(top = SpeakerLineGap),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(SpeakerLineGap),
                ) {
                    speakers.forEach { speaker -> InlineSpeaker(speaker) { onEvent(NoteEvent.SpeakerTap(speaker.id)) } }
                }
            }
        }
        // Existing trust/version controls stay available and scroll with the rest of the metadata.
        state.chips?.let {
            NoteChipsRow(it, onEvent, showSpeakerCount = state.mode != NoteMode.Reading || speakers.isEmpty())
        }
        MeetingBanners(state, onEvent)
        HorizontalDivider(color = c.line, modifier = Modifier.testTag("meeting-header-divider"))
    }
}

@Composable
private fun InlineSpeaker(speaker: MeetingSpeakerUi, onClick: () -> Unit) {
    val c = CassiniTheme.colors
    val unnamed = speaker.name is SpeakerNameUi.Placeholder
    CompactTouchTarget(
        Modifier.testTag("meeting-speaker-${speaker.id}")
            .turnHeaderSemantics(
                description = stringResource(
                    R.string.ui_join_dot,
                    stringResource(R.string.ui_turn_name_action),
                    Spoken.turn(speaker.name.display(), speaker.startMs, speaker.auto),
                ),
                clickLabel = stringResource(R.string.ui_turn_name_action),
                onClick = onClick,
            ),
    ) {
        Row(
            Modifier.clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(7.dp).background(speaker.tone.colors.ink, CircleShape))
            Text(
                speaker.name.display(),
                style = CassiniTheme.type.body14Plain.copy(fontSize = 15.sp),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).drawBehind {
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
            if (speaker.auto) {
                AutoBadge(stringResource(R.string.ui_turn_auto), Modifier.testTag("meeting-auto-${speaker.id}"))
            }
        }
    }
}

/** The space between the date and the names, and between wrapped lines of names (QA 25 Nov: about 8 dp, not 40). */
private val SpeakerLineGap = 8.dp

/** Below the names: half of a 48 dp target less a 20 dp line of names, so the last line's targets are not clipped. */
private val TouchReach = 14.dp

/** How far the 48 dp touch target reaches above (and below) the visual content of a [CompactTouchTarget]. */
private val VisualInset = HorizontalAlignmentLine(::min)

/**
 * A control whose touch target is at least 48 x 48 dp ([CassiniSpacing.touchTarget]) with its [content] centred
 * vertically and at the start, but laid out in its row at the content's own height: the target reaches past it above
 * and below, unclipped. Wrapped speaker names thus keep their full touch targets with their lines close together.
 */
@Composable
private fun CompactTouchTarget(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                val inset = placeable[VisualInset].takeIf { it != AlignmentLine.Unspecified } ?: 0
                layout(placeable.width, placeable.height - 2 * inset) { placeable.place(0, -inset) }
            }
            .then(modifier),
    ) { measurables, constraints ->
        val minimum = CassiniSpacing.touchTarget.roundToPx()
        val visual = measurables.single().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val width = max(visual.width, minimum).coerceAtMost(constraints.maxWidth)
        val height = max(visual.height, minimum)
        val inset = (height - visual.height) / 2
        layout(width, height, mapOf(VisualInset to inset)) { visual.place(0, inset) }
    }
}
