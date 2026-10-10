// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 6. The file
// is named after its composable, not after the TopBarStyle enum it also holds.
@file:Suppress("FunctionNaming", "MagicNumber", "MatchingDeclarationName")

package org.cassini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.theme.CassiniIcon
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/**
 * The top bar styles: [Screen] (Settings, Saved voices, Trash: `screenTitle`), [Note] (the Note screen with its
 * subtitle: `noteTitle`) and [Read] (Read & listen: `noteTitle`, no bottom padding and no gap after Back, so the
 * title starts at 54 dp instead of 56).
 */
enum class TopBarStyle { Screen, Note, Read }

/**
 * The top bar of every pushed screen, a plain Row. Board padding 12/8/4 with 44 dp buttons, compensated for the
 * 48 dp layout to top 10, horizontal 6, bottom 2 (Read 0) and a 2 dp gap after Back (Read 0), so the title starts
 * at 56 dp. The title is one line with an ellipsis and a heading; [subtitle] (`caption`, text2) sits directly below
 * it. [actions] are [IconButton44]s or a [DestructiveTextButton] at the end.
 */
@Composable
@Suppress("LongParameterList") // Title, back button and its label, style, subtitle and the action slot.
fun CassiniTopBar(
    title: String,
    onBack: () -> Unit,
    backLabel: String,
    modifier: Modifier = Modifier,
    style: TopBarStyle = TopBarStyle.Screen,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = CassiniTheme.colors
    val read = style == TopBarStyle.Read
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 6.dp, end = 6.dp, top = 10.dp, bottom = if (read) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton44(Glyph.Back, contentDescription = backLabel, onClick = onBack, stroke = 1.8f)
        if (!read) Spacer(Modifier.width(2.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                modifier = Modifier.semantics { heading() },
                style = if (style == TopBarStyle.Screen) CassiniTheme.type.screenTitle else CassiniTheme.type.noteTitle,
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(text = subtitle, style = CassiniTheme.type.caption, color = c.text2, maxLines = 1)
            }
        }
        actions()
    }
}

/**
 * The Library header: logo 28/1.6 + 10 dp + "Notes" (`libraryTitle`, a heading), Trash and Settings at the end.
 * Board padding 20/16/8 with 44 dp buttons, compensated to 18/14/6; the logo keeps its 16 dp inset.
 */
@Composable
fun LibraryTopBar(onTrash: () -> Unit, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val c = CassiniTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(2.dp))
        CassiniIcon(Glyph.Logo, 28.dp, 1.6f, c.ink, null)
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.ui_library_title),
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
            style = CassiniTheme.type.libraryTitle,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton44(Glyph.Trash, stringResource(R.string.ui_library_trash), onTrash, stroke = 1.7f)
        IconButton44(Glyph.Settings, stringResource(R.string.ui_library_settings), onSettings, stroke = 1.7f)
    }
}

@Preview
@Composable
internal fun CassiniTopBarPreview() {
    CassiniTheme {
        Column(Modifier.background(CassiniTheme.colors.bg)) {
            LibraryTopBar(onTrash = {}, onSettings = {})
            CassiniTopBar(title = "Settings", onBack = {}, backLabel = "Back to library")
            CassiniTopBar(
                title = "Weekly product sync",
                onBack = {},
                backLabel = "Back to library",
                style = TopBarStyle.Note,
                subtitle = "Today, 10:02 · 48:13",
            ) {
                IconButton44(Glyph.More, "More actions", onClick = {}, stroke = 0f)
            }
        }
    }
}
