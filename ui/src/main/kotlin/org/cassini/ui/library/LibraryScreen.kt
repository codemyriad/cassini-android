// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md sections 6 and 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package org.cassini.ui.library

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import org.cassini.ui.R
import org.cassini.ui.components.Banner
import org.cassini.ui.components.CassiniSnackbarHost
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
import org.cassini.ui.components.screenContentWidth
import org.cassini.ui.format.Counts
import org.cassini.ui.format.Dates
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.Progress
import org.cassini.ui.theme.CassiniTheme

/**
 * The bottom bar's height on the board (40 + 64 + 24 dp), the least bottom padding of the list before the bar is
 * measured. The bar grows when the Record label wraps (large font scales, a recording), and the list then uses the
 * measured height.
 */
private val BottomBarHeight = 128.dp

/** The bar's top padding: the snackbar sits 24 dp above the Record button, so the bar's height less this. */
private val BarTopPadding = 40.dp

/**
 * The Library (board 00, features.md section 1; ui.md section 8). A `Box` on bg holds one `LazyColumn`: the header,
 * the search field (padding 16 horizontal, top 4) with the privacy line, the activity area (recording card, progress
 * card, banners), then either the day sections (list padding top 6) or, while searching, the flat results. The
 * bottom bar with Import and Record lies over the list, the snackbar 24 dp above Record; the list's bottom padding
 * and the snackbar follow the bar's measured height. The sheets and the dialogs open in their own windows from
 * [LibraryUiState.sheet] and [LibraryUiState.dialog].
 *
 * While a search is open (a query or results), system back clears it ([LibraryEvent.ClearQuery]) before it leaves
 * the app (architecture.md section 6).
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
    BackHandler(enabled = state.query.isNotEmpty() || state.results != null) { onEvent(LibraryEvent.ClearQuery) }
    val density = LocalDensity.current
    var barPx by remember { mutableIntStateOf(0) }
    val bar: Dp = max(
        with(density) { barPx.toDp() },
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + BottomBarHeight,
    )
    // The keyboard covers the bottom bar: the results end above whichever is taller (ui.md section 9).
    val listBottom = max(bar, WindowInsets.ime.asPaddingValues().calculateBottomPadding())
    Box(
        modifier
            .fillMaxSize()
            .background(CassiniTheme.colors.bg)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .screenContentWidth(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberLazyListState(),
            contentPadding = PaddingValues(bottom = listBottom),
        ) {
            item(key = "header", contentType = "header") {
                LibraryTopBar(
                    onTrash = { onEvent(LibraryEvent.OpenTrash) },
                    onSettings = { onEvent(LibraryEvent.OpenSettings) },
                )
            }
            item(key = "search", contentType = "search") { SearchBlock(state.query, onEvent) }
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
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { barPx = it.height },
            recordingElapsedMs = state.recording?.elapsedMs,
        )
        CassiniSnackbarHost(
            spec = state.snackbar,
            onTimeout = { onEvent(LibraryEvent.SnackbarTimeout) },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bar - BarTopPadding),
            onUndo = { onEvent(LibraryEvent.Undo) },
        )
    }
    state.sheet?.let { LibrarySheetWindow(it, onEvent) }
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
        item(key = "recording", contentType = "recording") {
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
        item(key = "progress", contentType = "progress") { ProgressCard(progress, onEvent, modifier) }
    }
    state.banners.forEachIndexed { index, spec ->
        val modifier = top()
        // Ids are unique in a list (BannerSpec); the index only keeps a duplicate from crashing the list.
        item(key = "banner-$index-${spec.id}", contentType = "banner") {
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
    item(key = "list-top", contentType = "list-top") { Spacer(Modifier.height(6.dp)) }
    sections.forEachIndexed { index, section ->
        item(key = "day-${section.label}", contentType = "day") {
            DayHeader(Dates.dayLabel(section.label), first = index == 0)
        }
        rows(section.rows, section.label, onEvent)
    }
}

/** The flat search results, newest first, each with its own day in the meta line. */
private fun LazyListScope.results(results: List<NoteRowUi>, onEvent: (LibraryEvent) -> Unit) {
    item(key = "list-top", contentType = "list-top") { Spacer(Modifier.height(6.dp)) }
    rows(results, day = null, onEvent)
}

private fun LazyListScope.rows(rows: List<NoteRowUi>, day: DayLabel?, onEvent: (LibraryEvent) -> Unit) {
    itemsIndexed(rows, key = { _, row -> "row-${row.id}" }, contentType = { _, _ -> "note" }) { index, row ->
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
