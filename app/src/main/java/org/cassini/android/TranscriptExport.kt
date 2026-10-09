package org.cassini.android

import java.util.Locale

/**
 * Plain copies of a transcript for people and players that do not read Cassini documents: text, SubRip and WebVTT.
 * They carry the words, their timing and the speaker names only; never voiceprints, provenance or other variants.
 * Word order is canonical, as in [Transcript]; cues follow it.
 */
object TranscriptExport {
    enum class Format(val extension: String, val mime: String) {
        TEXT("txt", "text/plain"), SRT("srt", "application/x-subrip"), VTT("vtt", "text/vtt")
    }

    /** One run of words by one speaker, or one subtitle cue. */
    internal data class Cue(val speaker: String, val startMs: Long, val endMs: Long, val text: String)

    /** Longest cue, longest line of subtitle text, and the silence that starts a new cue. */
    private const val CUE_MS = 6000L
    private const val CUE_CHARS = 84
    private const val GAP_MS = 1500L

    /** [label] names a speaker id; null leaves names out, as for a single unnamed voice. */
    fun render(format: Format, words: List<Word>, label: ((String) -> String)?): String = when (format) {
        Format.TEXT -> text(words, label)
        Format.SRT -> cues(words).mapIndexed { index, cue ->
            "${index + 1}\n${time(cue.startMs, ',')} --> ${time(cue.endMs, ',')}\n${label?.let { "${it(cue.speaker)}: " }.orEmpty()}${cue.text}\n"
        }.joinToString("\n")
        Format.VTT -> "WEBVTT\n\n" + cues(words).joinToString("\n") { cue ->
            "${time(cue.startMs, '.')} --> ${time(cue.endMs, '.')}\n${label?.let { "<v ${escape(it(cue.speaker))}>" }.orEmpty()}${escape(cue.text)}\n"
        }
    }

    /** Paragraphs per speaker turn, each headed by its name and start time when there are names. */
    private fun text(words: List<Word>, label: ((String) -> String)?): String = turns(words).joinToString("\n\n") { turn ->
        (label?.let { "${it(turn.speaker)} [${clock(turn.startMs)}]\n" }.orEmpty()) + turn.text
    } + "\n"

    internal fun turns(words: List<Word>): List<Cue> = group(words) { current, word -> current.speaker == word.speaker }

    internal fun cues(words: List<Word>): List<Cue> = group(words) { current, word ->
        val sentenceEnded = current.text.lastOrNull() in listOf('.', '?', '!') && current.endMs - current.startMs >= 1500
        current.speaker == word.speaker && word.startMs - current.endMs <= GAP_MS && word.endMs - current.startMs <= CUE_MS &&
            current.text.length + 1 + word.text.length <= CUE_CHARS && !sentenceEnded
    }

    private fun group(words: List<Word>, joins: (Cue, Word) -> Boolean): List<Cue> {
        val out = mutableListOf<Cue>()
        for (word in words) {
            val text = word.text.trim()
            if (text.isEmpty()) continue
            val last = out.lastOrNull()
            if (last != null && joins(last, word)) out[out.lastIndex] = last.copy(endMs = maxOf(last.endMs, word.endMs), text = "${last.text} $text")
            else out += Cue(word.speaker, word.startMs, maxOf(word.endMs, word.startMs), text)
        }
        // A cue never ends after the next one starts.
        return out.mapIndexed { i, cue -> out.getOrNull(i + 1)?.let { next -> cue.copy(endMs = cue.endMs.coerceAtMost(maxOf(next.startMs, cue.startMs))) } ?: cue }
    }

    internal fun time(ms: Long, separator: Char): String {
        val t = ms.coerceAtLeast(0)
        return String.format(Locale.ROOT, "%02d:%02d:%02d%c%03d", t / 3_600_000, t / 60_000 % 60, t / 1000 % 60, separator, t % 1000)
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
