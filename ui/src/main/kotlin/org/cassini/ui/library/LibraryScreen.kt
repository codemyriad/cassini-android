// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md sections 6 and 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.cassini.ui.R
import org.cassini.ui.components.Banner
import org.cassini.ui.components.CassiniSnackbarHost
import org.cassini.ui.components.ConfirmDialog
import org.cassini.ui.components.DayHeader
import org.cassini.ui.components.LibraryBottomBar
import org.cassini.ui.components.LibraryTopBar
import org.cassini.ui.components.NoteRow
import org.cassini.ui.components.NoteRowAction
import org.cassini.ui.components.NoteRowUi
import org.cassini.ui.components.PillSearchField
import org.cassini.ui.components.PrivacyLine
import org.cassini.ui.components.ProgressBannerCard
import org.cassini.ui.components.RecordingBannerCard
import org.cassini.ui.components.RenameDialog
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Dates
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.Progress
import org.cassini.ui.note.NoteActionsSheet
import org.cassini.ui.note.startOverSpec
import org.cassini.ui.note.stopAndTrashSpec
import org.cassini.ui.theme.CassiniTheme

/** The list's bottom padding: the bottom bar (40 + 64 + 24 dp) plus the navigation bar. */
private val BottomBarHeight = 128.dp

/** The snackbar sits 24 dp above the Record button: the button and its 24 dp bottom padding. */
private val SnackbarLift = 88.dp

/**
 * The Library (board 00, features.md section 1; ui.md section 8). A `Box` on bg holds one `LazyColumn`: the header,
 * the search field (padding 16 horizontal, top 4) with the privacy line, the activity area (recording card, progress
 * card, banners), then either the day sections (list padding top 6) or, while searching, the flat results. The
 * bottom bar with Import and Record lies over the list, the snackbar 24 dp above Record. The actions sheet and the
 * dialogs open in their own windows from [LibraryUiState.sheet] and [LibraryUiState.dialog].
 *
 * While [LibraryUiState.loading] the list shows nothing below the privacy line, so the empty banner does not flash
 * before the first rows arrive. The empty and no-match states arrive as banners (LIB_EMPTY, LIB_NO_MATCH).
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarHeight
    Box(
        modifier
            .fillMaxSize()
            .background(CassiniTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(bottom = bottom),
        ) {
            item(key = "header") {
                LibraryTopBar(
                    onTrash = { onEvent(LibraryEvent.OpenTrash) },
                    onSettings = { onEvent(LibraryEvent.OpenSettings) },
                )
            }
            item(key = "search") { SearchBlock(state.query, onEvent) }
            activity(state, onEvent)
            if (!state.loading) {
                val results = state.results
                if (results != null) results(results, onEvent) else sections(state.sections, onEvent)
            }
        }
        LibraryBottomBar(
            onImport = { onEvent(LibraryEvent.Import) },
            onRecord = {
                onEvent(if (state.recording != null) LibraryEvent.ReturnToRecording else LibraryEvent.Record)
            },
            modifier = Modifier.align(Alignment.BottomCenter),
            recordingElapsedMs = state.recording?.elapsedMs,
        )
        CassiniSnackbarHost(
            spec = state.snackbar,
            onTimeout = { onEvent(LibraryEvent.SnackbarTimeout) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = SnackbarLift),
            onUndo = { onEvent(LibraryEvent.Undo) },
        )
    }
    state.sheet?.let { sheet ->
        NoteActionsSheet(
            ui = sheet,
            onAction = { onEvent(LibraryEvent.SheetAction(it)) },
            onDismiss = { onEvent(LibraryEvent.DismissSheet) },
        )
    }
    state.dialog?.let { LibraryDialogWindow(it, onEvent) }
}

/** The search field and the privacy line under it. IME Search hides the keyboard; the results follow the query. */
@Composable
private fun SearchBlock(query: String, onEvent: (LibraryEvent) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
        PillSearchField(
            query = query,
            onQueryChange = { onEvent(LibraryEvent.Query(it)) },
            placeholder = stringResource(R.string.ui_library_search_placeholder),
            onSearch = { keyboard?.hide() },
            onClear = { onEvent(LibraryEvent.ClearQuery) },
        )
        PrivacyLine(stringResource(R.string.ui_library_privacy))
    }
}

/**
 * The activity area (features.md section 1, item 4): the recording card, the progress card and the banners, in this
 * order. The first item sits 14 dp below the privacy line, the next ones 8 dp below the one before.
 */
private fun LazyListScope.activity(state: LibraryUiState, onEvent: (LibraryEvent) -> Unit) {
    var first = true
    fun top(): Modifier {
        val gap = if (first) 14.dp else 8.dp
        first = false
        return Modifier.padding(start = 16.dp, end = 16.dp, top = gap)
    }
    state.recording?.let { recording ->
        val modifier = top()
        item(key = "recording") {
            RecordingBannerCard(
                title = recording.title,
                elapsedMs = recording.elapsedMs,
                paused = recording.paused,
                onClick = { onEvent(LibraryEvent.ReturnToRecording) },
                modifier = modifier,
            )
        }
    }
    state.progress?.let { progress ->
        val modifier = top()
        item(key = "progress") { ProgressCard(progress, onEvent, modifier) }
    }
    state.banners.forEach { spec ->
        val modifier = top()
        item(key = "banner-${spec.id}") {
            Banner(
                spec = spec,
                onAction = { onEvent(LibraryEvent.BannerAction(spec.id, it)) },
                modifier = modifier,
                onDismiss = { onEvent(LibraryEvent.DismissBanner(spec.id)) },
            )
        }
    }
}

/** The transcription banner with its title and line 2 (features.md section 1, item 4). */
@Composable
private fun ProgressCard(progress: ProgressBannerUi, onEvent: (LibraryEvent) -> Unit, modifier: Modifier) {
    val title = when {
        progress.title == null -> stringResource(R.string.ui_progress_downloading_models)
        progress.line is ProgressLine.Downloading || progress.line is ProgressLine.Checking ->
            stringResource(R.string.ui_progress_getting_ready, progress.title)
        else -> stringResource(R.string.ui_progress_transcribing, progress.title)
    }
    val line = when (val l = progress.line) {
        is ProgressLine.TimeLeft -> Progress.timeLeftLine(l.timeLeft)
        ProgressLine.Waiting -> stringResource(R.string.ui_headline_waiting)
        is ProgressLine.Downloading -> l.part.downloading(l.percent)
        is ProgressLine.Checking -> stringResource(R.string.ui_banner_dl_checking_title, l.percent)
    }
    val text = if (progress.queued > 0) {
        stringResource(R.string.ui_join_dot, line, Counts.plural(R.plurals.ui_count_more_in_queue, progress.queued))
    } else {
        line
    }
    ProgressBannerCard(
        title = title,
        percent = progress.percent,
        progress = progress.progress,
        line = text,
        onClick = { onEvent(LibraryEvent.OpenProgress) },
        modifier = modifier,
        onClickLabel = stringResource(
            if (progress.noteId == null) R.string.ui_action_open_settings else R.string.ui_action_open_note,
        ),
    )
}

/** The day sections: an overline header per day (top 14 for the first, else 16) and rows 2 dp apart. */
private fun LazyListScope.sections(sections: List<DaySection>, onEvent: (LibraryEvent) -> Unit) {
    item(key = "list-top") { Spacer(Modifier.height(6.dp)) }
    sections.forEachIndexed { index, section ->
        item(key = "day-${section.label}") { DayHeader(Dates.dayLabel(section.label), first = index == 0) }
        rows(section.rows, section.label, onEvent)
    }
}

/** The flat search results, newest first, each with its own day in the meta line. */
private fun LazyListScope.results(results: List<NoteRowUi>, onEvent: (LibraryEvent) -> Unit) {
    item(key = "list-top") { Spacer(Modifier.height(6.dp)) }
    rows(results, day = null, onEvent)
}

private fun LazyListScope.rows(rows: List<NoteRowUi>, day: DayLabel?, onEvent: (LibraryEvent) -> Unit) {
    itemsIndexed(rows, key = { _, row -> "row-${row.id}" }) { index, row ->
        NoteRow(
            row = row,
            day = row.day ?: day ?: DayLabel.Today,
            onAction = { onEvent(row.event(it)) },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = if (index == 0) 0.dp else 2.dp),
        )
    }
}

private fun NoteRowUi.event(action: NoteRowAction): LibraryEvent = when (action) {
    NoteRowAction.OPEN -> LibraryEvent.OpenNote(id)
    NoteRowAction.LONG_PRESS -> LibraryEvent.LongPress(id)
    NoteRowAction.RESUME -> LibraryEvent.Resume(id)
    NoteRowAction.TRY_AGAIN -> LibraryEvent.TryAgain(id)
    NoteRowAction.RENAME -> LibraryEvent.Rename(id)
    NoteRowAction.TRASH -> LibraryEvent.Trash(id)
}

/** A Library dialog in its window: Rename, or a confirmation from the actions sheet. */
@Composable
private fun LibraryDialogWindow(dialog: LibraryDialog, onEvent: (LibraryEvent) -> Unit) {
    val dismiss = { onEvent(LibraryEvent.DismissDialog) }
    when (dialog) {
        is LibraryDialog.Rename -> RenameDialog(
            title = dialog.title,
            onConfirm = { onEvent(LibraryEvent.RenameConfirmed(dialog.id, it)) },
            onDismiss = dismiss,
        )
        is LibraryDialog.StartOver -> ConfirmDialog(
            spec = startOverSpec(),
            onConfirm = { onEvent(LibraryEvent.ConfirmDialog) },
            onDismiss = dismiss,
        )
        is LibraryDialog.StopAndTrash -> ConfirmDialog(
            spec = stopAndTrashSpec(),
            onConfirm = { onEvent(LibraryEvent.ConfirmDialog) },
            onDismiss = dismiss,
        )
    }
}
