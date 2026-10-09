package org.cassini.ui.format

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import org.cassini.ui.R
import java.time.LocalTime

/** Forms for TalkBack: "48 minutes 13 seconds", "today 10:02", the Library row and transcript turn descriptions. */
@Suppress("TooManyFunctions") // A pure form and a composable form of each formatter.
object Spoken {
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val SECONDS_PER_HOUR = 3600L

    /** "48 minutes 13 seconds", "1 hour 12 minutes 5 seconds", "0 seconds"; zero parts are left out. */
    fun duration(res: Resources, ms: Long, format: FormatSettings): String {
        val seconds = ms.coerceAtLeast(0) / Durations.SECOND_MS
        val parts = listOfNotNull(
            part(res, R.plurals.ui_duration_spoken_hours, seconds / SECONDS_PER_HOUR, format),
            part(res, R.plurals.ui_duration_spoken_minutes, seconds / SECONDS_PER_MINUTE % MINUTES_PER_HOUR, format),
            part(res, R.plurals.ui_duration_spoken_seconds, seconds % SECONDS_PER_MINUTE, format),
        )
        return when (parts.size) {
            0 -> Counts.plural(res, R.plurals.ui_duration_spoken_seconds, 0, format)
            1 -> parts[0]
            2 -> res.getString(R.string.ui_duration_spoken_pair, parts[0], parts[1])
            else -> res.getString(R.string.ui_duration_spoken_triple, parts[0], parts[1], parts[2])
        }
    }

    /** "today 10:02", "yesterday 15:10", "Monday 5 Oct 08:14" (Italian "oggi alle 10:02"). */
    fun dayTime(res: Resources, label: DayLabel, time: LocalTime, format: FormatSettings): String {
        val day = when (label) {
            DayLabel.Today -> res.getString(R.string.ui_day_today_inline)
            DayLabel.Yesterday -> res.getString(R.string.ui_day_yesterday_inline)
            is DayLabel.Date -> Dates.dayLabel(res, label, format)
        }
        return res.getString(R.string.ui_day_time_spoken, day, Dates.time(time, format))
    }

    /**
     * The Library row description: "Weekly product sync, 48 minutes 13 seconds, today 10:02, transcribing".
     * [status] is the chip text; it is lowercased for the sentence.
     */
    @Suppress("LongParameterList") // The row's four parts, the resources and the format.
    fun noteRow(
        res: Resources,
        title: String,
        durationMs: Long,
        day: DayLabel,
        time: LocalTime,
        status: String,
        format: FormatSettings,
    ): String =
        res.getString(
            R.string.ui_join_comma4,
            title,
            duration(res, durationMs, format),
            dayTime(res, day, time, format),
            status.lowercase(format.locale),
        )

    /** A speaker label button: "Giulia, named automatically, at 11 minutes 41 seconds". */
    fun turn(res: Resources, name: String, startMs: Long, auto: Boolean, format: FormatSettings): String =
        res.getString(
            if (auto) R.string.ui_turn_description_auto else R.string.ui_turn_description,
            name,
            duration(res, startMs, format),
        )

    /** The slider state: "11:44 of 23:50". */
    fun position(res: Resources, positionMs: Long, durationMs: Long): String =
        res.getString(R.string.ui_player_position_state, Durations.clock(positionMs), Durations.clock(durationMs))

    private fun part(res: Resources, id: Int, value: Long, format: FormatSettings): String? =
        if (value == 0L) null else Counts.plural(res, id, value.toInt(), format)

    @Composable
    @ReadOnlyComposable
    fun duration(ms: Long): String = duration(resources(), ms, format())

    @Composable
    @ReadOnlyComposable
    fun dayTime(label: DayLabel, time: LocalTime): String = dayTime(resources(), label, time, format())

    @Composable
    @ReadOnlyComposable
    fun noteRow(title: String, durationMs: Long, day: DayLabel, time: LocalTime, status: String): String =
        noteRow(resources(), title, durationMs, day, time, status, format())

    @Composable
    @ReadOnlyComposable
    fun turn(name: String, startMs: Long, auto: Boolean): String = turn(resources(), name, startMs, auto, format())

    @Composable
    @ReadOnlyComposable
    fun position(positionMs: Long, durationMs: Long): String = position(resources(), positionMs, durationMs)
}
