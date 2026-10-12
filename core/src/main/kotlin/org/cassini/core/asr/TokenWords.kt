package org.cassini.core.asr

import org.cassini.core.Failure
import org.cassini.core.format.TimedWord
import org.cassini.core.format.Transcript
import org.cassini.core.format.Word
import org.cassini.core.requireUser
import kotlin.math.roundToLong

/** Recognizer tokens to words: `Transcript.fromTokens` and `timedWordsFromTokens` of 0.1.0, unchanged. */
object TokenWords {
    /** SentencePiece pieces carry seconds, including TDT durations. No guessed alignment. */
    fun fromTokens(tokens: Array<String>, timestamps: FloatArray, durations: FloatArray): Transcript =
        Transcript(timedWordsFromTokens(tokens, timestamps, durations).map { it.word })

    /**
     * [fromTokens] words with their extension cap: the end of the word's last piece, punctuation
     * included. Only a word with a speech-bearing piece gets one; a mark alone has no audio of its
     * own, and its timestamp is the next onset, so a cap would walk it over the next word.
     */
    fun timedWordsFromTokens(tokens: Array<String>, timestamps: FloatArray, durations: FloatArray): List<TimedWord> {
        requireUser(tokens.size == timestamps.size && tokens.size == durations.size, Failure.TIMINGS)
        val words = mutableListOf<TimedWord>()
        val text = StringBuilder()
        var start = 0L
        var speechEnd = 0L
        var pieceEnd = 0L
        var spoken = false
        var previousTimestamp = -1f
        fun flush() {
            val end = maxOf(start, speechEnd)
            if (text.isNotEmpty()) words += TimedWord(Word("spk_1", start, end, text.toString()), if (spoken) maxOf(end, pieceEnd) else end)
            text.clear()
            spoken = false
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
                    pieceEnd = start
                }
                text.append(piece)
                val end = ((timestamp.toDouble() + duration) * 1000).roundToLong()
                pieceEnd = maxOf(pieceEnd, end)
                // Punctuation can be stamped at the NEXT acoustic onset. Preserve its text,
                // but do not stretch the previous word across the intervening silence.
                if (piece.any { it.isLetterOrDigit() }) {
                    spoken = true
                    speechEnd = maxOf(speechEnd, end)
                }
            }
        }
        flush()
        return words
    }
}
