package org.cassini.core.format

import org.cassini.core.Failure
import org.cassini.core.requireUser
import org.json.JSONArray
import org.json.JSONObject

data class Word(val speaker: String, val startMs: Long, val endMs: Long, val text: String)

/**
 * Decode scaffolding, never transcript data: [capMs] is the furthest end [WordGate] may extend
 * the word to over its speaker's continuing audio, the end of its last piece INCLUDING trailing
 * punctuation. A cap not above the word's end permits no extension.
 */
data class TimedWord(val word: Word, val capMs: Long = 0) {
    val extentCapMs: Long get() = if (capMs > word.endMs) capMs else word.endMs
}

/** Word order is canonical; never sort a Cassini transcript by time. */
/** [language] is empty unless something actually identified it: Parakeet does not report the spoken language. */
data class Transcript(val words: List<Word>, val language: String = "") {
    fun json(): String = JSONObject()
        .put("format", "cassini.words.v1")
        .apply { if (language.isNotEmpty()) put("language", language) }
        .put("wordCount", words.size)
        .put("items", JSONArray().also { items ->
            words.forEach { word ->
                items.put(JSONObject().put("speaker", word.speaker)
                    .put("startMs", word.startMs).put("endMs", word.endMs).put("text", word.text))
            }
        }).toString(2)

    companion object {
        fun fromJson(json: String): Transcript {
            val body = CassiniPayload.json(json)
            requireUser(body.getString("format") == "cassini.words.v1", Failure.TIMINGS)
            require(body.get("format") is String)
            require(!body.has("items") || body.isNull("items") || body.get("items") is JSONArray)
            val items = body.optJSONArray("items") ?: JSONArray()
            val words = (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                require(item.get("speaker") is String && item.get("text") is String)
                val word = Word(item.getString("speaker"), CassiniDocument.number(item, "startMs"), CassiniDocument.number(item, "endMs"), item.getString("text"))
                requireUser(word.startMs >= 0 && word.endMs >= word.startMs && word.text.isNotBlank() && word.speaker.isNotEmpty() && word.text.none { it.isWhitespace() }, Failure.TIMINGS)
                word
            }
            return Transcript(words, body.optString("language", ""))
        }
    }
}
