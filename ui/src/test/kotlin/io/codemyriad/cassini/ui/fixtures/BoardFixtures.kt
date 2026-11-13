package io.codemyriad.cassini.ui.fixtures

import io.codemyriad.cassini.ui.banner.BannerGroup
import io.codemyriad.cassini.ui.banner.BannerKind
import io.codemyriad.cassini.ui.banner.BannerSpec
import io.codemyriad.cassini.ui.banner.boardArgs
import io.codemyriad.cassini.ui.components.Highlighted
import io.codemyriad.cassini.ui.components.NoteRowUi
import io.codemyriad.cassini.ui.components.NoteStatusUi
import io.codemyriad.cassini.ui.components.ProcessingCardUi
import io.codemyriad.cassini.ui.components.SnackbarKind
import io.codemyriad.cassini.ui.components.SnackbarSpec
import io.codemyriad.cassini.ui.components.SpeakerNameUi
import io.codemyriad.cassini.ui.components.SuggestionUi
import io.codemyriad.cassini.ui.components.TurnUi
import io.codemyriad.cassini.ui.components.WordRef
import io.codemyriad.cassini.ui.components.WordUi
import io.codemyriad.cassini.ui.components.boardWaveformLevels
import io.codemyriad.cassini.ui.format.BatteryUi
import io.codemyriad.cassini.ui.format.DayLabel
import io.codemyriad.cassini.ui.format.ModelBytes
import io.codemyriad.cassini.ui.format.ModelPartUi
import io.codemyriad.cassini.ui.format.ProgressHeadline
import io.codemyriad.cassini.ui.format.StageUi
import io.codemyriad.cassini.ui.format.TimeLeftUi
import io.codemyriad.cassini.ui.library.DaySection
import io.codemyriad.cassini.ui.library.LibraryUiState
import io.codemyriad.cassini.ui.library.ProgressBannerUi
import io.codemyriad.cassini.ui.library.ProgressLine
import io.codemyriad.cassini.ui.models.ModelDownloadUiState
import io.codemyriad.cassini.ui.models.ModelPartSizeUi
import io.codemyriad.cassini.ui.note.DocumentKindUi
import io.codemyriad.cassini.ui.note.NameSheetUi
import io.codemyriad.cassini.ui.note.NoteActionsUi
import io.codemyriad.cassini.ui.note.NoteChipsUi
import io.codemyriad.cassini.ui.note.NoteMode
import io.codemyriad.cassini.ui.note.NoteSubtitleUi
import io.codemyriad.cassini.ui.note.NoteUiState
import io.codemyriad.cassini.ui.note.PlaybackUi
import io.codemyriad.cassini.ui.note.SaveState
import io.codemyriad.cassini.ui.note.TranscribeRowUi
import io.codemyriad.cassini.ui.note.TrustUi
import io.codemyriad.cassini.ui.note.VersionChipUi
import io.codemyriad.cassini.ui.note.VersionKindUi
import io.codemyriad.cassini.ui.note.VoiceOptionUi
import io.codemyriad.cassini.ui.recording.CaptureFormat
import io.codemyriad.cassini.ui.recording.RecordingHint
import io.codemyriad.cassini.ui.recording.RecordingPhase
import io.codemyriad.cassini.ui.recording.RecordingUiState
import io.codemyriad.cassini.ui.settings.BackupSettingsUi
import io.codemyriad.cassini.ui.settings.LanguageChoice
import io.codemyriad.cassini.ui.settings.ModelRowUi
import io.codemyriad.cassini.ui.settings.ModelStatusUi
import io.codemyriad.cassini.ui.settings.SettingsUiState
import io.codemyriad.cassini.ui.settings.SpeechModelUi
import io.codemyriad.cassini.ui.theme.SpeakerTone
import io.codemyriad.cassini.ui.trash.TrashBackTo
import io.codemyriad.cassini.ui.trash.TrashItemUi
import io.codemyriad.cassini.ui.trash.TrashUiState
import io.codemyriad.cassini.ui.voices.PersonUi
import io.codemyriad.cassini.ui.voices.SavedVoicesUiState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.time.LocalDate
import java.time.LocalTime

internal const val SECOND = 1_000L
internal const val MINUTE = 60 * SECOND
internal const val HOUR = 60 * MINUTE
internal const val MIB = 1_048_576L

/** The words of [text] split at spaces, [stepMs] apart from [startMs]. */
internal fun timedWords(text: String, startMs: Long, stepMs: Long = 400): ImmutableList<WordUi> =
    text.split(' ').mapIndexed { index, word -> WordUi(word, startMs + index * stepMs) }.toImmutableList()

/** Clock values written as on the boards: `clock(48, 13)` is 48:13, `clock(1, 12, 5)` is 1:12:05. */
internal fun clock(minutes: Int, seconds: Int): Long = minutes * MINUTE + seconds * SECOND

internal fun clock(hours: Int, minutes: Int, seconds: Int): Long = hours * HOUR + clock(minutes, seconds)

/**
 * One UiState per design board (docs/design/ui.md section 10.2), with the boards' content word for word
 * (the HTML files in design/reference/markup). The fixed now is 9 October 2026, 10:30 in Rome (`BoardTest.FIXED_NOW`): day
 * labels and "days left" are what a ViewModel with that clock would classify.
 */
internal object BoardFixtures {
    // Board 00: Library.

    val weeklySync = NoteRowUi(
        id = "weekly",
        title = "Weekly product sync",
        durationMs = clock(48, 13),
        time = LocalTime.of(10, 2),
        status = NoteStatusUi.Transcribing,
        transcriptionMinutes = 9,
        highlighted = true,
    )
    val interview = NoteRowUi(
        id = "interview",
        title = "Interview — Marta",
        durationMs = clock(1, 12, 5),
        time = LocalTime.of(8, 40),
        status = NoteStatusUi.Queued,
    )

    /**
     * The `PreviewText` excerpt at the saved position 11:44 (board 03's active word "so"): it starts at the
     * playing word with a leading ellipsis and is cut with a trailing one.
     */
    val supplierPreview = Highlighted(
        text = "so the delivery slips to Thursday, but the price stays as agreed in the March order",
        leadingEllipsis = true,
        trailingEllipsis = true,
    )
    val supplier = NoteRowUi(
        id = "supplier",
        title = "Call with supplier",
        durationMs = clock(23, 50),
        time = LocalTime.of(15, 10),
        status = NoteStatusUi.Transcribed(speakers = 3, words = 4_812),
        preview = supplierPreview,
    )
    val boardReview = NoteRowUi(
        id = "board-review",
        title = "Board review prep",
        durationMs = clock(34, 12),
        time = LocalTime.of(11, 0),
        status = NoteStatusUi.Paused(atMs = clock(12, 40)),
    )
    val voiceNote = NoteRowUi(
        id = "voice-note",
        title = "Voice note 8 Oct, 08:14",
        durationMs = clock(2, 41),
        time = LocalTime.of(8, 14),
        status = NoteStatusUi.AudioOnly,
    )

    /** "Transcribing Weekly product sync" 62 %, "About 6–9 min left · 1 more in queue". */
    val libraryProgress = ProgressBannerUi(
        noteId = weeklySync.id,
        title = weeklySync.title,
        percent = 62,
        progress = 0.62f,
        line = ProgressLine.TimeLeft(TimeLeftUi.Range(lowMs = 6 * MINUTE, highMs = 9 * MINUTE)),
        queued = 1,
    )

    val library = LibraryUiState(
        progress = libraryProgress,
        sections = persistentListOf(
            DaySection(DayLabel.Today, persistentListOf(weeklySync, interview)),
            DaySection(DayLabel.Yesterday, persistentListOf(supplier, boardReview, voiceNote)),
        ),
    )

    // Board 01: Recording.

    val recording = RecordingUiState(
        phase = RecordingPhase.Recording,
        title = "Weekly product sync",
        elapsedMs = clock(24, 7),
        levels = boardWaveformLevels(),
        format = CaptureFormat.OPUS,
        sampleRateHz = 48_000,
        hint = RecordingHint.Space(31 * HOUR),
    )

    // Board 02: Transcribing.

    /** Finding words and speakers 29:00–29:45, 62 %, step 3 of 5, 6–9 min, 29:52 / 48:13, 54 % → 41 %, 4,812. */
    val transcribingCard = ProcessingCardUi(
        headline = ProgressHeadline.FINDING_WORDS_AND_SPEAKERS,
        rangeStartMs = clock(29, 0),
        rangeEndMs = clock(29, 45),
        percent = 62,
        stage = StageUi.WORDS_AND_VOICES,
        skippedMatching = false,
        timeLeft = TimeLeftUi.Range(lowMs = 6 * MINUTE, highMs = 9 * MINUTE),
        doneMs = clock(29, 52),
        totalMs = clock(48, 13),
        battery = BatteryUi.Projected(now = 54, atFinish = 41),
        words = 4_812,
        lowMemory = false,
    )

    val draftGiulia = TurnUi(
        id = "d1",
        speakerId = "s1",
        name = SpeakerNameUi.Named("Giulia"),
        tone = SpeakerTone.One,
        startMs = clock(28, 31),
        words = timedWords(
            "So for the October release we keep the import flow as it is, and we move the onboarding changes to " +
                "November.",
            clock(28, 31),
        ),
    )

    /** Speaker 2 with the "Is this Luca?" card; the whole paragraph after the card is unsettled (text4 on the board). */
    val draftSpeaker2 = TurnUi(
        id = "d2",
        speakerId = "s2",
        name = SpeakerNameUi.Placeholder(2),
        tone = SpeakerTone.Unnamed,
        startMs = clock(29, 2),
        words = timedWords(
            "All right, but we must notify support by Friday, otherwise they'll get the tickets " +
                "before the",
            clock(29, 2),
        ),
        unsettledFrom = 0,
        suggestion = SuggestionUi(voiceId = "luca", name = "Luca"),
    )

    val transcribing = NoteUiState(
        noteId = weeklySync.id,
        title = "Weekly product sync",
        subtitle = NoteSubtitleUi(DayLabel.Today, LocalTime.of(10, 2), clock(48, 13)),
        mode = NoteMode.Processing(transcribingCard),
        turns = persistentListOf(draftGiulia, draftSpeaker2),
    )

    // Board 03: Read & listen.

    val readGiulia = TurnUi(
        id = "t1",
        speakerId = "s1",
        name = SpeakerNameUi.Named("Giulia"),
        tone = SpeakerTone.One,
        startMs = clock(11, 32),
        words = timedWords(
            "Ok, then let's confirm the quantities first. We said four hundred units for the first batch, right?",
            clock(11, 32),
        ),
    )

    /** Paolo Ferri, named automatically; word 8, "so", starts at 11:44, the player position. */
    val readPaolo = TurnUi(
        id = "t2",
        speakerId = "s2",
        name = SpeakerNameUi.Named("Paolo Ferri"),
        tone = SpeakerTone.Two,
        startMs = clock(11, 41),
        auto = true,
        words = timedWords(
            "Yes, four hundred. It's the second delivery problem: so the delivery slips to Thursday, but the price " +
                "stays as agreed in the March order.",
            clock(11, 41),
            stepMs = 375,
        ),
    )
    const val ACTIVE_WORD = 8

    val readSpeaker3 = TurnUi(
        id = "t3",
        speakerId = "s3",
        name = SpeakerNameUi.Placeholder(3),
        tone = SpeakerTone.Unnamed,
        startMs = clock(12, 20),
        words = timedWords(
            "Can you send that in writing? I need it for the purchase order before Friday.",
            clock(12, 20),
        ),
    )

    val readAndListen = NoteUiState(
        noteId = supplier.id,
        title = "Call with supplier",
        subtitle = NoteSubtitleUi(DayLabel.Yesterday, LocalTime.of(15, 10), clock(23, 50)),
        mode = NoteMode.Reading,
        chips = NoteChipsUi(
            trust = TrustUi.VERIFIED,
            version = VersionChipUi(index = 2, count = 2, kind = VersionKindUi.MERGED),
            speakers = 3,
        ),
        turns = persistentListOf(readGiulia, readPaolo, readSpeaker3),
        activeWord = WordRef(readPaolo.id, ACTIVE_WORD),
        playback = PlaybackUi(positionMs = clock(11, 44), durationMs = clock(23, 50), playing = true),
    )

    /** L-14: all six named pairs plus the unnamed stone pair, in one real meeting screen. */
    val sevenSpeakers = readAndListen.copy(
        title = "Seven speakers",
        chips = NoteChipsUi(trust = TrustUi.VERIFIED, speakers = 7),
        turns = SpeakerTone.entries.mapIndexed { index, tone ->
            val start = index * 5_000L
            TurnUi(
                id = "seven-$index",
                speakerId = "speaker-$index",
                name = if (tone == SpeakerTone.Unnamed) {
                    SpeakerNameUi.Placeholder(7)
                } else {
                    SpeakerNameUi.Named(listOf("Ada", "Ben", "Chloe", "Dario", "Eva", "Faye")[index])
                },
                tone = tone,
                startMs = start,
                words = timedWords("Ready.", start),
            )
        }.toImmutableList(),
        activeWord = null,
        playback = PlaybackUi(positionMs = 0, durationMs = 35_000, playing = false),
    )

    // Board 04: Name a speaker (Speaker 3 of board 03, "Anna" typed, the same-person question open).

    val nameSpeaker = NameSheetUi(
        speakerId = readSpeaker3.speakerId,
        label = SpeakerNameUi.Placeholder(3),
        tone = SpeakerTone.Unnamed,
        speakingMs = clock(3, 12),
        canSample = true,
        name = "Anna",
        voices = persistentListOf(
            VoiceOptionUi(id = "anna", name = "Anna Ricci", likely = true),
            VoiceOptionUi(id = "annalisa", name = "Annalisa B."),
            VoiceOptionUi(id = "marco", name = "Marco"),
        ),
        ranked = true,
        samePerson = "Anna Ricci",
        remember = true,
        rememberEnabled = true,
        canMerge = true,
        save = SaveState.BLOCKED_BY_QUESTION,
        nameFieldFocused = true,
    )

    // Board 05: Model download (from a note, nothing installed).

    val modelDownload = ModelDownloadUiState(
        parts = persistentListOf(
            ModelPartSizeUi(ModelPartUi.SPEECH, ModelBytes.SPEECH),
            ModelPartSizeUi(ModelPartUi.SPEAKERS, ModelBytes.SPEAKERS),
            ModelPartSizeUi(ModelPartUi.VOICES, ModelBytes.VOICES),
        ),
        requiredFreeBytes = ModelBytes.REQUIRED_FREE,
        withNote = true,
    )

    // Board 06: Note actions and export (Call with supplier).

    val noteActions = NoteActionsUi(
        noteId = supplier.id,
        title = "Call with supplier",
        recordedDay = DayLabel.Yesterday,
        recordedAt = LocalTime.of(15, 10),
        durationMs = clock(23, 50),
        speakers = 3,
        documentKind = DocumentKindUi.Document,
        textExportEnabled = true,
        transcribeRow = TranscribeRowUi.TranscribeAgain(nextVersion = 3, namesCarry = true),
    )

    // Board 07: Saved voices.

    val savedVoices = SavedVoicesUiState(
        people = persistentListOf(
            PersonUi(id = "giulia", name = "Giulia", notes = 14, confirmedSeconds = 22 * 60),
            PersonUi(tone = SpeakerTone.Two, id = "paolo", name = "Paolo Ferri", notes = 6, confirmedSeconds = 9 * 60),
            PersonUi(tone = SpeakerTone.Three, id = "anna", name = "Anna Ricci", notes = 3, confirmedSeconds = 4 * 60),
            PersonUi(
                tone = SpeakerTone.Four,
                id = "luca",
                name = "Luca",
                notes = 2,
                confirmedSeconds = 60,
                stale = true,
            ),
        ),
    )

    // Board 08: Settings (the board's About card reads "Cassini 0.1.0"; since the rename it reads "Cassini talk").

    val settings = SettingsUiState(
        speechModel = SpeechModelUi.INCLUDED,
        includedAvailable = true,
        language = LanguageChoice.PHONE,
        models = persistentListOf(
            ModelRowUi(ModelPartUi.SPEECH, 440L * 1024 * 1024, ModelStatusUi.Ready),
            ModelRowUi(ModelPartUi.SPEAKERS, ModelBytes.SPEAKERS, ModelStatusUi.Ready),
            ModelRowUi(ModelPartUi.VOICES, ModelBytes.VOICES, ModelStatusUi.NotDownloaded),
        ),
        voices = 4,
        trash = 2,
        version = "0.1.0",
        backup = BackupSettingsUi(transfer = true, transferAvailable = true, quotaExceeded = false),
    )

    // Board 09: Trash, with the fixture-only "moved to trash" snackbar (ui.md section 11, item 11).

    val trash = TrashUiState(
        items = persistentListOf(
            TrashItemUi(
                id = "test-recording",
                title = "Test recording",
                durationMs = clock(0, 18),
                deletedOn = DayLabel.Date(LocalDate.of(2026, 10, 2), withYear = false),
                daysLeft = 23,
            ),
            TrashItemUi(
                id = "recovered",
                title = "Recovered recording 6 Oct",
                durationMs = clock(5, 12),
                deletedOn = DayLabel.Today,
                daysLeft = 30,
            ),
        ),
        backTo = TrashBackTo.LIBRARY,
        snackbar = SnackbarSpec(
            kind = SnackbarKind.MOVED_TO_TRASH,
            text = "Call with supplier",
            undo = true,
            durationMs = 10_000,
        ),
    )

    // Board 10: States, banners and errors. The five columns in board order, with the board copy's arguments.

    /** The column headings of board 10 (test copy, as the board writes them). */
    val stateHeadings: Map<BannerGroup, String> = mapOf(
        BannerGroup.TRUST to "Document trust",
        BannerGroup.RECORDING to "Recording",
        BannerGroup.TRANSCRIPTION to "Transcription",
        BannerGroup.DOWNLOAD to "Model download",
        BannerGroup.LIBRARY to "Library and import",
    )

    /**
     * The banners of each column, in board order. RECOVERED and NO_ENCODER are drawn without their close button, as
     * the board (ui.md section 11, item 10).
     */
    val states: Map<BannerGroup, ImmutableList<BannerSpec>> = mapOf(
        BannerGroup.TRUST to column(
            BannerKind.TRUST_VERIFIED,
            BannerKind.TRUST_NOT_VERIFIED,
            BannerKind.TRUST_AUDIO_CHANGED,
            BannerKind.TRUST_UNREADABLE,
            BannerKind.TRUST_NEWER_FORMAT,
            BannerKind.PLAIN_AUDIO,
        ),
        BannerGroup.RECORDING to column(
            BannerKind.REC_PAUSED_OTHER_APP,
            BannerKind.REC_STORAGE_LOW,
            BannerKind.REC_STOPPED_STORAGE,
            BannerKind.REC_MIC_OFF,
            BannerKind.REC_MIC_FAILED,
            BannerKind.RECOVERED,
        ),
        BannerGroup.TRANSCRIPTION to column(
            BannerKind.TX_PAUSED,
            BannerKind.TX_CALCULATING,
            BannerKind.TX_BATTERY,
            BannerKind.TX_ONE_VOICE,
            BannerKind.TX_CHECKPOINT_MISMATCH,
            BannerKind.TX_MEMORY,
        ),
        BannerGroup.DOWNLOAD to column(
            BannerKind.DL_DOWNLOADING,
            BannerKind.DL_CHECKING,
            BannerKind.DL_NO_CONNECTION,
            BannerKind.DL_NO_SPACE,
            BannerKind.DL_FILE_CHECK,
            BannerKind.DL_RECORD_NOW,
        ),
        BannerGroup.LIBRARY to column(
            BannerKind.LIB_EMPTY,
            BannerKind.LIB_NO_MATCH,
            BannerKind.IMPORT_TOO_LARGE,
            BannerKind.IMPORT_NO_SPACE,
            BannerKind.IMPORT_WAV,
            BannerKind.NO_ENCODER,
        ),
    )

    private fun column(vararg kinds: BannerKind): ImmutableList<BannerSpec> =
        kinds.map { BannerSpec(it, boardArgs(it), dismissible = false) }.toImmutableList()

    /** Board slug (the golden name of ui.md section 10.2) to its fixture. */
    val boards: Map<String, Any> = mapOf(
        "00-library" to library,
        "01-recording" to recording,
        "02-transcribing" to transcribing,
        "03-read-and-listen" to readAndListen,
        "04-name-a-speaker" to nameSpeaker,
        "05-model-download" to modelDownload,
        "06-note-actions-and-export" to noteActions,
        "07-saved-voices" to savedVoices,
        "08-settings" to settings,
        "09-trash" to trash,
        "10-states-and-errors" to states,
    )
}
