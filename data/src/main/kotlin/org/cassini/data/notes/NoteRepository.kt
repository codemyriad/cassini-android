package org.cassini.data.notes

import androidx.annotation.WorkerThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.cassini.core.format.DocumentState
import org.cassini.core.format.Word
import org.cassini.core.library.AudioKind
import org.cassini.core.library.JobState
import org.cassini.core.library.Origin
import org.cassini.core.library.PreviewText
import org.cassini.core.library.SearchQuery
import org.cassini.core.library.TrashPolicy
import org.cassini.data.AppPaths
import org.cassini.data.CassiniDispatchers
import org.cassini.data.NoteLocks
import org.cassini.data.Threads
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.Counts
import org.cassini.data.db.LibraryRow
import org.cassini.data.db.NoteDetails
import org.cassini.data.db.NoteEntity
import org.cassini.data.db.SearchRow
import org.cassini.data.db.TrashRow
import org.cassini.data.db.WordEntity
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/** The rules of a note title (features.md section 6, Rename). */
object NoteTitles {
    const val MAX_LENGTH = 200

    /** [input] trimmed, or null when that is blank or longer than [MAX_LENGTH]. */
    fun clean(input: String): String? = input.trim().takeIf { it.isNotEmpty() && it.length <= MAX_LENGTH }

    /**
     * A title taken from a file (a display name, a document's `meeting.title`): [input] trimmed and cut to
     * [MAX_LENGTH], or null when nothing is left.
     */
    fun fit(input: String?): String? = input?.trim()?.take(MAX_LENGTH)?.trim()?.ifEmpty { null }
}

/**
 * Notes as the screens see them (docs/architecture.md, section 4.1): the Library, search, Trash, recording rows, edits
 * of the row and the trash life cycle. `notes.title` is authoritative; documents get it written in the background.
 * Document files are [DocumentRepository]'s; this class delegates adopt and refresh to it.
 */
// The note life cycle is one API for the ViewModels; the queries it wraps are one line each.
@Suppress("TooManyFunctions", "LongParameterList")
class NoteRepository(
    private val database: CassiniDatabase,
    private val paths: AppPaths,
    private val dispatchers: CassiniDispatchers,
    private val locks: NoteLocks,
    private val documents: DocumentRepository,
    /** Runs the document title writes that follow a rename; the application scope in the app. */
    private val background: CoroutineScope,
    private val queue: JobQueueWriter,
    private val cleaner: NoteFilesCleaner,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val notes = database.notes()
    private val cache = database.cache()
    private val jobs = database.jobs()

    // Reads.

    /** Live notes, newest first. */
    fun library(): Flow<List<LibraryRow>> = notes.libraryFlow()

    /**
     * Live notes matching [query] (`SearchQuery.fts`) with snippets. A query with nothing searchable yields the
     * Library, with an empty snippet and the plain title.
     */
    fun search(query: String): Flow<List<SearchRow>> {
        val match = SearchQuery.fts(query) ?: return library().map { rows ->
            rows.map { SearchRow(it, snippet = "", titleSnippet = it.title) }
        }
        return notes.searchFlow(match)
    }

    /** One note with its variants, speakers and job; the Note screen renders from it without reading the document. */
    fun note(id: String): Flow<NoteDetails?> = notes.noteFlow(id)

    /** Cached words of one variant in canonical order, [limit] from word [from]. */
    suspend fun words(id: String, variantId: String, from: Int = 0, limit: Int = Int.MAX_VALUE): List<WordEntity> =
        cache.words(id, variantId, from, limit)

    fun trash(): Flow<List<TrashRow>> = notes.trashFlow()

    fun counts(): Flow<Counts> = notes.counts()

    // Recording.

    /**
     * Creates `notes/<id>/` and a LIVE_CAPTURE row before capture starts; the capture file is `capture.<format>`
     * (`opus` or `m4a`). Returns the new note id.
     */
    suspend fun createRecording(title: String, createdAt: Long, format: String): String {
        val clean = requireNotNull(NoteTitles.clean(title)) { "Invalid title" }
        require(format in CAPTURE_FORMATS) { "Unknown capture format $format" }
        val id = UUID.randomUUID().toString()
        locks.withNoteLock(id, dispatchers.io) { check(paths.note(id).mkdirs()) { "Cannot create the note directory" } }
        val now = clock()
        notes.insert(
            NoteEntity(
                id = id,
                title = clean,
                createdAt = createdAt,
                addedAt = now,
                origin = Origin.RECORDED,
                audioFile = "capture.$format",
                audioKind = AudioKind.LIVE_CAPTURE,
                updatedAt = now,
            ),
        )
        return id
    }

    /** The capture ended: the note becomes plain audio of [durationMs]. False unless it was a live capture. */
    suspend fun finishRecording(id: String, durationMs: Long): Boolean =
        notes.finishRecording(id, durationMs, clock()) > 0

    suspend fun markUnplayable(id: String): Boolean = notes.markUnplayable(id, clock()) > 0

    // Edits.

    /**
     * Renames a note: `notes.title` and `note_text.title` change in one transaction. A DOCUMENT note is marked
     * `title_dirty`; when no job row exists and the document is OK, the title is then written into the document in
     * the background, which clears the mark. Otherwise adopt writes it after the job, and a document that is not OK
     * keeps the new title in Room only (it is never sealed again; a fresh seal later uses the row's title).
     */
    suspend fun rename(id: String, title: String): RenameOutcome {
        val clean = NoteTitles.clean(title) ?: return RenameOutcome.Invalid
        val row = notes.note(id)
        val isDocument = row?.audioKind == AudioKind.DOCUMENT
        return when {
            row == null || !notes.rename(id, clean, titleDirty = isDocument, now = clock()) -> RenameOutcome.NoNote
            else -> {
                if (isDocument && row.documentState == DocumentState.OK && jobs.job(id) == null) {
                    background.launch(dispatchers.io) { documents.retitle(id) }
                }
                RenameOutcome.Renamed
            }
        }
    }

    /**
     * Saves the playback position. With [final] (on pause and on leaving the note) it also stores the Library preview
     * excerpt, `PreviewText` at [positionMs] over the selected variant's cached words.
     */
    suspend fun setPosition(id: String, positionMs: Long, final: Boolean = false) {
        if (!final) {
            notes.setPosition(id, positionMs)
            return
        }
        val preview = PreviewText.of(selectedWords(id), positionMs)?.display
        notes.setPositionAndPreview(id, positionMs, preview)
    }

    /** Selects a readable variant: the row, `note_text.body` and the preview change together. */
    suspend fun selectVariant(id: String, variantId: String): Boolean {
        documents.refreshIfStale(id) // The word cache must be complete before it becomes the searchable body.
        val row = notes.note(id)
        val readable = cache.variants(id).any { it.variantId == variantId && it.readable }
        if (row == null || !readable) return false
        val words = cache.words(id, variantId).map(::word)
        val preview = PreviewText.of(words, row.positionMs)?.display
        return notes.selectVariant(id, variantId, words.joinToString(" ") { it.text }, preview, clock())
    }

    /** "Play anyway" on a document that is not OK. */
    suspend fun acknowledgeTrust(id: String) = notes.acknowledgeTrust(id)

    // Documents.

    /** Installs a sealed job output; see [DocumentRepository.adoptSealed]. */
    suspend fun adoptSealed(id: String, selectNewDefault: Boolean = true): AdoptOutcome =
        documents.adoptSealed(id, selectNewDefault)

    /** Called when the Note screen opens; see [DocumentRepository.refreshIfStale]. */
    suspend fun refreshIfStale(id: String): RefreshOutcome = documents.refreshIfStale(id)

    /** The title at seal time, read on the inference thread. */
    @WorkerThread
    fun titleBlocking(id: String): String? = rowBlocking(id)?.title

    @WorkerThread
    fun rowBlocking(id: String): NoteEntity? {
        Threads.assertOffMain("rowBlocking")
        return notes.noteBlocking(id)
    }

    // Trash (features.md section 8, architecture 4.10).

    /** Moves a live note to Trash and records its job for Restore. The caller stops a running job first. */
    suspend fun moveToTrash(id: String): Boolean = notes.trash(id, clock())

    /**
     * Restores a trashed note. A job that was QUEUED when the note was trashed goes back to the queue at the back;
     * any other job row was kept as it was (a paused note stays paused).
     */
    suspend fun restore(id: String): Boolean {
        val trashed = notes.untrash(id, clock()) ?: return false
        trashed.trashedJob?.takeIf { it.state == JobState.QUEUED }?.let { queue.requeueAtBack(id, it.kind) }
        return true
    }

    /** "Delete now" for one trashed note. False for a live note. */
    suspend fun deleteNow(id: String): Boolean = notes.note(id)?.trashedAt != null && purge(id)

    /** "Empty": deletes every trashed note. Returns how many went. */
    suspend fun emptyTrash(): Int = notes.trashed().count { purge(it) }

    /** Deletes the trashed notes whose 30 days are over at [now] in [zone] (`TrashPolicy`). Returns how many went. */
    suspend fun purgeExpired(now: Instant, zone: ZoneId): Int =
        notes.expired(TrashPolicy.purgeCutoff(now, zone)).count { purge(it) }

    /**
     * Deletes one note in the order of architecture 4.10: the job, the voice files, the note directory, then the row.
     * Files go before the row, so a crash midway leaves a trashed row that the next run finishes, and a catalogue
     * rebuild cannot bring the audio back. When a voice file or the directory cannot be deleted the row stays for the
     * next run.
     */
    private suspend fun purge(id: String): Boolean {
        queue.cancelAndRemove(id)
        val deleted = cleaner.deleteForNote(id) && documents.deleteFiles(id)
        if (deleted) notes.delete(id)
        return deleted
    }

    /** The cached words of the selected variant (else the default, else the first readable one). */
    private suspend fun selectedWords(id: String): List<Word> {
        val selected = notes.note(id)?.selectedVariant
        val variants = cache.variants(id).filter { it.readable }
        val variantId = variants.firstOrNull { it.variantId == selected }?.variantId
            ?: variants.firstOrNull { it.isDefault }?.variantId
            ?: variants.firstOrNull()?.variantId
        return variantId?.let { cache.words(id, it).map(::word) }.orEmpty()
    }

    private fun word(row: WordEntity) = Word(row.speaker, row.startMs, row.endMs, row.text)

    private companion object {
        val CAPTURE_FORMATS = setOf("opus", "m4a")
    }
}
