package org.cassini.ui.format

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import org.cassini.ui.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * A day as the ViewModel classifies it with its injected clock (ui.md section 2): the UI never asks what today is.
 * [Date.withYear] is false in the current year.
 */
sealed interface DayLabel {
    data object Today : DayLabel

    data object Yesterday : DayLabel

    @Immutable
    data class Date(val date: LocalDate, val withYear: Boolean) : DayLabel
}

/**
 * Days and times (features.md section 0): "Today", "Yesterday", "Monday 5 Oct", "5 Oct 2025"; times of day in
 * 24-hour ("10:02") or 12-hour ("10:02 AM") form after [FormatSettings.is24Hour].
 */
@Suppress("TooManyFunctions") // A pure form and a composable form of each formatter.
object Dates {
    /** The day label of day headers and subtitles: "Today", "Yesterday", "Monday 5 Oct", "5 Oct 2025". */
    fun dayLabel(res: Resources, label: DayLabel, format: FormatSettings): String =
        when (label) {
            DayLabel.Today -> res.getString(R.string.ui_day_today)
            DayLabel.Yesterday -> res.getString(R.string.ui_day_yesterday)
            is DayLabel.Date -> {
                val pattern = if (label.withYear) "d MMM yyyy" else "EEEE d MMM"
                capitalise(pattern(pattern, format).format(label.date), format)
            }
        }

    /** "10:02" or "10:02 AM". */
    fun time(time: LocalTime, format: FormatSettings): String =
        pattern(if (format.is24Hour) "HH:mm" else "h:mm a", format).format(time)

    /** "Today, 10:02", "Monday 5 Oct, 10:02" (subtitles of the Note screen and the actions sheet). */
    fun dayTime(res: Resources, label: DayLabel, time: LocalTime, format: FormatSettings): String =
        res.getString(R.string.ui_day_time, dayLabel(res, label, format), time(time, format))

    /** "8 Oct", or "8 Oct 2025" with the year. */
    fun shortDate(date: LocalDate, withYear: Boolean, format: FormatSettings): String =
        pattern(if (withYear) "d MMM yyyy" else "d MMM", format).format(date)

    /** "8 Oct, 15:40" (the Versions sheet), with the year when [withYear]. */
    fun shortDateTime(
        res: Resources,
        date: LocalDate,
        time: LocalTime,
        withYear: Boolean,
        format: FormatSettings,
    ): String = res.getString(R.string.ui_day_time, shortDate(date, withYear, format), time(time, format))

    /** "8 Oct 2026, 15:10" (Document info). */
    fun longDateTime(res: Resources, date: LocalDate, time: LocalTime, format: FormatSettings): String =
        shortDateTime(res, date, time, withYear = true, format = format)

    /** "Deleted today", "Deleted yesterday", "Deleted 2 Oct" (Trash cards). */
    fun deleted(res: Resources, label: DayLabel, format: FormatSettings): String =
        when (label) {
            DayLabel.Today -> res.getString(R.string.ui_trash_deleted_today)
            DayLabel.Yesterday -> res.getString(R.string.ui_trash_deleted_yesterday)
            is DayLabel.Date -> {
                val date = shortDate(label.date, label.withYear, format)
                res.getString(R.string.ui_trash_deleted_on, date)
            }
        }

    /** "Deleted 2 Oct · 23 days left". */
    fun trashLine(res: Resources, label: DayLabel, daysLeft: Int, format: FormatSettings): String =
        res.getString(R.string.ui_join_dot, deleted(res, label, format), Counts.daysLeft(res, daysLeft, format))

    /** The default title of a new recording: "Voice note 8 Oct, 08:14" / "Nota vocale 8 ott, 08:14". */
    fun voiceNoteTitle(res: Resources, date: LocalDate, time: LocalTime, format: FormatSettings): String =
        res.getString(R.string.ui_voice_note_title, shortDateTime(res, date, time, withYear = false, format = format))

    private fun pattern(pattern: String, format: FormatSettings): DateTimeFormatter =
        DateTimeFormatter.ofPattern(pattern, format.locale)

    private fun capitalise(text: String, format: FormatSettings): String =
        text.replaceFirstChar { it.titlecase(format.locale) }

    @Composable
    @ReadOnlyComposable
    fun dayLabel(label: DayLabel): String = dayLabel(resources(), label, format())

    @Composable
    @ReadOnlyComposable
    fun time(time: LocalTime): String = time(time, format())

    @Composable
    @ReadOnlyComposable
    fun dayTime(label: DayLabel, time: LocalTime): String = dayTime(resources(), label, time, format())

    @Composable
    @ReadOnlyComposable
    fun shortDate(date: LocalDate, withYear: Boolean): String = shortDate(date, withYear, format())

    @Composable
    @ReadOnlyComposable
    fun shortDateTime(date: LocalDate, time: LocalTime, withYear: Boolean): String =
        shortDateTime(resources(), date, time, withYear, format())

    @Composable
    @ReadOnlyComposable
    fun longDateTime(date: LocalDate, time: LocalTime): String = longDateTime(resources(), date, time, format())

    @Composable
    @ReadOnlyComposable
    fun deleted(label: DayLabel): String = deleted(resources(), label, format())

    @Composable
    @ReadOnlyComposable
    fun trashLine(label: DayLabel, daysLeft: Int): String = trashLine(resources(), label, daysLeft, format())

    @Composable
    @ReadOnlyComposable
    fun voiceNoteTitle(date: LocalDate, time: LocalTime): String = voiceNoteTitle(resources(), date, time, format())
}
