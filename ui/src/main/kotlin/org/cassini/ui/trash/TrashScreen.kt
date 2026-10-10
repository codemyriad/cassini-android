// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md section 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.trash

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.BannerCard
import org.cassini.ui.components.CassiniSnackbarHost
import org.cassini.ui.components.CassiniTopBar
import org.cassini.ui.components.DestructiveTextButton
import org.cassini.ui.components.DestructiveTextButtonSize
import org.cassini.ui.components.SecondaryButton
import org.cassini.ui.components.SecondaryButtonSize
import org.cassini.ui.components.screenContentWidth
import org.cassini.ui.format.Dates
import org.cassini.ui.format.Durations
import org.cassini.ui.format.Spoken
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Tone

/**
 * Trash (board 09, features.md section 8; ui.md section 8). A Column on bg: the top bar (back "Back to library" or
 * "Back to settings" after [TrashUiState.backTo], "Trash", and "Empty" while there are notes), then one LazyColumn
 * with the intro and a card per note, oldest deletion first. With no notes the intro gives way to the plain "Trash
 * is empty" card. The snackbar sits 24 dp above the navigation bar; the confirmations open in their own windows.
 */
@Composable
fun TrashScreen(
    state: TrashUiState,
    onEvent: (TrashEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        modifier
            .fillMaxSize()
            .background(CassiniTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .screenContentWidth(),
    ) {
        Column(Modifier.fillMaxSize()) {
            CassiniTopBar(
                title = stringResource(R.string.ui_trash_title),
                onBack = { onEvent(TrashEvent.Back) },
                backLabel = stringResource(
                    if (state.backTo == TrashBackTo.SETTINGS) R.string.ui_back_to_settings else R.string.ui_back_to_library,
                ),
            ) {
                if (state.items.isNotEmpty()) {
                    DestructiveTextButton(
                        text = stringResource(R.string.ui_trash_empty_action),
                        onClick = { onEvent(TrashEvent.Empty) },
                        // The bar's 6 dp end padding is for 44 dp icon buttons; a text button is no wider than its
                        // visual, so it takes the board's 8.
                        modifier = Modifier.padding(end = 2.dp),
                        size = DestructiveTextButtonSize.Header,
                    )
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(bottom = bottom + 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                trashItems(state, onEvent)
            }
        }
        CassiniSnackbarHost(
            spec = state.snackbar,
            onTimeout = { onEvent(TrashEvent.SnackbarTimeout) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding(),
            onUndo = { onEvent(TrashEvent.Undo) },
        )
    }
    val dialog = state.dialog
    if (dialog != null) TrashDialogWindow(dialog, onEvent)
}

private fun LazyListScope.trashItems(
    state: TrashUiState,
    onEvent: (TrashEvent) -> Unit,
) {
    if (state.items.isEmpty()) {
        item(key = "empty") {
            BannerCard(
                tone = Tone.Plain,
                title = stringResource(R.string.ui_trash_is_empty),
                body = stringResource(R.string.ui_trash_is_empty_body),
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
            )
        }
        return
    }
    item(key = "intro") {
        // The intro's 14 dp bottom margin: the list's 8 dp gap and 6 dp here, less the 1 dp that Android's three
        // 20.5 dp lines take beyond the board's 20.3.
        Text(
            text = stringResource(R.string.ui_trash_intro),
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 5.dp),
            style = CassiniTheme.type.body14,
            color = CassiniTheme.colors.text3,
        )
    }
    items(state.items, key = { it.id }) { item -> TrashCard(item, onEvent) }
}

/**
 * A trashed note: surface, 1 dp line, radius 16, margin 16 horizontal. Board padding 14/14/10 with 40 dp buttons;
 * the 48 dp layout of the buttons takes 4 dp from the meta line's 8 dp margin and 4 dp from the bottom padding.
 * Android sets the 16 sp title 1 dp lower in its line than the browser, so the top padding is 1 dp less and the meta
 * line's top margin 0.5 dp more (measured on board 09). The
 * title (`rowTitle`) and the duration (`monoTime` text2) share the first row, gap 8; "Deleted 2 Oct · 23 days left"
 * (`caption` text2) follows 2 dp below; then Restore and "Delete now", gap 8.
 */
@Composable
private fun TrashCard(item: TrashItemUi, onEvent: (TrashEvent) -> Unit) {
    val c = CassiniTheme.colors
    val type = CassiniTheme.type
    val shape = CassiniTheme.shapes.card
    val spokenDuration = Spoken.duration(item.durationMs)
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.line, shape)
            .padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 7.dp),
    ) {
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = item.title, modifier = Modifier.weight(1f), style = type.rowTitle, color = c.ink)
                Text(
                    text = Durations.clock(item.durationMs),
                    modifier = Modifier.semantics { contentDescription = spokenDuration },
                    style = type.monoTime,
                    color = c.text2,
                )
            }
            Text(
                text = Dates.trashLine(item.deletedOn, item.daysLeft),
                modifier = Modifier.padding(top = 2.5.dp, bottom = 4.dp),
                style = type.caption,
                color = c.text2,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = stringResource(R.string.ui_trash_restore),
                onClick = { onEvent(TrashEvent.Restore(item.id)) },
                size = SecondaryButtonSize.Medium,
            )
            DestructiveTextButton(
                text = stringResource(R.string.ui_trash_delete_now),
                onClick = { onEvent(TrashEvent.DeleteNow(item.id)) },
                size = DestructiveTextButtonSize.Small,
            )
        }
    }
}
