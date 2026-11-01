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
/**
 * [language] is the BCP-47 tag of the recognizer run that made the words (coordination D53.2), the same in the word
 * body, the descriptor and the provenance. Parakeet does not identify the spoken language, so its runs write
 * [UNDETERMINED]. Empty only where no run is known yet (decoded windows, a body that has no tag).
 */
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
        /** BCP-47 "undetermined": a recognizer run that does not identify the language (Parakeet), D53.2. */
        const val UNDETERMINED = "und"

        /** [tag] when it names a language; null for an empty tag or [UNDETERMINED], which the UI does not show. */
        fun knownLanguage(tag: String?): String? =
            tag?.takeUnless { it.isEmpty() || it.equals(UNDETERMINED, ignoreCase = true) }

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
