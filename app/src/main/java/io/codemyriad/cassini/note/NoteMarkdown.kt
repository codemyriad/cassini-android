package io.codemyriad.cassini.note

import android.content.res.Resources
import android.icu.text.ListFormatter
import io.codemyriad.cassini.core.export.MarkdownCopy
import io.codemyriad.cassini.core.export.MarkdownDate
import io.codemyriad.cassini.core.export.MarkdownOptions
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.FormatSettings
import java.util.Locale
import io.codemyriad.cassini.ui.R as UiR

/** S-5 resource copy and D50.3 formatting, captured together when Save or Share starts. */
internal object NoteMarkdown {
    fun options(res: Resources, format: FormatSettings, createdAt: Long, durationMs: Long): MarkdownOptions {
        val uiLocale = res.configuration.locales[0].let { if (it.language == "en") Locale.UK else it }
        val formatter = ListFormatter.getInstance(uiLocale)
        val copy = MarkdownCopy(
            speakersLine = res.getString(UiR.string.ui_markdown_speakers),
            transcriptHeading = res.getString(UiR.string.ui_markdown_transcript),
            notesHeading = res.getString(UiR.string.ui_markdown_notes),
            overLine = res.getString(UiR.string.ui_markdown_over),
            speakerList = { formatter.format(it) },
        )
        val dateLine = MarkdownDate.line(
            createdAt,
            format.zone,
            uiLocale,
            format.locale,
            format.is24Hour,
            Durations.roughMinutes(res, durationMs).replace('\u00a0', ' ').takeIf { durationMs > 0 },
        )
        return MarkdownOptions(dateLine, copy)
    }
}
