// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.LocalFormat
import io.codemyriad.cassini.ui.format.Spoken
import io.codemyriad.cassini.ui.theme.CassiniTheme
import kotlinx.collections.immutable.persistentListOf
import java.time.LocalTime

/**
 * L-51..53 phone row: two-line title and duration, then time, an unfinished status or finished count/no-speech
 * pill, and recovery actions. Search results alone keep their highlighted snippet. The row retains explicit
 * localized spoken status and custom actions independently of its compact visual label.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongMethod") // The row's three lines and its semantics.
fun NoteRow(row: NoteRowUi, day: DayLabel, onAction: (NoteRowAction) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    val status = rowStatus(row.status, row.durationMs, row.transcriptionMinutes)
    val preview = row.preview?.takeIf { row.day != null }
    val (boundsModifier, layerModifier) = noteRowBoundsModifiers(row.id)
    // The explicit description replaces the merged texts for TalkBack, so it also carries what they add: a suffix
    // that is not a button ("3 speakers", "No speech") and the preview, which tells why a search result matched.
    val locale = LocalFormat.current.locale
    val description = listOfNotNull(
        Spoken.noteRow(
            row.title,
            row.durationMs,
            row.day ?: day,
            row.time,
            status.spokenText,
            row.recordedLabel,
        ),
        stringResource(StatusBadgeKind.Received.label)
            .takeIf { row.imported && row.status is NoteStatusUi.Transcribed }
            ?.lowercase(locale),
        stringResource(R.string.ui_row_starred_a11y).takeIf { row.starred }?.lowercase(locale),
        spokenSuffix(row.status, status)
            ?.takeIf { status.suffixAction == null }
            ?.trimStart('·', ' ', '\u00A0')
            ?.lowercase(locale),
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
                if (row.status == NoteStatusUi.Transcribing && row.transcriptionMinutes != null) {
                    stateDescription = status.text
                }
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
                maxLines = 2,
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
        RowMeta(row, day, status, onAction)
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

/** The chip and suffix of a status (features.md section 1, the status table). */
internal data class RowStatus(
    val text: String,
    val kind: StatusBadgeKind?,
    val spoken: String? = null,
    val suffix: String? = null,
    val suffixAction: NoteRowAction? = null,
) {
    val spokenText: String get() = spoken ?: text
}

@Composable
internal fun rowStatus(status: NoteStatusUi, liveMs: Long = 0, minutes: Long? = null): RowStatus = when (status) {
    NoteStatusUi.Recording -> RowStatus(
        statusBadgeText(StatusBadgeKind.Recording, liveMs = liveMs),
        StatusBadgeKind.Recording,
        spoken = stringResource(R.string.ui_status_recording),
    )
    NoteStatusUi.Transcribing -> RowStatus(
        statusBadgeText(StatusBadgeKind.Transcribing, minutes = minutes),
        StatusBadgeKind.Transcribing,
        spoken = stringResource(R.string.ui_status_transcribing),
    )
    NoteStatusUi.Queued -> badgeRowStatus(StatusBadgeKind.Queued, R.string.ui_status_queued)
    is NoteStatusUi.Paused -> RowStatus(
        text = stringResource(R.string.ui_badge_paused, Durations.clock(status.atMs)),
        kind = StatusBadgeKind.Paused,
        spoken = stringResource(R.string.ui_status_paused_at, Durations.clock(status.atMs)),
        suffix = stringResource(R.string.ui_status_suffix_resume),
        suffixAction = NoteRowAction.RESUME,
    )
    NoteStatusUi.Failed -> RowStatus(
        text = stringResource(R.string.ui_badge_failed),
        kind = StatusBadgeKind.Failed,
        spoken = stringResource(R.string.ui_status_failed),
        suffix = stringResource(R.string.ui_status_suffix_try_again),
        suffixAction = NoteRowAction.TRY_AGAIN,
    )
    NoteStatusUi.Unplayable -> badgeRowStatus(StatusBadgeKind.Unplayable, R.string.ui_status_unplayable)
    NoteStatusUi.Unreadable -> badgeRowStatus(StatusBadgeKind.Unreadable, R.string.ui_status_unreadable)
    NoteStatusUi.NewerFormat -> badgeRowStatus(StatusBadgeKind.NewerFormat, R.string.ui_status_newer_format)
    is NoteStatusUi.Transcribed -> RowStatus(
        text = stringResource(R.string.ui_status_transcribed),
        kind = null,
        suffix = when {
            status.words == 0 -> stringResource(R.string.ui_status_suffix_no_speech)
            status.speakers >= 2 -> Counts.plural(R.plurals.ui_status_suffix_speakers, status.speakers)
            else -> null
        },
    )
    NoteStatusUi.AudioOnly -> badgeRowStatus(StatusBadgeKind.AudioOnly, R.string.ui_status_audio_only)
}

/** A compact badge and its independent, established spoken label. */
@Composable
private fun badgeRowStatus(kind: StatusBadgeKind, @StringRes spoken: Int): RowStatus = RowStatus(
    text = stringResource(kind.label),
    kind = kind,
    spoken = stringResource(spoken),
)

/**
 * Rename always; Star or Unstar (L-34); Move to trash except on the note being recorded (features.md section 6, as
 * the actions sheet); Resume when paused; Try again when failed.
 */
@Composable
private fun customActions(row: NoteRowUi, onAction: (NoteRowAction) -> Unit): List<CustomAccessibilityAction> {
    val rename = stringResource(R.string.ui_action_rename)
    val trash = stringResource(R.string.ui_action_move_to_trash)
    val resume = stringResource(R.string.ui_action_resume)
    val tryAgain = stringResource(R.string.ui_action_try_again)
    val star = stringResource(if (row.starred) R.string.ui_meeting_unstar else R.string.ui_meeting_star)
    return buildList {
        when (row.status) {
            is NoteStatusUi.Paused -> add(action(resume, NoteRowAction.RESUME, onAction))
            NoteStatusUi.Failed -> add(action(tryAgain, NoteRowAction.TRY_AGAIN, onAction))
            else -> Unit
        }
        add(action(rename, NoteRowAction.RENAME, onAction))
        add(action(star, if (row.starred) NoteRowAction.UNSTAR else NoteRowAction.STAR, onAction))
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
