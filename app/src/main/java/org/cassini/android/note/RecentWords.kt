package org.cassini.android.note

import org.cassini.core.format.Word

/**
 * The full word lists of the notes that were opened last in this process (FAST NOTE OPENING). A later open of the same
 * version of a note shows all its words at once: it reads no word cache rows and has no first page. A key holds the
 * document signature and the word count, so a changed document or a new version never gives old words. Thread-safe.
 */
class RecentWords(private val capacity: Int = DEFAULT_CAPACITY) {
    data class Key(val noteId: String, val variantId: String, val signature: String?, val words: Int)

    private val entries = LinkedHashMap<Key, List<Word>>(capacity + 1, LOAD_FACTOR, true)

    @Synchronized
    fun get(key: Key): List<Word>? = entries[key]

    @Synchronized
    fun put(key: Key, words: List<Word>) {
        entries[key] = words
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    private companion object {
        const val DEFAULT_CAPACITY = 2
        const val LOAD_FACTOR = 0.75f
    }
}
