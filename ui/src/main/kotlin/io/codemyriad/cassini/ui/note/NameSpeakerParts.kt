@file:Suppress("FunctionNaming", "MagicNumber") // Native WP6 controls and approved board dimensions.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.sans

/** Quoted source speech stays above the Name field and scrolls with it. */
@Composable
internal fun SpeakerQuote(quote: String) {
    val c = CassiniTheme.colors
    Text(
        stringResource(R.string.ui_sp_quote, quote),
        Modifier.fillMaxWidth().testTag("speaker-quote").drawBehind {
            val stroke = 3.dp.toPx()
            drawLine(c.line, Offset(stroke / 2, 0f), Offset(stroke / 2, size.height), stroke)
        }.padding(start = 15.dp),
        style = sans(15, lh = 1.5f),
        color = c.text2,
    )
}

/** A native 48dp target around the compact 32dp notice pill. Text can grow and wrap at large scales. */
@Composable
internal fun NameNoticeButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val c = CassiniTheme.colors
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(
            label,
            Modifier.clip(CassiniTheme.shapes.pill)
                .background(if (primary) c.accentFill else c.ink.copy(alpha = 0.10f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            style = CassiniTheme.type.label14,
            color = if (primary) c.onAccentFill else c.ink,
        )
    }
}

/** One toggleable card with a decorative native switch avoids duplicate or competing click actions. */
@Composable
internal fun RememberVoiceCard(ui: NameSheetUi, subtitle: String, onEvent: (NameSheetEvent) -> Unit) {
    val c = CassiniTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(CassiniTheme.shapes.row)
            .background(c.field).border(1.dp, c.lineSoft, CassiniTheme.shapes.row)
            .heightIn(min = 48.dp).testTag("remember-voice")
            .toggleable(
                value = ui.remember,
                enabled = ui.rememberEnabled,
                role = Role.Switch,
                onValueChange = { onEvent(NameSheetEvent.RememberChanged(it)) },
            ).semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ui_sp_remember_title), style = CassiniTheme.type.body16, color = c.ink)
            Text(subtitle, style = CassiniTheme.type.caption, color = c.text2)
        }
        Switch(
            checked = ui.remember,
            onCheckedChange = null,
            enabled = ui.rememberEnabled,
            modifier = Modifier.clearAndSetSemantics {},
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accentFill,
                checkedThumbColor = c.onAccentFill,
                checkedBorderColor = c.accentFill,
            ),
        )
    }
}
