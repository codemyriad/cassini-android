package org.cassini.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

/**
 * `note_voice_links`, the Room mirror of the matches in `voices/notes/<id>.json`. Every write of a per-note voice
 * file rewrites that note's links; startup re-syncs all of them (docs/architecture.md, section 4.8).
 */
@Dao
abstract class VoiceLinkDao {
    @Query("SELECT * FROM note_voice_links WHERE note_id = :noteId ORDER BY variant_id, speaker_id")
    abstract suspend fun links(noteId: String): List<NoteVoiceLinkEntity>

    @Query("SELECT * FROM note_voice_links ORDER BY note_id, variant_id, speaker_id")
    abstract suspend fun all(): List<NoteVoiceLinkEntity>

    @Insert
    protected abstract suspend fun insert(links: List<NoteVoiceLinkEntity>)

    @Query("DELETE FROM note_voice_links WHERE note_id = :noteId")
    abstract suspend fun deleteForNote(noteId: String)

    /** Removes every link to [voiceId] (the person was forgotten). */
    @Query("DELETE FROM note_voice_links WHERE voice_id = :voiceId")
    abstract suspend fun deleteForVoice(voiceId: String)

    @Query("DELETE FROM note_voice_links")
    abstract suspend fun clear()

    @Query("SELECT id FROM notes")
    protected abstract suspend fun noteIds(): List<String>

    /** Replaces the links of one note with [links] in one transaction. */
    @Transaction
    open suspend fun replaceForNote(noteId: String, links: List<NoteVoiceLinkEntity>) {
        require(links.all { it.noteId == noteId }) { "links of another note" }
        deleteForNote(noteId)
        insert(links)
    }

    /**
     * Replaces every link with [links] in one transaction, skipping links of notes without a row. Returns the note
     * ids that were skipped, so the caller can delete their voice files.
     */
    @Transaction
    open suspend fun replaceAll(links: List<NoteVoiceLinkEntity>): Set<String> {
        val notes = noteIds().toHashSet()
        val (kept, orphans) = links.partition { it.noteId in notes }
        clear()
        insert(kept)
        return orphans.mapTo(HashSet()) { it.noteId }
    }
}
