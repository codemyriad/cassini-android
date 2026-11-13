@file:Suppress("FunctionNaming") // Native M3 find bar.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.IconButton44
import io.codemyriad.cassini.ui.components.rememberSyncedText
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** One native bar: close, query, result counter and previous/next; opening an empty query requests the IME. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptSearchHeader(search: TranscriptSearchUi, onEvent: (NoteEvent) -> Unit) {
    val c = CassiniTheme.colors
    val focus = remember { FocusRequester() }
    val openedEmpty = remember { search.query.isEmpty() }
    if (openedEmpty) LaunchedEffect(Unit) { focus.requestFocus() }
    androidx.compose.foundation.layout.Column {
        TopAppBar(
            title = {
                FindQueryField(search, focus, onEvent)
            },
            navigationIcon = {
                IconButton44(Glyph.Back, stringResource(R.string.ui_search_close), { onEvent(NoteEvent.CloseSearch) })
            },
            actions = {
                if (search.query.isNotBlank()) {
                    Text(
                        if (search.total > 0) {
                            stringResource(R.string.ui_search_counter, search.current + 1, search.total)
                        } else {
                            stringResource(R.string.ui_search_no_matches)
                        },
                        style = CassiniTheme.type.monoTime,
                        color = c.text2,
                    )
                }
                IconButton44(
                    Glyph.ChevronUp,
                    stringResource(R.string.ui_search_previous),
                    { onEvent(NoteEvent.PreviousMatch) },
                    enabled = search.total > 0,
                )
                IconButton44(
                    Glyph.ChevronDown,
                    stringResource(R.string.ui_search_next),
                    { onEvent(NoteEvent.NextMatch) },
                    enabled = search.total > 0,
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = c.surface),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        HorizontalDivider(color = c.line)
    }
}

@Composable
private fun FindQueryField(search: TranscriptSearchUi, focus: FocusRequester, onEvent: (NoteEvent) -> Unit) {
    val c = CassiniTheme.colors
    val keyboard = LocalSoftwareKeyboardController.current
    val text = rememberSyncedText(search.query)
    val description = stringResource(R.string.ui_note_search)
    TextField(
        value = text.value,
        onValueChange = { text.edit(it, send = { query -> onEvent(NoteEvent.SearchQuery(query)) }) },
        modifier = Modifier.focusRequester(focus).semantics { contentDescription = description },
        textStyle = CassiniTheme.type.body16,
        singleLine = true,
        placeholder = {
            Text(
                stringResource(R.string.ui_note_search_placeholder),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = c.surface,
            unfocusedContainerColor = c.surface,
            focusedIndicatorColor = c.surface.copy(alpha = 0f),
            unfocusedIndicatorColor = c.surface.copy(alpha = 0f),
            focusedTextColor = c.ink,
            unfocusedTextColor = c.ink,
            cursorColor = c.accent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
    )
}
