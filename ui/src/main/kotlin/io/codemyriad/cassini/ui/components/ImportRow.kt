// Composables are nouns in PascalCase; the numbers are the Library row's board values (ui.md section 7).
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/**
 * One import in the Library list (L-25, W4.5 and W4.6; COMPONENTS.md section 7): [name] is the file's display
 * name. While it runs the card shows the neutral "Importing…" badge with its spinner; a failed import keeps its card
 * with the amber "Needs attention" badge ([failed]) and a Dismiss button. [id] keys it in the list.
 */
@Immutable
data class ImportRowUi(val id: Long, val name: String, val failed: Boolean = false)

/**
 * The card of [row], drawn as a Library row (surface, line border, row shape) without duration or time: the import
 * has no note yet. It is a polite live region, so TalkBack announces "Importing…" and a failure as they happen.
 * [onDismiss] closes a failed card.
 */
@Composable
fun ImportRow(row: ImportRowUi, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    val kind = if (row.failed) StatusBadgeKind.Attention else StatusBadgeKind.Importing
    val badge = statusBadgeText(kind)
    Box(
        modifier
            .fillMaxWidth()
            .clip(CassiniTheme.shapes.row)
            .background(c.surface)
            .border(1.dp, c.line, CassiniTheme.shapes.row),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("import-row-${row.id}")
                .semantics(mergeDescendants = true) {
                    contentDescription = "${row.name}, $badge"
                    liveRegion = LiveRegionMode.Polite
                }
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = if (row.failed) 52.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EllipsisText(
                text = AnnotatedString(row.name),
                style = CassiniTheme.type.meetingTitle,
                color = c.ink,
                maxLines = 2,
            )
            Row(verticalAlignment = Alignment.CenterVertically) { StatusChip(badge, kind) }
        }
        if (row.failed) {
            IconButton44(
                glyph = Glyph.Close,
                contentDescription = stringResource(R.string.ui_action_dismiss),
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp),
                iconSize = 20.dp,
                stroke = 1.8f,
                tint = c.text2,
            )
        }
    }
}

@Preview
@Composable
internal fun ImportRowPreview() {
    CassiniTheme {
        Column(
            Modifier
                .width(390.dp)
                .background(CassiniTheme.colors.bg)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ImportRow(ImportRowUi(1, "Quarterly review.m4a"), onDismiss = {})
            ImportRow(ImportRowUi(2, "voice-note.ogg", failed = true), onDismiss = {})
        }
    }
}
