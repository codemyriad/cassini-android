package org.cassini.android.note

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.cassini.android.library.LibraryMapping
import org.cassini.android.nav.ModelDownloadKey
import org.cassini.android.nav.NoteKey
import org.cassini.android.nav.NoteStart
import org.cassini.android.notices.NoticeFacts
import org.cassini.android.notices.Notices
import org.cassini.android.notices.TrashedNote
import org.cassini.android.playback.PlaybackState
import org.cassini.android.processing.NoteJobState
import org.cassini.android.processing.TranscribeOutcome
import org.cassini.android.recording.RecordingState
import org.cassini.core.checkpoint.CheckpointNames
import org.cassini.core.format.Word
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.speakers.Match
import org.cassini.core.speakers.SampleWindow
import org.cassini.core.speakers.SpeakerPrint
import org.cassini.core.speakers.SpeakerStats
import org.cassini.data.db.NoteDetails
import org.cassini.data.db.WordEntity
import org.cassini.data.notes.EditOutcome
import org.cassini.data.notes.ExportOutcome
import org.cassini.data.voices.SavedVoices
import org.cassini.engine.job.DraftState
import org.cassini.engine.job.JobFailure
import org.cassini.engine.job.JobPhase
import org.cassini.ui.banner.BannerAction
import org.cassini.ui.banner.BannerArgs
import org.cassini.ui.banner.BannerKind
import org.cassini.ui.banner.BannerSpec
import org.cassini.ui.components.SnackbarKind
import org.cassini.ui.components.SnackbarSpec
import org.cassini.ui.components.SuggestionUi
import org.cassini.ui.components.WordRef
import org.cassini.ui.note.ExportKind
import org.cassini.ui.note.InfoUi
import org.cassini.ui.note.MergeEvent
import org.cassini.ui.note.MergeTargetUi
import org.cassini.ui.note.MergeVersionsUi
import org.cassini.ui.note.NameSheetEvent
import org.cassini.ui.note.NoteAction
import org.cassini.ui.note.NoteDialog
import org.cassini.ui.note.NoteEvent
import org.cassini.ui.note.NoteMode
import org.cassini.ui.note.NoteSheet
import org.cassini.ui.note.NoteUiState
import org.cassini.ui.note.PlaybackUi
import org.cassini.ui.note.ReadOnlyReason
import org.cassini.ui.note.TranscriptMatch
import org.cassini.ui.note.TranscriptSearchUi
import org.cassini.ui.note.TrustUi
import org.cassini.ui.theme.SpeakerTone
import java.time.Clock
import java.time.Instant

/** What the Note route does for the ViewModel: navigation, the system pickers and the permission request. */
sealed interface NoteEffect {
    /** Back: pop the Note screen. */
    data object Close : NoteEffect

    /** After Move to trash: back to the Library, which shows the Undo snackbar. */
    data object CloseToLibrary : NoteEffect

    data class OpenModelDownload(val key: ModelDownloadKey) : NoteEffect

    /** "Save a copy": `ACTION_CREATE_DOCUMENT` with [mime] and the suggested [name]; the Uri goes to `onExportTarget`. */
    data class CreateFile(val mime: String, val name: String) : NoteEffect

    /** POST_NOTIFICATIONS, once, before the first Transcribe (API 33+). A denial blocks nothing. */
    data object RequestNotifications : NoteEffect

    data object OpenNotificationSettings : NoteEffect
}

/**
 * The Note screen (boards 02 and 03, features.md sections 3, 4 and 6; ui.md section 7). One [state] built from the
 * catalogue row of the note (`NoteRepository.note`), the Room word cache of the selected variant, the voice files,
 * the coordinator (mode, progress card, draft) and the player.
 *
 * FAST NOTE OPENING: the screen renders from the cache rows (title, chips, speakers, words); `refreshIfStale` runs in
 * the background when the screen opens and re-derives the rows only when the document's signature changed. The first
 * page of words loads before the rest, and turns, search and the word index are built on [compute], never on the main
 * thread. The position ticks of the player only search the word index.
 *
 * While a job row exists for a document note, relabel, merge and Versions are refused with
 * [NoteDialog.ReadOnly] (architecture 3.1); draft naming goes to `coordinator.draft` while naming is open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
// One handler per Note event of ui.md section 7; the handlers start with guard clauses for a row that is gone.
@Suppress("TooManyFunctions", "LargeClass", "ReturnCount")
class NoteViewModel(
    private val saved: SavedStateHandle,
    key: NoteKey,
    private val ports: NotePorts,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val compute: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {
    val noteId: String = key.noteId

    private val effectChannel = Channel<NoteEffect>(Channel.BUFFERED)

    /** One-shot navigation, picker and permission requests; the route collects them. */
    val effects: Flow<NoteEffect> = effectChannel.receiveAsFlow()

    private val local = MutableStateFlow(
        Local(search = searchAtOpen(key.query), sheet = savedSheet(), dialog = savedDialog()),
    )
    private val printsVersion = MutableStateFlow(0)
    private val visible = MutableStateFlow(false)
    private var snackbarCount = 0L
    private var closing = false

    // Sources. -------------------------------------------------------------------------------------------------------

    /** null until the first row arrives; then the row ([Loaded.details] null when the note is gone). */
    private val details: StateFlow<Loaded?> = ports.note(noteId)
        .map { Loaded(it) }
        .catch { error ->
            Log.e(TAG, "The note cannot be read", error)
            emit(Loaded(null))
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val job: Flow<NoteJobState?> = ports.processing.map { it.notes[noteId] }.distinctUntilChanged()

    private val people: StateFlow<SavedVoices> = ports.people()
        .catch { error ->
            Log.w(TAG, "Saved voices cannot be read", error)
            emit(SavedVoices(emptyList(), null))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SavedVoices(emptyList(), null))

    /** The sealed transcript of the selected variant, from the word cache: the first page first, then all. */
    private val sealedWords: Flow<List<Word>?> = details
        .map { loaded -> loaded?.details?.let(::wordsKey) }
        .distinctUntilChanged()
        .flatMapLatest { key ->
            if (key == null) {
                flowOf(null)
            } else {
                flow {
                    val first = ports.words(noteId, key.variantId, 0, FIRST_PAGE).map(::word)
                    emit(first)
                    if (first.size == FIRST_PAGE) emit(ports.words(noteId, key.variantId).map(::word))
                }.catch { error ->
                    Log.w(TAG, "The transcript cannot be read", error)
                    emit(emptyList())
                }
            }
        }

    /** The voiceprints and matches of the selected variant; again after every voice edit. */
    private val prints: Flow<Map<String, SpeakerPrint>> = combine(
        details.map { loaded -> loaded?.details?.let { NoteMapping.selected(it)?.variantId } }.distinctUntilChanged(),
        printsVersion,
    ) { variant, _ -> variant }
        .mapLatest { variant ->
            if (variant == null) {
                emptyMap()
            } else {
                try {
                    ports.prints(noteId, variant)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                    Log.w(TAG, "Voiceprints cannot be read", error)
                    emptyMap()
                }
            }
        }
        .onStart { emit(emptyMap()) }

    private val sealedTranscript: Flow<SealedShown?> = combine(
        sealedWords,
        details.map { it?.details?.speakers.orEmpty() }.distinctUntilChanged(),
        prints,
        people,
    ) { words, speakers, prints, people ->
        words?.let { list ->
            val variant = details.value?.details?.let(NoteMapping::selected)?.variantId
            val shown = speakers.filter { it.variantId == variant }
            SealedShown(NoteTranscripts.sealed(list, shown, prints, people.voices.map { it.voice }), prints)
        }
    }.flowOn(compute)

    /** The draft of a running job (its tracker) or of a paused or failed one (the checkpoint and `names.json`). */
    private val draftTranscript: Flow<DraftShown?> = job
        .map { draftSource(it) }
        .distinctUntilChanged()
        .flatMapLatest { source ->
            when (source) {
                DraftSource.None -> flowOf(null)
                DraftSource.Running -> runningDraft()
                is DraftSource.Saved -> savedDraft()
            }
        }
        .flowOn(compute)

    private val core: StateFlow<Core?> =
        combine(details, job, sealedTranscript, draftTranscript, people) { loaded, job, sealed, draft, people ->
            Core(loaded, job, sealed, draft, people)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val matches: Flow<ImmutableList<TranscriptMatch>> = combine(
        core.map { it?.shown()?.turns.orEmpty() }.distinctUntilChanged(),
        local.map { it.search }.distinctUntilChanged(),
    ) { turns, query -> turns to query }
        .mapLatest { (turns, query) ->
            if (query.isNullOrBlank()) persistentListOf() else TranscriptSearch.matches(turns, query)
        }
        .flowOn(compute)
        .onStart { emit(persistentListOf()) }

    private val live: Flow<Live> = combine(
        ports.playback,
        ports.notices,
        ports.recording,
        ports.voiceModelReady.onStart { emit(false) },
    ) { playback, facts, recording, voices -> Live(playback, facts, recording, voices) }

    private val requiredFreeBytes: Flow<Long> = details
        .map { loaded -> loaded?.details?.job?.failure?.takeIf { JobFailure.downloadError(it) != null } }
        .distinctUntilChanged()
        .mapLatest { failure ->
            if (failure == null) 0L else runCatching { ports.requiredFreeBytes() }.getOrDefault(0L)
        }
        .onStart { emit(0L) }

    val state: StateFlow<NoteUiState> = combine(core, matches, live, local, requiredFreeBytes, ::build)
        .flowOn(compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), loading())

    init {
        launch { ports.refreshIfStale(noteId) }
        launch { closeWhenGone() }
        launch { followPlayer() }
        saved.get<String>(KEY_NAME_SHEET)?.let { speaker -> launch { openNaming(speaker, restoring = true) } }
        if (local.value.sheet == SheetKind.INFO) {
            launch { details.filterNotNull().first().details?.let { openInfo(it) } }
        }
        launch { saveOverlays() }
        key.start?.takeIf { saved.get<Boolean>(KEY_STARTED) != true }?.let { row ->
            saved[KEY_STARTED] = true
            launch { startRow(row) }
        }
    }

    // State. ---------------------------------------------------------------------------------------------------------

    /** What only this screen keeps: overlays, search, follow mode and the trust banner toggle. */
    private data class Local(
        val search: String? = null,
        val current: Int = 0,
        val following: Boolean = true,
        val trustToggled: Boolean = false,
        val dismissed: Set<String> = emptySet(),
        val sheet: SheetKind? = null,
        val naming: NamingState? = null,
        val info: DocumentFacts? = null,
        val dialog: NoteDialog? = null,
        val snackbar: SnackbarSpec? = null,
        val busyRow: NoteAction? = null,
        val seekingMs: Long? = null,
    )

    private enum class SheetKind { ACTIONS, VERSIONS, SUBTITLES, INFO, NAME }

    private data class Loaded(val details: NoteDetails?)

    private data class WordsKey(val variantId: String, val signature: String?, val words: Int)

    private class SealedShown(val transcript: NoteTranscript, val prints: Map<String, SpeakerPrint>)

    /**
     * A draft on screen: its [transcript], its [words] (merges applied, for the speaker numbers of the Name sheet),
     * the voice suggestions with their scores, whether naming is open, and the live matches by speaker.
     */
    private class DraftShown(
        val transcript: NoteTranscript,
        val words: List<Word>,
        val scores: Map<String, Float>,
        val namingOpen: Boolean,
        val live: Map<String, Match>,
    )

    private sealed interface DraftSource {
        data object None : DraftSource

        data object Running : DraftSource

        /** A paused or failed job; [updatedAt] reloads the draft when the row changes. */
        data class Saved(val updatedAt: Long) : DraftSource
    }

    private class Core(
        val loaded: Loaded?,
        val job: NoteJobState?,
        val sealed: SealedShown?,
        val draft: DraftShown?,
        val people: SavedVoices,
    ) {
        val details: NoteDetails? get() = loaded?.details
        val card get() = NoteMapping.card(job?.phase)
        val mode: NoteMode get() = details?.let { NoteMapping.mode(it, job, card) } ?: NoteMode.Loading
        val namingOpen: Boolean get() = draft?.namingOpen ?: true

        /** A transcript (sealed or draft) has loaded. */
        val shownLoaded: Boolean get() = sealed != null || draft != null

        fun shown(): NoteTranscript {
            val mode = mode
            return if (NoteMapping.showsDraft(mode)) {
                draft?.transcript ?: NoteTranscript.EMPTY
            } else {
                sealed?.transcript ?: NoteTranscript.EMPTY
            }
        }
    }

    private class Live(
        val playback: PlaybackState,
        val facts: NoticeFacts,
        val recording: RecordingState,
        val voiceModelReady: Boolean,
    )

    private fun build(
        core: Core?,
        matches: ImmutableList<TranscriptMatch>,
        live: Live,
        ui: Local,
        freeBytes: Long,
    ): NoteUiState {
        val details = core?.details ?: return loading()
        val note = details.note
        val mode = core.mode
        val transcript = core.shown()
        val draft = NoteMapping.showsDraft(mode)
        val now = Instant.now(clock)
        val playback = playbackUi(details, mode, live.playback, ui)
        val ours = live.playback.noteId == noteId && !live.playback.failed
        val active = if (!draft && ours && playback != null) transcript.activeWord(playback.positionMs) else null
        return NoteUiState(
            noteId = noteId,
            title = note.title,
            subtitle = NoteMapping.subtitle(note, mode, now, clock.zone),
            mode = mode,
            readOnlyReason = NoteMapping.readOnly(details, mode, core.namingOpen),
            chips = if (draft) null else NoteMapping.chips(details),
            banners = banners(details, core, live.facts, ui, freeBytes),
            turns = transcript.turns,
            activeWord = active,
            playback = playback,
            search = ui.search?.let { query ->
                TranscriptSearchUi(query, matches, ui.current.coerceIn(0, (matches.size - 1).coerceAtLeast(0)))
            },
            sheet = sheet(details, live, ui, now),
            dialog = ui.dialog,
            notificationsOff = live.facts.notificationsOff,
            snackbar = ui.snackbar,
        )
    }

    private fun loading() = NoteUiState(noteId, "", mode = NoteMode.Loading)

    private fun playbackUi(details: NoteDetails, mode: NoteMode, playback: PlaybackState, ui: Local): PlaybackUi? {
        if (!NoteMapping.hasPlayer(mode)) return null
        val ours = playback.noteId == noteId && !playback.failed
        val duration = if (ours && playback.durationMs > 0) playback.durationMs else details.note.durationMs
        val position = ui.seekingMs ?: if (ours) playback.positionMs else details.note.positionMs
        return PlaybackUi(position, duration, playing = ours && playback.playing, following = ui.following)
    }

    @Suppress("CyclomaticComplexMethod") // one branch per banner source of features.md sections 3 and 13
    private fun banners(
        details: NoteDetails,
        core: Core,
        facts: NoticeFacts,
        ui: Local,
        freeBytes: Long,
    ): ImmutableList<BannerSpec> {
        val note = details.note
        val mode = core.mode
        val list = mutableListOf<BannerSpec>()
        when (mode) {
            NoteMode.AudioOnly -> list += BannerSpec(
                BannerKind.PLAIN_AUDIO,
                actions = if (note.audioKind == AudioKind.LIVE_CAPTURE) persistentListOf() else BannerKind.PLAIN_AUDIO.actions,
            )
            NoteMode.Unplayable -> list += BannerSpec(BannerKind.UNPLAYABLE)
            else -> Unit
        }
        val overDocument = mode == NoteMode.Reading || (mode as? NoteMode.Queued)?.overDocument == true ||
            (mode as? NoteMode.Failed)?.overDocument == true
        if (overDocument) trustBanner(details, ui)?.let { list += it }
        val phase = core.job?.phase.takeIf { mode !is NoteMode.Processing }
        val failure = details.job?.failure?.let { ports.failureText(it) }
        val refused = LibraryMapping.transcribeAgainRefused(details)
        list += Notices.job(details.job, phase, NoteMapping.oneVoice(details), failure, freeBytes, refused)
        if (mode is NoteMode.Paused && facts.hold && facts.queued > 0) {
            list += BannerSpec(BannerKind.TX_QUEUE_HELD, BannerArgs(count = facts.queued))
        }
        return list.filter { it.id !in ui.dismissed }.distinctBy { it.id }.toImmutableList()
    }

    /** The trust banner: non-OK states show it (UNVERIFIED until Play anyway); the chip toggles it. */
    private fun trustBanner(details: NoteDetails, ui: Local): BannerSpec? {
        val trust = NoteMapping.trust(details.note.documentState)
        val shownByDefault = trust != TrustUi.VERIFIED &&
            !(trust == TrustUi.NOT_VERIFIED && details.note.trustAcknowledged)
        if (shownByDefault == ui.trustToggled) return null
        val kind = when (trust) {
            TrustUi.VERIFIED -> BannerKind.TRUST_VERIFIED
            TrustUi.NOT_VERIFIED -> BannerKind.TRUST_NOT_VERIFIED
            TrustUi.AUDIO_CHANGED -> BannerKind.TRUST_AUDIO_CHANGED
            TrustUi.UNREADABLE -> BannerKind.TRUST_UNREADABLE
            TrustUi.NEWER_FORMAT -> BannerKind.TRUST_NEWER_FORMAT
        }
        return BannerSpec(kind)
    }

    private fun sheet(details: NoteDetails, live: Live, ui: Local, now: Instant): NoteSheet? =
        when (ui.sheet) {
            null -> null
            SheetKind.ACTIONS -> {
                val active = activeNoteId(live.recording)
                val actions = LibraryMapping.actions(details, active, now, clock.zone, live.voiceModelReady)
                NoteSheet.Actions(actions.copy(busyRow = ui.busyRow))
            }
            SheetKind.VERSIONS -> NoteSheet.Versions(NoteMapping.versions(details, now, clock.zone))
            SheetKind.SUBTITLES -> NoteSheet.SubtitleFormat
            SheetKind.INFO -> NoteSheet.Info(info(details, ui.info))
            SheetKind.NAME -> ui.naming?.let { naming ->
                SpeakerNaming.mergeUi(naming)?.let { NoteSheet.Merge(it) }
                    ?: NoteSheet.NameSpeaker(SpeakerNaming.ui(naming, sampling = live.playback.sample))
            }
        }

    private fun info(details: NoteDetails, facts: DocumentFacts?): InfoUi {
        val note = details.note
        val at = Instant.ofEpochMilli(note.createdAt).atZone(clock.zone)
        val selected = NoteMapping.selected(details)
        val inference = selected?.inferenceMs?.takeIf { it > 0 }
        return InfoUi(
            trust = NoteMapping.trust(note.documentState),
            title = note.title,
            recordedDate = at.toLocalDate(),
            recordedTime = at.toLocalTime(),
            durationMs = note.durationMs,
            versions = details.variants.size,
            speakers = selected?.speakerCount ?: 0,
            language = selected?.language?.ifEmpty { null },
            processingMs = inference,
            realtimeFactor = inference?.let { note.durationMs.toDouble() / it }?.takeIf { it > 0 },
            model = facts?.model,
            speakersToldApart = selected?.speakersToldApart,
            namesFromVoices = facts?.namesFromVoices ?: 0,
            resumedFromMs = facts?.resumedFromMs,
            fileBytes = facts?.fileBytes ?: 0,
            meetingId = note.meetingId,
            readError = note.documentError,
        )
    }

    // Events. --------------------------------------------------------------------------------------------------------

    @Suppress("CyclomaticComplexMethod", "LongMethod") // one branch per event of ui.md section 7
    fun onEvent(event: NoteEvent) {
        when (event) {
            NoteEvent.Back -> emit(NoteEffect.Close)
            NoteEvent.More -> openSheet(SheetKind.ACTIONS)
            NoteEvent.Stop -> ports.stop(noteId)
            NoteEvent.RemoveFromQueue -> launch { ports.dequeue(noteId) }
            is NoteEvent.WordTap -> wordTap(event.word)
            is NoteEvent.PlayTurn -> launch { playTurn(event.turnId) }
            is NoteEvent.SpeakerTap -> launch { openNaming(event.speakerId) }
            is NoteEvent.AcceptSuggestion -> launch { accept(event.speakerId, event.voiceId) }
            is NoteEvent.RejectSuggestion -> launch { reject(event.speakerId, event.voiceId) }
            NoteEvent.PlayPause -> ports.togglePlay()
            is NoteEvent.Seek -> local.update { it.copy(seekingMs = event.positionMs) }
            NoteEvent.SeekFinished -> seekFinished()
            is NoteEvent.Skip -> ports.skip(if (event.forward) SKIP_MS else -SKIP_MS)
            NoteEvent.PauseFollowing -> local.update { it.copy(following = false) }
            NoteEvent.FollowPlayback -> local.update { it.copy(following = true) }
            NoteEvent.TrustChip -> local.update { it.copy(trustToggled = !it.trustToggled) }
            NoteEvent.OpenVersions -> openVersions()
            is NoteEvent.PickVersion -> launch { pickVersion(event.index) }
            NoteEvent.OpenSearch -> setSearch(local.value.search ?: "")
            is NoteEvent.SearchQuery -> setSearch(event.query)
            NoteEvent.NextMatch -> stepMatch(1)
            NoteEvent.PreviousMatch -> stepMatch(-1)
            NoteEvent.CloseSearch -> setSearch(null)
            is NoteEvent.SheetAction -> launch { sheetAction(event.action) }
            is NoteEvent.Export -> launch { requestExport(event.kind) }
            is NoteEvent.RenameConfirmed -> {
                local.update { it.copy(dialog = null) }
                launch { ports.rename(noteId, event.title) }
            }
            NoteEvent.ConfirmDialog -> confirmDialog()
            is NoteEvent.NameSheet -> nameSheet(event.event)
            is NoteEvent.Merge -> mergeSheet(event.event)
            is NoteEvent.BannerAction -> launch { bannerAction(event.id, event.action) }
            is NoteEvent.DismissBanner -> dismissBanner(event.id)
            NoteEvent.DismissSheet -> closeSheet()
            NoteEvent.DismissDialog -> local.update { it.copy(dialog = null) }
            NoteEvent.SnackbarTimeout -> local.update { it.copy(snackbar = null) }
        }
    }

    /** The screen is shown (the player may open) or left (the player saves its position and releases). */
    fun onVisible(shown: Boolean) {
        visible.value = shown
    }

    /** The Uri that the "Save a copy" picker returned; null when the user cancelled it. */
    fun onExportTarget(uri: String?) {
        val kind = saved.get<String>(KEY_EXPORT)?.let { name -> ExportKind.entries.firstOrNull { it.name == name } }
        saved[KEY_EXPORT] = null
        if (kind == null || uri == null) return
        launch { export(kind, uri) }
    }

    /** The screen is visible again (ON_RESUME): the notification permission may have changed. */
    fun onResume() = ports.refreshNotices()

    // Playback. ------------------------------------------------------------------------------------------------------

    /** Opens the player while the screen is shown and the mode has one; prepares again after a document replace. */
    private suspend fun followPlayer() {
        var opened: String? = null
        var signature: String? = null
        combine(details, job, visible) { loaded, job, shown ->
            val details = loaded?.details
            val mode = details?.let { NoteMapping.mode(it, job, null) }
            PlayerKey(
                file = details?.note?.audioFile?.takeIf { shown && mode != null && NoteMapping.hasPlayer(mode) },
                signature = details?.note?.documentSignature,
                positionMs = details?.note?.positionMs ?: 0,
            )
        }.distinctUntilChanged { a, b -> a.file == b.file && a.signature == b.signature }.collect { key ->
            val file = key.file
            when {
                file == null -> if (opened != null) {
                    ports.leavePlayer(noteId)
                    opened = null
                }
                file != opened -> {
                    ports.openPlayer(noteId, file, key.positionMs)
                    opened = file
                }
                key.signature != signature -> ports.reloadPlayer(noteId, file)
            }
            signature = key.signature
        }
    }

    private data class PlayerKey(val file: String?, val signature: String?, val positionMs: Long)

    private fun wordTap(word: WordRef) {
        val start = core.value?.shown()?.wordStart(word) ?: return
        seekAndPlay(start)
    }

    private suspend fun playTurn(turnId: String) {
        val start = core.filterNotNull().first().shown().turnStart(turnId) ?: return
        seekAndPlay(start)
    }

    private fun seekAndPlay(positionMs: Long) {
        local.update { it.copy(following = true) }
        ports.seekTo(positionMs)
        ports.play()
    }

    private fun seekFinished() {
        val target = local.value.seekingMs ?: return
        ports.seekTo(target)
        local.update { it.copy(seekingMs = null) }
    }

    // Search. --------------------------------------------------------------------------------------------------------

    private fun setSearch(query: String?) {
        saved[KEY_SEARCH] = query
        local.update { it.copy(search = query, current = 0) }
    }

    private fun stepMatch(delta: Int) {
        val total = state.value.search?.total ?: 0
        if (total == 0) return
        local.update { it.copy(current = Math.floorMod(it.current + delta, total)) }
    }

    /**
     * Keeps the open sheet and dialog in [saved] (architecture 6), so that they come back after process death. The
     * Name sheet has its own keys (KEY_NAME_SHEET).
     */
    private suspend fun saveOverlays() {
        local.map { it.sheet to it.dialog }.distinctUntilChanged().collect { (sheet, dialog) ->
            saved[KEY_SHEET] = sheet?.takeIf { it != SheetKind.NAME }?.name
            saved[KEY_DIALOG] = when (dialog) {
                null -> null
                is NoteDialog.Rename -> DIALOG_RENAME
                NoteDialog.StartOver -> DIALOG_START_OVER
                NoteDialog.TranscribeFresh -> DIALOG_FRESH
                NoteDialog.StopAndTrash -> DIALOG_STOP_AND_TRASH
                is NoteDialog.ReadOnly -> DIALOG_READ_ONLY + dialog.reason.name
            }
            saved[KEY_DIALOG_TITLE] = (dialog as? NoteDialog.Rename)?.title
        }
    }

    private fun savedSheet(): SheetKind? =
        saved.get<String>(KEY_SHEET)?.let { name -> SheetKind.entries.firstOrNull { it.name == name } }

    private fun savedDialog(): NoteDialog? {
        val key = saved.get<String>(KEY_DIALOG) ?: return null
        return when {
            key == DIALOG_RENAME -> saved.get<String>(KEY_DIALOG_TITLE)?.let(NoteDialog::Rename)
            key == DIALOG_START_OVER -> NoteDialog.StartOver
            key == DIALOG_FRESH -> NoteDialog.TranscribeFresh
            key == DIALOG_STOP_AND_TRASH -> NoteDialog.StopAndTrash
            key.startsWith(DIALOG_READ_ONLY) -> ReadOnlyReason.entries
                .firstOrNull { it.name == key.removePrefix(DIALOG_READ_ONLY) }
                ?.let(NoteDialog::ReadOnly)
            else -> null
        }
    }

    private fun searchAtOpen(query: String?): String? {
        if (!saved.contains(KEY_SEARCH)) saved[KEY_SEARCH] = query?.trim()?.ifEmpty { null }
        return saved.get<String>(KEY_SEARCH)
    }

    // Sheets and dialogs. --------------------------------------------------------------------------------------------

    private fun openSheet(kind: SheetKind) = local.update { it.copy(sheet = kind) }

    private fun closeSheet() {
        saved[KEY_NAME_SHEET] = null
        local.update { it.copy(sheet = null, naming = null, busyRow = null) }
    }

    private fun showReadOnly(reason: ReadOnlyReason) {
        closeSheet()
        local.update { it.copy(dialog = NoteDialog.ReadOnly(reason)) }
    }

    private fun openVersions() {
        val core = core.value ?: return
        val details = core.details ?: return
        if (NoteMapping.readOnly(details, core.mode, core.namingOpen) == ReadOnlyReason.JOB_PENDING) {
            showReadOnly(ReadOnlyReason.JOB_PENDING)
        } else {
            openSheet(SheetKind.VERSIONS)
        }
    }

    private suspend fun pickVersion(index: Int) {
        val details = details.value?.details ?: return
        val variant = details.variants.sortedBy { it.ordinal }.getOrNull(index)
        closeSheet()
        if (variant != null && variant.readable) ports.selectVariant(noteId, variant.variantId)
    }

    private fun confirmDialog() {
        val dialog = local.value.dialog ?: return
        local.update { it.copy(dialog = null) }
        when (dialog) {
            NoteDialog.StartOver -> launch { consentOrDone(ports.startOver(noteId)) }
            NoteDialog.TranscribeFresh -> launch { transcribe(JobKind.TRANSCRIBE_AGAIN) }
            NoteDialog.StopAndTrash -> launch { moveToTrash() }
            is NoteDialog.Rename, is NoteDialog.ReadOnly -> Unit
        }
    }

    @Suppress("CyclomaticComplexMethod") // one branch per row of the Note actions sheet
    private suspend fun sheetAction(action: NoteAction) {
        val details = details.value?.details ?: return closeSheet()
        when (action) {
            NoteAction.SAVE_DOCUMENT -> requestExport(ExportKind.DOCUMENT)
            NoteAction.SAVE_TEXT -> requestExport(ExportKind.TEXT)
            NoteAction.SAVE_SUBTITLES -> if (NoteExports.textExportEnabled(details)) openSheet(SheetKind.SUBTITLES)
            NoteAction.RENAME -> {
                closeSheet()
                local.update { it.copy(dialog = NoteDialog.Rename(details.note.title)) }
            }
            NoteAction.TRANSCRIBE -> {
                closeSheet()
                transcribe(JobKind.TRANSCRIBE)
            }
            NoteAction.TRANSCRIBE_AGAIN -> {
                closeSheet()
                transcribeAgain(details)
            }
            NoteAction.STOP -> {
                closeSheet()
                ports.stop(noteId)
            }
            NoteAction.REMOVE_FROM_QUEUE -> {
                closeSheet()
                ports.dequeue(noteId)
            }
            NoteAction.RESUME -> {
                closeSheet()
                transcribe(details.job?.kind ?: JobKind.TRANSCRIBE)
            }
            NoteAction.START_OVER -> {
                closeSheet()
                local.update { it.copy(dialog = NoteDialog.StartOver) }
            }
            NoteAction.DOCUMENT_INFO -> openInfo(details)
            NoteAction.MOVE_TO_TRASH -> {
                closeSheet()
                trash(details)
            }
        }
    }

    private suspend fun openInfo(details: NoteDetails) {
        local.update { it.copy(sheet = SheetKind.INFO, info = null) }
        val facts = try {
            ports.documentFacts(noteId, NoteMapping.selected(details)?.variantId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "Document info cannot be read", error)
            null
        }
        local.update { if (it.sheet == SheetKind.INFO) it.copy(info = facts) else it }
    }

    /** The Library's actions sheet row that opened this screen (NoteKey.start), once. */
    private suspend fun startRow(row: NoteStart) {
        val details = details.filterNotNull().first().details ?: return
        when (row) {
            NoteStart.SAVE_DOCUMENT -> requestExport(ExportKind.DOCUMENT)
            NoteStart.SAVE_TEXT -> requestExport(ExportKind.TEXT)
            NoteStart.SAVE_SRT -> requestExport(ExportKind.SRT)
            NoteStart.SAVE_VTT -> requestExport(ExportKind.VTT)
            NoteStart.CHOOSE_SUBTITLES -> if (NoteExports.textExportEnabled(details)) openSheet(SheetKind.SUBTITLES)
            NoteStart.DOCUMENT_INFO -> openInfo(details)
        }
    }

    // Queue actions. -------------------------------------------------------------------------------------------------

    /** Transcribe, Transcribe again and Resume: POST_NOTIFICATIONS once first, then the queue. */
    private suspend fun transcribe(kind: JobKind) {
        if (ports.shouldAskNotifications()) {
            ports.markNotificationsAsked()
            emit(NoteEffect.RequestNotifications)
        }
        consentOrDone(ports.transcribe(noteId, kind))
    }

    private fun consentOrDone(outcome: TranscribeOutcome) {
        if (outcome == TranscribeOutcome.NeedsConsent) emit(NoteEffect.OpenModelDownload(ModelDownloadKey(noteId)))
    }

    /** Hides the banner [id]; a dismissed final failure also removes its job row, so the document shows again. */
    private fun dismissBanner(id: String) {
        val details = details.value?.details
        val job = details?.job
        val final = id == BannerKind.TX_FAILED.name && job != null &&
            Notices.finalFailure(job, LibraryMapping.transcribeAgainRefused(details))
        if (final) {
            launch { ports.clearFailed(noteId) }
        } else {
            local.update { it.copy(dismissed = it.dismissed + id) }
        }
    }

    /** Transcribe again: a document that is not OK asks first ("Earlier versions can't be kept…"). */
    private suspend fun transcribeAgain(details: NoteDetails) {
        if (LibraryMapping.transcribeAgainRefused(details)) {
            Log.w(TAG, "Transcribe again is not possible for this document")
        } else if (LibraryMapping.needsFreshConfirmation(details)) {
            local.update { it.copy(dialog = NoteDialog.TranscribeFresh) }
        } else {
            transcribe(JobKind.TRANSCRIBE_AGAIN)
        }
    }

    /** Move to trash: a running job asks first; then the note goes, and the Library offers Undo. */
    private suspend fun trash(details: NoteDetails) {
        if (details.job?.state == JobState.RUNNING) {
            local.update { it.copy(dialog = NoteDialog.StopAndTrash) }
        } else {
            moveToTrash()
        }
    }

    private suspend fun moveToTrash() {
        val title = details.value?.details?.note?.title.orEmpty()
        closing = true
        if (!ports.moveToTrash(noteId)) {
            closing = false
            return
        }
        ports.leavePlayer(noteId)
        ports.offerUndo(TrashedNote(noteId, title))
        emit(NoteEffect.CloseToLibrary)
    }

    /** Leaves the screen when the note goes (trashed or deleted elsewhere). */
    private suspend fun closeWhenGone() {
        details.filterNotNull().first { loaded ->
            val note = loaded.details?.note
            note == null || note.trashedAt != null
        }
        if (!closing) {
            closing = true
            emit(NoteEffect.Close)
        }
    }

    @Suppress("CyclomaticComplexMethod") // one branch per banner action of features.md section 13
    private suspend fun bannerAction(id: String, action: BannerAction) {
        val details = details.value?.details ?: return
        val kind = details.job?.kind ?: JobKind.TRANSCRIBE
        when (action) {
            BannerAction.RESUME, BannerAction.TRY_AGAIN, BannerAction.RETRY, BannerAction.DOWNLOAD_AGAIN ->
                transcribe(kind)
            BannerAction.START_OVER -> local.update { it.copy(dialog = NoteDialog.StartOver) }
            BannerAction.TRANSCRIBE -> transcribe(JobKind.TRANSCRIBE)
            BannerAction.TRANSCRIBE_AGAIN -> transcribeAgain(details)
            BannerAction.PLAY_ANYWAY -> {
                ports.acknowledgeTrust(noteId)
                ports.play()
            }
            BannerAction.CONTINUE -> ports.continueQueue()
            BannerAction.CANCEL -> ports.cancelDownload()
            BannerAction.TURN_ON, BannerAction.OPEN_SETTINGS -> emit(NoteEffect.OpenNotificationSettings)
            BannerAction.DOWNLOAD -> emit(NoteEffect.OpenModelDownload(ModelDownloadKey(noteId)))
            else -> Log.w(TAG, "No Note handler for $action on $id")
        }
    }

    // Exports. -------------------------------------------------------------------------------------------------------

    private suspend fun requestExport(kind: ExportKind) {
        val details = details.filterNotNull().first().details ?: return
        val file = NoteExports.file(details, kind) ?: return
        saved[KEY_EXPORT] = kind.name
        local.update { it.copy(sheet = SheetKind.ACTIONS) }
        emit(NoteEffect.CreateFile(file.mime, file.name))
    }

    private suspend fun export(kind: ExportKind, uri: String) {
        // After process death the picker answers before the row is back: wait for it.
        val details = details.filterNotNull().first().details ?: return
        val file = NoteExports.file(details, kind) ?: return
        local.update { it.copy(sheet = SheetKind.ACTIONS, busyRow = NoteExports.row(kind)) }
        val format = NoteExports.format(kind)
        val outcome = if (format == null) {
            ports.exportCopy(noteId, uri)
        } else {
            val speakers = NoteMapping.selected(details)?.speakerCount ?: 0
            val request = NoteExports.request(
                details,
                format,
                ports::placeholderLabel,
                ports.exportSubtitle(details.note.createdAt, details.note.durationMs, speakers),
                ports.exportEmptyText(),
            )
            ports.exportTranscript(noteId, request, uri)
        }
        val ok = outcome is ExportOutcome.Saved
        if (!ok) Log.w(TAG, "Export of $kind ended $outcome")
        val name = if (ok) ports.displayName(uri) ?: file.name else null
        val snackbar = SnackbarSpec(
            kind = if (ok) SnackbarKind.SAVED_FILE else SnackbarKind.SAVE_FAILED,
            text = name,
            durationMs = ports.snackbarTimeoutMs(),
            id = ++snackbarCount,
        )
        closeSheet()
        local.update { it.copy(snackbar = snackbar) }
    }

    // Naming. --------------------------------------------------------------------------------------------------------

    /** Opens the Name sheet for [speakerId], or the read-only message when renaming is not allowed now. */
    private suspend fun openNaming(speakerId: String, restoring: Boolean = false) {
        val core = core.filterNotNull().first { !restoring || it.details != null && it.shownLoaded }
        val details = core.details ?: return
        val mode = core.mode
        NoteMapping.readOnly(details, mode, core.namingOpen)?.let { return showReadOnly(it) }
        val input = if (NoteMapping.showsDraft(mode)) {
            draftInput(core, speakerId)
        } else {
            sealedInput(core, details, speakerId)
        }
        if (input == null) {
            saved[KEY_NAME_SHEET] = null
            return
        }
        var naming = SpeakerNaming.open(input)
        saved.get<String>(KEY_NAME_TEXT)?.takeIf { restoring }?.let {
            naming = SpeakerNaming.reduce(naming, NamingEvent.NameChanged(it)).state ?: naming
        }
        saved[KEY_NAME_SHEET] = speakerId
        saved[KEY_NAME_TEXT] = naming.name
        local.update { it.copy(sheet = SheetKind.NAME, naming = naming) }
    }

    private suspend fun sealedInput(core: Core, details: NoteDetails, speakerId: String): NamingInput? {
        val variant = NoteMapping.selected(details) ?: return null
        val speakers = details.speakers.filter { it.variantId == variant.variantId }
        val speaker = speakers.firstOrNull { it.speakerId == speakerId } ?: return null
        val transcript = core.shown()
        val tones = transcript.turns.associate { it.speakerId to it.tone }
        val print = runCatching { ports.prints(noteId, variant.variantId)[speakerId] }.getOrNull()
        val sample = speaker.sampleStartMs?.let { start ->
            speaker.sampleEndMs?.let { end -> SampleWindow(start, end) }
        }
        val targets = speakers.filter { it.speakerId != speakerId && it.wordCount > 0 }.sortedBy { it.ordinal }.map {
            MergeTargetUi(it.speakerId, NoteTranscripts.name(it), tones[it.speakerId] ?: AMBER, it.speakingMs)
        }
        val live = livePlayer()
        return NamingInput(
            speakerId = speakerId,
            label = NoteTranscripts.name(speaker),
            tone = tones[speakerId] ?: AMBER,
            speakingMs = speaker.speakingMs,
            sample = sample,
            draft = false,
            print = print?.embedding,
            printSeconds = print?.seconds ?: 0.0,
            match = print?.match,
            voices = core.people.voices.map { it.voice },
            voicesReadOnly = core.people.readOnly,
            voiceModelReady = ports.voiceModelReady.first(),
            mergeTargets = targets,
            sealedVersions = MergeVersionsUi(shown = variant.ordinal + 1, added = details.variants.size + 1),
            canSample = live,
        )
    }

    private suspend fun draftInput(core: Core, speakerId: String): NamingInput? {
        val draft = core.draft ?: return null
        val turns = draft.transcript.turns
        val turn = turns.firstOrNull { it.speakerId == speakerId } ?: return null
        val stats = SpeakerStats.of(draft.words)
        val firsts = turns.distinctBy { it.speakerId }
        val targets = firsts.filter { it.speakerId != speakerId }.map {
            MergeTargetUi(it.speakerId, it.name, it.tone, stats[it.speakerId]?.speakingMs ?: 0)
        }
        val modelReady = ports.voiceModelReady.first()
        return NamingInput(
            speakerId = speakerId,
            label = turn.name,
            tone = turn.tone,
            speakingMs = stats[speakerId]?.speakingMs ?: 0,
            sample = stats[speakerId]?.sample,
            draft = true,
            willHavePrint = modelReady,
            match = draft.live[speakerId],
            voices = core.people.voices.map { it.voice },
            voicesReadOnly = core.people.readOnly,
            voiceModelReady = modelReady,
            mergeTargets = targets,
            canSample = livePlayer() && core.mode !is NoteMode.Processing,
        )
    }

    private fun livePlayer(): Boolean = ports.playback.value.let { it.noteId == noteId && !it.failed }

    @Suppress("CyclomaticComplexMethod") // one branch per Name sheet event
    private fun nameSheet(event: NameSheetEvent) {
        val naming = local.value.naming ?: return
        val mapped = when (event) {
            is NameSheetEvent.NameChanged -> NamingEvent.NameChanged(event.text)
            NameSheetEvent.PlaySample -> NamingEvent.PlaySample
            is NameSheetEvent.PickVoice -> NamingEvent.PickVoice(event.voiceId)
            NameSheetEvent.ShowAll -> NamingEvent.ShowAll
            NameSheetEvent.SamePerson -> NamingEvent.SamePerson
            NameSheetEvent.SomeoneElse -> if (SpeakerNaming.samePerson(naming) != null) {
                NamingEvent.SomeoneElse
            } else {
                NamingEvent.KeepSaved
            }
            NameSheetEvent.RenameSaved -> NamingEvent.RenameSaved
            NameSheetEvent.NotName -> NamingEvent.NotThisPerson
            is NameSheetEvent.RememberChanged -> NamingEvent.RememberToggled(event.remember)
            NameSheetEvent.MergeInto -> NamingEvent.MergeInto
            NameSheetEvent.Cancel -> NamingEvent.Cancel
            NameSheetEvent.Save, NameSheetEvent.SaveBlocked -> NamingEvent.Save
        }
        naming(mapped)
    }

    private fun mergeSheet(event: MergeEvent) = naming(
        when (event) {
            is MergeEvent.PickTarget -> NamingEvent.MergeTarget(event.speakerId)
            MergeEvent.Confirm -> NamingEvent.ConfirmMerge
            MergeEvent.CancelConfirm -> NamingEvent.CancelMerge
        },
    )

    private fun naming(event: NamingEvent) {
        val naming = local.value.naming ?: return
        val result = SpeakerNaming.reduce(naming, event)
        val next = result.state
        if (next == null) {
            closeSheet()
        } else {
            saved[KEY_NAME_TEXT] = next.name
            local.update { it.copy(naming = next) }
        }
        val effects = result.effects.filter { it != NamingEffect.Close && it != NamingEffect.FocusQuestion }
        if (effects.isNotEmpty()) launch { apply(effects) }
    }

    /** Carries out the sheet's effects in order; a refused sealed edit shows why. */
    private suspend fun apply(effects: List<NamingEffect>) {
        val details = details.value?.details ?: return
        val variant = NoteMapping.selected(details)?.variantId
        val draft = ports.draft(noteId)
        for (effect in effects) {
            val refused = when (effect) {
                is NamingEffect.Relabel -> refusal(ports.relabel(noteId, mapOf(effect.speakerId to effect.label)))
                is NamingEffect.Reject -> reject(effect, details, variant, draft)
                is NamingEffect.MergeSealed -> mergeSealed(effect, variant)
                else -> {
                    applyOther(effect, variant, draft)
                    null
                }
            }
            if (refused != null) {
                showReadOnly(refused)
                break
            }
        }
        printsVersion.update { it + 1 }
    }

    /** The effects that never refuse: voice files, draft names and the sample. */
    private suspend fun applyOther(effect: NamingEffect, variant: String?, draft: DraftNaming) {
        when (effect) {
            is NamingEffect.RememberVoice -> variant?.let {
                ports.enrol(noteId, it, effect.speakerId, effect.voiceId, effect.name)
            }
            is NamingEffect.RenameVoice -> ports.renameVoice(effect.voiceId, effect.name)
            is NamingEffect.ClearMatch -> variant?.let { ports.setMatch(noteId, it, effect.speakerId, null) }
            is NamingEffect.Unenrol -> variant?.let { ports.unenrol(noteId, it, effect.speakerId) }
            is NamingEffect.MergeDraft -> draft.merge(effect.source, effect.target, effect.targetLabel)
            is NamingEffect.DraftName -> draftName(effect, draft)
            is NamingEffect.PlayRange -> ports.playRange(noteId, effect.startMs, effect.endMs)
            is NamingEffect.Relabel, is NamingEffect.Reject, is NamingEffect.MergeSealed,
            NamingEffect.FocusQuestion, NamingEffect.Close -> Unit
        }
    }

    private fun refusal(outcome: EditOutcome): ReadOnlyReason? = when (outcome) {
        EditOutcome.JobPending -> ReadOnlyReason.JOB_PENDING
        is EditOutcome.NotEditable -> ReadOnlyReason.TRUST
        else -> null.also { if (outcome !is EditOutcome.Done) Log.w(TAG, "Edit ended $outcome") }
    }

    private suspend fun reject(
        effect: NamingEffect.Reject,
        details: NoteDetails,
        variant: String?,
        draft: DraftNaming,
    ): ReadOnlyReason? {
        if (effect.draft) {
            draft.reject(effect.speakerId, effect.voiceId, effect.voiceName)
            return null
        }
        variant ?: return null
        val match = ports.prints(noteId, variant)[effect.speakerId]?.match
        if (effect.unenrol) ports.unenrol(noteId, variant, effect.speakerId)
        val rejected = Match(effect.voiceId, match?.score ?: 0f, Match.State.REJECTED)
        ports.setMatch(noteId, variant, effect.speakerId, rejected)
        if (!effect.restorePlaceholder) return null
        val speaker = details.speakers.firstOrNull { it.variantId == variant && it.speakerId == effect.speakerId }
        val number = speaker?.placeholderNumber ?: ((speaker?.ordinal ?: 0) + 1)
        return refusal(ports.relabel(noteId, mapOf(effect.speakerId to ports.placeholderLabel(number))))
    }

    private suspend fun mergeSealed(effect: NamingEffect.MergeSealed, variant: String?): ReadOnlyReason? {
        variant ?: return null
        val outcome = ports.mergeSpeakers(noteId, variant, effect.source, effect.target)
        val added = (outcome as? EditOutcome.Done)?.variantId
        if (added != null) ports.copyPrints(noteId, variant, added, without = effect.source)
        return refusal(outcome)
    }

    private suspend fun draftName(effect: NamingEffect.DraftName, draft: DraftNaming) {
        val voiceId = effect.voiceId
        if (effect.confirm && voiceId != null) {
            draft.confirm(effect.speakerId, voiceId, effect.name, effect.score)
        } else {
            draft.rename(effect.speakerId, effect.name)
            draft.remember(effect.speakerId, voiceId)
        }
    }

    /** "Yes, Luca" on a suggestion card. */
    private suspend fun accept(speakerId: String, voiceId: String) {
        val core = core.filterNotNull().first()
        val details = core.details ?: return
        NoteMapping.readOnly(details, core.mode, core.namingOpen)?.let { return showReadOnly(it) }
        val name = core.people.voices.firstOrNull { it.id == voiceId }?.name ?: return
        if (NoteMapping.showsDraft(core.mode)) {
            val score = core.draft?.scores?.get(speakerId) ?: 1f
            ports.draft(noteId).confirm(speakerId, voiceId, name, score)
            return
        }
        val variant = NoteMapping.selected(details)?.variantId ?: return
        refusal(ports.relabel(noteId, mapOf(speakerId to name)))?.let { return showReadOnly(it) }
        ports.enrol(noteId, variant, speakerId, voiceId, name)
        printsVersion.update { it + 1 }
    }

    /** "Not Luca" on a suggestion card. */
    private suspend fun reject(speakerId: String, voiceId: String) {
        val core = core.filterNotNull().first()
        val details = core.details ?: return
        NoteMapping.readOnly(details, core.mode, core.namingOpen)?.let { return showReadOnly(it) }
        if (NoteMapping.showsDraft(core.mode)) {
            val name = core.people.voices.firstOrNull { it.id == voiceId }?.name
            ports.draft(noteId).reject(speakerId, voiceId, name)
            return
        }
        val variant = NoteMapping.selected(details)?.variantId ?: return
        val score = core.sealed?.prints?.get(speakerId)?.match?.score ?: 0f
        ports.setMatch(noteId, variant, speakerId, Match(voiceId, score, Match.State.REJECTED))
        printsVersion.update { it + 1 }
    }

    // Drafts. --------------------------------------------------------------------------------------------------------

    private fun draftSource(job: NoteJobState?): DraftSource = when (job?.state) {
        JobState.RUNNING -> DraftSource.Running
        JobState.PAUSED, JobState.FAILED -> DraftSource.Saved(job.job.updatedAt)
        else -> DraftSource.None
    }

    private fun runningDraft(): Flow<DraftShown?> = combine(
        // The tracker ticks every second; only a new draft rebuilds the turns.
        job.map { (it?.phase as? JobPhase.Transcribing)?.draft }.distinctUntilChanged(),
        people,
        ports.draftNames(noteId).catch { emit(CheckpointNames(emptyMap(), emptyMap())) },
    ) { draft, people, names ->
        val state = draft ?: DraftState.EMPTY
        val voiceNames = people.voices.associate { it.id to it.name }
        val suggestions = state.suggestions.filter { it.voiceId in voiceNames }
            .associate { it.speakerId to SuggestionUi(it.voiceId, it.name) }
        val transcript = NoteTranscripts.draft(
            state.words,
            state.labels,
            state.automatic,
            suggestions,
            state.tentativeFromMs,
        )
        DraftShown(
            transcript,
            state.words,
            state.suggestions.associate { it.speakerId to it.score },
            state.namingOpen,
            names.live,
        )
    }

    private fun savedDraft(): Flow<DraftShown?> = combine(
        ports.draftNames(noteId).catch { emit(CheckpointNames(emptyMap(), emptyMap())) },
        people,
    ) { names, people -> names to people }
        .mapLatest { (names, people) ->
            val draft = runCatching { ports.loadDraft(noteId) }.getOrNull() ?: return@mapLatest null
            val voiceNames = people.voices.associate { it.id to it.name }
            val suggestions = names.live.filterValues { it.state == Match.State.SUGGESTED && it.voiceId in voiceNames }
                .filter { (speaker, match) -> match.voiceId !in names.rejected[speaker].orEmpty() }
                .mapValues { (_, match) -> SuggestionUi(match.voiceId, voiceNames.getValue(match.voiceId)) }
            val automatic = names.automatic.filter { (id, name) -> draft.labels[id] == name }.keys
            val transcript = NoteTranscripts.draft(draft.words, draft.labels, automatic, suggestions, null)
            DraftShown(
                transcript,
                draft.words,
                names.live.mapValues { it.value.score },
                namingOpen = true,
                live = names.live,
            )
        }

    // Helpers. -------------------------------------------------------------------------------------------------------

    private fun wordsKey(details: NoteDetails): WordsKey? {
        if (details.note.audioKind != AudioKind.DOCUMENT) return null
        val variant = NoteMapping.selected(details) ?: return null
        return WordsKey(variant.variantId, details.note.documentSignature, variant.wordCount)
    }

    private fun word(entity: WordEntity) = Word(entity.speaker, entity.startMs, entity.endMs, entity.text)

    private fun activeNoteId(state: RecordingState): String? = when (state) {
        is RecordingState.Active -> state.noteId
        is RecordingState.Finishing -> state.noteId
        else -> null
    }

    private fun emit(effect: NoteEffect) {
        effectChannel.trySend(effect)
    }

    /** Runs [block] on the ViewModel scope; a failure is logged, never thrown at the screen. */
    private fun launch(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Log.w(TAG, "A Note action failed", error)
        }
    }

    override fun onCleared() {
        ports.leavePlayer(noteId)
    }

    companion object {
        /** The words loaded before the rest: more than a screen of turns. */
        const val FIRST_PAGE = 400

        const val SKIP_MS = 10_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val TAG = "CassiniNote"
        private val AMBER = SpeakerTone.Amber

        private const val KEY_SEARCH = "search"
        private const val KEY_EXPORT = "export"
        private const val KEY_NAME_SHEET = "nameSheet"
        private const val KEY_NAME_TEXT = "nameText"
        private const val KEY_STARTED = "started"
        private const val KEY_SHEET = "sheet"
        private const val KEY_DIALOG = "dialog"
        private const val KEY_DIALOG_TITLE = "dialogTitle"
        private const val DIALOG_RENAME = "rename"
        private const val DIALOG_START_OVER = "startOver"
        private const val DIALOG_FRESH = "transcribeFresh"
        private const val DIALOG_STOP_AND_TRASH = "stopAndTrash"
        private const val DIALOG_READ_ONLY = "readOnly:"
    }
}
