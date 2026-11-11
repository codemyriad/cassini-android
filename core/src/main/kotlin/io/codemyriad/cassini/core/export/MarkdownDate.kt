package io.codemyriad.cassini.core.export

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** D50.3: date words use the UI language; the clock uses the formatting locale and hour-cycle choice. */
object MarkdownDate {
    @Suppress("LongParameterList") // D50.3 inputs are independent; never infer one locale from the other.
    fun line(
        atMs: Long,
        zone: ZoneId,
        uiLocale: Locale,
        formattingLocale: Locale,
        is24Hour: Boolean,
        duration: String?,
    ): String {
        val date = Instant.ofEpochMilli(atMs).atZone(zone)
        val fullDate = if (uiLocale.language == "en") {
            DateTimeFormatter.ofPattern("EEEE d MMMM y", Locale.UK)
        } else {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(uiLocale)
        }
        val clock = DateTimeFormatter.ofPattern(if (is24Hour) "HH:mm" else "h:mm a", formattingLocale)
        return fullDate.format(date) + ", " + clock.format(date) + (duration?.let { " · $it" } ?: "")
    }
}
