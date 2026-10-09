package org.cassini.core.export

/** The plain-text transcript export (`.txt`, features.md section 9). */
object TextExport {
    /**
     * The title line, the [subtitle] line ("8 Oct 2026, 15:10 · 23:50 · 3 speakers", made by the caller), a blank
     * line, then one block per turn: `[<clock>] <label>` and the turn's words on the next line. Blocks are separated
     * by one blank line. With one distinct speaker the label is left out (`[<clock>]` only). Without words the body is
     * [emptyText]. The result uses `\n` line endings and ends with exactly one `\n`.
     */
    fun write(t: ExportTranscript, subtitle: String, emptyText: String): String {
        val out = StringBuilder()
        out.append(t.title).append('\n').append(subtitle).append("\n\n")
        val turns = TranscriptTurns.of(t.words)
        if (turns.isEmpty()) return out.append(emptyText).append('\n').toString()
        val labelled = t.speakerCount >= 2
        turns.forEachIndexed { index, turn ->
            if (index > 0) out.append('\n')
            out.append('[').append(ExportClock.clock(turn.startMs, t.durationMs)).append(']')
            if (labelled) out.append(' ').append(t.label(turn.speaker))
            out.append('\n').append(turn.text).append('\n')
        }
        return out.toString()
    }
}
