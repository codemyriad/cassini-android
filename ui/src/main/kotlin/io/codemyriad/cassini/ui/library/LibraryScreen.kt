// Composables are nouns in PascalCase; the numbers are the board values of docs/design/ui.md sections 6 and 8.
@file:Suppress("FunctionNaming", "MagicNumber")

package io.codemyriad.cassini.ui.library

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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.components.Banner
import io.codemyriad.cassini.ui.components.CassiniSnackbarHost
import io.codemyriad.cassini.ui.components.DayHeader
import io.codemyriad.cassini.ui.components.LibraryActionBarMetrics
import io.codemyriad.cassini.ui.components.LibraryBottomBar
import io.codemyriad.cassini.ui.components.NoteRow
import io.codemyriad.cassini.ui.components.NoteRowAction
import io.codemyriad.cassini.ui.components.NoteRowUi
import io.codemyriad.cassini.ui.components.RecordingBannerCard
import io.codemyriad.cassini.ui.components.screenContentWidth
import io.codemyriad.cassini.ui.format.Counts
import io.codemyriad.cassini.ui.format.Dates
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.theme.CassiniTheme

/**
 * The bottom bar's height on the board (40 + 64 + 24 dp), the least bottom padding of the list before the bar is
 * measured. The bar grows when the Record label wraps (large font scales, a recording), and the list then uses the
 * measured height.
 */
private val BottomBarHeight = LibraryActionBarMetrics.minimumHeight

/** The bar's top padding: the snackbar sits 24 dp above the Record button, so the bar's height less this. */
private val BarTopPadding = LibraryActionBarMetrics.topPadding

/**
 * Phase 1 Library: a native pinned search bar with a hidden All meetings heading, then a scrolling list.
 * Search filters scroll with results; snippets belong only to search. Recording and import notices remain,
 * while transcription is represented by its own row. The process-owned transfer dialogs stay in LibraryRoute.
 * Import/Record and the snackbar float above the measured list bottom inset. Empty search also handles Back.
 */
@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    modifier: Modifier = Modifier,
    tools: (@Composable () -> Unit)? = null,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    val active = searching || state.query.isNotEmpty() || state.results != null
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val close = {
        focus.clearFocus()
        keyboard?.hide()
        searching = false
        onEvent(LibraryEvent.ClearQuery)
    }
    BackHandler(enabled = active, onBack = close)
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
        Column(Modifier.fillMaxSize()) {
            LibraryPinnedHeader(state, active, onActive = { searching = true }, onClose = close, onEvent = onEvent)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("library-list"),
                state = rememberLazyListState(),
                contentPadding = PaddingValues(bottom = listBottom),
            ) {
                if (tools != null) item(key = "transfers", contentType = "transfers") { tools() }
                if (state.query.isNotBlank()) {
                    item(key = "filters", contentType = "filters") {
                        Column(Modifier.padding(horizontal = 16.dp)) { SearchFiltersBar(state.searchFilters, onEvent) }
                    }
                }
                activity(state, onEvent)
                if (!state.loading) {
                    val results = state.results
                    if (results != null) results(results, onEvent) else sections(state.sections, onEvent)
                }
            }
        }
        LibraryActions(state, onEvent) { barPx = it }
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

/**
 * Existing recording feedback and import/problem banners; no separate transcription progress or privacy card.
 * The first item sits 14dp below the search/filter region, the next ones 8dp below the one before.
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
    item(key = "results-count", contentType = "results-count") {
        Text(
            Counts.plural(R.plurals.ui_search_meetings, results.size),
            Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() },
            style = CassiniTheme.type.caption,
            color = CassiniTheme.colors.text2,
        )
    }
    rows(results, day = null, onEvent)
}

private fun LazyListScope.rows(rows: List<NoteRowUi>, day: DayLabel?, onEvent: (LibraryEvent) -> Unit) {
    itemsIndexed(rows, key = { _, row -> "row-${row.id}" }, contentType = { _, _ -> "note" }) { index, row ->
        NoteRow(
            row = row,
            day = row.day ?: day ?: DayLabel.Today,
            onAction = { onEvent(row.event(it)) },
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = if (index == 0) 0.dp else 12.dp),
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

@Composable
private fun LibraryPinnedHeader(
    state: LibraryUiState,
    active: Boolean,
    onActive: () -> Unit,
    onClose: () -> Unit,
    onEvent: (LibraryEvent) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val heading = stringResource(R.string.ui_all_meetings)
    Column(
        Modifier.fillMaxWidth()
            .background(CassiniTheme.colors.bg)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics {
                heading()
                contentDescription = heading
            },
    ) {
        LibrarySearchBar(
            query = state.query,
            active = active,
            onActive = onActive,
            onQuery = { onEvent(LibraryEvent.Query(it)) },
            onClear = { onEvent(LibraryEvent.ClearQuery) },
            onClose = onClose,
            onSearch = { keyboard?.hide() },
            onTrash = { onEvent(LibraryEvent.OpenTrash) },
            onSettings = { onEvent(LibraryEvent.OpenSettings) },
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.LibraryActions(
    state: LibraryUiState,
    onEvent: (LibraryEvent) -> Unit,
    onHeight: (Int) -> Unit,
) {
    LibraryBottomBar(
        onImport = { onEvent(LibraryEvent.Import) },
        onRecord = {
            onEvent(if (state.recording != null) LibraryEvent.ReturnToRecording else LibraryEvent.Record)
        },
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .onSizeChanged { onHeight(it.height) },
        recordingElapsedMs = state.recording?.elapsedMs,
    )
}
