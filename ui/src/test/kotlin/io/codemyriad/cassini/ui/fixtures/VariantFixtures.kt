package io.codemyriad.cassini.ui.fixtures

import io.codemyriad.cassini.ui.banner.BannerAction
import io.codemyriad.cassini.ui.banner.BannerArgs
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.banner.boardArgs
import io.codemyriad.cassini.ui.components.DownloadUi
import io.codemyriad.cassini.ui.components.Highlighted
import io.codemyriad.cassini.ui.components.NoteRowUi
import io.codemyriad.cassini.ui.components.NoteStatusUi
import io.codemyriad.cassini.ui.components.ProcessingCardUi
import io.codemyriad.cassini.ui.components.SnackbarKind
import io.codemyriad.cassini.ui.components.SnackbarSpec
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.TurnUi
import io.codemyriad.cassini.ui.components.WaveformLevels
import io.codemyriad.cassini.ui.components.turnText
import io.codemyriad.cassini.ui.format.BatteryUi
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.format.ModelBytes
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.format.ProgressHeadline
import io.codemyriad.cassini.ui.format.StageUi
import io.codemyriad.cassini.ui.format.TimeLeftUi
import io.codemyriad.cassini.ui.library.DaySection
import io.codemyriad.cassini.ui.library.FirstRunHeroUi
import io.codemyriad.cassini.ui.library.FirstRunPrivacy
import io.codemyriad.cassini.ui.library.LibraryDialog
import io.codemyriad.cassini.ui.library.LibrarySheet
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.library.ProgressBannerUi
import io.codemyriad.cassini.ui.library.ProgressLine
import io.codemyriad.cassini.ui.library.RecordingPillUi
import io.codemyriad.cassini.ui.licenses.LicenseEntry
import io.codemyriad.cassini.ui.licenses.LicenseText
import io.codemyriad.cassini.ui.licenses.LicensesUiState
import io.codemyriad.cassini.ui.models.ModelDownloadUiState
import io.codemyriad.cassini.ui.models.ModelPartSizeUi
import io.codemyriad.cassini.ui.note.DocumentKindUi
import io.codemyriad.cassini.ui.note.InfoUi
import io.codemyriad.cassini.ui.note.MergeTargetUi
import io.codemyriad.cassini.ui.note.MergeUi
import io.codemyriad.cassini.ui.note.MergeVersionsUi
import io.codemyriad.cassini.ui.note.NameSheetUi
import io.codemyriad.cassini.ui.note.NoteAction
import io.codemyriad.cassini.ui.note.NoteActionsUi
import io.codemyriad.cassini.ui.note.NoteChipsUi
import io.codemyriad.cassini.ui.note.NoteDialog
import io.codemyriad.cassini.ui.note.NoteMode
import io.codemyriad.cassini.ui.note.NoteSheet
import io.codemyriad.cassini.ui.note.NoteSubtitleUi
import io.codemyriad.cassini.ui.note.NoteUiState
import io.codemyriad.cassini.ui.note.PlaybackUi
import io.codemyriad.cassini.ui.note.ReadOnlyReason
import io.codemyriad.cassini.ui.note.RememberReason
import io.codemyriad.cassini.ui.note.SaveState
import io.codemyriad.cassini.ui.note.TranscribeRowUi
import io.codemyriad.cassini.ui.note.TranscriptMatch
import io.codemyriad.cassini.ui.note.TranscriptSearchUi
import io.codemyriad.cassini.ui.note.TrustUi
import io.codemyriad.cassini.ui.note.VersionKindUi
import io.codemyriad.cassini.ui.note.VersionRowUi
import io.codemyriad.cassini.ui.note.VersionsUi
import io.codemyriad.cassini.ui.note.VoiceOptionUi
import io.codemyriad.cassini.ui.recording.CaptureFormat
import io.codemyriad.cassini.ui.recording.RecordingHint
import io.codemyriad.cassini.ui.recording.RecordingPhase
import io.codemyriad.cassini.ui.settings.LanguageChoice
import io.codemyriad.cassini.ui.settings.ModelRowUi
import io.codemyriad.cassini.ui.settings.ModelStatusUi
import io.codemyriad.cassini.ui.settings.SpeechModelUi
import io.codemyriad.cassini.ui.theme.SpeakerTone
import io.codemyriad.cassini.ui.trash.TrashBackTo
import io.codemyriad.cassini.ui.trash.TrashDialog
import io.codemyriad.cassini.ui.voices.PersonUi
import io.codemyriad.cassini.ui.voices.SavedVoicesDialog
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.time.LocalDate
import java.time.LocalTime

/** A banner with the board copy's arguments (features.md section 13). */
internal fun banner(kind: BannerKind, args: BannerArgs = boardArgs(kind), announce: Boolean = false): BannerSpec =
    BannerSpec(kind, args, announce = announce)

/** Every case-insensitive occurrence of [query] in [text], as search reports it. */
internal fun rangesOf(text: String, query: String): ImmutableList<IntRange> =
    Regex(Regex.escape(query), RegexOption.IGNORE_CASE).findAll(text).map { it.range }.toList().toImmutableList()

/** The search hits of [query] in [turns], in text order. */
internal fun matchesOf(turns: List<TurnUi>, query: String): ImmutableList<TranscriptMatch> =
    turns.flatMap { turn -> rangesOf(turnText(turn.words), query).map { TranscriptMatch(turn.id, it) } }
        .toImmutableList()

/**
 * The variants named in the acceptance lists of docs/design/features.md (sections 1–11) and the no-board specs of
 * ui.md section 8, built from [BoardFixtures] with the same fixed now. Each map goes from the golden name
 * (`variants/<name>.png`, ui.md section 10.2) to the state.
 */
@Suppress("LargeClass") // A data table: one map per screen, in features.md order.
internal object VariantFixtures {
    private val b = BoardFixtures

    // Library (features.md section 1).

    /** Search "delivery": a transcript match with an FTS snippet, and a title-only match without a snippet. */
    val searchResults: ImmutableList<NoteRowUi> = run {
        val snippet = "The problem is the second delivery: so the delivery slips to Thursday"
        persistentListOf(
            NoteRowUi(
                id = "deliveries",
                title = "October deliveries",
                titleRanges = rangesOf("October deliveries", "deliver"),
                durationMs = clock(6, 3),
                time = LocalTime.of(9, 12),
                day = DayLabel.Today,
                status = NoteStatusUi.AudioOnly,
            ),
            b.supplier.copy(
                day = DayLabel.Yesterday,
                preview = Highlighted(
                    text = snippet,
                    ranges = rangesOf(snippet, "deliver"),
                    leadingEllipsis = true,
                    trailingEllipsis = true,
                ),
            ),
        )
    }

    val library: Map<String, LibraryUiState> = mapOf(
        "library-loading" to LibraryUiState(loading = true),
        // L-27 first-run hero (D-975): the shipped state (cloud backup off, transfer on) and the D98 backup-on state.
        "library-empty" to LibraryUiState(hero = FirstRunHeroUi(false, FirstRunPrivacy.PROCESSING)),
        "library-empty-backup" to LibraryUiState(hero = FirstRunHeroUi(true, FirstRunPrivacy.WITH_BACKUP)),
        "library-empty-phone-only" to LibraryUiState(hero = FirstRunHeroUi(false, FirstRunPrivacy.PHONE_ONLY)),
        "library-no-results" to b.library.copy(
            query = "invoice",
            progress = null,
            results = persistentListOf(),
            banners = persistentListOf(banner(BannerKind.LIB_NO_MATCH)),
        ),
        "library-search" to b.library.copy(query = "deliver", progress = null, results = searchResults),
        "library-recording-card" to b.library.copy(
            recording = RecordingPillUi(title = "Standup", elapsedMs = clock(24, 7), paused = false),
        ),
        "library-recording-paused" to b.library.copy(
            recording = RecordingPillUi(title = "Standup", elapsedMs = clock(24, 7), paused = true),
        ),
        "library-progress-calculating" to b.library.copy(
            progress = b.libraryProgress.copy(percent = 3, progress = 0.03f, line = ProgressLine.TimeLeft(TimeLeftUi.Calculating)),
        ),
        "library-progress-waiting" to b.library.copy(
            progress = b.libraryProgress.copy(percent = null, progress = null, line = ProgressLine.Waiting, queued = 0),
        ),
        "library-progress-downloading" to b.library.copy(
            progress = b.libraryProgress.copy(
                percent = 46,
                progress = 0.46f,
                line = ProgressLine.Downloading(ModelPartUi.SPEECH, 46),
                queued = 0,
            ),
        ),
        "library-progress-checking" to b.library.copy(
            progress = b.libraryProgress.copy(percent = 80, progress = 0.8f, line = ProgressLine.Checking(80), queued = 0),
        ),
        "library-progress-standalone" to b.library.copy(
            progress = ProgressBannerUi(
                noteId = null,
                title = null,
                percent = 46,
                progress = 0.46f,
                line = ProgressLine.Downloading(ModelPartUi.SPEECH, 46),
            ),
        ),
        "library-notices" to b.library.copy(
            progress = null,
            banners = persistentListOf(
                banner(BannerKind.IMPORT_TOO_LARGE, announce = true),
                banner(BannerKind.RECOVERED).copy(dismissible = true),
                banner(BannerKind.IMPORTING),
                banner(BannerKind.NOTIFICATIONS_OFF),
            ),
        ),
        "library-snackbar" to b.library.copy(
            snackbar = SnackbarSpec(SnackbarKind.MOVED_TO_TRASH, text = "Call with supplier", undo = true, durationMs = 10_000),
        ),
        "library-actions-sheet" to b.library.copy(sheet = LibrarySheet.Actions(b.noteActions)),
        "library-rename" to b.library.copy(dialog = LibraryDialog.Rename(b.supplier.id, b.supplier.title)),
        "library-other-statuses" to b.library.copy(
            progress = null,
            sections = persistentListOf(
                DaySection(
                    DayLabel.Today,
                    persistentListOf(
                        b.weeklySync.copy(id = "rec", title = "Standup", status = NoteStatusUi.Recording, highlighted = false),
                        b.interview.copy(id = "failed", status = NoteStatusUi.Failed),
                    ),
                ),
                DaySection(
                    DayLabel.Date(LocalDate.of(2026, 10, 6), withYear = false),
                    persistentListOf(
                        b.voiceNote.copy(id = "unplayable", title = "Recovered recording 6 Oct", status = NoteStatusUi.Unplayable),
                        b.supplier.copy(id = "unreadable", preview = null, status = NoteStatusUi.Unreadable),
                        b.supplier.copy(id = "newer", preview = null, status = NoteStatusUi.NewerFormat),
                        b.supplier.copy(id = "silent", preview = null, status = NoteStatusUi.Transcribed(speakers = 0, words = 0)),
                    ),
                ),
            ),
        ),
    )

    // Recording (features.md section 2).

    val recording = mapOf(
        "recording-paused" to b.recording.copy(phase = RecordingPhase.Paused),
        "recording-paused-other-app" to b.recording.copy(
            phase = RecordingPhase.Paused,
            banner = banner(BannerKind.REC_PAUSED_OTHER_APP, announce = true),
        ),
        "recording-permission-off" to b.recording.copy(
            phase = RecordingPhase.NeedsPermission,
            title = "Voice note 9 Oct, 10:30",
            elapsedMs = 0,
            levels = WaveformLevels.Empty,
            hint = null,
            banner = banner(BannerKind.REC_MIC_OFF),
        ),
        "recording-starting" to b.recording.copy(
            phase = RecordingPhase.Starting,
            title = "Voice note 9 Oct, 10:30",
            elapsedMs = 0,
            levels = WaveformLevels.Empty,
        ),
        "recording-stopping" to b.recording.copy(phase = RecordingPhase.Stopping),
        "recording-storage-low" to b.recording.copy(
            hint = RecordingHint.Space(10 * MINUTE),
            banner = banner(BannerKind.REC_STORAGE_LOW, announce = true),
        ),
        "recording-stopped-file-limit" to b.recording.copy(
            phase = RecordingPhase.Stopping,
            format = CaptureFormat.M4A,
            elapsedMs = clock(74, 33, 0),
            banner = banner(BannerKind.REC_STOPPED_FILE_LIMIT, announce = true),
        ),
        "recording-long-hint" to b.recording.copy(elapsedMs = clock(26, 14, 9), hint = RecordingHint.Space(140 * HOUR)),
        "recording-m4a" to b.recording.copy(format = CaptureFormat.M4A, sampleRateHz = 44_100),
    )

    // Note screen (features.md section 3).

    private val boardReviewSubtitle = NoteSubtitleUi(DayLabel.Yesterday, LocalTime.of(11, 0), clock(34, 12))

    /** The read-only draft of the paused Board review prep, at 12:40 of 34:12. */
    private val pausedDraft = persistentListOf(
        b.draftGiulia.copy(
            startMs = clock(11, 58),
            words = timedWords("Let's start with the numbers for the third quarter.", clock(11, 58)),
        ),
        b.draftSpeaker2.copy(
            startMs = clock(12, 21),
            words = timedWords(
                "The board will ask about the delay, so we need one slide on the supplier.",
                clock(12, 21),
            ),
            unsettledFrom = null,
        ),
    )

    private val queuedInterview = NoteUiState(
        noteId = b.interview.id,
        title = b.interview.title,
        subtitle = NoteSubtitleUi(DayLabel.Today, LocalTime.of(8, 40), clock(1, 12, 5)),
        mode = NoteMode.Queued(ahead = 1, overDocument = false),
        playback = PlaybackUi(positionMs = 0, durationMs = clock(1, 12, 5), playing = false),
    )

    private val paused = NoteUiState(
        noteId = b.boardReview.id,
        title = b.boardReview.title,
        subtitle = boardReviewSubtitle,
        mode = NoteMode.Paused(doneMs = clock(12, 40), totalMs = clock(34, 12), hasCheckpoint = true),
        banners = persistentListOf(banner(BannerKind.TX_PAUSED)),
        turns = pausedDraft,
        playback = PlaybackUi(positionMs = 0, durationMs = clock(34, 12), playing = false),
    )

    private val failed = paused.copy(
        mode = NoteMode.Failed(overDocument = false),
        banners = persistentListOf(banner(BannerKind.TX_FAILED, announce = true)),
        turns = persistentListOf(),
    )

    private val startingCard = b.transcribingCard.copy(
        headline = ProgressHeadline.STARTING,
        rangeStartMs = 0,
        rangeEndMs = 0,
        percent = 0,
        stage = StageUi.READING,
        timeLeft = TimeLeftUi.Calculating,
        doneMs = 0,
        battery = BatteryUi.Measuring(now = 80),
        words = 0,
    )

    private val downloadArgs = boardArgs(BannerKind.DL_DOWNLOADING)

    private fun processing(card: ProcessingCardUi, turns: Boolean = false) = b.transcribing.copy(
        mode = NoteMode.Processing(card),
        turns = if (turns) b.transcribing.turns else persistentListOf(),
    )

    val transcribing: Map<String, NoteUiState> = mapOf(
        "transcribing-queued" to queuedInterview,
        "transcribing-queued-next" to queuedInterview.copy(mode = NoteMode.Queued(ahead = 0, overDocument = false)),
        "transcribing-paused" to paused,
        "transcribing-paused-early" to paused.copy(
            mode = NoteMode.Paused(doneMs = 0, totalMs = clock(34, 12), hasCheckpoint = false),
            banners = persistentListOf(banner(BannerKind.TX_PAUSED_EARLY)),
            turns = persistentListOf(),
        ),
        "transcribing-queue-held" to paused.copy(
            banners = persistentListOf(banner(BannerKind.TX_PAUSED), banner(BannerKind.TX_QUEUE_HELD)),
        ),
        "transcribing-downloading" to processing(
            startingCard.copy(
                download = DownloadUi(
                    part = ModelPartUi.SPEECH,
                    percent = requireNotNull(downloadArgs.percent),
                    bytes = downloadArgs.bytes,
                    totalBytes = downloadArgs.bytes2,
                ),
            ),
        ),
        "transcribing-checking" to processing(
            startingCard.copy(
                download = DownloadUi(
                    part = ModelPartUi.SPEECH,
                    percent = 80,
                    bytes = ModelBytes.SPEECH,
                    totalBytes = ModelBytes.SPEECH,
                    checking = true,
                ),
            ),
        ),
        "transcribing-waiting" to processing(
            startingCard.copy(headline = ProgressHeadline.WAITING_FOR_PREVIOUS, battery = BatteryUi.Hidden),
        ),
        "transcribing-starting-low-memory" to processing(startingCard.copy(lowMemory = true)),
        "transcribing-saving" to processing(
            b.transcribingCard.copy(
                headline = ProgressHeadline.SAVING,
                rangeStartMs = 0,
                rangeEndMs = 0,
                percent = 99,
                stage = StageUi.SAVING,
                skippedMatching = true,
                timeLeft = TimeLeftUi.Finishing,
                doneMs = clock(48, 13),
                battery = BatteryUi.PluggedIn(now = 35),
            ),
            turns = true,
        ).copy(readOnlyReason = ReadOnlyReason.SAVING),
        "transcribing-notifications-off" to b.transcribing.copy(notificationsOff = true),
        "transcribing-no-connection" to failed.copy(banners = persistentListOf(banner(BannerKind.DL_NO_CONNECTION, announce = true))),
        "transcribing-failed" to failed,
        "transcribing-failed-checkpoint" to failed.copy(
            banners = persistentListOf(
                banner(BannerKind.TX_FAILED, announce = true)
                    .copy(actions = persistentListOf(BannerAction.TRY_AGAIN, BannerAction.START_OVER)),
            ),
            turns = pausedDraft,
        ),
        "transcribing-memory" to paused.copy(banners = persistentListOf(banner(BannerKind.TX_MEMORY, announce = true))),
        "transcribing-checkpoint-mismatch" to failed.copy(
            banners = persistentListOf(banner(BannerKind.TX_CHECKPOINT_MISMATCH, announce = true)),
        ),
        "note-audio-only" to NoteUiState(
            noteId = b.voiceNote.id,
            title = b.voiceNote.title,
            subtitle = NoteSubtitleUi(DayLabel.Yesterday, LocalTime.of(8, 14), clock(2, 41)),
            mode = NoteMode.AudioOnly,
            banners = persistentListOf(banner(BannerKind.PLAIN_AUDIO)),
            playback = PlaybackUi(positionMs = 0, durationMs = clock(2, 41), playing = false),
        ),
        "note-unplayable" to NoteUiState(
            noteId = "unplayable",
            title = "Recovered recording 6 Oct",
            subtitle = NoteSubtitleUi(DayLabel.Date(LocalDate.of(2026, 10, 6), withYear = false), LocalTime.of(7, 55), clock(5, 12)),
            mode = NoteMode.Unplayable,
            banners = persistentListOf(banner(BannerKind.UNPLAYABLE)),
        ),
    )

    /** Read & listen with trust [trust], its banner [kind] and the speaker renames blocked. */
    private fun untrusted(trust: TrustUi, kind: BannerKind, transcript: Boolean) = b.readAndListen.copy(
        readOnlyReason = ReadOnlyReason.TRUST,
        chips = NoteChipsUi(trust = trust, speakers = if (transcript) 3 else 0),
        banners = persistentListOf(banner(kind)),
        turns = if (transcript) b.readAndListen.turns else persistentListOf(),
        activeWord = if (transcript) b.readAndListen.activeWord else null,
    )

    /** One voice: every word in one placeholder turn. */
    private val oneVoiceTurn = TurnUi(
        id = "t1",
        speakerId = "s1",
        name = SpeakerNameUi.Placeholder(1),
        tone = SpeakerTone.Unnamed,
        startMs = b.readGiulia.startMs,
        words = (b.readGiulia.words + b.readPaolo.words + b.readSpeaker3.words).toImmutableList(),
    )

    val read: Map<String, NoteUiState> = mapOf(
        "read-not-verified" to untrusted(TrustUi.NOT_VERIFIED, BannerKind.TRUST_NOT_VERIFIED, transcript = true)
            .copy(playback = b.readAndListen.playback?.copy(playing = false)),
        "read-audio-changed" to untrusted(TrustUi.AUDIO_CHANGED, BannerKind.TRUST_AUDIO_CHANGED, transcript = true),
        "read-unreadable" to untrusted(TrustUi.UNREADABLE, BannerKind.TRUST_UNREADABLE, transcript = false),
        "read-newer-format" to untrusted(TrustUi.NEWER_FORMAT, BannerKind.TRUST_NEWER_FORMAT, transcript = false),
        "read-verified-banner" to b.readAndListen.copy(banners = persistentListOf(banner(BannerKind.TRUST_VERIFIED))),
        // A document with no words (docs/ui-fidelity.md, device follow-up 5): the "No speech" card, the player.
        "read-no-words" to b.readAndListen.copy(
            chips = NoteChipsUi(trust = TrustUi.VERIFIED, speakers = 0),
            turns = persistentListOf(),
            activeWord = null,
            playback = b.readAndListen.playback?.copy(playing = false, positionMs = 0),
            noWords = true,
        ),
        "read-one-voice" to b.readAndListen.copy(
            chips = NoteChipsUi(trust = TrustUi.VERIFIED, speakers = 1),
            banners = persistentListOf(banner(BannerKind.TX_ONE_VOICE)),
            turns = persistentListOf(oneVoiceTurn),
            activeWord = null,
            playback = b.readAndListen.playback?.copy(playing = false),
        ),
        "read-search" to b.readAndListen.copy(
            search = TranscriptSearchUi(query = "delivery", matches = matchesOf(b.readAndListen.turns, "delivery"), current = 1),
            playback = b.readAndListen.playback?.copy(playing = false),
        ),
        "read-search-no-matches" to b.readAndListen.copy(
            search = TranscriptSearchUi(query = "invoice"),
            playback = b.readAndListen.playback?.copy(playing = false),
        ),
        "read-follow-paused" to b.readAndListen.copy(playback = b.readAndListen.playback?.copy(following = false)),
        "read-queued-over-document" to b.readAndListen.copy(
            subtitle = NoteSubtitleUi(DayLabel.Yesterday, LocalTime.of(15, 10), clock(23, 50)),
            mode = NoteMode.Queued(ahead = 1, overDocument = true),
            readOnlyReason = ReadOnlyReason.JOB_PENDING,
            activeWord = null,
            playback = b.readAndListen.playback?.copy(playing = false),
        ),
        "read-failed-over-document" to b.readAndListen.copy(
            subtitle = NoteSubtitleUi(DayLabel.Yesterday, LocalTime.of(15, 10), clock(23, 50)),
            mode = NoteMode.Failed(overDocument = true),
            banners = persistentListOf(banner(BannerKind.TX_MEMORY)),
            activeWord = null,
            playback = b.readAndListen.playback?.copy(playing = false),
        ),
        "read-snackbar-saved" to b.readAndListen.copy(
            snackbar = SnackbarSpec(SnackbarKind.SAVED_FILE, text = "Call with supplier.txt", durationMs = 10_000),
        ),
        "read-subtitle-format" to b.readAndListen.copy(sheet = NoteSheet.SubtitleFormat),
        "read-rename" to b.readAndListen.copy(dialog = NoteDialog.Rename(b.readAndListen.title)),
        "read-transcribe-fresh" to b.readAndListen.copy(dialog = NoteDialog.TranscribeFresh),
        "read-read-only" to b.readAndListen.copy(dialog = NoteDialog.ReadOnly(ReadOnlyReason.JOB_PENDING)),
        "paused-start-over" to paused.copy(dialog = NoteDialog.StartOver),
        "transcribing-stop-and-trash" to b.transcribing.copy(dialog = NoteDialog.StopAndTrash),
    )

    // Name a speaker (features.md section 4).

    private val annaRicci = VoiceOptionUi(id = "anna", name = "Anna Ricci", likely = true)

    val nameSheet: Map<String, NameSheetUi> = mapOf(
        "name-rename-saved" to b.nameSpeaker.copy(
            label = SpeakerNameUi.Named("Anna Ricci"),
            tone = SpeakerTone.One,
            name = "Anna Rossi",
            samePerson = null,
            renameSaved = "Anna Ricci",
            notName = "Anna Ricci",
        ),
        "name-not-auto" to b.nameSpeaker.copy(
            speakerId = b.readPaolo.speakerId,
            label = SpeakerNameUi.Named("Paolo Ferri"),
            tone = SpeakerTone.Two,
            speakingMs = clock(6, 40),
            quote = b.readPaolo.words.joinToString(" ") { it.text },
            name = "Paolo Ferri",
            voices = persistentListOf(VoiceOptionUi(id = "paolo", name = "Paolo Ferri", likely = true, tone = SpeakerTone.Two)),
            samePerson = null,
            notName = "Paolo Ferri",
            save = SaveState.DISABLED,
        ),
        "name-no-voices" to b.nameSpeaker.copy(
            name = "",
            voices = persistentListOf(),
            samePerson = null,
            save = SaveState.DISABLED,
        ),
        "name-no-print" to b.nameSpeaker.copy(
            speakingMs = 3 * SECOND,
            voices = persistentListOf(
                VoiceOptionUi(id = "anna", name = "Anna Ricci"),
                VoiceOptionUi(id = "annalisa", name = "Annalisa B.", tone = SpeakerTone.Two),
                VoiceOptionUi(id = "giulia", name = "Giulia", tone = SpeakerTone.Four),
            ),
            showAll = true,
            ranked = false,
            samePerson = null,
            remember = false,
            rememberEnabled = false,
            rememberReason = RememberReason.TOO_LITTLE_SPEECH,
            save = SaveState.ENABLED,
        ),
        "name-no-model" to b.nameSpeaker.copy(
            voices = persistentListOf(),
            ranked = false,
            samePerson = null,
            remember = false,
            rememberEnabled = false,
            rememberReason = RememberReason.NO_MODEL,
            save = SaveState.ENABLED,
        ),
        "name-show-all" to b.nameSpeaker.copy(
            name = "",
            voices = persistentListOf(
                annaRicci,
                VoiceOptionUi(id = "annalisa", name = "Annalisa B.", tone = SpeakerTone.Two),
                VoiceOptionUi(id = "marco", name = "Marco", tone = SpeakerTone.Three),
            ),
            showAll = true,
            samePerson = null,
            save = SaveState.DISABLED,
            nameFieldFocused = false,
        ),
        "name-draft" to b.nameSpeaker.copy(
            speakerId = b.draftSpeaker2.speakerId,
            label = SpeakerNameUi.Placeholder(2),
            speakingMs = clock(1, 5),
            quote = b.draftSpeaker2.words.joinToString(" ") { it.text },
            name = "Luca",
            voices = persistentListOf(VoiceOptionUi(id = "luca", name = "Luca", likely = true)),
            samePerson = null,
            save = SaveState.ENABLED,
            draft = true,
            canSample = false,
        ),
        "name-sampling" to b.nameSpeaker.copy(sampling = true),
    )

    // Merge sheet and confirmation (no board; features.md section 4, item 8).

    private val mergeTargets = persistentListOf(
        MergeTargetUi("s1", SpeakerNameUi.Named("Giulia"), SpeakerTone.One, clock(9, 41)),
        MergeTargetUi("s2", SpeakerNameUi.Named("Paolo Ferri"), SpeakerTone.Two, clock(6, 40)),
        MergeTargetUi("s4", SpeakerNameUi.Placeholder(4), SpeakerTone.Unnamed, clock(0, 48)),
    )
    private val merge = MergeUi(
        sourceId = b.readSpeaker3.speakerId,
        source = SpeakerNameUi.Placeholder(3),
        targets = mergeTargets,
        sealed = MergeVersionsUi(shown = 2, added = 3),
    )

    val merges: Map<String, MergeUi> = mapOf(
        "merge-sheet" to merge,
        "merge-confirm-sealed" to merge.copy(confirm = mergeTargets[1]),
        "merge-confirm-draft" to merge.copy(confirm = mergeTargets[0], sealed = null),
    )

    // Versions sheet (no board; features.md section 3.4) and Document info (no board; features.md section 6).

    private fun version(index: Int, kind: VersionKindUi, date: LocalDate, time: LocalTime) =
        VersionRowUi(index = index, number = index + 1, kind = kind, date = date, time = time, words = 4_812)

    val versions = VersionsUi(
        rows = persistentListOf(
            version(0, VersionKindUi.ORIGINAL, LocalDate.of(2026, 10, 8), LocalTime.of(15, 40)).copy(language = "it"),
            version(1, VersionKindUi.MERGED, LocalDate.of(2026, 10, 8), LocalTime.of(16, 2)).copy(fromVersion = 1),
            version(2, VersionKindUi.TRANSCRIBED_AGAIN, LocalDate.of(2026, 10, 9), LocalTime.of(9, 5))
                .copy(words = 0, readable = false),
        ),
        selected = 1,
    )

    val info = InfoUi(
        trust = TrustUi.VERIFIED,
        title = "Call with supplier",
        recordedDate = LocalDate.of(2026, 10, 8),
        recordedTime = LocalTime.of(15, 10),
        durationMs = clock(23, 50),
        versions = 2,
        speakers = 3,
        language = "it",
        processingMs = clock(4, 12),
        realtimeFactor = 11.3,
        model = "Parakeet TDT 0.6B v3, INT8",
        speakersToldApart = true,
        namesFromVoices = 2,
        resumedFromMs = clock(12, 40),
        fileBytes = 11_534_336,
        meetingId = "7f3c9a2e-41d8-4b6e-9c0a-5e2f18d4b7a1",
    )

    val noteSheets: Map<String, NoteUiState> = mapOf(
        "read-versions" to b.readAndListen.copy(sheet = NoteSheet.Versions(versions)),
        "read-document-info" to b.readAndListen.copy(sheet = NoteSheet.Info(info)),
        "read-merge" to b.readAndListen.copy(sheet = NoteSheet.Merge(merge)),
    )

    // Note actions (features.md section 6).

    val noteActions: Map<String, NoteActionsUi> = mapOf(
        "actions-plain" to b.noteActions.copy(
            noteId = b.voiceNote.id,
            title = b.voiceNote.title,
            recordedAt = LocalTime.of(8, 14),
            durationMs = clock(2, 41),
            speakers = 0,
            documentKind = DocumentKindUi.Plain("opus"),
            textExportEnabled = false,
            transcribeRow = TranscribeRowUi.Transcribe,
        ),
        "actions-unreadable" to b.noteActions.copy(
            textExportEnabled = false,
            transcribeRow = TranscribeRowUi.TranscribeAgain(nextVersion = 1, namesCarry = false),
        ),
        "actions-stop" to b.noteActions.copy(
            noteId = b.weeklySync.id,
            title = b.weeklySync.title,
            recordedDay = DayLabel.Today,
            recordedAt = LocalTime.of(10, 2),
            durationMs = clock(48, 13),
            speakers = 0,
            documentKind = DocumentKindUi.Plain("opus"),
            textExportEnabled = false,
            transcribeRow = TranscribeRowUi.Stop,
        ),
        "actions-remove-from-queue" to b.noteActions.copy(transcribeRow = TranscribeRowUi.RemoveFromQueue),
        "actions-resume-or-start-over" to b.noteActions.copy(
            noteId = b.boardReview.id,
            title = b.boardReview.title,
            recordedAt = LocalTime.of(11, 0),
            durationMs = clock(34, 12),
            speakers = 0,
            documentKind = DocumentKindUi.Plain("m4a"),
            textExportEnabled = false,
            transcribeRow = TranscribeRowUi.ResumeOrStartOver,
        ),
        "actions-busy" to b.noteActions.copy(busyRow = NoteAction.SAVE_TEXT),
        "actions-recording" to b.noteActions.copy(
            noteId = "rec",
            title = "Standup",
            recordedDay = DayLabel.Today,
            recordedAt = LocalTime.of(10, 6),
            durationMs = clock(24, 7),
            speakers = 0,
            documentKind = DocumentKindUi.Plain("opus"),
            textExportEnabled = false,
            transcribeRow = TranscribeRowUi.Transcribe,
            recording = true,
        ),
        "actions-no-names-carry" to b.noteActions.copy(
            transcribeRow = TranscribeRowUi.TranscribeAgain(nextVersion = 3, namesCarry = false),
        ),
    )

    // Model download (features.md section 5).

    val modelDownload: Map<String, ModelDownloadUiState> = mapOf(
        "models-parakeet-only" to b.modelDownload.copy(
            parts = persistentListOf(ModelPartSizeUi(ModelPartUi.SPEECH, 465378233L)),
            requiredFreeBytes = 1_157_082_403L, // Pinned speech artifacts + largest packed file + 32 MiB slack.
            withNote = false,
        ),
        "models-no-space" to b.modelDownload.copy(banner = banner(BannerKind.DL_NO_SPACE, announce = true)),
        "models-no-connection" to b.modelDownload.copy(banner = banner(BannerKind.DL_NO_CONNECTION, announce = true)),
        "models-without-note" to b.modelDownload.copy(withNote = false),
        "models-busy" to b.modelDownload.copy(busy = true),
        "models-voices-only" to b.modelDownload.copy(
            parts = persistentListOf(ModelPartSizeUi(ModelPartUi.VOICES, ModelBytes.VOICES)),
            requiredFreeBytes = 60 * MIB,
        ),
        // 640 + 62 MB: the body says "about 702 MB", not the 700 MB of the rounded byte sum.
        "models-speech-speakers" to b.modelDownload.copy(
            parts = persistentListOf(
                ModelPartSizeUi(ModelPartUi.SPEECH, ModelBytes.SPEECH),
                ModelPartSizeUi(ModelPartUi.SPEAKERS, ModelBytes.SPEAKERS),
            ),
        ),
    )

    // Settings (features.md section 11).

    val settings = mapOf(
        "settings-parakeet-suggestion" to b.settings.copy(
            parakeetSuggestion = BannerSpec(BannerKind.PARAKEET_RECOMMENDED, BannerArgs(bytes = 465378233L)),
            models = b.settings.models.map {
                if (it.part == ModelPartUi.SPEECH) it.copy(status = ModelStatusUi.NotDownloaded) else it
            }.toImmutableList(),
        ),
        "settings-parakeet-default" to b.settings.copy(
            speechModel = SpeechModelUi.PARAKEET,
            parakeetNowDefault = true,
        ),
        "settings-included-unavailable" to b.settings.copy(
            speechModel = SpeechModelUi.PARAKEET,
            includedAvailable = false,
        ),
        "settings-notifications-off" to b.settings.copy(notificationsOff = true),
        "settings-downloading" to b.settings.copy(
            speechModel = SpeechModelUi.PARAKEET,
            models = persistentListOf(
                ModelRowUi(ModelPartUi.SPEECH, 440L * 1024 * 1024, ModelStatusUi.Downloading(46)),
                ModelRowUi(ModelPartUi.SPEAKERS, ModelBytes.SPEAKERS, ModelStatusUi.Pending),
                ModelRowUi(ModelPartUi.VOICES, ModelBytes.VOICES, ModelStatusUi.Pending),
            ),
        ),
        "settings-model-statuses" to b.settings.copy(
            models = persistentListOf(
                ModelRowUi(ModelPartUi.SPEECH, 440L * 1024 * 1024, ModelStatusUi.Checking),
                ModelRowUi(ModelPartUi.SPEAKERS, ModelBytes.SPEAKERS, ModelStatusUi.Failed),
                ModelRowUi(ModelPartUi.VOICES, ModelBytes.VOICES, ModelStatusUi.NotDownloaded),
            ),
        ),
        "settings-italian-none" to b.settings.copy(language = LanguageChoice.ITALIAN, voices = 0, trash = 0, version = "0.2.0"),
    )

    // Saved voices (features.md section 10).

    val savedVoices = mapOf(
        "voices-empty" to b.savedVoices.copy(people = persistentListOf()),
        "voices-stale" to b.savedVoices.copy(
            people = b.savedVoices.people.map { if (it.id == "paolo") it.copy(stale = true) else it }.toImmutableList(),
        ),
        "voices-error" to b.savedVoices.copy(readOnly = true),
        "voices-error-empty" to b.savedVoices.copy(readOnly = true, people = persistentListOf()),
        "voices-menu" to b.savedVoices.copy(menuFor = "anna"),
        "voices-rename" to b.savedVoices.copy(dialog = SavedVoicesDialog.Rename("anna", "Anna Ricci")),
        "voices-forget" to b.savedVoices.copy(dialog = SavedVoicesDialog.Forget("giulia", "Giulia")),
        "voices-forget-everyone" to b.savedVoices.copy(dialog = SavedVoicesDialog.ForgetEveryone(4)),
        "voices-under-a-minute" to b.savedVoices.copy(
            people = persistentListOf(
                PersonUi(id = "marta", name = "Marta", notes = 1, confirmedSeconds = 42),
                PersonUi(id = "giulia", name = "Giulia", notes = 31, confirmedSeconds = 75 * 60),
            ),
        ),
    )

    // Trash (features.md section 8).

    private val trashNoSnackbar = b.trash.copy(snackbar = null)

    val trash = mapOf(
        "trash-empty" to trashNoSnackbar.copy(items = persistentListOf()),
        "trash-plain" to trashNoSnackbar,
        "trash-from-settings" to trashNoSnackbar.copy(backTo = TrashBackTo.SETTINGS),
        "trash-delete" to trashNoSnackbar.copy(dialog = TrashDialog.Delete("test-recording", "Test recording")),
        "trash-empty-all" to trashNoSnackbar.copy(dialog = TrashDialog.EmptyAll(2)),
        "trash-yesterday" to trashNoSnackbar.copy(
            items = persistentListOf(
                b.trash.items[0].copy(id = "one-day", deletedOn = DayLabel.Yesterday, daysLeft = 29),
                b.trash.items[0].copy(
                    id = "last-day",
                    deletedOn = DayLabel.Date(LocalDate.of(2026, 9, 10), withYear = false),
                    daysLeft = 1,
                ),
            ),
        ),
    )

    // Licenses (no board; features.md section 11).

    val licenses = mapOf(
        "licenses" to LicensesUiState(entries = LicenseEntry.entries.toImmutableList()),
        "licenses-text" to LicensesUiState(
            entries = LicenseEntry.entries.toImmutableList(),
            open = LicenseText(
                LicenseEntry.CASSINI,
                "GNU GENERAL PUBLIC LICENSE\n\nVersion 3, 29 June 2007",
            ),
        ),
    )

    /** Every variant map, for a test that checks the names are unique across screens. */
    val all: List<Map<String, Any>> = listOf(
        library,
        recording,
        transcribing,
        read,
        nameSheet,
        merges,
        noteSheets,
        noteActions,
        modelDownload,
        settings,
        savedVoices,
        trash,
        licenses,
    )
}
