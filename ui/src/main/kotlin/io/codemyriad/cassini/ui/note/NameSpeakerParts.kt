@file:Suppress("FunctionNaming", "MagicNumber") // Native WP6 controls and approved board dimensions.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.display
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.colors
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
        Box(
            Modifier.heightIn(min = 32.dp).clip(CassiniTheme.shapes.pill)
                .background(if (primary) c.accentFill else c.ink.copy(alpha = 0.10f))
                .testTag(if (primary) "name-primary-pill" else "name-secondary-pill")
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = CassiniTheme.type.label14,
                color = if (primary) c.onAccentFill else c.ink,
            )
        }
    }
}

/**
 * One toggleable card with a decorative native switch avoids duplicate or competing click actions. A [subtitle] with a
 * link (the Settings link while voice recognition is off) is not merged into the card, so TalkBack reaches the link.
 */
@Composable
internal fun RememberVoiceCard(ui: NameSheetUi, subtitle: AnnotatedString, onEvent: (NameSheetEvent) -> Unit) {
    val linked = subtitle.getLinkAnnotations(0, subtitle.length).isNotEmpty()
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
            ).semantics(mergeDescendants = !linked) {}
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

/**
 * The speaker row across the top of the sheet (D112 / L-86; board Feature-BackgroundVoices 2F): every speaker in the
 * order of the names under the meeting title, scrolling sideways. A chip is 34 dp tall (48 dp to touch), radius 8,
 * padding 10, a 7 dp colour dot and the name at 15 sp; the selected one has the accent tint and a 1.5 dp accent
 * border; a background voice has a hollow dot and a grey name; a placeholder name is underlined, as under the title.
 * A 1 dp soft line closes the row.
 */
@Composable
internal fun SpeakerRow(
    chips: List<SpeakerChipUi>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = CassiniTheme.colors
    val state = rememberLazyListState()
    val index = chips.indexOfFirst { it.speakerId == selected }
    LaunchedEffect(index) {
        val visible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val viewport = state.layoutInfo.viewportEndOffset
        val clipped = visible == null || visible.offset < 0 || visible.offset + visible.size > viewport
        if (index >= 0 && clipped) state.scrollToItem(index)
    }
    LazyRow(
        state = state,
        modifier = modifier
            .fillMaxWidth()
            .testTag("speaker-row")
            .drawBehind {
                val line = 1.dp.toPx()
                val y = size.height - line / 2
                drawLine(c.lineSoft, Offset(0f, y), Offset(size.width, y), line)
            }
            .selectableGroup()
            .padding(bottom = 6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(chips, key = { _, chip -> chip.speakerId }) { _, chip ->
            SpeakerChip(chip, chip.speakerId == selected) { onSelect(chip.speakerId) }
        }
    }
}

@Composable
private fun SpeakerChip(chip: SpeakerChipUi, selected: Boolean, onClick: () -> Unit) {
    val c = CassiniTheme.colors
    val name = chip.label.display()
    val shape = CassiniTheme.shapes.tag
    val description = if (chip.background) {
        stringResource(R.string.ui_join_dot, name, stringResource(R.string.ui_bg_switch))
    } else {
        name
    }
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .testTag("speaker-chip-${chip.speakerId}")
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 34.dp)
                .clip(shape)
                .then(if (selected) Modifier.background(c.accentTint).border(1.5.dp, c.accent, shape) else Modifier)
                .padding(horizontal = 10.dp)
                .clearAndSetSemantics {},
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dot = Modifier.size(7.dp)
            Box(
                if (chip.background) {
                    dot.border(1.5.dp, c.text3, CircleShape)
                } else {
                    dot.background(chip.tone.colors.ink, CircleShape)
                },
            )
            val placeholder = chip.label is SpeakerNameUi.Placeholder
            Text(
                name,
                style = CassiniTheme.type.body14Plain.copy(fontSize = 15.sp),
                color = if (chip.background) c.text3 else c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp).drawBehind {
                    if (placeholder && !chip.background) {
                        drawLine(
                            c.text2.copy(alpha = 0.55f),
                            Offset(0f, size.height),
                            Offset(size.width, size.height),
                            1.dp.toPx(),
                        )
                    }
                },
            )
        }
    }
}

/**
 * The Background voice card (D112 / L-86): "Background voice" with "Not part of the conversation. …" and a native
 * switch; the whole card toggles, as Remember does. Switched on, its border is the 2 dp accent of board 2F.
 */
@Composable
internal fun BackgroundVoiceCard(ui: NameSheetUi, onEvent: (NameSheetEvent) -> Unit) {
    val c = CassiniTheme.colors
    val shape = CassiniTheme.shapes.row
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(c.field)
            .border(if (ui.background) 2.dp else 1.dp, if (ui.background) c.accent else c.lineSoft, shape)
            .heightIn(min = 48.dp).testTag("background-voice")
            .toggleable(
                value = ui.background,
                role = Role.Switch,
                onValueChange = { onEvent(NameSheetEvent.BackgroundChanged(it)) },
            ).semantics(mergeDescendants = true) {}
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.ui_bg_switch), style = CassiniTheme.type.body16, color = c.ink)
            Text(stringResource(R.string.ui_bg_description), style = CassiniTheme.type.caption, color = c.text2)
        }
        Switch(
            checked = ui.background,
            onCheckedChange = null,
            modifier = Modifier.clearAndSetSemantics {},
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accentFill,
                checkedThumbColor = c.onAccentFill,
                checkedBorderColor = c.accentFill,
            ),
        )
    }
}

/** The 1 dp soft line between the rows of the saved voices card. */
@Composable
internal fun RowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(CassiniTheme.colors.lineSoft),
    )
}
