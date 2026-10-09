package org.cassini.core.export

/** The SubRip export (`.srt`, features.md section 9). */
object SrtWriter {
    /**
     * Numbered cues from 1, separated by one blank line, with no markup. With 2 or more speakers a cue starts with
     * `"<label>: "` when its speaker differs from the cue before it in output order, and the first cue always does.
     * Without cues the result is a single `\n`.
     */
    fun write(t: ExportTranscript): String {
        val cues = SubtitleCues.build(t, labelPrefixForSrt = true)
        if (cues.isEmpty()) return "\n"
        val out = StringBuilder()
        cues.forEachIndexed { index, cue ->
            if (index > 0) out.append('\n')
            out.append(index + 1).append('\n')
            out.append(ExportClock.srt(cue.startMs)).append(" --> ").append(ExportClock.srt(cue.endMs)).append('\n')
            cue.lines.forEach { out.append(it).append('\n') }
        }
        return out.toString()
    }
}
