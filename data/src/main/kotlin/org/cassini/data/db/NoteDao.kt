package org.cassini.data.db

import androidx.annotation.WorkerThread
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.cassini.core.library.AudioKind

/** Reads and row writes of `notes` and `note_text` (docs/architecture.md, section 3.3). */
// One DAO per table group; Room DAOs are flat lists of queries.
@Suppress("TooManyFunctions")
@Dao
abstract class NoteDao {
    /** Live notes, newest first, with their job and selected variant. */
    @Query(
        "SELECT ${Sql.LIBRARY_COLUMNS} FROM notes n ${Sql.LIBRARY_JOINS} " +
            "WHERE n.trashed_at IS NULL ORDER BY n.created_at DESC",
    )
    abstract fun libraryFlow(): Flow<List<LibraryRow>>

    /**
     * Live notes matching the FTS4 expression [match] (from `SearchQuery.fts`), newest first, with snippets of the
     * transcript (15 tokens) and of the title. Trashed notes are excluded.
     */
    @Query(
        "SELECT ${Sql.LIBRARY_COLUMNS}, " +
            "snippet(note_fts, char(2), char(3), '…', 1, 15) AS snippet, " +
            "snippet(note_fts, char(2), char(3), '…', 0, 64) AS title_snippet " +
            "FROM note_fts JOIN note_text s ON s.rowid = note_fts.rowid JOIN notes n ON n.id = s.note_id " +
            "${Sql.LIBRARY_JOINS} " +
            "WHERE note_fts MATCH :match AND n.trashed_at IS NULL ORDER BY n.created_at DESC",
    )
    abstract fun searchFlow(match: String): Flow<List<SearchRow>>

    /** Trashed notes, the soonest to expire (the oldest `trashed_at`) first. */
    @Query(
        "SELECT id, title, duration_ms, trashed_at FROM notes WHERE trashed_at IS NOT NULL ORDER BY trashed_at ASC, id",
    )
    abstract fun trashFlow(): Flow<List<TrashRow>>

    /** Ids of trashed notes with `trashed_at < cutoff` (`TrashPolicy.purgeCutoff`), oldest first. */
    @Query("SELECT id FROM notes WHERE trashed_at IS NOT NULL AND trashed_at < :cutoff ORDER BY trashed_at ASC")
    abstract suspend fun expired(cutoff: Long): List<String>

    /** Every trashed note with the time it went to Trash (Empty trash), oldest first. */
    @Query(
        "SELECT id, title, duration_ms, trashed_at FROM notes WHERE trashed_at IS NOT NULL ORDER BY trashed_at ASC, id",
    )
    abstract suspend fun trashed(): List<TrashRow>

    @Transaction
    @Query("SELECT * FROM notes WHERE id = :id")
    abstract fun noteFlow(id: String): Flow<NoteDetails?>

    @Query("SELECT * FROM notes WHERE id = :id")
    abstract suspend fun note(id: String): NoteEntity?

    /** The row for the inference thread (seal time). */
    @WorkerThread
    @Query("SELECT * FROM notes WHERE id = :id")
    abstract fun noteBlocking(id: String): NoteEntity?

    @Query("SELECT * FROM notes")
    abstract suspend fun all(): List<NoteEntity>

    /** A live note whose imported bytes have [sha256] (import dedupe ignores trashed notes). */
    @Query("SELECT id FROM notes WHERE source_sha256 = :sha256 AND trashed_at IS NULL ORDER BY added_at LIMIT 1")
    abstract suspend fun liveWithSource(sha256: String): String?

    /** [liveWithSource] for a caller that holds a note's lock or runs inside a transaction. */
    @WorkerThread
    @Query("SELECT id FROM notes WHERE source_sha256 = :sha256 AND trashed_at IS NULL ORDER BY added_at LIMIT 1")
    abstract fun liveWithSourceBlocking(sha256: String): String?

    /**
     * Inserts an imported note unless a live note already has its `source_sha256`, in one transaction, so two imports
     * of the same file cannot both be added. Returns the id of that live note, or null when [note] was inserted.
     */
    @Transaction
    open suspend fun insertUnlessDuplicate(note: NoteEntity, body: String = ""): String? {
        val existing = note.sourceSha256?.let { liveWithSource(it) }
        if (existing == null) insert(note, body)
        return existing
    }

    @Query("SELECT id FROM notes")
    abstract suspend fun ids(): List<String>

    @Query("SELECT COUNT(*) FROM notes")
    abstract suspend fun noteCount(): Int

    /** Notes whose capture is still marked as being written (crash recovery, architecture 4.7). */
    @Query("SELECT * FROM notes WHERE audio_kind = 'LIVE_CAPTURE' ORDER BY created_at")
    abstract suspend fun liveCaptures(): List<NoteEntity>

    /** Finished Opus captures without a known duration: a sign that the capture file was not closed cleanly. */
    @Query("SELECT * FROM notes WHERE audio_kind = 'PLAIN' AND duration_ms = 0 AND audio_file = 'capture.opus'")
    abstract suspend fun plainCapturesWithoutDuration(): List<NoteEntity>

    /** Ends a live capture as [kind] (PLAIN or UNPLAYABLE) with [durationMs]. Returns 0 unless it was a live capture. */
    @Query(
        "UPDATE notes SET audio_kind = :kind, duration_ms = :durationMs, updated_at = :now " +
            "WHERE id = :id AND audio_kind = 'LIVE_CAPTURE'",
    )
    abstract suspend fun endCapture(id: String, kind: AudioKind, durationMs: Long, now: Long): Int

    @Query("UPDATE notes SET duration_ms = :durationMs, updated_at = :now WHERE id = :id")
    abstract suspend fun setDuration(id: String, durationMs: Long, now: Long)

    @Query(
        "SELECT (SELECT COUNT(*) FROM notes WHERE trashed_at IS NULL) AS live, " +
            "(SELECT COUNT(*) FROM notes WHERE trashed_at IS NOT NULL) AS trashed, " +
            "(SELECT COUNT(*) FROM jobs j JOIN notes n ON n.id = j.note_id " +
            "WHERE j.state = 'QUEUED' AND n.trashed_at IS NULL) AS queued",
    )
    abstract fun counts(): Flow<Counts>

    /** Per voice, the live notes whose selected variant links a speaker to it as AUTO or CONFIRMED. */
    @Query(
        "SELECT l.voice_id, COUNT(DISTINCT l.note_id) AS notes FROM note_voice_links l JOIN notes n ON n.id = l.note_id " +
            "WHERE n.trashed_at IS NULL AND l.voice_id IS NOT NULL AND l.state IN ('AUTO', 'CONFIRMED') " +
            "AND l.variant_id = ${Sql.SELECTED_VARIANT} GROUP BY l.voice_id",
    )
    abstract fun voiceNoteCounts(): Flow<List<VoiceNoteCount>>

    @Insert
    protected abstract suspend fun insertRow(note: NoteEntity)

    @Update
    abstract suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    abstract suspend fun delete(id: String)

    @Insert
    protected abstract suspend fun insertText(text: NoteTextEntity)

    @Query("UPDATE note_text SET title = :title, body = :body WHERE note_id = :noteId")
    protected abstract suspend fun updateText(noteId: String, title: String, body: String): Int

    @Query("UPDATE note_text SET title = :title WHERE note_id = :noteId")
    protected abstract suspend fun updateTextTitle(noteId: String, title: String): Int

    @Query("UPDATE note_text SET body = :body WHERE note_id = :noteId")
    protected abstract suspend fun updateTextBody(noteId: String, body: String): Int

    @Query("SELECT * FROM note_text WHERE note_id = :noteId")
    abstract suspend fun text(noteId: String): NoteTextEntity?

    /** Inserts a new note and its searchable text ([body], "" without a transcript). Fails if the id exists. */
    @Transaction
    open suspend fun insert(note: NoteEntity, body: String = "") {
        insertRow(note)
        putText(note.id, note.title, body)
    }

    /** Writes the searchable text of a note, keeping its rowid (the FTS docid) when it exists. */
    @Transaction
    open suspend fun putText(noteId: String, title: String, body: String) {
        if (updateText(noteId, title, body) == 0) {
            insertText(NoteTextEntity(noteId = noteId, title = title, body = body))
        }
    }

    @Query("UPDATE notes SET title = :title, title_dirty = :titleDirty, updated_at = :now WHERE id = :id")
    protected abstract suspend fun updateTitle(id: String, title: String, titleDirty: Boolean, now: Long): Int

    /** Renames a note: `notes.title` and `note_text.title` change together. Returns false without a row. */
    @Transaction
    open suspend fun rename(id: String, title: String, titleDirty: Boolean, now: Long): Boolean {
        if (updateTitle(id, title, titleDirty, now) == 0) return false
        if (updateTextTitle(id, title) == 0) insertText(NoteTextEntity(noteId = id, title = title, body = ""))
        return true
    }

    @Query("UPDATE notes SET title_dirty = :dirty, updated_at = :now WHERE id = :id")
    abstract suspend fun setTitleDirty(id: String, dirty: Boolean, now: Long)

    @Query("UPDATE notes SET position_ms = :positionMs WHERE id = :id")
    abstract suspend fun setPosition(id: String, positionMs: Long)

    @Query("UPDATE notes SET position_ms = :positionMs, preview = :preview WHERE id = :id")
    abstract suspend fun setPositionAndPreview(id: String, positionMs: Long, preview: String?)

    @Query("UPDATE notes SET selected_variant = :variantId, preview = :preview, updated_at = :now WHERE id = :id")
    protected abstract suspend fun updateSelection(id: String, variantId: String?, preview: String?, now: Long): Int

    /** Selects a variant: the row, the searchable body and the preview change together. */
    @Transaction
    open suspend fun selectVariant(id: String, variantId: String?, body: String, preview: String?, now: Long): Boolean {
        if (updateSelection(id, variantId, preview, now) == 0) return false
        updateTextBody(id, body)
        return true
    }

    /** A finished capture becomes plain audio with its duration. Returns 0 unless the note was a live capture. */
    @Query(
        "UPDATE notes SET audio_kind = 'PLAIN', duration_ms = :durationMs, updated_at = :now " +
            "WHERE id = :id AND audio_kind = 'LIVE_CAPTURE'",
    )
    abstract suspend fun finishRecording(id: String, durationMs: Long, now: Long): Int

    @Query("UPDATE notes SET audio_kind = 'UNPLAYABLE', updated_at = :now WHERE id = :id")
    abstract suspend fun markUnplayable(id: String, now: Long): Int

    @Query("UPDATE notes SET trust_acknowledged = 1 WHERE id = :id")
    abstract suspend fun acknowledgeTrust(id: String)

    @Query("UPDATE notes SET trashed_at = :at, trashed_job = :job, updated_at = :at WHERE id = :id")
    abstract suspend fun moveToTrash(id: String, at: Long, job: TrashedJob?)

    @Query("UPDATE notes SET trashed_at = NULL, trashed_job = NULL, updated_at = :now WHERE id = :id")
    abstract suspend fun restore(id: String, now: Long)

    @Query("UPDATE notes SET trashed_at = NULL, trashed_job = NULL, updated_at = :now WHERE id = :id")
    protected abstract fun restoreBlocking(id: String, now: Long)

    @Query("SELECT * FROM jobs WHERE note_id = :id")
    protected abstract suspend fun jobOf(id: String): JobEntity?

    @Query("DELETE FROM jobs WHERE note_id = :id AND state = 'QUEUED'")
    protected abstract suspend fun deleteQueuedJob(id: String)

    /**
     * Moves a live note to Trash and records its job's state and kind for Restore, in one transaction. Returns false
     * when there is no live note with [id]. A QUEUED job row is deleted (Restore puts it back at the end of the
     * queue, and its checkpoint in `work/` stays); other job rows stay (architecture 5.2 rule 9).
     */
    @Transaction
    open suspend fun trash(id: String, at: Long): Boolean {
        val row = note(id)
        if (row == null || row.trashedAt != null) return false
        moveToTrash(id, at, jobOf(id)?.let { TrashedJob(it.state, it.kind) })
        deleteQueuedJob(id)
        return true
    }

    /** Restores a trashed note in one transaction and returns its row as it was in Trash; null if it was not trashed. */
    @Transaction
    open suspend fun untrash(id: String, now: Long): NoteEntity? {
        val row = note(id)?.takeIf { it.trashedAt != null } ?: return null
        restore(id, now)
        return row
    }

    /** [untrash] for a caller that holds the note's lock (Restore, which the purge must not overlap). */
    @WorkerThread
    @Transaction
    open fun untrashBlocking(id: String, now: Long): NoteEntity? {
        val row = noteBlocking(id)?.takeIf { it.trashedAt != null } ?: return null
        restoreBlocking(id, now)
        return row
    }
}
