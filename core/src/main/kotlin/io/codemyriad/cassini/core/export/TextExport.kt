package io.codemyriad.cassini.core.export

/** The plain-text transcript export (`.txt`, features.md section 9). */
object TextExport {
    /**
     * The title line, the [subtitle] line ("8 Oct 2026, 15:10 · 23:50 · 3 speakers", made by the caller), a blank
     * line, then one block per turn: `[<clock>] <label>` and the turn's words on the next line. Blocks are separated
     * by one blank line. Continuation paragraphs keep the time and hide the name, as in the reader.
     * Without words the body is [emptyText]. The result uses `\n` line endings and ends with exactly one `\n`.
     */
    fun write(t: ExportTranscript, subtitle: String, emptyText: String): String {
        val out = StringBuilder()
        out.append(t.title).append('\n').append(subtitle).append("\n\n")
        val reading = ReadingTurns.of(t.words, t.endsBoundedByAudio)
        val turns = reading.rows
        if (turns.isEmpty()) return out.append(emptyText).append('\n').toString()
        turns.forEachIndexed { index, turn ->
            if (index > 0) out.append('\n')
            out.append('[').append(ExportClock.clock(turn.startMs, t.durationMs)).append(']')
            if (!turn.continuation) out.append(' ').append(t.label(turn.speaker))
            val over = ReadingText.over(turn, t::label)
            if (over.isNotEmpty()) out.append(' ').append(over)
            out.append('\n').append(ReadingText.paragraph(turn, reading.words, t::label)).append('\n')
        }
        return out.toString()
    }
}
