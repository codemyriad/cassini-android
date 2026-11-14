package io.codemyriad.cassini.library

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.codemyriad.cassini.core.audio.ImportTypes
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.core.library.SearchDate
import io.codemyriad.cassini.core.library.SearchFilters
import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.data.db.NoteDetails
import io.codemyriad.cassini.data.db.SearchRow
import io.codemyriad.cassini.engine.job.JobPhase
import io.codemyriad.cassini.nav.ModelDownloadKey
import io.codemyriad.cassini.nav.NoteKey
import io.codemyriad.cassini.nav.NoteStart
import io.codemyriad.cassini.notices.LibraryFacts
import io.codemyriad.cassini.notices.NoticeFacts
import io.codemyriad.cassini.notices.Notices
import io.codemyriad.cassini.processing.ProcessingState
import io.codemyriad.cassini.processing.TranscribeOutcome
import io.codemyriad.cassini.recording.RecordingState
import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.banner.BannerGroup
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.components.SnackbarKind
import io.codemyriad.cassini.ui.components.SnackbarSpec
import io.codemyriad.cassini.ui.library.LibraryDialog
import io.codemyriad.cassini.ui.library.LibraryEvent
import io.codemyriad.cassini.ui.library.LibraryFilterUi
import io.codemyriad.cassini.ui.library.LibrarySheet
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.library.ProgressLine
import io.codemyriad.cassini.ui.library.SearchDateUi
import io.codemyriad.cassini.ui.library.SearchFiltersUi
import io.codemyriad.cassini.ui.note.NoteAction
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** What the Library route does for the ViewModel: navigation and the system pickers and settings. */
sealed interface LibraryEffect {
    data class OpenNote(val key: NoteKey) : LibraryEffect

    /** The Recording screen: it starts a recording, or returns to the one in progress. */
    data object OpenRecording : LibraryEffect

    data object OpenTrash : LibraryEffect

    data object OpenSettings : LibraryEffect

    data class OpenModelDownload(val key: ModelDownloadKey) : LibraryEffect

    /** "Import audio": the system picker (`ACTION_OPEN_DOCUMENT`) with [LibraryViewModel.IMPORT_TYPES]. */
    data object PickAudio : LibraryEffect

    /** The app's notification settings ("Turn on"). */
    data object OpenNotificationSettings : LibraryEffect
}

/**
 * The Library (board 00, features.md section 1; ui.md section 7). One [state] built from the catalogue (`library()`,
 * or `search()` 150 ms after the last keystroke), the coordinator (status chips, the running row, the transcription
 * banner), the recorder (the recording card and the Record button) and [NoticeFacts] (the banners).
 *
 * The query, the open sheet and the open dialog are in [saved], so they survive process death. Navigation and the
 * system pickers go to the route as [effects].
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Suppress("TooManyFunctions") // one handler per Library event of ui.md section 7
class LibraryViewModel(
    private val saved: SavedStateHandle,
    private val ports: LibraryPorts,
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Where [state] and changed Library rows are mapped. */
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    private val effectChannel = Channel<LibraryEffect>(Channel.BUFFERED)

    /** One-shot navigation and picker requests; the route collects them. */
    val effects: Flow<LibraryEffect> = effectChannel.receiveAsFlow()

    private val query: StateFlow<String> = saved.getStateFlow(KEY_QUERY, "")
    private val sheetId: StateFlow<String?> = saved.getStateFlow(KEY_SHEET, null)
    private val dialogKey: StateFlow<String?> = saved.getStateFlow(KEY_DIALOG, null)
    private val dialogTitle: StateFlow<String?> = saved.getStateFlow(KEY_DIALOG_TITLE, null)

    /** Built from StateFlows only: [state] runs on [compute], and SavedStateHandle.get is main-thread only. */
    private val dialog: Flow<LibraryDialog?> = combine(dialogKey, dialogTitle) { key, title ->
        key?.let { dialogOf(it, title) }
    }
    private val snackbar = MutableStateFlow<SnackbarSpec?>(null)
    private var trashed: String? = null
    private var snackbarCount = 0L
    private val listingCache = LibraryListing()
    private val filters = MutableStateFlow(SearchFiltersUi())

    /** The Library's `All` / `Starred` choice at rest (L-34), remembered per device; `All` until it is read. */
    private val starredOnly: StateFlow<Boolean> = ports.libraryStarredOnly
        .catch { error ->
            Log.w(TAG, "The Library filter cannot be read", error)
            emit(false)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val calendarRefresh = MutableStateFlow(0L)
    private val controls = combine(
        filters,
        ports.namedSpeakers().distinctUntilChanged().catch { emit(emptyList()) },
    ) { filters, names ->
        filters.copy(speakers = names.toImmutableList())
    }
    private val calendar = combine(ports.calendarZone, calendarRefresh) { zone, _ ->
        Instant.now(clock).atZone(zone).toLocalDate() to zone
    }.distinctUntilChanged()
        .flowOn(compute)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)
    private val environment = combine(controls, calendar) { filters, day ->
        SearchEnvironment(filters, day.first, day.second)
    }.flowOn(compute).shareIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), replay = 1)

    private data class SearchEnvironment(val filters: SearchFiltersUi, val today: LocalDate, val zone: ZoneId)

    /** The catalogue cannot be read (the database did not open): the Library says so, it is not empty. */
    private val unavailable = MutableStateFlow(false)

    private val rows: Flow<List<LibraryRow>?> = ports.library()
        .map<List<LibraryRow>, List<LibraryRow>?> { it }
        .onStart { emit(null) }
        .catch { error ->
            Log.e(TAG, "The library cannot be read", error)
            unavailable.value = true
            emit(emptyList())
        }

    /** The searched query and its results; null results while not searching. */
    private val searchedText = query.map { it.trim() }.distinctUntilChanged()
        .debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS }

    // Menu inventory is display state, never a search/cache lifetime input.
    private val domainFilters = filters.map { it.domain() }.distinctUntilChanged()
    private val search: Flow<Pair<String, List<SearchRow>?>> = combine(
        searchedText,
        domainFilters,
        calendar,
    ) { text, filters, day ->
        SearchRequest(text, filters, day.first, day.second)
    }.distinctUntilChanged().flatMapLatest { request ->
        val text = request.text
        if (text.isEmpty()) {
            flowOf(text to null)
        } else {
            ports.search(text, request.filters, request.zone, request.today)
                .map<List<SearchRow>, Pair<String, List<SearchRow>?>> { text to it }
                .onStart { emit(text to null) }
                .catch { error ->
                    Log.w(TAG, "Search failed", error)
                    emit(text to emptyList())
                }
        }
    }

    private data class SearchRequest(val text: String, val filters: SearchFilters, val today: LocalDate, val zone: ZoneId)

    private fun SearchFiltersUi.domain(): SearchFilters = SearchFilters(
        speaker,
        when (date) {
            SearchDateUi.ANY -> SearchDate.ANY
            SearchDateUi.TODAY -> SearchDate.TODAY
            SearchDateUi.WEEK -> SearchDate.WEEK
            SearchDateUi.MONTH -> SearchDate.MONTH
            SearchDateUi.YEAR -> SearchDate.YEAR
        },
        recordedByMe,
        starred,
    )

    private val sheet: Flow<NoteDetails?> = sheetId.flatMapLatest { id ->
        if (id == null) flowOf(null) else ports.details(id).catch { emit(null) }
    }

    private val live: Flow<Live> = combine(
        ports.processing,
        ports.recording,
        ports.notices,
        ports.voiceModelReady.onStart { emit(false) }.distinctUntilChanged(),
        ports.backupReach.map<BackupReach, BackupReach?> { it }.onStart { emit(null) }.catch { emit(null) },
    ) { processing, rec, facts, voices, reach -> Live(processing, rec, facts, voices, reach) }

    val state: StateFlow<LibraryUiState> = combine(
        combine(
            combine(rows, search, query, unavailable, environment) { r, s, q, u, e -> Listing(r, s, q, u, e) },
            starredOnly,
        ) { listing, starred -> listing.copy(starredOnly = starred) },
        live,
        sheet,
        dialog,
        snackbar,
    ) { listing, live, sheet, dialog, snackbar ->
        build(listing, live, sheet, dialog, snackbar)
    }.flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryUiState(loading = true))

    init {
        // After process death the query survives but the filters do not: an open Search takes the Library's choice.
        if (query.value.isNotBlank()) {
            viewModelScope.launch {
                val starred = ports.libraryStarredOnly.catch { emit(false) }.first()
                if (filters.value == SearchFiltersUi()) filters.value = SearchFiltersUi(starred = starred)
            }
        }
        // Move to trash on the Note screen lands here with the Undo snackbar (architecture section 6).
        viewModelScope.launch {
            ports.trashed.filterNotNull().collect { note ->
                ports.takeTrashed(note)
                showTrashed(note.noteId, note.title)
            }
        }
    }

    private data class Listing(
        val rows: List<LibraryRow>?,
        val search: Pair<String, List<SearchRow>?>,
        val query: String,
        val unavailable: Boolean,
        val environment: SearchEnvironment,
        val starredOnly: Boolean = false,
    )

    private data class Live(
        val processing: ProcessingState,
        val recording: RecordingState,
        val facts: NoticeFacts,
        val voiceModelReady: Boolean,
        /** Null until the D97 choices are read: no privacy claim is drawn before then. */
        val backupReach: BackupReach?,
    )

    private fun build(
        listing: Listing,
        live: Live,
        details: NoteDetails?,
        dialog: LibraryDialog?,
        snackbar: SnackbarSpec?,
    ): LibraryUiState {
        val now = Instant.now(clock)
        val zone = listing.environment.zone
        val recording = live.recording
        val progress = LibraryMapping.progress(live.processing)
        val context = RowContext(
            activeNoteId = activeNoteId(recording),
            recordingElapsedMs = (recording as? RecordingState.Active)?.elapsedMs ?: 0,
            runningNoteId = live.processing.running?.noteId,
            transcriptionMinutes = LibraryMapping.badgeMinutes((progress?.line as? ProgressLine.TimeLeft)?.timeLeft),
        )
        val rows = listing.rows.orEmpty()
        val (searched, found) = listing.search
        val searching = listing.query.isNotBlank()
        val mapped = listingCache.map(rows, found, searching, context, now, zone, listing.starredOnly)
        val facts = LibraryFacts(
            loading = listing.rows == null,
            liveNotes = rows.size,
            query = if (searching) searched else "",
            results = mapped.results?.size?.takeIf { found != null },
            audioOnly = mapped.audioOnly,
            unavailable = listing.unavailable,
        )
        val notices = Notices.library(live.facts, facts)
        val empty = notices.any { it.kind == BannerKind.LIB_EMPTY }
        return LibraryUiState(
            loading = listing.rows == null,
            query = listing.query,
            searchFilters = listing.environment.filters,
            recording = LibraryMapping.recordingPill(recording),
            progress = progress,
            // The first-run hero replaces the empty-Library banner (L-27); until the choices are read, neither shows.
            banners = if (empty) notices.filterNot { it.kind == BannerKind.LIB_EMPTY }.toImmutableList() else notices,
            hero = live.backupReach?.takeIf { empty }?.let(LibraryMapping::hero),
            backupCard = live.backupReach?.card == true && !searching,
            libraryFilter = if (listing.starredOnly) LibraryFilterUi.STARRED else LibraryFilterUi.ALL,
            libraryFilters = !searching && rows.isNotEmpty(),
            starredEmpty = !searching && listing.starredOnly && rows.isNotEmpty() && !mapped.anyStarred,
            imports = LibraryMapping.imports(live.facts.imports),
            sections = mapped.sections,
            results = mapped.results,
            snackbar = snackbar,
            sheet = details?.let {
                val stopBusy = live.processing.notes[it.note.id]?.phase is JobPhase.Stopping
                LibrarySheet.Actions(
                    LibraryMapping.actions(it, context.activeNoteId, now, zone, live.voiceModelReady, stopBusy),
                )
            },
            dialog = dialog,
        )
    }

    // Events. ----------------------------------------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod") // one branch per event of ui.md section 7
    fun onEvent(event: LibraryEvent) {
        when (event) {
            is LibraryEvent.Query -> {
                when {
                    event.text.isBlank() -> filters.value = SearchFiltersUi()
                    // L-35: Search opens with the Library's Starred choice; the other filters start cleared.
                    query.value.isBlank() -> filters.value = SearchFiltersUi(starred = starredOnly.value)
                }
                saved[KEY_QUERY] = event.text
            }
            LibraryEvent.ClearQuery -> {
                filters.value = SearchFiltersUi()
                saved[KEY_QUERY] = ""
            }
            is LibraryEvent.FilterSpeaker -> filters.value = filters.value.copy(speaker = event.name)
            is LibraryEvent.FilterDate -> filters.value = filters.value.copy(date = event.date)
            LibraryEvent.ToggleRecordedByMe -> filters.value = filters.value.copy(recordedByMe = !filters.value.recordedByMe)
            LibraryEvent.ToggleSearchStarred -> filters.value = filters.value.copy(starred = !filters.value.starred)
            // No optimistic state: the row follows the stored choice (BUG-CLASSES).
            is LibraryEvent.SelectLibraryFilter -> launch {
                ports.setLibraryStarredOnly(event.filter == LibraryFilterUi.STARRED)
            }
            is LibraryEvent.SetStarred -> launch { ports.setStarred(event.id, event.starred) }
            is LibraryEvent.OpenNote -> openNote(event.id)
            is LibraryEvent.LongPress -> saved[KEY_SHEET] = event.id
            LibraryEvent.Record, LibraryEvent.ReturnToRecording -> emit(LibraryEffect.OpenRecording)
            LibraryEvent.Import -> emit(LibraryEffect.PickAudio)
            LibraryEvent.OpenTrash -> emit(LibraryEffect.OpenTrash)
            LibraryEvent.OpenSettings -> emit(LibraryEffect.OpenSettings)
            LibraryEvent.OpenProgress -> openProgress()
            is LibraryEvent.Resume -> launch { resume(event.id) }
            is LibraryEvent.TryAgain -> launch { resume(event.id) }
            is LibraryEvent.Rename -> launch { openRename(event.id) }
            is LibraryEvent.Trash -> launch { trash(event.id) }
            is LibraryEvent.BannerAction -> bannerAction(event.id, event.action)
            is LibraryEvent.DismissBanner -> kindOf(event.id)?.let(ports::dismissNotice)
            is LibraryEvent.DismissImport -> ports.dismissImport(event.id)
            LibraryEvent.Undo -> undo()
            // A failed write keeps the card: the choices did not change (BUG-CLASSES: no optimistic state).
            LibraryEvent.KeepOnPhoneOnly -> launch { ports.keepOnPhoneOnly() }
            LibraryEvent.DismissBackupCard -> launch { ports.dismissBackupCard() }
            LibraryEvent.SnackbarTimeout -> snackbar.value = null
            is LibraryEvent.SheetAction -> sheetId.value?.let { id -> launch { sheetAction(id, event.action) } }
            LibraryEvent.DismissSheet -> closeSheet()
            is LibraryEvent.RenameConfirmed -> {
                saved[KEY_DIALOG] = null
                launch { ports.rename(event.id, event.title) }
            }
            LibraryEvent.ConfirmDialog -> confirmDialog()
            LibraryEvent.DismissDialog -> saved[KEY_DIALOG] = null
        }
    }

    /** The result of the multi-document picker: empty when the user cancelled it. */
    fun onPicked(uris: List<String>) {
        if (uris.isNotEmpty()) ports.import(uris)
    }

    /** The screen is visible again (ON_RESUME): the notification permission may have changed in Settings. */
    fun onResume() {
        calendarRefresh.value++
        ports.refreshNotices()
    }

    /** Opens note [id], with the search query when searching (the in-note search starts with it). */
    private fun openNote(id: String) {
        emit(LibraryEffect.OpenNote(NoteKey(id, query.value.trim().ifEmpty { null })))
    }

    private fun openProgress() = launch {
        val running = ports.processing.first().running?.noteId
        emit(if (running != null) LibraryEffect.OpenNote(NoteKey(running)) else LibraryEffect.OpenSettings)
    }

    /** Resume on a paused row and Try again on a failed one: the job's own kind goes to the back of the queue. */
    private suspend fun resume(id: String) {
        val details = ports.details(id).first()
        val job = details?.job
        // A final failure (too long, or a refused document) repeats at each try: the note tells why instead.
        if (details != null && job != null && Notices.finalFailure(job, LibraryMapping.transcribeAgainRefused(details))) {
            emit(LibraryEffect.OpenNote(NoteKey(id)))
            return
        }
        transcribe(id, job?.kind ?: JobKind.TRANSCRIBE)
    }

    private suspend fun transcribe(id: String, kind: JobKind) {
        if (ports.transcribe(id, kind) == TranscribeOutcome.NeedsConsent) {
            emit(LibraryEffect.OpenModelDownload(ModelDownloadKey(id)))
        }
    }

    private suspend fun openRename(id: String) {
        val title = ports.details(id).first()?.note?.title ?: return
        saved[KEY_DIALOG_TITLE] = title
        saved[KEY_DIALOG] = dialogKey(DIALOG_RENAME, id)
    }

    /** Move to trash: a running note asks first (Stop and move to trash), any other goes at once. */
    private suspend fun trash(id: String) {
        val details = ports.details(id).first() ?: return
        if (details.job?.state == JobState.RUNNING) {
            saved[KEY_DIALOG] = dialogKey(DIALOG_STOP_AND_TRASH, id)
            return
        }
        moveToTrash(id, details.note.title)
    }

    private suspend fun moveToTrash(id: String, title: String) {
        if (ports.moveToTrash(id)) showTrashed(id, title)
    }

    private fun showTrashed(id: String, title: String) {
        trashed = id
        snackbar.value = SnackbarSpec(
            kind = SnackbarKind.MOVED_TO_TRASH,
            text = title,
            undo = true,
            durationMs = ports.snackbarTimeoutMs(),
            id = ++snackbarCount,
        )
    }

    private fun undo() {
        val id = trashed ?: return
        trashed = null
        snackbar.value = null
        launch { ports.restore(id) }
    }

    @Suppress("CyclomaticComplexMethod") // one branch per row of the Note actions sheet
    private suspend fun sheetAction(id: String, action: NoteAction) {
        val details = ports.details(id).first() ?: return closeSheet()
        when (action) {
            NoteAction.RENAME -> {
                closeSheet()
                saved[KEY_DIALOG_TITLE] = details.note.title
                saved[KEY_DIALOG] = dialogKey(DIALOG_RENAME, id)
            }
            NoteAction.STAR, NoteAction.UNSTAR -> {
                closeSheet()
                ports.setStarred(id, action == NoteAction.STAR)
            }
            NoteAction.TRANSCRIBE -> {
                closeSheet()
                transcribe(id, JobKind.TRANSCRIBE)
            }
            NoteAction.TRANSCRIBE_AGAIN -> {
                closeSheet()
                if (LibraryMapping.transcribeAgainRefused(details)) {
                    Log.w(TAG, "Transcribe again is not possible for this document")
                } else if (LibraryMapping.needsFreshConfirmation(details)) {
                    saved[KEY_DIALOG] = dialogKey(DIALOG_TRANSCRIBE_FRESH, id)
                } else {
                    transcribe(id, JobKind.TRANSCRIBE_AGAIN)
                }
            }
            NoteAction.STOP -> {
                closeSheet()
                if (ports.processing.first().notes[id]?.phase !is JobPhase.Stopping) ports.stop(id)
            }
            NoteAction.REMOVE_FROM_QUEUE -> {
                closeSheet()
                ports.dequeue(id)
            }
            NoteAction.RESUME -> {
                closeSheet()
                transcribe(id, details.job?.kind ?: JobKind.TRANSCRIBE)
            }
            NoteAction.START_OVER -> {
                closeSheet()
                saved[KEY_DIALOG] = dialogKey(DIALOG_START_OVER, id)
            }
            NoteAction.MOVE_TO_TRASH -> {
                closeSheet()
                trash(id)
            }
            NoteAction.SAVE_DOCUMENT, NoteAction.SAVE_TEXT, NoteAction.SAVE_MARKDOWN, NoteAction.SAVE_SRT,
            NoteAction.SAVE_VTT, NoteAction.SHARE_DOCUMENT, NoteAction.SHARE_TEXT, NoteAction.SHARE_MARKDOWN,
            NoteAction.SHARE_SRT, NoteAction.SHARE_VTT, NoteAction.DOCUMENT_INFO,
            -> {
                // The exports, the shares and Document info are the Note screen's: it opens and starts the row.
                closeSheet()
                emit(LibraryEffect.OpenNote(NoteKey(id, start = start(action))))
            }
        }
    }

    private fun start(action: NoteAction): NoteStart? = when (action) {
        NoteAction.SAVE_DOCUMENT -> NoteStart.SAVE_DOCUMENT
        NoteAction.SAVE_TEXT -> NoteStart.SAVE_TEXT
        NoteAction.SAVE_MARKDOWN -> NoteStart.SAVE_MARKDOWN
        NoteAction.SAVE_SRT -> NoteStart.SAVE_SRT
        NoteAction.SAVE_VTT -> NoteStart.SAVE_VTT
        NoteAction.SHARE_DOCUMENT -> NoteStart.SHARE_DOCUMENT
        NoteAction.SHARE_TEXT -> NoteStart.SHARE_TEXT
        NoteAction.SHARE_MARKDOWN -> NoteStart.SHARE_MARKDOWN
        NoteAction.SHARE_SRT -> NoteStart.SHARE_SRT
        NoteAction.SHARE_VTT -> NoteStart.SHARE_VTT
        NoteAction.DOCUMENT_INFO -> NoteStart.DOCUMENT_INFO
        else -> null
    }

    private fun confirmDialog() {
        val key = dialogKey.value ?: return
        saved[KEY_DIALOG] = null
        val (kind, id) = key.split(DIALOG_SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: return
        launch {
            when (kind) {
                DIALOG_START_OVER -> if (ports.startOver(id) == TranscribeOutcome.NeedsConsent) {
                    emit(LibraryEffect.OpenModelDownload(ModelDownloadKey(id)))
                }
                DIALOG_TRANSCRIBE_FRESH -> transcribe(id, JobKind.TRANSCRIBE_AGAIN)
                DIALOG_STOP_AND_TRASH -> {
                    val title = ports.details(id).first()?.note?.title.orEmpty()
                    moveToTrash(id, title)
                }
            }
        }
    }

    @Suppress("CyclomaticComplexMethod") // One dispatch branch per catalogued Library banner action.
    private fun bannerAction(id: String, action: BannerAction) {
        val kind = kindOf(id) ?: return
        when (action) {
            BannerAction.NOT_NOW -> ports.dismissNotice(kind)
            BannerAction.CONTINUE -> launch { ports.continueQueue() }
            BannerAction.OPEN -> launch {
                val recovered = ports.notices.first().recovered ?: return@launch
                ports.dismissNotice(BannerKind.RECOVERED)
                emit(LibraryEffect.OpenNote(NoteKey(recovered.latestNoteId)))
            }
            BannerAction.DOWNLOAD -> {
                val key = ModelDownloadKey(parakeetOnly = kind == BannerKind.PARAKEET_RECOMMENDED)
                emit(LibraryEffect.OpenModelDownload(key))
            }
            BannerAction.RETRY, BannerAction.DOWNLOAD_AGAIN -> {
                ports.dismissNotice(kind)
                ports.downloadModels()
            }
            BannerAction.TRY_AGAIN -> {
                ports.dismissNotice(kind)
                if (kind.group == BannerGroup.DOWNLOAD) ports.downloadModels() else emit(LibraryEffect.PickAudio)
            }
            BannerAction.OPEN_SETTINGS -> {
                ports.dismissNotice(kind)
                emit(LibraryEffect.OpenSettings)
            }
            BannerAction.TURN_ON -> emit(LibraryEffect.OpenNotificationSettings)
            BannerAction.CHOOSE_ANOTHER, BannerAction.IMPORT -> {
                ports.dismissNotice(kind)
                emit(LibraryEffect.PickAudio)
            }
            BannerAction.RECORD -> emit(LibraryEffect.OpenRecording)
            else -> Log.w(TAG, "No Library handler for $action on $kind")
        }
    }

    private fun closeSheet() {
        saved[KEY_SHEET] = null
    }

    private fun dialogOf(key: String, title: String?): LibraryDialog? {
        val (kind, id) = key.split(DIALOG_SEPARATOR, limit = 2).takeIf { it.size == 2 } ?: return null
        return when (kind) {
            DIALOG_RENAME -> LibraryDialog.Rename(id, title.orEmpty())
            DIALOG_START_OVER -> LibraryDialog.StartOver(id)
            DIALOG_TRANSCRIBE_FRESH -> LibraryDialog.TranscribeFresh(id)
            DIALOG_STOP_AND_TRASH -> LibraryDialog.StopAndTrash(id)
            else -> null
        }
    }

    private fun emit(effect: LibraryEffect) {
        effectChannel.trySend(effect)
    }

    /** Runs [block] on the ViewModel scope; a failure is logged, never thrown at the screen. */
    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Log.w(TAG, "A Library action failed", error)
            }
        }
    }

    private fun kindOf(id: String): BannerKind? = BannerKind.entries.firstOrNull { it.name == id }

    private fun activeNoteId(state: RecordingState): String? = when (state) {
        is RecordingState.Active -> state.noteId
        is RecordingState.Finishing -> state.noteId
        else -> null
    }

    private fun dialogKey(kind: String, id: String) = "$kind$DIALOG_SEPARATOR$id"

    companion object {
        /**
         * The picker's types (features.md section 1, item 6): the decided `androidMimeTypes` of the shared
         * import-types contract (L-25, [ImportTypes]) and Cassini's ZIP archives (D96).
         */
        val IMPORT_TYPES: Array<String> =
            (ImportTypes.ANDROID_MIME_TYPES + listOf("application/zip", "application/x-zip-compressed")).toTypedArray()

        const val SEARCH_DEBOUNCE_MS = 150L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val TAG = "CassiniLibrary"

        private const val KEY_QUERY = "query"
        private const val KEY_SHEET = "sheet"
        private const val KEY_DIALOG = "dialog"
        private const val KEY_DIALOG_TITLE = "dialogTitle"
        private const val DIALOG_SEPARATOR = ":"
        private const val DIALOG_RENAME = "rename"
        private const val DIALOG_START_OVER = "startOver"
        private const val DIALOG_TRANSCRIBE_FRESH = "transcribeFresh"
        private const val DIALOG_STOP_AND_TRASH = "stopAndTrash"
    }
}
