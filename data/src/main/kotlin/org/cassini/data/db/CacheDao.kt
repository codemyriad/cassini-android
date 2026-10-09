package org.cassini.data.db

import androidx.annotation.WorkerThread
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/**
 * The cache rows derived from a note's document: variants, speakers, words and the searchable text, together with
 * the derived columns of the note row (docs/architecture.md, section 3.1). They are always replaced together, in one
 * transaction with the new document signature.
 */
// Room DAOs are flat lists of queries.
@Suppress("TooManyFunctions")
@Dao
abstract class CacheDao {
    @Upsert
    protected abstract suspend fun upsertNote(note: NoteEntity)

    @Query("DELETE FROM variants WHERE note_id = :noteId")
    protected abstract suspend fun deleteVariants(noteId: String)

    @Insert
    protected abstract suspend fun insertVariants(variants: List<VariantEntity>)

    @Insert
    protected abstract suspend fun insertSpeakers(speakers: List<SpeakerEntity>)

    @Insert
    protected abstract suspend fun insertWords(words: List<WordEntity>)

    @Insert
    protected abstract suspend fun insertText(text: NoteTextEntity)

    @Query("UPDATE note_text SET title = :title, body = :body WHERE note_id = :noteId")
    protected abstract suspend fun updateText(noteId: String, title: String, body: String): Int

    /**
     * Replaces every cache row of one note with [cache], and writes the note row (insert or update; an update never
     * deletes the row, so nothing cascades). Variants are deleted first, which cascades their speakers and words.
     * The searchable text keeps its rowid.
     */
    @Transaction
    open suspend fun replaceCache(cache: NoteCache) {
        val noteId = cache.note.id
        require(cache.variants.all { it.noteId == noteId }) { "variants of another note" }
        require(cache.speakers.all { it.noteId == noteId }) { "speakers of another note" }
        require(cache.words.all { it.noteId == noteId }) { "words of another note" }
        upsertNote(cache.note)
        deleteVariants(noteId)
        insertVariants(cache.variants)
        insertSpeakers(cache.speakers)
        insertWords(cache.words)
        if (updateText(noteId, cache.note.title, cache.body) == 0) {
            insertText(NoteTextEntity(noteId = noteId, title = cache.note.title, body = cache.body))
        }
    }

    /** Words of one variant in canonical order, [limit] from word [from] (the Note screen's first screen). */
    @Query(
        "SELECT * FROM words WHERE note_id = :noteId AND variant_id = :variantId AND idx >= :from ORDER BY idx LIMIT :limit",
    )
    abstract suspend fun words(
        noteId: String,
        variantId: String,
        from: Int = 0,
        limit: Int = Int.MAX_VALUE,
    ): List<WordEntity>

    @WorkerThread
    @Query("SELECT * FROM words WHERE note_id = :noteId AND variant_id = :variantId ORDER BY idx")
    abstract fun wordsBlocking(noteId: String, variantId: String): List<WordEntity>

    /** How many words are cached for a variant; a count below `variants.word_count` means the cache must be rebuilt. */
    @Query("SELECT COUNT(*) FROM words WHERE note_id = :noteId AND variant_id = :variantId")
    abstract suspend fun cachedWordCount(noteId: String, variantId: String): Int

    /** Drops the word cache of a note; the next refresh derives it again from the document. */
    @Query("DELETE FROM words WHERE note_id = :noteId")
    abstract suspend fun dropWords(noteId: String)

    @Query("SELECT * FROM variants WHERE note_id = :noteId ORDER BY ordinal")
    abstract suspend fun variants(noteId: String): List<VariantEntity>

    @Query("SELECT * FROM speakers WHERE note_id = :noteId AND variant_id = :variantId ORDER BY ordinal")
    abstract suspend fun speakers(noteId: String, variantId: String): List<SpeakerEntity>
}
