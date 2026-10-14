// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.persistentListOf
import org.cassini.ui.R
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Dates
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.Durations
import org.cassini.ui.format.LocalFormat
import org.cassini.ui.format.Spoken
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Tone
import java.time.LocalTime

/**
 * A Library row (board 00): radius 14, surface when [NoteRowUi.highlighted] else transparent, padding 12/10, gap 4.
 * The title (`rowTitle`, up to 2 lines) and the duration (`monoTime`, text2, on the title's line height); the
 * preview (`snippet`, text3, up to 2 lines) with its ellipses and highlights; the meta line (gap 8): the time (or
 * [day] and time in search results), the [StatusChip] and the suffix.
 *
 * "· Resume" and "· Try again" are inline text buttons: they keep the board's text layout, and Compose's extended
 * hit testing gives them a 48 dp touch area (ui.md section 5). The row is one merged node described as
 * "Weekly product sync, 48 minutes 13 seconds, today 10:02, transcribing", followed by a suffix that is not a
 * button ("3 speakers") and the preview text; it opens on click, reports a long press, and offers Rename, Move to
 * trash and Resume / Try again as TalkBack custom actions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
@Suppress("LongMethod") // The row's three lines and its semantics.
fun NoteRow(row: NoteRowUi, day: DayLabel, onAction: (NoteRowAction) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val t = CassiniTheme.type
    val status = rowStatus(row.status)
    val (boundsModifier, layerModifier) = noteRowBoundsModifiers(row.id)
    // The explicit description replaces the merged texts for TalkBack, so it also carries what they add: a suffix
    // that is not a button ("3 speakers", "No speech") and the preview, which tells why a search result matched.
    val locale = LocalFormat.current.locale
    val description = listOfNotNull(
        Spoken.noteRow(row.title, row.durationMs, row.day ?: day, row.time, status.text),
        status.suffix?.takeIf { status.suffixAction == null }?.trimStart('·', ' ', '\u00A0')?.lowercase(locale),
        row.preview?.text,
    ).joinToString(", ")
    val actions = customActions(row, onAction)
    val match = SpanStyle(fontWeight = FontWeight.W600, color = c.ink, background = c.accentTint)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(boundsModifier)
            .clip(CassiniTheme.shapes.row)
            .background(if (row.highlighted) c.surface else Color.Transparent)
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
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EllipsisText(
                text = Highlighted(row.title, row.titleRanges).toAnnotatedString(match),
                modifier = Modifier.weight(1f),
                style = t.rowTitle,
                color = c.ink,
                maxLines = 2,
            )
            Text(
                text = Durations.clock(row.durationMs),
                style = t.monoTime.copy(lineHeight = t.rowTitle.lineHeight),
                color = c.text2,
            )
        }
        val preview = row.preview
        if (preview != null) {
            EllipsisText(
                text = preview.toAnnotatedString(match),
                style = t.snippet,
                color = c.text3,
                maxLines = 2,
            )
        }
        // A flow row: at a large font scale the suffix ("· No speech", "· Resume") goes to the next line, not out of
        // the row.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.day?.let { Dates.dayTime(it, row.time) } ?: Dates.time(row.time),
                style = t.caption,
                color = c.text2,
            )
            StatusChip(status.text, status.tone, dot = status.dot)
            val suffix = status.suffix
            if (suffix != null) {
                val action = status.suffixAction
                val suffixDescription = when (action) {
                    NoteRowAction.RESUME -> stringResource(R.string.ui_row_resume_description, row.title)
                    NoteRowAction.TRY_AGAIN -> stringResource(R.string.ui_row_try_again_description, row.title)
                    else -> null
                }
                Text(
                    text = suffix,
                    modifier = if (action != null && suffixDescription != null) {
                        Modifier
                            .clickable(role = Role.Button, onClick = { onAction(action) })
                            .semantics { contentDescription = suffixDescription }
                    } else {
                        Modifier
                    },
                    style = t.caption,
                    color = c.text2,
                    maxLines = 1,
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

/** The chip and suffix of a status (features.md section 1, the status table). */
internal data class RowStatus(
    val text: String,
    val tone: Tone,
    val dot: Boolean = false,
    val suffix: String? = null,
    val suffixAction: NoteRowAction? = null,
)

@Composable
internal fun rowStatus(status: NoteStatusUi): RowStatus = when (status) {
    NoteStatusUi.Recording -> RowStatus(stringResource(R.string.ui_status_recording), Tone.Error, dot = true)
    NoteStatusUi.Transcribing -> RowStatus(stringResource(R.string.ui_status_transcribing), Tone.Info)
    NoteStatusUi.Queued -> RowStatus(stringResource(R.string.ui_status_queued), Tone.Outline)
    is NoteStatusUi.Paused -> RowStatus(
        text = stringResource(R.string.ui_status_paused_at, Durations.clock(status.atMs)),
        tone = Tone.Warn,
        suffix = stringResource(R.string.ui_status_suffix_resume),
        suffixAction = NoteRowAction.RESUME,
    )
    NoteStatusUi.Failed -> RowStatus(
        text = stringResource(R.string.ui_status_failed),
        tone = Tone.Error,
        suffix = stringResource(R.string.ui_status_suffix_try_again),
        suffixAction = NoteRowAction.TRY_AGAIN,
    )
    NoteStatusUi.Unplayable -> RowStatus(stringResource(R.string.ui_status_unplayable), Tone.Error)
    NoteStatusUi.Unreadable -> RowStatus(stringResource(R.string.ui_status_unreadable), Tone.Error)
    NoteStatusUi.NewerFormat -> RowStatus(stringResource(R.string.ui_status_newer_format), Tone.Outline)
    is NoteStatusUi.Transcribed -> RowStatus(
        text = stringResource(R.string.ui_status_transcribed),
        tone = Tone.Ok,
        suffix = when {
            status.words == 0 -> stringResource(R.string.ui_status_suffix_no_speech)
            status.speakers >= 2 -> Counts.plural(R.plurals.ui_status_suffix_speakers, status.speakers)
            else -> null
        },
    )
    NoteStatusUi.AudioOnly -> RowStatus(stringResource(R.string.ui_status_audio_only), Tone.Outline)
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
                        text = "quindi la consegna slitta a giovedì, but the price stays as agreed",
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
