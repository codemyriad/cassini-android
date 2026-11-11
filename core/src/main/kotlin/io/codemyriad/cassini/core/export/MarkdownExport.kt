package io.codemyriad.cassini.core.export

/** Localized S-5 headings. Stored content is never translated (D47/D50.2). */
data class MarkdownCopy(
    val speakersLine: String,
    val transcriptHeading: String,
    val notesHeading: String,
    val overLine: String,
    val speakerList: (List<String>) -> String,
)

/** A personal note from the portable document, kept separate from transcript words. */
data class MarkdownOptions(val dateLine: String, val copy: MarkdownCopy)

/** One timed personal note. */
data class MarkdownNote(val atMs: Long, val text: String)

/** S-5 Markdown with the shared D84 reading projection, UTF-8/LF and one final newline. */
object MarkdownExport {
    private const val MAX_FILENAME = 120
    private const val MAX_FILENAME_BYTES = 230 // GNOME S-5 guard: leave room for the extension and provider suffix.
    private val whitespace = Regex("[\\s\\p{Z}]+")
    private val leadingNumber = Regex("^([0-9]+)\\.")

    fun write(
        transcript: ExportTranscript,
        dateLine: String,
        copy: MarkdownCopy,
        notes: List<MarkdownNote> = emptyList(),
    ): String {
        val reading = ReadingTurns.of(transcript.words, transcript.endsBoundedByAudio)
        val blocks = mutableListOf("# ${escape(transcript.title)}")
        if (dateLine.isNotEmpty()) blocks += dateLine
        // Chip-only speakers also belong in the first-speaking inventory. Sorting never changes word indices.
        val speakers = transcript.words.withIndex().sortedWith(compareBy({ it.value.startMs }, { it.index }))
            .map { it.value.speaker }.distinct()
        if (speakers.isNotEmpty()) {
            blocks += copy.speakersLine.replace("{names}", speakers.joinToString(", ") { escape(transcript.label(it)) })
        }
        blocks += "## ${copy.transcriptHeading}"
        for (row in reading.rows) {
            val speaker = if (row.continuation) "" else "**${escape(transcript.label(row.speaker))}** · "
            val over = row.over.map(transcript::label).distinct().takeIf { it.isNotEmpty() }
                ?.let { " ${escape(copy.overLine.replace("{names}", copy.speakerList(it)))}" }.orEmpty()
            val clock = ExportClock.srt(row.startMs).substringBefore(',')
            blocks += "$speaker$clock$over\n${escape(ReadingText.paragraph(row, reading.words, transcript::label))}"
        }
        if (notes.isNotEmpty()) {
            blocks += "## ${copy.notesHeading}"
            blocks += notes.sortedBy { it.atMs }.joinToString("\n") {
                "- ${ExportClock.srt(it.atMs).substringBefore(',')} ${escape(it.text)}"
            }
        }
        return blocks.joinToString("\n\n") + "\n"
    }

    /** Escape content, including text that would introduce a Markdown block. */
    fun escape(value: String): String {
        val normalized = whitespace.replace(value, " ").trim()
        val escaped = buildString {
            for (c in normalized) {
                if (c in "\\`*_[]<>") append('\\')
                append(c)
            }
        }
        return when {
            escaped.firstOrNull() in listOf('#', '-', '+') -> "\\$escaped"
            else -> leadingNumber.replaceFirst(escaped, "$1\\\\.")
        }
    }

    /** The system document picker handles collisions; this supplies only the S-5 suggested name. */
    fun filename(title: String, untitled: String): String {
        val replaced = title.map {
            if (it == '/' || it == '\\' || Character.isISOControl(it)) '-' else it
        }.joinToString("").trim()
        val stem = buildString {
            var offset = 0
            var characters = 0
            var bytes = 0
            while (offset < replaced.length && characters < MAX_FILENAME) {
                val codePoint = replaced.codePointAt(offset)
                val next = String(Character.toChars(codePoint))
                val width = next.toByteArray(Charsets.UTF_8).size
                if (bytes + width > MAX_FILENAME_BYTES) break
                append(next)
                bytes += width
                characters++
                offset += Character.charCount(codePoint)
            }
        }.ifEmpty { untitled }
        return "$stem.md"
    }
}
