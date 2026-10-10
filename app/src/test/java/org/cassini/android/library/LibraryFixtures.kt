package org.cassini.android.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.cassini.android.notices.NoticeFacts
import org.cassini.android.notices.TrashedNote
import org.cassini.android.processing.ProcessingState
import org.cassini.android.processing.TranscribeOutcome
import org.cassini.android.recording.RecordingState
import org.cassini.core.format.DocumentState
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobKind
import org.cassini.core.library.JobState
import org.cassini.core.library.Origin
import org.cassini.data.db.JobEntity
import org.cassini.data.db.LibraryRow
import org.cassini.data.db.NoteDetails
import org.cassini.data.db.NoteEntity
import org.cassini.data.db.SearchRow
import org.cassini.data.notes.RenameOutcome
import org.cassini.ui.banner.BannerKind
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** "Now" in the Library tests: Thursday 8 Oct 2026, 10:00 in Rome. */
internal val ROME: ZoneId = ZoneId.of("Europe/Rome")
internal val NOW: Instant = LocalDateTime.of(2026, 10, 8, 10, 0).atZone(ROME).toInstant()
internal val CLOCK: Clock = Clock.fixed(NOW, ROME)

/** Epoch ms of [day] days before today at [hour]:[minute] in Rome. */
internal fun daysAgo(day: Long, hour: Int = 9, minute: Int = 0): Long =
    LocalDateTime.of(2026, 10, 8, hour, minute).minusDays(day).atZone(ROME).toInstant().toEpochMilli()

@Suppress("LongParameterList") // a fixture: every column the tests vary
internal fun libraryRow(
    id: String,
    createdAt: Long = daysAgo(0),
    title: String = "Note $id",
    audioKind: AudioKind = AudioKind.PLAIN,
    documentState: DocumentState? = null,
    jobState: JobState? = null,
    doneMs: Long? = null,
    preview: String? = null,
    words: Int? = null,
    speakers: Int? = null,
    durationMs: Long = 61_000,
) = LibraryRow(
    id = id,
    title = title,
    createdAt = createdAt,
    durationMs = durationMs,
    audioKind = audioKind,
    documentState = documentState,
    preview = preview,
    jobState = jobState,
    jobKind = jobState?.let { JobKind.TRANSCRIBE },
    pauseReason = null,
    failure = null,
    doneMs = doneMs,
    totalMs = null,
    selectedVariant = words?.let { "v1" },
    wordCount = words,
    speakerCount = speakers,
)

/** A document row in [state]. */
internal fun docRow(id: String, state: DocumentState, words: Int? = null, speakers: Int? = null) =
    libraryRow(id, audioKind = AudioKind.DOCUMENT, documentState = state, words = words, speakers = speakers)

internal fun details(row: LibraryRow, job: JobEntity? = null) = NoteDetails(
    note = NoteEntity(
        id = row.id,
        title = row.title,
        createdAt = row.createdAt,
        addedAt = row.createdAt,
        origin = Origin.RECORDED,
        audioFile = if (row.audioKind == AudioKind.DOCUMENT) "document.opus" else "capture.m4a",
        audioKind = row.audioKind,
        durationMs = row.durationMs,
        documentState = row.documentState,
        updatedAt = row.createdAt,
    ),
    variants = emptyList(),
    speakers = emptyList(),
    job = job,
)

internal fun jobOf(id: String, state: JobState, kind: JobKind = JobKind.TRANSCRIBE) =
    JobEntity(noteId = id, seq = 1, kind = kind, state = state, enqueuedAt = 1, updatedAt = 1)

/** A [LibraryPorts] over flows the test sets; it records the actions. */
internal class FakeLibraryPorts : LibraryPorts {
    val rows = MutableStateFlow<List<LibraryRow>>(emptyList())
    val searchRows = MutableStateFlow<Map<String, List<SearchRow>>>(emptyMap())
    val detailsById = MutableStateFlow<Map<String, NoteDetails>>(emptyMap())
    override val processing = MutableStateFlow(ProcessingState(loading = false))
    override val recording: MutableStateFlow<RecordingState> = MutableStateFlow(RecordingState.Idle)
    override val notices = MutableStateFlow(NoticeFacts())
    override val voiceModelReady = MutableStateFlow(true)
    var transcribeOutcome: TranscribeOutcome = TranscribeOutcome.Queued(changed = true)
    val actions = mutableListOf<String>()
    val searches = mutableListOf<String>()

    override fun library(): Flow<List<LibraryRow>> = rows

    override fun search(query: String): Flow<List<SearchRow>> {
        searches += query
        return searchRows.map { it[query].orEmpty() }
    }

    override fun details(id: String): Flow<NoteDetails?> = detailsById.map { it[id] }

    override suspend fun transcribe(id: String, kind: JobKind): TranscribeOutcome {
        actions += "transcribe $id $kind"
        return transcribeOutcome
    }

    override suspend fun startOver(id: String): TranscribeOutcome {
        actions += "startOver $id"
        return transcribeOutcome
    }

    override fun stop(id: String) {
        actions += "stop $id"
    }

    override suspend fun dequeue(id: String) {
        actions += "dequeue $id"
    }

    override suspend fun continueQueue() {
        actions += "continue"
    }

    override fun downloadModels() {
        actions += "download"
    }

    override suspend fun moveToTrash(id: String): Boolean {
        actions += "trash $id"
        return true
    }

    override suspend fun restore(id: String): Boolean {
        actions += "restore $id"
        return true
    }

    override suspend fun rename(id: String, title: String): RenameOutcome {
        actions += "rename $id $title"
        return RenameOutcome.Renamed
    }

    override fun import(uri: String) {
        actions += "import $uri"
    }

    override fun dismissNotice(kind: BannerKind) {
        actions += "dismiss $kind"
    }

    override fun refreshNotices() {
        actions += "refresh"
    }

    override fun snackbarTimeoutMs(): Long = 12_000

    override val trashed = MutableStateFlow<TrashedNote?>(null)

    override fun takeTrashed(note: TrashedNote) {
        trashed.compareAndSet(note, null)
    }

    /** Puts [row] in the library and its details in [detailsById]. */
    fun add(row: LibraryRow, job: JobEntity? = null) {
        rows.value = rows.value + row
        detailsById.value = detailsById.value + (row.id to details(row, job))
    }
}
