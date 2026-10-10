package org.cassini.android.library

import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.cassini.android.notices.Notices
import org.cassini.android.processing.InstallState
import org.cassini.android.processing.ProcessingState
import org.cassini.android.recording.RecordingState
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.DayGroups
import org.cassini.core.library.DayKey
import org.cassini.core.library.JobState
import org.cassini.core.library.NoteFacts
import org.cassini.core.library.NoteStatus
import org.cassini.core.library.SearchQuery
import org.cassini.data.db.LibraryRow
import org.cassini.data.db.NoteDetails
import org.cassini.data.db.SearchRow
import org.cassini.engine.job.DownloadProgress
import org.cassini.engine.job.JobPhase
import org.cassini.engine.job.TimeLeft
import org.cassini.ui.components.Highlighted
import org.cassini.ui.components.NoteRowUi
import org.cassini.ui.components.NoteStatusUi
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.format.TimeLeftUi
import org.cassini.ui.library.DaySection
import org.cassini.ui.library.ProgressBannerUi
import org.cassini.ui.library.ProgressLine
import org.cassini.ui.library.RecordingPillUi
import org.cassini.ui.note.DocumentKindUi
import org.cassini.ui.note.NoteActionsUi
import org.cassini.ui.note.TranscribeRowUi
import java.time.Instant
import java.time.ZoneId

/**
 * The live facts every Library row needs besides its Room row: the note that records now and its elapsed time, and
 * the note whose job runs now.
 */
data class RowContext(
    val activeNoteId: String? = null,
    val recordingElapsedMs: Long = 0,
    val runningNoteId: String? = null,
)

/**
 * The pure mapping from the catalogue, the coordinator and the recorder to the Library's UiState values
 * (features.md section 1; ui.md section 7). The ViewModel calls it; tests call it directly.
 */
@Suppress("TooManyFunctions") // one mapping per UiState part of the Library
object LibraryMapping {
    /** The status chip of [row] (`NoteStatus.of`, first match wins) with its semantic values. */
    fun status(row: LibraryRow, context: RowContext): NoteStatusUi {
        val facts = NoteFacts(
            audioKind = row.audioKind,
            documentState = row.documentState,
            jobState = row.jobState,
            isActiveCapture = row.id == context.activeNoteId,
        )
        return when (NoteStatus.of(facts)) {
            NoteStatus.RECORDING -> NoteStatusUi.Recording
            NoteStatus.TRANSCRIBING -> NoteStatusUi.Transcribing
            NoteStatus.QUEUED -> NoteStatusUi.Queued
            NoteStatus.PAUSED -> NoteStatusUi.Paused(row.doneMs ?: 0)
            NoteStatus.FAILED -> NoteStatusUi.Failed
            NoteStatus.UNPLAYABLE -> NoteStatusUi.Unplayable
            NoteStatus.UNREADABLE -> NoteStatusUi.Unreadable
            NoteStatus.NEWER_FORMAT -> NoteStatusUi.NewerFormat
            NoteStatus.TRANSCRIBED -> NoteStatusUi.Transcribed(row.speakerCount ?: 0, row.wordCount ?: 0)
            NoteStatus.AUDIO_ONLY -> NoteStatusUi.AudioOnly
        }
    }

    /** A search match of one row: its day, the highlighted title and the snippet that replaces the preview. */
    data class Hit(
        val day: DayLabel,
        val title: String? = null,
        val titleRanges: ImmutableList<IntRange> = persistentListOf(),
        val snippet: Highlighted? = null,
    )

    /** One row; [hit] is set in the flat search results only. */
    fun row(row: LibraryRow, context: RowContext, zone: ZoneId, hit: Hit? = null): NoteRowUi {
        val recording = row.id == context.activeNoteId && row.audioKind == AudioKind.LIVE_CAPTURE
        return NoteRowUi(
            id = row.id,
            title = hit?.title ?: row.title,
            titleRanges = hit?.titleRanges ?: persistentListOf(),
            durationMs = if (recording) context.recordingElapsedMs else row.durationMs,
            time = Instant.ofEpochMilli(row.createdAt).atZone(zone).toLocalTime(),
            day = hit?.day,
            status = status(row, context),
            preview = if (hit != null) hit.snippet else storedPreview(row.preview),
            highlighted = row.id == context.runningNoteId || row.jobState == JobState.RUNNING,
        )
    }

    /** The day sections of [rows] (newest first), grouped by the local day of `created_at` at [now] in [zone]. */
    fun sections(rows: List<LibraryRow>, context: RowContext, now: Instant, zone: ZoneId): ImmutableList<DaySection> =
        DayGroups.group(rows, now, zone) { it.createdAt }
            .map { (key, group) -> DaySection(dayLabel(key), group.map { row(it, context, zone) }.toImmutableList()) }
            .toImmutableList()

    /**
     * The flat search results, newest first, each with its day. The transcript snippet is the preview when it has a
     * match; a title-only match highlights the title and shows no snippet (features.md section 1).
     */
    fun results(rows: List<SearchRow>, context: RowContext, now: Instant, zone: ZoneId): ImmutableList<NoteRowUi> =
        rows.map { result ->
            // A title snippet that FTS cut (a very long title) is not shown; the plain title is, without ranges.
            val title = SearchQuery.highlights(result.titleSnippet)
                .takeUnless { it.text.isEmpty() || it.leadingEllipsis || it.trailingEllipsis }
            val snippet = result.snippet.takeIf { SearchQuery.MATCH_START in it }?.let(::highlighted)
            val hit = Hit(
                day = dayLabel(DayGroups.key(result.row.createdAt, now, zone)),
                title = title?.text,
                titleRanges = title?.ranges.orEmpty().toImmutableList(),
                snippet = snippet,
            )
            row(result.row, context, zone, hit)
        }.toImmutableList()

    fun dayLabel(key: DayKey): DayLabel = when (key) {
        DayKey.Today -> DayLabel.Today
        DayKey.Yesterday -> DayLabel.Yesterday
        is DayKey.Date -> DayLabel.Date(key.date, key.withYear)
    }

    /**
     * The stored preview: `notes.preview` in its display form (`PreviewText`, with its ellipses), or the first 160
     * characters of the transcript that the query falls back to. That fallback is cut at a word here, with a trailing
     * ellipsis, when it fills the 160 characters.
     */
    fun storedPreview(stored: String?): Highlighted? {
        if (stored.isNullOrBlank()) return null
        val parsed = highlighted(stored)
        val fallback = !parsed.leadingEllipsis && !parsed.trailingEllipsis &&
            stored.codePointCount(0, stored.length) >= FALLBACK_PREVIEW_CHARS
        return if (fallback) Highlighted(cutAtWord(stored), trailingEllipsis = true) else parsed
    }

    private fun cutAtWord(text: String): String =
        text.substring(0, text.lastIndexOf(' ').takeIf { it > 0 } ?: text.length)
            .trimEnd { it in CUT_PUNCTUATION || it.isWhitespace() }
            .ifEmpty { text }

    private fun highlighted(text: String): Highlighted {
        val parsed = SearchQuery.highlights(text)
        return Highlighted(
            text = parsed.text,
            ranges = parsed.ranges.toImmutableList(),
            leadingEllipsis = parsed.leadingEllipsis,
            trailingEllipsis = parsed.trailingEllipsis,
        )
    }

    // The activity area. --------------------------------------------------------------------------------------

    /** The recording card and the Record button's recording form, while a capture is Active. */
    fun recordingPill(state: RecordingState): RecordingPillUi? = (state as? RecordingState.Active)?.let {
        RecordingPillUi(it.title.ifBlank { it.defaultTitle }, elapsedMs = it.elapsedMs, paused = it.pause != null)
    }

    /**
     * The transcription banner while a job runs or a standalone download runs (features.md section 1, item 4). The
     * queue suffix counts the QUEUED notes after the running one.
     */
    fun progress(state: ProcessingState): ProgressBannerUi? {
        val running = state.running ?: return standaloneDownload(state)
        return when (val phase = running.phase) {
            JobPhase.Starting, JobPhase.Stopping -> ProgressBannerUi(
                noteId = running.noteId,
                title = running.title,
                percent = null,
                progress = null,
                line = ProgressLine.TimeLeft(
                    if (phase == JobPhase.Stopping) TimeLeftUi.Finishing else TimeLeftUi.Calculating,
                ),
                queued = state.waiting,
            )
            JobPhase.WaitingForPrevious -> ProgressBannerUi(
                running.noteId,
                running.title,
                null,
                null,
                ProgressLine.Waiting,
                state.waiting,
            )
            is JobPhase.Downloading -> {
                val line = downloadLine(phase.progress)
                ProgressBannerUi(running.noteId, running.title, null, fill(line), line, state.waiting)
            }
            is JobPhase.Transcribing -> ProgressBannerUi(
                noteId = running.noteId,
                title = running.title,
                percent = phase.progress.percent,
                progress = phase.progress.percent?.let { it / PERCENT },
                line = ProgressLine.TimeLeft(timeLeft(phase.progress.timeLeft)),
                queued = state.waiting,
            )
        }
    }

    /** The banner of a download from Settings or a banner: no note, "Downloading models". */
    private fun standaloneDownload(state: ProcessingState): ProgressBannerUi? {
        val install = state.install as? InstallState.Installing
        if (!state.downloading && install?.standalone != true) return null
        val line = downloadLine(install?.progress)
        return ProgressBannerUi(null, null, percent = null, progress = fill(line), line = line, queued = state.waiting)
    }

    private fun downloadLine(progress: DownloadProgress?): ProgressLine = when {
        progress == null -> ProgressLine.Downloading(ModelPartUi.SPEECH, 0)
        progress.verifying -> ProgressLine.Checking(progress.percent)
        else -> ProgressLine.Downloading(Notices.partUi(progress.part), progress.percent)
    }

    private fun fill(line: ProgressLine): Float? = when (line) {
        is ProgressLine.Downloading -> line.percent / PERCENT
        is ProgressLine.Checking -> line.percent / PERCENT
        else -> null
    }

    fun timeLeft(timeLeft: TimeLeft): TimeLeftUi = when (timeLeft) {
        TimeLeft.Calculating -> TimeLeftUi.Calculating
        TimeLeft.Finishing -> TimeLeftUi.Finishing
        is TimeLeft.Range -> TimeLeftUi.Range(timeLeft.lowMs, timeLeft.highMs)
    }

    // The Note actions sheet. ---------------------------------------------------------------------------------

    /** The Note actions sheet of [details] (features.md section 6) as the Library opens it. */
    fun actions(details: NoteDetails, activeNoteId: String?, now: Instant, zone: ZoneId): NoteActionsUi {
        val note = details.note
        val selected = details.variants.firstOrNull { it.variantId == note.selectedVariant }
            ?: details.variants.firstOrNull { it.isDefault }
            ?: details.variants.minByOrNull { it.ordinal }
        val document = note.audioKind == AudioKind.DOCUMENT
        val readable = note.documentState !in UNREADABLE_STATES
        val namesCarry = details.speakers.any { it.variantId == selected?.variantId && !it.isPlaceholder }
        return NoteActionsUi(
            noteId = note.id,
            title = note.title,
            recordedDay = dayLabel(DayGroups.key(note.createdAt, now, zone)),
            recordedAt = Instant.ofEpochMilli(note.createdAt).atZone(zone).toLocalTime(),
            durationMs = note.durationMs,
            speakers = if (document) selected?.speakerCount ?: 0 else 0,
            documentKind = if (document) {
                DocumentKindUi.Document
            } else {
                DocumentKindUi.Plain(note.audioFile.substringAfterLast('.', "opus"))
            },
            textExportEnabled = document && readable && selected?.readable == true,
            transcribeRow = transcribeRow(details, namesCarry),
            recording = note.audioKind == AudioKind.LIVE_CAPTURE && note.id == activeNoteId,
        )
    }

    /** The transcribe row of the sheet for the job state of [details] (features.md section 6). */
    private fun transcribeRow(details: NoteDetails, namesCarry: Boolean): TranscribeRowUi = when (details.job?.state) {
        JobState.RUNNING -> TranscribeRowUi.Stop
        JobState.QUEUED -> TranscribeRowUi.RemoveFromQueue
        JobState.PAUSED -> TranscribeRowUi.ResumeOrStartOver
        JobState.FAILED, JobState.NEEDS_CONSENT, null -> if (details.note.audioKind == AudioKind.DOCUMENT) {
            TranscribeRowUi.TranscribeAgain(details.variants.size + 1, namesCarry)
        } else {
            TranscribeRowUi.Transcribe
        }
    }

    /** Whether Transcribe again on [details] makes a fresh document, which needs a confirmation first. */
    fun needsFreshConfirmation(details: NoteDetails): Boolean =
        details.note.audioKind == AudioKind.DOCUMENT && details.note.documentState in FRESH_STATES

    private val UNREADABLE_STATES =
        setOf(DocumentState.UNREADABLE, DocumentState.INVALID_METADATA, DocumentState.UNKNOWN_FORMAT)

    /** Documents that Transcribe again replaces with a fresh one (features.md section 6). */
    private val FRESH_STATES =
        setOf(DocumentState.STALE_AUDIO, DocumentState.UNREADABLE, DocumentState.INVALID_METADATA)

    private const val PERCENT = 100f
    private const val FALLBACK_PREVIEW_CHARS = 160
    private const val CUT_PUNCTUATION = ".,;:!?…"
}
