@file:Suppress("FunctionNaming", "MagicNumber") // Native Find targets and adaptive query space.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.IconButton44
import io.codemyriad.cassini.ui.components.rememberSyncedText
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** The query always keeps usable width; wider counters and large text get a separate action row. */
@Composable
internal fun TranscriptSearchHeader(search: TranscriptSearchUi, onEvent: (NoteEvent) -> Unit) {
    val focus = remember { FocusRequester() }
    val openedEmpty = remember { search.query.isEmpty() }
    if (openedEmpty) LaunchedEffect(Unit) { focus.requestFocus() }
    val count = if (search.query.isBlank()) {
        ""
    } else if (search.total > 0) {
        stringResource(R.string.ui_search_counter, search.current + 1, search.total)
    } else {
        stringResource(R.string.ui_search_no_matches)
    }
    val style = if (search.total > 0) CassiniTheme.type.monoTime else CassiniTheme.type.caption
    val measure = rememberTextMeasurer()
    val size = measure.measure(AnnotatedString(count), style = style).size
    val countWidth = with(LocalDensity.current) { size.width.toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth().background(CassiniTheme.colors.surface).testTag("meeting-find-bar")) {
        val availableWidth = maxWidth
        val stacked = availableWidth - 8.dp - 48.dp - 96.dp - countWidth < 144.dp
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton44(
                    Glyph.Back,
                    stringResource(R.string.ui_search_close),
                    { onEvent(NoteEvent.CloseSearch) },
                    modifier = Modifier.testTag("meeting-find-close"),
                )
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) { FindQueryField(search, focus, onEvent) }
                if (!stacked) FindActions(search, count, style, countWidth, onEvent)
            }
            if (stacked) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                ) {
                    FindActions(search, count, style, (availableWidth - 120.dp).coerceAtLeast(0.dp), onEvent)
                }
            }
        }
    }
}

@Composable
private fun FindActions(
    search: TranscriptSearchUi,
    count: String,
    style: TextStyle,
    countWidth: Dp,
    onEvent: (NoteEvent) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (count.isNotEmpty()) {
            Text(
                count,
                modifier = Modifier.widthIn(max = countWidth).testTag("meeting-find-count"),
                style = style,
                color = CassiniTheme.colors.text2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton44(
            Glyph.ChevronUp,
            stringResource(R.string.ui_search_previous),
            { onEvent(NoteEvent.PreviousMatch) },
            modifier = Modifier.testTag("meeting-find-previous"),
            enabled = search.total > 0,
        )
        IconButton44(
            Glyph.ChevronDown,
            stringResource(R.string.ui_search_next),
            { onEvent(NoteEvent.NextMatch) },
            modifier = Modifier.testTag("meeting-find-next"),
            enabled = search.total > 0,
        )
    }
}

@Composable
private fun FindQueryField(search: TranscriptSearchUi, focus: FocusRequester, onEvent: (NoteEvent) -> Unit) {
    val c = CassiniTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val text = rememberSyncedText(search.query)
    val description = stringResource(R.string.ui_note_search)
    BasicTextField(
        value = text.value,
        onValueChange = { text.edit(it, send = { query -> onEvent(NoteEvent.SearchQuery(query)) }) },
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).focusRequester(focus)
            .semantics { contentDescription = description }.testTag("meeting-find-query"),
        textStyle = CassiniTheme.type.body16.copy(color = c.ink),
        singleLine = true,
        cursorBrush = SolidColor(c.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (text.value.text.isEmpty()) {
                    Text(
                        stringResource(R.string.ui_note_search_placeholder),
                        style = CassiniTheme.type.body16,
                        color = c.text2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            }
        },
    )
}
