package org.cassini.android

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToLong

data class Word(val speaker: String, val startMs: Long, val endMs: Long, val text: String)

/** Word order is canonical; never sort a Cassini transcript by time. */
data class Transcript(val words: List<Word>, val language: String = "it") {
    fun json(): String = JSONObject()
        .put("format", "cassini.words.v1")
        .put("language", language)
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

        /** SentencePiece pieces carry seconds, including TDT durations. No guessed alignment. */
        fun fromTokens(tokens: Array<String>, timestamps: FloatArray, durations: FloatArray): Transcript {
            requireUser(tokens.size == timestamps.size && tokens.size == durations.size, Failure.TIMINGS)
            val words = mutableListOf<Word>()
            val text = StringBuilder()
            var start = 0L
            var speechEnd = 0L
            var previousTimestamp = -1f
            fun flush() {
                if (text.isNotEmpty()) words += Word("spk_1", start, maxOf(start, speechEnd), text.toString())
                text.clear()
            }
            tokens.forEachIndexed { i, original ->
                val timestamp = timestamps[i]
                val duration = durations[i]
                requireUser(timestamp.isFinite() && duration.isFinite() && timestamp >= 0 && duration >= 0, Failure.TIMINGS)
                requireUser(timestamp >= previousTimestamp, Failure.TIMINGS)
                previousTimestamp = timestamp
                val boundary = original.startsWith('▁') || original.startsWith(' ') || original == "<space>"
                if (boundary) flush()
                val piece = if (original == "<space>") "" else original.removePrefix("▁").trim()
                requireUser(piece.none { it.isWhitespace() || it == '▁' }, Failure.TIMINGS)
                if (piece.isNotEmpty()) {
                    if (text.isEmpty()) {
                        start = (timestamp.toDouble() * 1000).roundToLong()
                        speechEnd = start
                    }
                    text.append(piece)
                    // Punctuation can be stamped at the NEXT acoustic onset. Preserve its text,
                    // but do not stretch the previous word across the intervening silence.
                    if (piece.any { it.isLetterOrDigit() }) {
                        speechEnd = maxOf(speechEnd, ((timestamp.toDouble() + duration) * 1000).roundToLong())
                    }
                }
            }
            flush()
            return Transcript(words)
        }
    }
}
