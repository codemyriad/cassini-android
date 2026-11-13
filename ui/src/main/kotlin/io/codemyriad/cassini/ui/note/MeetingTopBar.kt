@file:Suppress("FunctionNaming", "MagicNumber") // Native M3 bar with approved phone type.

package io.codemyriad.cassini.ui.note

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.theme.CassiniIcon
import io.codemyriad.cassini.ui.theme.CassiniTheme
import io.codemyriad.cassini.ui.theme.Glyph

/** Android retains its title in the bar; its scrolled edge has the native phone hairline (L-55). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MeetingTopBar(state: NoteUiState, scrolled: Boolean, onEvent: (NoteEvent) -> Unit) {
    val c = CassiniTheme.colors
    Column {
        TopAppBar(
            modifier = Modifier.testTag("meeting-app-bar"),
            title = {
                Text(
                    state.title,
                    style = CassiniTheme.type.noteTitle.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
            },
            navigationIcon = {
                MeetingIcon(Glyph.Back, stringResource(R.string.ui_back_to_library)) { onEvent(NoteEvent.Back) }
            },
            actions = {
                MeetingIcon(Glyph.Search, stringResource(R.string.ui_note_search)) { onEvent(NoteEvent.OpenSearch) }
                MeetingIcon(
                    Glyph.Share,
                    stringResource(R.string.ui_actions_share, stringResource(R.string.ui_actions_document)),
                ) {
                    onEvent(NoteEvent.SheetAction(NoteAction.SHARE_DOCUMENT))
                }
                MeetingIcon(Glyph.More, stringResource(R.string.ui_note_more_actions)) { onEvent(NoteEvent.More) }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = if (scrolled) c.surface else c.bg),
            windowInsets = WindowInsets(0, 0, 0, 0),
        )
        if (scrolled) HorizontalDivider(color = c.line, modifier = Modifier.testTag("meeting-scrolled-edge"))
    }
}

@Composable
private fun MeetingIcon(glyph: Glyph, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        CassiniIcon(
            glyph,
            21.dp,
            if (glyph == Glyph.More) 0f else 1.8f,
            if (glyph == Glyph.Back) CassiniTheme.colors.ink else CassiniTheme.colors.text2,
            label,
        )
    }
}
