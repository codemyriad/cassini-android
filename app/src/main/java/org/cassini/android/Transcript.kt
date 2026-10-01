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
        /** SentencePiece pieces carry seconds, including TDT durations. No guessed alignment. */
        fun fromTokens(tokens: Array<String>, timestamps: FloatArray, durations: FloatArray): Transcript {
            require(tokens.size == timestamps.size && tokens.size == durations.size) {
                "Parakeet returned incomplete token timings. Cannot export a word-timed transcript."
            }
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
                require(timestamp.isFinite() && duration.isFinite() && timestamp >= 0 && duration >= 0) {
                    "Parakeet returned invalid token timings."
                }
                require(timestamp >= previousTimestamp) { "Parakeet token times went backwards." }
                previousTimestamp = timestamp
                val boundary = original.startsWith('▁') || original.startsWith(' ') || original == "<space>"
                if (boundary) flush()
                val piece = if (original == "<space>") "" else original.removePrefix("▁").trim()
                require(piece.none { it.isWhitespace() || it == '▁' }) {
                    "Unexpected multiword token: cannot assign individual word timings."
                }
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
