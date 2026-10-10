// Composables are nouns in PascalCase; the numbers are the values of docs/design/ui.md section 8 (no board).
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.CassiniSheet
import org.cassini.ui.components.ConfirmDialog
import org.cassini.ui.components.ConfirmDialogContent
import org.cassini.ui.components.ConfirmSpec
import org.cassini.ui.components.display
import org.cassini.ui.format.Durations
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.colors

/**
 * The Merge sheet (no board; features.md section 4, item 8; ui.md section 8) in its [CassiniSheet] window, with the
 * confirmation dialog on top while [MergeUi.confirm] is set. A swipe or a scrim tap sends [onDismiss].
 */
@Composable
fun MergeSheet(ui: MergeUi, onEvent: (MergeEvent) -> Unit, onDismiss: () -> Unit) {
    CassiniSheet(onDismissRequest = onDismiss) { MergeSheetContent(ui, onEvent) }
    ui.confirm?.let { target ->
        ConfirmDialog(
            spec = mergeConfirmSpec(ui, target),
            onConfirm = { onEvent(MergeEvent.Confirm) },
            onDismiss = { onEvent(MergeEvent.CancelConfirm) },
        )
    }
}

/**
 * The sheet's content, also drawn inline in screenshots: the title "Merge <A> into…" (`sheetTitle`, padding 20,
 * 16 below the handle), then one 56 dp row per other speaker: an 8 dp dot in its tone, the label in `body16` and the
 * speaking time in `monoTime` text2 at the end. A row opens the confirmation ([MergeEvent.PickTarget]).
 */
@Composable
fun MergeSheetContent(ui: MergeUi, onEvent: (MergeEvent) -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(top = 16.dp, bottom = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.ui_merge_sheet_title, ui.source.display()),
            modifier = Modifier
                .padding(start = 20.dp, end = 20.dp, bottom = 8.dp)
                .semantics { heading() },
            style = CassiniTheme.type.sheetTitle,
            color = c.ink,
        )
        ui.targets.forEach { target ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { onEvent(MergeEvent.PickTarget(target.speakerId)) }
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(target.tone.colors.dot, CircleShape),
                )
                Text(
                    text = target.label.display(),
                    modifier = Modifier.weight(1f),
                    style = CassiniTheme.type.body16,
                    color = c.ink,
                )
                Text(text = Durations.clock(target.speakingMs), style = CassiniTheme.type.monoTime, color = c.text2)
            }
        }
    }
}

/** The confirmation drawn inline, for screenshots (`DialogBoardFrame`). */
@Composable
fun MergeConfirmContent(ui: MergeUi, target: MergeTargetUi, onEvent: (MergeEvent) -> Unit) {
    ConfirmDialogContent(
        spec = mergeConfirmSpec(ui, target),
        onConfirm = { onEvent(MergeEvent.Confirm) },
        onDismiss = { onEvent(MergeEvent.CancelConfirm) },
    )
}

/**
 * "Merge <A> into <B>?" — "Everything <A> said will be shown as <B>." then "This adds version 3; version 2 stays
 * available." (sealed, [MergeVersionsUi]) or "This changes the draft." (draft); the primary button is "Merge".
 */
@Composable
private fun mergeConfirmSpec(ui: MergeUi, target: MergeTargetUi): ConfirmSpec {
    val a = ui.source.display()
    val b = target.label.display()
    val versions = ui.sealed
    val effect = if (versions != null) {
        stringResource(R.string.ui_merge_confirm_sealed, versions.added, versions.shown)
    } else {
        stringResource(R.string.ui_merge_confirm_draft)
    }
    return ConfirmSpec(
        title = stringResource(R.string.ui_merge_confirm_title, a, b),
        body = stringResource(R.string.ui_join_space, stringResource(R.string.ui_merge_confirm_body, a, b), effect),
        confirm = stringResource(R.string.ui_merge_action),
    )
}
