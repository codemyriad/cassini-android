package org.cassini.core.checkpoint

import org.cassini.core.format.Word
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * The words of the settled windows of checkpoint version 2. A job keeps them in `work/words.log`, not in
 * checkpoint.json, so a checkpoint has a size that does not grow with the recording: each window appends only its
 * new settled words (docs/architecture.md section 3.5).
 *
 * The log has one line for each word: a compact JSON array `["<speaker>",<startMs>,<endMs>,"<text>"]` and `\n`.
 * The checkpoint names the valid prefix ([WordLogRef]): its first `count` words, in the first `bytes` bytes. Bytes
 * after the prefix are the rest of a write that a crash interrupted, and the reader ignores them.
 */
object WordLog {
    /**
     * A word that ends this long before the start of the window being filled never changes again. Only the merge of
     * the next window changes words, and only in its 2 s overlap (`SeamMerge`, 400 ms tolerance).
     */
    const val SETTLE_MS = 10_000L

    /**
     * How many leading [words] are settled while the window that starts at [windowStartMs] fills: the longest prefix
     * of words that end at or before `windowStartMs - SETTLE_MS`. The scan starts at [from], a count already known
     * to be settled, so each window costs only its new words.
     */
    fun settled(words: List<Word>, windowStartMs: Long, from: Int = 0): Int {
        require(from in 0..words.size)
        val limit = windowStartMs - SETTLE_MS
        var count = from
        while (count < words.size && words[count].endMs <= limit) count++
        return count
    }

    /** The log lines of `words[from until to]`, as UTF-8. */
    fun lines(words: List<Word>, from: Int, to: Int): ByteArray {
        require(from in 0..to && to <= words.size)
        val text = StringBuilder()
        for (i in from until to) {
            val word = words[i]
            text.append(JSONArray().put(word.speaker).put(word.startMs).put(word.endMs).put(word.text)).append('\n')
        }
        return text.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * The words of a log prefix: exactly [count] lines that fill [bytes] exactly. Throws [IllegalArgumentException]
     * for any other content, so a damaged log never resumes.
     */
    fun read(bytes: ByteArray, count: Int): List<Word> {
        require(count >= 0)
        val text = String(bytes, Charsets.UTF_8)
        val words = ArrayList<Word>(count)
        var at = 0
        while (at < text.length) {
            val end = text.indexOf('\n', at)
            require(end >= 0) { "Unterminated word log line" }
            words += word(text.substring(at, end))
            at = end + 1
        }
        require(words.size == count) { "Word log holds ${words.size} words, expected $count" }
        return words
    }

    private fun word(line: String): Word = try {
        val item = JSONTokener(line).nextValue() as? JSONArray ?: throw IllegalArgumentException("Not a word")
        require(item.length() == WORD_MEMBERS) { "Not a word" }
        val speaker = item.get(0) as? String ?: throw IllegalArgumentException("Not a speaker")
        val text = item.get(TEXT) as? String ?: throw IllegalArgumentException("Not a word text")
        val word = Word(speaker, item.getLong(1), item.getLong(2), text)
        require(word.startMs >= 0 && word.endMs >= word.startMs && word.speaker.isNotEmpty() && word.text.isNotEmpty())
        word
    } catch (error: JSONException) {
        throw IllegalArgumentException("Damaged word log line", error)
    }

    private const val WORD_MEMBERS = 4
    private const val TEXT = 3
}

/**
 * The `wordLog` member of checkpoint version 2: the first [count] words of the transcript are the first [bytes]
 * bytes of `work/words.log`.
 */
data class WordLogRef(val count: Int, val bytes: Long) {
    init {
        require(count >= 0 && bytes >= 0 && (count == 0) == (bytes == 0L)) { "Invalid word log reference" }
    }

    fun json(): JSONObject = JSONObject().put("count", count).put("bytes", bytes)

    companion object {
        val EMPTY = WordLogRef(0, 0)

        fun of(json: JSONObject) = WordLogRef(json.getInt("count"), json.getLong("bytes"))
    }
}
