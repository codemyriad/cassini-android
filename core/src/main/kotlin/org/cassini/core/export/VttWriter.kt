package org.cassini.core.export

/** The WebVTT export (`.vtt`, features.md section 9). */
object VttWriter {
    /**
     * The `WEBVTT` header and one cue per [SubtitleCues] cue, without cue ids, STYLE or REGION blocks. With 2 or more
     * speakers every cue is wrapped in a `<v Label>…</v>` span. `&`, `<` and `>` are escaped in text and labels, and
     * labels lose their control characters (so `-->` in a label is written `--&gt;`).
     *
     * gocassini's `WriteCaptionsVTT` differs: one cue per segment as `<Label> text` (architecture section 12).
     */
    fun write(t: ExportTranscript): String {
        val out = StringBuilder("WEBVTT\n")
        val voiced = t.speakerCount >= 2
        for (cue in SubtitleCues.build(t, labelPrefixForSrt = false)) {
            out.append('\n')
            out.append(ExportClock.vtt(cue.startMs)).append(" --> ").append(ExportClock.vtt(cue.endMs)).append('\n')
            val text = cue.lines.joinToString("\n") { escape(it) }
            if (voiced) {
                out.append("<v ").append(escape(cleanLabel(cue.label))).append('>').append(text).append("</v>\n")
            } else {
                out.append(text).append('\n')
            }
        }
        return out.toString()
    }

    private fun cleanLabel(label: String): String = label.filterNot { Character.isISOControl(it) }

    private fun escape(text: String): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
