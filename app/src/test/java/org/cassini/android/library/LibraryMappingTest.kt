package org.cassini.android.library

import kotlinx.collections.immutable.persistentListOf
import org.cassini.android.processing.InstallState
import org.cassini.android.processing.ProcessingState
import org.cassini.android.processing.QueuedNote
import org.cassini.android.processing.RunningJob
import org.cassini.android.recording.CaptureFormat
import org.cassini.android.recording.RecordingPause
import org.cassini.android.recording.RecordingState
import org.cassini.core.audio.RecordingSpace
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.models.ModelPart
import org.cassini.data.db.SearchRow
import org.cassini.data.db.SpeakerEntity
import org.cassini.data.db.VariantEntity
import org.cassini.engine.job.BatteryLine
import org.cassini.engine.job.DownloadProgress
import org.cassini.engine.job.DraftState
import org.cassini.engine.job.JobPhase
import org.cassini.engine.job.ProgressHeadline
import org.cassini.engine.job.RunningProgress
import org.cassini.engine.job.Step
import org.cassini.engine.job.TimeLeft
import org.cassini.ui.components.Highlighted
import org.cassini.ui.components.NoteStatusUi
import org.cassini.ui.format.DayLabel
import org.cassini.ui.format.ModelPartUi
import org.cassini.ui.format.TimeLeftUi
import org.cassini.ui.library.ProgressBannerUi
import org.cassini.ui.library.ProgressLine
import org.cassini.ui.library.RecordingPillUi
import org.cassini.ui.note.DocumentKindUi
import org.cassini.ui.note.TranscribeRowUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** The Library's pure mapping (features.md section 1): status chips, day groups, previews, banner inputs, sheet. */
class LibraryMappingTest {
    private val context = RowContext()

    @Test
    fun statusChipForEveryRow() {
        val cases = listOf(
            libraryRow("rec", audioKind = AudioKind.LIVE_CAPTURE) to NoteStatusUi.Recording,
            libraryRow("tx", jobState = JobState.RUNNING) to NoteStatusUi.Transcribing,
            libraryRow("q", jobState = JobState.QUEUED) to NoteStatusUi.Queued,
            libraryRow("p", jobState = JobState.PAUSED, doneMs = 760_000) to NoteStatusUi.Paused(760_000),
            libraryRow("f", jobState = JobState.FAILED) to NoteStatusUi.Failed,
            libraryRow("u", audioKind = AudioKind.UNPLAYABLE) to NoteStatusUi.Unplayable,
            docRow("r", DocumentState.UNREADABLE) to NoteStatusUi.Unreadable,
            docRow("m", DocumentState.INVALID_METADATA) to NoteStatusUi.Unreadable,
            docRow("n", DocumentState.UNKNOWN_FORMAT) to NoteStatusUi.NewerFormat,
            docRow("t", DocumentState.OK, words = 40, speakers = 3) to
                NoteStatusUi.Transcribed(3, 40),
            docRow("s", DocumentState.STALE_AUDIO, words = 0, speakers = 0) to
                NoteStatusUi.Transcribed(0, 0),
            libraryRow("a") to NoteStatusUi.AudioOnly,
            libraryRow("c", jobState = JobState.NEEDS_CONSENT) to NoteStatusUi.AudioOnly,
            // A live capture that is not the active one (recovery has not run yet) is not "Recording".
            libraryRow("old", audioKind = AudioKind.LIVE_CAPTURE) to NoteStatusUi.AudioOnly,
        )
        val active = RowContext(activeNoteId = "rec")
        for ((row, status) in cases) assertEquals(row.id, status, LibraryMapping.status(row, active))
    }

    @Test
    fun groupsByLocalDayNewestFirst() {
        val rows = listOf(
            libraryRow("today2", createdAt = daysAgo(0, 9, 30)),
            libraryRow("today1", createdAt = daysAgo(0, 0, 5)),
            libraryRow("yesterday", createdAt = daysAgo(1, 23, 50)),
            libraryRow("monday", createdAt = daysAgo(3)),
            libraryRow("lastYear", createdAt = daysAgo(400)),
        )
        val sections = LibraryMapping.sections(rows, context, NOW, ROME)
        assertEquals(
            listOf(
                DayLabel.Today,
                DayLabel.Yesterday,
                DayLabel.Date(LocalDate.of(2026, 10, 5), withYear = false),
                DayLabel.Date(LocalDate.of(2025, 9, 3), withYear = true),
            ),
            sections.map { it.label },
        )
        assertEquals(listOf("today2", "today1"), sections[0].rows.map { it.id })
        assertEquals(LocalTime.of(9, 30), sections[0].rows[0].time)
        assertNull("day sections carry the day in the header", sections[0].rows[0].day)
    }

    @Test
    fun runningAndRecordingRows() {
        val rec = libraryRow("rec", audioKind = AudioKind.LIVE_CAPTURE)
        val running = libraryRow("run", jobState = JobState.RUNNING)
        val live = RowContext(activeNoteId = "rec", recordingElapsedMs = 1_447_000, runningNoteId = "run")
        val recRow = LibraryMapping.row(rec, live, ROME)
        assertEquals(1_447_000L, recRow.durationMs)
        assertFalse(recRow.highlighted)
        assertTrue(LibraryMapping.row(running, live, ROME).highlighted)
    }

    @Test
    fun searchResultsHighlightTitleAndSnippet() {
        val row = libraryRow("a", createdAt = daysAgo(1))
        val results = LibraryMapping.results(
            listOf(
                SearchRow(row, snippet = "…la \u0002consegna\u0003 slitta…", titleSnippet = "Call with supplier"),
                SearchRow(libraryRow("b"), snippet = "no markers here", titleSnippet = "\u0002Fattura\u0003 marzo"),
            ),
            context,
            NOW,
            ROME,
        )
        val first = results[0]
        assertEquals(DayLabel.Yesterday, first.day)
        assertEquals(Highlighted("la consegna slitta", persistentListOf(3..10), true, true), first.preview)
        assertTrue(first.titleRanges.isEmpty())
        val second = results[1]
        assertEquals("Fattura marzo", second.title)
        assertEquals(listOf(0..6), second.titleRanges)
        assertNull("a title-only match shows no snippet", second.preview)
        assertEquals(DayLabel.Today, second.day)
    }

    @Test
    fun storedPreviewKeepsItsEllipsesAndCutsTheFallbackAtAWord() {
        assertEquals(
            Highlighted("quindi la consegna slitta", leadingEllipsis = true, trailingEllipsis = true),
            LibraryMapping.storedPreview("…quindi la consegna slitta…"),
        )
        assertEquals(Highlighted("Short transcript."), LibraryMapping.storedPreview("Short transcript."))
        assertNull(LibraryMapping.storedPreview(null))
        assertNull(LibraryMapping.storedPreview(""))
        val body = ("parola ".repeat(30)).take(160)
        val cut = LibraryMapping.storedPreview(body)!!
        assertTrue(cut.trailingEllipsis)
        assertFalse(cut.text.endsWith(" "))
        assertTrue(cut.text.length < 160 && body.startsWith(cut.text))
        assertTrue(cut.text.endsWith("parola"))
    }

    @Test
    fun transcriptionBannerInputs() {
        assertNull(LibraryMapping.progress(ProcessingState(loading = false)))
        val progress = RunningProgress(
            headline = ProgressHeadline.FINDING_WORDS_AND_SPEAKERS,
            rangeStartMs = 0,
            rangeEndMs = 0,
            percent = 62,
            step = Step.WORDS_AND_VOICES,
            matchingSkipped = false,
            timeLeft = TimeLeft.Range(360_000, 540_000),
            doneMs = 1,
            totalMs = 2,
            battery = BatteryLine.Unavailable,
            words = 10,
            lowMemory = false,
        )
        val queue = listOf(QueuedNote("q1", "Next"))
        val transcribing = ProcessingState(
            loading = false,
            running = RunningJob("n1", "Weekly", JobPhase.Transcribing(progress, emptyDraft())),
            queue = queue,
        )
        assertEquals(
            ProgressBannerUi("n1", "Weekly", 62, 0.62f, ProgressLine.TimeLeft(TimeLeftUi.Range(360_000, 540_000)), 1),
            LibraryMapping.progress(transcribing),
        )
        val waiting = transcribing.copy(
            running = RunningJob("n1", "Weekly", JobPhase.WaitingForPrevious),
            queue = emptyList(),
        )
        assertEquals(ProgressLine.Waiting, LibraryMapping.progress(waiting)!!.line)
        assertEquals(0, LibraryMapping.progress(waiting)!!.queued)
        val starting = waiting.copy(running = RunningJob("n1", "Weekly", JobPhase.Starting))
        assertEquals(ProgressLine.TimeLeft(TimeLeftUi.Calculating), LibraryMapping.progress(starting)!!.line)
        val download = DownloadProgress(ModelPart.SPEECH, 46, 100, verifying = false)
        val downloading = waiting.copy(running = RunningJob("n1", "Weekly", JobPhase.Downloading(download)))
        assertEquals(ProgressLine.Downloading(ModelPartUi.SPEECH, 46), LibraryMapping.progress(downloading)!!.line)
        val checking = waiting.copy(
            running = RunningJob("n1", "Weekly", JobPhase.Downloading(download.copy(doneBytes = 80, verifying = true))),
        )
        assertEquals(ProgressLine.Checking(80), LibraryMapping.progress(checking)!!.line)

        val standalone = ProcessingState(
            loading = false,
            install = InstallState.Installing(
                parts = setOf(ModelPart.SPEAKERS),
                progress = download.copy(part = ModelPart.SPEAKERS),
                standalone = true,
                waiters = 1,
            ),
            downloading = true,
        )
        val banner = LibraryMapping.progress(standalone)!!
        assertNull("a standalone download opens Settings", banner.noteId)
        assertNull("\"Downloading models\"", banner.title)
        assertEquals(ProgressLine.Downloading(ModelPartUi.SPEAKERS, 46), banner.line)
    }

    private fun emptyDraft() = DraftState(
        words = emptyList(),
        labels = emptyMap(),
        automatic = emptySet(),
        suggestions = emptyList(),
        tentativeFromMs = 0,
        namingOpen = true,
    )

    @Test
    fun recordingCard() {
        assertNull(LibraryMapping.recordingPill(RecordingState.Idle))
        val active = RecordingState.Active(
            noteId = "rec",
            title = "",
            defaultTitle = "Voice note 8 Oct, 08:14",
            format = CaptureFormat.OPUS,
            sampleRateHz = 48_000,
            elapsedMs = 1_447_000,
            pause = null,
            space = RecordingSpace(1),
            warning = null,
        )
        assertEquals(RecordingPillUi("Voice note 8 Oct, 08:14", 1_447_000, false), LibraryMapping.recordingPill(active))
        assertEquals(
            RecordingPillUi("Standup", 1_447_000, true),
            LibraryMapping.recordingPill(active.copy(title = "Standup", pause = RecordingPause.USER)),
        )
    }

    @Test
    fun actionsSheetRows() {
        val plain = details(libraryRow("p"))
        val plainUi = LibraryMapping.actions(plain, null, NOW, ROME, voiceModelReady = true)
        assertEquals(TranscribeRowUi.Transcribe, plainUi.transcribeRow)
        assertEquals(DocumentKindUi.Plain("m4a"), plainUi.documentKind)
        assertFalse(plainUi.textExportEnabled)
        assertEquals(DayLabel.Today, plainUi.recordedDay)

        val doc = details(docRow("d", DocumentState.OK)).copy(
            variants = listOf(
                VariantEntity(noteId = "d", variantId = "v1", ordinal = 0, isDefault = true, speakerCount = 2),
            ),
            speakers = listOf(
                SpeakerEntity("d", "v1", "s1", 0, label = "Luca", isPlaceholder = false),
                SpeakerEntity("d", "v1", "s2", 1, label = "Speaker 2", isPlaceholder = true, placeholderNumber = 2),
            ),
        )
        val docUi = LibraryMapping.actions(doc, null, NOW, ROME, voiceModelReady = true)
        assertEquals(TranscribeRowUi.TranscribeAgain(nextVersion = 2, namesCarry = true), docUi.transcribeRow)
        assertEquals(
            "names carry over only through voiceprints",
            TranscribeRowUi.TranscribeAgain(nextVersion = 2, namesCarry = false),
            LibraryMapping.actions(doc, null, NOW, ROME, voiceModelReady = false).transcribeRow,
        )
        for (state in listOf(DocumentState.UNVERIFIED, DocumentState.UNKNOWN_FORMAT)) {
            val refused = doc.copy(note = doc.note.copy(documentState = state))
            assertTrue(state.name, LibraryMapping.transcribeAgainRefused(refused))
            assertEquals(
                state.name,
                TranscribeRowUi.TranscribeAgainUnavailable,
                LibraryMapping.actions(refused, null, NOW, ROME, voiceModelReady = true).transcribeRow,
            )
        }
        assertFalse(LibraryMapping.transcribeAgainRefused(doc))
        assertEquals(DocumentKindUi.Document, docUi.documentKind)
        assertTrue(docUi.textExportEnabled)
        assertEquals(2, docUi.speakers)

        val states = mapOf(
            JobState.RUNNING to TranscribeRowUi.Stop,
            JobState.QUEUED to TranscribeRowUi.RemoveFromQueue,
            JobState.PAUSED to TranscribeRowUi.ResumeOrStartOver,
            JobState.FAILED to TranscribeRowUi.Transcribe,
        )
        for ((state, row) in states) {
            val job = plain.copy(job = jobOf("p", state))
            val ui = LibraryMapping.actions(job, null, NOW, ROME, voiceModelReady = true)
            assertEquals(state.name, row, ui.transcribeRow)
        }

        val unreadable = details(docRow("u", DocumentState.UNREADABLE))
        assertFalse(LibraryMapping.actions(unreadable, null, NOW, ROME, voiceModelReady = true).textExportEnabled)
        assertTrue(LibraryMapping.needsFreshConfirmation(unreadable))
        assertFalse(LibraryMapping.needsFreshConfirmation(doc))

        val recording = details(libraryRow("r", audioKind = AudioKind.LIVE_CAPTURE))
        assertTrue(LibraryMapping.actions(recording, "r", NOW, ROME, voiceModelReady = true).recording)
        assertEquals(JobKind.TRANSCRIBE, jobOf("x", JobState.QUEUED).kind)
    }
}
