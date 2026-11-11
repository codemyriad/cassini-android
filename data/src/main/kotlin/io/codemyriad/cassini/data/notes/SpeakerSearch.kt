package io.codemyriad.cassini.data.notes

import android.database.sqlite.SQLiteDatabase
import io.codemyriad.cassini.core.export.ReadingTurns
import io.codemyriad.cassini.core.format.Word
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable

/**
 * Reuse the actual unicode61 FTS4 matcher for speaker hits, including prefix/phrase/diacritic rules. The scratch
 * index holds one candidate note at a time, never labels or display punctuation, and never changes the catalogue.
 */
internal class SpeakerSearch : Closeable {
    private val database = SQLiteDatabase.create(null).apply {
        execSQL("CREATE VIRTUAL TABLE runs USING fts4(body, tokenize=unicode61 \"remove_diacritics=1\")")
        execSQL("CREATE VIRTUAL TABLE note USING fts4(title, body, tokenize=unicode61 \"remove_diacritics=1\")")
    }

    suspend fun snippet(match: String, words: List<Word>, speakers: Set<String>, bounded: Boolean): String? {
        database.execSQL("DELETE FROM runs")
        val runs = ReadingTurns.wholeRuns(words, bounded)
            .filter { it.speaker in speakers }
            .sortedWith(compareBy({ it.startMs }, { it.wordIndices.first() }))
        database.beginTransaction()
        try {
            database.compileStatement("INSERT INTO runs(body) VALUES (?)").use { insert ->
                for (run in runs) {
                    currentCoroutineContext().ensureActive()
                    insert.bindString(1, run.wordIndices.joinToString(" ") { words[it].text })
                    insert.executeInsert()
                }
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        currentCoroutineContext().ensureActive()
        return database.rawQuery(
            "SELECT snippet(runs, char(2), char(3), '…', 0, 15) FROM runs WHERE runs MATCH ? ORDER BY rowid LIMIT 1",
            arrayOf(match),
        ).use { if (it.moveToFirst()) it.getString(0) else null }
    }

    /** The broad, persistent index may contain old display labels. Validate only title and spoken words. */
    fun note(match: String, title: String, words: List<Word>): SearchExcerpt? {
        database.execSQL("DELETE FROM note")
        database.compileStatement("INSERT INTO note(title, body) VALUES (?, ?)").use { insert ->
            insert.bindString(1, title)
            insert.bindString(2, words.joinToString(" ") { it.text })
            insert.executeInsert()
        }
        return database.rawQuery(
            "SELECT snippet(note, char(2), char(3), '…', 1, 15), " +
                "snippet(note, char(2), char(3), '…', 0, 64) FROM note WHERE note MATCH ?",
            arrayOf(match),
        ).use { if (it.moveToFirst()) SearchExcerpt(it.getString(0), it.getString(1)) else null }
    }

    override fun close() = database.close()
}
