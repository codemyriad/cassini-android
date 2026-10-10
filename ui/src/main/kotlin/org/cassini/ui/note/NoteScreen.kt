// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md sections 6 and 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.note

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.Banner
import org.cassini.ui.components.CassiniSnackbarHost
import org.cassini.ui.components.CassiniTopBar
import org.cassini.ui.components.FollowChip
import org.cassini.ui.components.IconButton44
import org.cassini.ui.components.PlaybackBar
import org.cassini.ui.components.RenameDialog
import org.cassini.ui.components.TopBarStyle
import org.cassini.ui.format.Dates
import org.cassini.ui.format.Durations
import org.cassini.ui.theme.CassiniTheme
import org.cassini.ui.theme.Glyph

/**
 * The Note screen (boards 02 and 03; features.md section 3; ui.md section 8): one destination whose body follows
 * [NoteUiState.mode]. The header is the top bar or, while [NoteUiState.search] is set, the in-note search header;
 * system back then closes the search first ([NoteEvent.CloseSearch]) and only a second back leaves the screen
 * (the navigation in :app handles that one). The player sits at the bottom whenever [NoteUiState.playback] is set.
 *
 * Reading mode (board 03) is [ReadAndListenBody]; every other mode shows the top bar with the subtitle and the
 * body of [NoteModeBody] (board 02 and the modes without a board). P8 step 4 adds the remaining sheets and dialogs.
 */
@Composable
fun NoteScreen(
    state: NoteUiState,
    onEvent: (NoteEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val search = state.search
    BackHandler(enabled = search != null) { onEvent(NoteEvent.CloseSearch) }
    val listState = rememberLazyListState()
    Column(
        modifier
            .fillMaxSize()
            .background(CassiniTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        if (search != null) {
            TranscriptSearchHeader(search, onEvent)
        } else {
            NoteTopBar(state, onEvent)
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(Modifier.fillMaxSize()) {
                when (state.mode) {
                    NoteMode.Reading -> ReadAndListenBody(state, listState, onEvent)
                    else -> NoteModeBody(state, onEvent)
                }
            }
            val playback = state.playback
            if (playback != null && !playback.following) {
                FollowChip(
                    text = stringResource(R.string.ui_follow_playback),
                    onClick = { onEvent(NoteEvent.FollowPlayback) },
                    // 16 dp above the player: the chip's 48 dp layout holds 8 dp of it.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
            }
            CassiniSnackbarHost(
                spec = state.snackbar,
                onTimeout = { onEvent(NoteEvent.SnackbarTimeout) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        val playback = state.playback
        if (playback != null) {
            PlaybackBar(
                positionMs = playback.positionMs,
                durationMs = playback.durationMs,
                playing = playback.playing,
                onSeek = { onEvent(NoteEvent.Seek(it)) },
                onSkipBack = { onEvent(NoteEvent.Skip(forward = false)) },
                onSkipForward = { onEvent(NoteEvent.Skip(forward = true)) },
                onPlayPause = { onEvent(NoteEvent.PlayPause) },
                modifier = Modifier.navigationBarsPadding(),
                onSeekFinished = { onEvent(NoteEvent.SeekFinished) },
            )
        }
    }
    NoteOverlays(state, onEvent)
}

/**
 * The top bar. Reading mode (board 03): no subtitle, Search ("Search in transcript", 21/1.8) and More ("Share and
 * more"). Every other mode (board 02): the subtitle "Today, 10:02 · 48:13" and More ("More actions").
 */
@Composable
private fun NoteTopBar(state: NoteUiState, onEvent: (NoteEvent) -> Unit) {
    val reading = state.mode == NoteMode.Reading
    val subtitle = state.subtitle?.let {
        stringResource(R.string.ui_join_dot, Dates.dayTime(it.day, it.time), Durations.clock(it.durationMs))
    }
    CassiniTopBar(
        title = state.title,
        onBack = { onEvent(NoteEvent.Back) },
        backLabel = stringResource(R.string.ui_back_to_library),
        style = if (reading) TopBarStyle.Read else TopBarStyle.Note,
        subtitle = if (reading) null else subtitle,
    ) {
        if (reading) {
            IconButton44(
                glyph = Glyph.Search,
                contentDescription = stringResource(R.string.ui_note_search),
                onClick = { onEvent(NoteEvent.OpenSearch) },
                iconSize = 21.dp,
                stroke = 1.8f,
            )
        }
        IconButton44(
            glyph = Glyph.More,
            contentDescription = stringResource(
                if (reading) R.string.ui_note_share_more else R.string.ui_note_more_actions,
            ),
            onClick = { onEvent(NoteEvent.More) },
            stroke = 0f,
        )
    }
}

/** Read & listen (board 03): the chips row, the banners (margin 16 horizontal, 10 below) and the transcript pane. */
@Composable
internal fun ColumnScope.ReadAndListenBody(
    state: NoteUiState,
    listState: LazyListState,
    onEvent: (NoteEvent) -> Unit,
) {
    state.chips?.let { NoteChipsRow(it, onEvent) }
    state.banners.forEach { spec ->
        Banner(
            spec = spec,
            onAction = { onEvent(NoteEvent.BannerAction(spec.id, it)) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            onDismiss = { onEvent(NoteEvent.DismissBanner(spec.id)) },
        )
    }
    TranscriptPane(
        turns = state.turns,
        activeWord = state.activeWord,
        search = state.search,
        following = state.playback?.let { it.following && it.playing } ?: false,
        followPaused = state.playback?.let { !it.following && it.playing } ?: false,
        listState = listState,
        onEvent = onEvent,
        modifier = Modifier.weight(1f),
    )
}

/** The open sheet and dialog. This step draws the actions sheet and Rename; P8 step 4 draws the others. */
@Composable
private fun NoteOverlays(state: NoteUiState, onEvent: (NoteEvent) -> Unit) {
    val dismissSheet = { onEvent(NoteEvent.DismissSheet) }
    when (val sheet = state.sheet) {
        is NoteSheet.Actions -> NoteActionsSheet(
            ui = sheet.ui,
            onAction = { onEvent(NoteEvent.SheetAction(it)) },
            onDismiss = dismissSheet,
        )
        else -> Unit
    }
    when (val dialog = state.dialog) {
        is NoteDialog.Rename -> RenameDialog(
            title = dialog.title,
            onConfirm = { onEvent(NoteEvent.RenameConfirmed(it)) },
            onDismiss = { onEvent(NoteEvent.DismissDialog) },
        )
        else -> Unit
    }
}
