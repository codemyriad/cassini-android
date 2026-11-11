package io.codemyriad.cassini.note

import android.content.res.Resources
import android.icu.text.ListFormatter
import io.codemyriad.cassini.core.export.MarkdownCopy
import io.codemyriad.cassini.core.export.MarkdownDate
import io.codemyriad.cassini.core.export.MarkdownOptions
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.FormatSettings
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import io.codemyriad.cassini.ui.R as UiR

/** S-5 resource copy and D50.3 formatting, captured together when Save or Share starts. */
internal object NoteMarkdown {
    /** With no explicit phone choice, derive the clock cycle from the numeric locale, never the app language. */
    fun is24Hour(formattingLocale: Locale, preference: String?): Boolean = when (preference) {
        "24" -> true
        "12" -> false
        else -> DateTimeFormatterBuilder.getLocalizedDateTimePattern(
            null,
            FormatStyle.SHORT,
            IsoChronology.INSTANCE,
            formattingLocale,
        ).let { 'H' in it || 'k' in it }
    }

    fun options(res: Resources, format: FormatSettings, createdAt: Long, durationMs: Long): MarkdownOptions {
        val configured = res.configuration.locales[0]
        // Unsupported phone languages use the English resource fallback; English copy uses British date words.
        val uiLocale = when (configured.language) {
            "it", "fr", "es", "pt" -> configured
            else -> Locale.UK
        }
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
