// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.components

import android.icu.text.ListFormatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Dates
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.LocalFormat
import io.codemyriad.cassini.ui.format.Spoken
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import java.time.LocalTime

/**
 * L-30 Library card: a single-line title and tabular duration, then time, one badge and speaker names.
 * Names ellipsize while TalkBack keeps the complete stored labels; metadata can grow at larger font scales.
 * Rest rows omit the transcript preview; search results retain their matched excerpt. Opening, long press
 * and Rename/Move to trash/Resume/Try again accessibility actions keep their existing events.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongMethod") // The row's three lines and its semantics.
fun NoteRow(row: NoteRowUi, day: DayLabel, onAction: (NoteRowAction) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    val status = rowStatus(row.status, row.durationMs, row.transcriptionMinutes)
    val spokenStatus = rowSpokenStatus(row.status, row.transcriptionMinutes)
    val preview = row.preview.takeIf { row.day != null }
    val names = row.speakerLabels.map { it.label ?: stringResource(R.string.ui_speaker_label, it.number) }
    val locale = LocalFormat.current.locale
    val speakers = remember(names, locale) { ListFormatter.getInstance(locale).format(names) }
    val (boundsModifier, layerModifier) = noteRowBoundsModifiers(row.id)
    val description = listOfNotNull(
        Spoken.noteRow(row.title, row.durationMs, row.day ?: day, row.time, spokenStatus, row.recordedLabel),
        rowSpokenSuffix(row.status)?.trimStart('·', ' ', '\u00A0')?.lowercase(locale),
        speakers.takeIf { it.isNotEmpty() },
        preview?.text,
    ).joinToString(", ")
    val actions = customActions(row, onAction)
    val match = SpanStyle(fontWeight = FontWeight.W600, color = c.highlightInk, background = c.highlight)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(boundsModifier)
            .clip(CassiniTheme.shapes.row)
            .background(c.surface)
            .border(1.dp, c.line, CassiniTheme.shapes.row)
            .combinedClickable(
                onClickLabel = stringResource(R.string.ui_action_open_note),
                role = Role.Button,
                onLongClickLabel = stringResource(R.string.ui_note_more_actions),
                onLongClick = { onAction(NoteRowAction.LONG_PRESS) },
                onClick = { onAction(NoteRowAction.OPEN) },
            )
            .semantics(mergeDescendants = true) {
                contentDescription = description
                customActions = actions
            }
            .then(layerModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EllipsisText(
                text = Highlighted(row.title, row.titleRanges).toAnnotatedString(match),
                modifier = Modifier.weight(1f),
                style = t.meetingTitle,
                color = c.ink,
                maxLines = 1,
            )
            Text(
                text = Durations.clock(row.durationMs),
                style = t.monoTime.copy(lineHeight = t.meetingTitle.lineHeight),
                color = c.text2,
            )
        }
        if (preview != null) {
            EllipsisText(
                text = preview.toAnnotatedString(match),
                style = t.snippet,
                color = c.text3,
                maxLines = 2,
            )
        }
        // The 22 dp meta line grows at larger font scales instead of clipping the badge or labels.
        FlowRow(
            modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.recordedLabel ?: if (day == DayLabel.Unknown) {
                    Dates.dayLabel(day)
                } else {
                    row.day?.let { Dates.dayTime(it, row.time) } ?: Dates.time(row.time)
                },
                style = t.monoTime,
                color = c.text2,
            )
            status.kind?.let { StatusChip(status.text, it) }
            if (speakers.isNotEmpty()) {
                Text(
                    text = speakers,
                    modifier = Modifier.weight(1f),
                    style = t.caption,
                    color = c.text2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun noteRowBoundsModifiers(noteId: String): Pair<Modifier, Modifier> {
    val noteBounds = LocalNoteBounds.current ?: return Modifier to Modifier
    val rowLayer = rememberGraphicsLayer()
    val boundsModifier = Modifier.onGloballyPositioned { coords ->
        if (coords.isAttached) noteBounds.update(noteId, coords.boundsInRoot(), rowLayer)
    }
    val layerModifier = Modifier.drawWithContent {
        rowLayer.record { this@drawWithContent.drawContent() }
        drawLayer(rowLayer)
    }
    return boundsModifier to layerModifier
}

/** Badge presentation; status facts and recovery actions remain owned by the existing app state. */
internal data class RowStatus(val text: String, val kind: StatusBadgeKind? = null)

@Composable
internal fun rowStatus(status: NoteStatusUi, durationMs: Long, minutes: Long?): RowStatus = when (status) {
    NoteStatusUi.Recording -> RowStatus(
        stringResource(R.string.ui_badge_recording, Durations.clock(durationMs)),
        StatusBadgeKind.Recording,
    )
    NoteStatusUi.Transcribing, NoteStatusUi.Queued -> RowStatus(
        transcribingLabel(minutes),
        StatusBadgeKind.Transcribing,
    )
    is NoteStatusUi.Paused -> RowStatus(
        stringResource(StatusBadgeKind.Interrupted.label),
        StatusBadgeKind.Interrupted,
    )
    NoteStatusUi.Failed, NoteStatusUi.Unplayable, NoteStatusUi.Unreadable, NoteStatusUi.NewerFormat ->
        RowStatus(stringResource(R.string.ui_badge_attention), StatusBadgeKind.Attention)
    is NoteStatusUi.Transcribed -> RowStatus(stringResource(R.string.ui_status_transcribed))
    NoteStatusUi.AudioOnly -> RowStatus(stringResource(R.string.ui_status_audio_only))
}

@Composable
private fun transcribingLabel(minutes: Long?): String {
    if (minutes == null) return stringResource(R.string.ui_badge_transcribing)
    return stringResource(R.string.ui_preview_badge_transcribing_eta, Counts.number(minutes))
}

/** Compact visual badges must not erase the saved position, failure reason or queued state from TalkBack. */
@Composable
private fun rowSpokenStatus(status: NoteStatusUi, minutes: Long?): String = when (status) {
    NoteStatusUi.Recording -> stringResource(R.string.ui_status_recording)
    NoteStatusUi.Transcribing -> if (minutes == null) {
        stringResource(R.string.ui_status_transcribing)
    } else {
        pluralStringResource(
            R.plurals.ui_badge_transcribing_spoken_eta,
            minutes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            Counts.number(minutes),
        )
    }
    NoteStatusUi.Queued -> stringResource(R.string.ui_status_queued)
    is NoteStatusUi.Paused -> stringResource(R.string.ui_status_paused_at, Durations.clock(status.atMs))
    NoteStatusUi.Failed -> stringResource(R.string.ui_status_failed)
    NoteStatusUi.Unplayable -> stringResource(R.string.ui_status_unplayable)
    NoteStatusUi.Unreadable -> stringResource(R.string.ui_status_unreadable)
    NoteStatusUi.NewerFormat -> stringResource(R.string.ui_status_newer_format)
    is NoteStatusUi.Transcribed -> stringResource(R.string.ui_status_transcribed)
    NoteStatusUi.AudioOnly -> stringResource(R.string.ui_status_audio_only)
}

@Composable
private fun rowSpokenSuffix(status: NoteStatusUi): String? = when {
    status !is NoteStatusUi.Transcribed -> null
    status.words == 0 -> stringResource(R.string.ui_status_suffix_no_speech)
    status.speakers >= 2 -> Counts.plural(R.plurals.ui_status_suffix_speakers, status.speakers)
    else -> null
}

/**
 * Rename always; Move to trash except on the note being recorded (features.md section 6, as the actions sheet);
 * Resume when paused; Try again when failed.
 */
@Composable
private fun customActions(row: NoteRowUi, onAction: (NoteRowAction) -> Unit): List<CustomAccessibilityAction> {
    val rename = stringResource(R.string.ui_action_rename)
    val trash = stringResource(R.string.ui_action_move_to_trash)
    val resume = stringResource(R.string.ui_action_resume)
    val tryAgain = stringResource(R.string.ui_action_try_again)
    return buildList {
        when (row.status) {
            is NoteStatusUi.Paused -> add(action(resume, NoteRowAction.RESUME, onAction))
            NoteStatusUi.Failed -> add(action(tryAgain, NoteRowAction.TRY_AGAIN, onAction))
            else -> Unit
        }
        add(action(rename, NoteRowAction.RENAME, onAction))
        if (row.status != NoteStatusUi.Recording) add(action(trash, NoteRowAction.TRASH, onAction))
    }
}

private fun action(label: String, action: NoteRowAction, onAction: (NoteRowAction) -> Unit) =
    CustomAccessibilityAction(label) {
        onAction(action)
        true
    }

/** The text with "…" before and after as flagged, and [match] on each range (clamped to the text). */
internal fun Highlighted.toAnnotatedString(match: SpanStyle): AnnotatedString = buildAnnotatedString {
    if (leadingEllipsis) append(ELLIPSIS)
    val offset = length
    append(text)
    for (range in ranges) {
        val start = range.first.coerceIn(0, text.length)
        val end = (range.last + 1).coerceIn(start, text.length)
        if (end > start) addStyle(match, offset + start, offset + end)
    }
    if (trailingEllipsis) append(ELLIPSIS)
}

private const val ELLIPSIS = '…'

@Preview
@Composable
internal fun NoteRowPreview() {
    CassiniTheme {
        Column(
            modifier = Modifier
                .background(CassiniTheme.colors.bg)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            NoteRow(
                row = NoteRowUi(
                    id = "1",
                    title = "Weekly product sync",
                    durationMs = 2_893_000,
                    time = LocalTime.of(10, 2),
                    status = NoteStatusUi.Transcribing,
                    highlighted = true,
                ),
                day = DayLabel.Today,
                onAction = {},
            )
            NoteRow(
                row = NoteRowUi(
                    id = "2",
                    title = "Call with supplier",
                    durationMs = 1_430_000,
                    time = LocalTime.of(15, 10),
                    day = DayLabel.Yesterday,
                    status = NoteStatusUi.Transcribed(speakers = 3, words = 4_812),
                    preview = Highlighted(
                        text = "so the delivery slips to Thursday, but the price stays as agreed",
                        ranges = persistentListOf(10..17),
                        leadingEllipsis = true,
                        trailingEllipsis = true,
                    ),
                ),
                day = DayLabel.Yesterday,
                onAction = {},
            )
        }
    }
}
