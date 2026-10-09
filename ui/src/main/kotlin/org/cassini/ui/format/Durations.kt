package org.cassini.ui.format

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import org.cassini.ui.R

/**
 * Durations as the boards write them (docs/design/features.md section 0). Each function has a pure form that takes
 * [Resources] and a composable form that reads them from the composition. [clock] needs no resources.
 */
@Suppress("TooManyFunctions") // A pure form and a composable form of each formatter.
object Durations {
    internal const val SECOND_MS = 1_000L
    internal const val MINUTE_MS = 60_000L
    internal const val HOUR_MS = 3_600_000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val MINUTES_PER_HOUR = 60L
    private const val RECORDING_WHOLE_HOURS_FROM_MS = 2 * HOUR_MS
    private const val RECORDING_MINUTE_STEP = 5L

    /** `mm:ss` below 1 h ("02:41"), `h:mm:ss` from 1 h ("1:12:05"). Seconds are floored; negative reads as 0. */
    fun clock(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / SECOND_MS
        val h = seconds / (SECONDS_PER_MINUTE * MINUTES_PER_HOUR)
        val m = seconds / SECONDS_PER_MINUTE % MINUTES_PER_HOUR
        val s = seconds % SECONDS_PER_MINUTE
        return if (h > 0) "$h:${two(m)}:${two(s)}" else "${two(m)}:${two(s)}"
    }

    /** "29:00–29:45", the range of the progress card. */
    fun clockRange(res: Resources, startMs: Long, endMs: Long): String =
        res.getString(R.string.ui_range, clock(startMs), clock(endMs))

    /** "29:52 / 48:13", the Processed stat. */
    fun processed(res: Resources, doneMs: Long, totalMs: Long): String =
        res.getString(R.string.ui_of_clock, clock(doneMs), clock(totalMs))

    /**
     * The prose form of speaking and processing times: "45 s", "3 min 12 s", "3 min", "1 h 12 min", "1 h".
     * Seconds are dropped from 1 h.
     */
    fun compact(res: Resources, ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / SECOND_MS
        val minutes = seconds / SECONDS_PER_MINUTE
        val s = (seconds % SECONDS_PER_MINUTE).toInt()
        return when {
            minutes >= MINUTES_PER_HOUR -> hoursMinutes(res, minutes)
            minutes == 0L -> res.getString(R.string.ui_duration_s, s)
            s == 0 -> res.getString(R.string.ui_duration_min, minutes.toInt())
            else -> res.getString(R.string.ui_duration_min_s, minutes.toInt(), s)
        }
    }

    /** Whole minutes, floored: "under 1 min" below 60 s, "22 min", then "1 h 5 min" (or "2 h") from 60 min. */
    fun roughMinutes(res: Resources, ms: Long): String =
        if (ms < MINUTE_MS) {
            res.getString(R.string.ui_duration_under_minute)
        } else {
            hoursMinutes(res, ms / MINUTE_MS)
        }

    /** Whole minutes, floored, at least 1: "10 min", "1 h 5 min". The storage-low banner's "about 10 min". */
    fun atLeastOneMinute(res: Resources, ms: Long): String =
        hoursMinutes(res, (ms / MINUTE_MS).coerceAtLeast(1))

    /**
     * The recording time left in the Recording hint (`RecordingSpace`): whole hours from 2 h ("31 h"), else minutes
     * rounded down to 5 with a minimum of 5 ("1 h 35 min", "35 min", "5 min").
     */
    fun recordingLeft(res: Resources, ms: Long): String =
        if (ms >= RECORDING_WHOLE_HOURS_FROM_MS) {
            res.getString(R.string.ui_duration_h, (ms / HOUR_MS).toInt())
        } else {
            val minutes = ms / MINUTE_MS / RECORDING_MINUTE_STEP * RECORDING_MINUTE_STEP
            hoursMinutes(res, minutes.coerceAtLeast(RECORDING_MINUTE_STEP))
        }

    /** "Speaks for 3 min 12 s in this note" (Name a speaker header). */
    fun speaksFor(res: Resources, ms: Long): String = res.getString(R.string.ui_name_speaks_for, compact(res, ms))

    /** "22 min confirmed speech", "under 1 min confirmed speech" (Saved voices rows). */
    fun confirmedSpeech(res: Resources, ms: Long): String =
        res.getString(R.string.ui_voices_confirmed_speech, roughMinutes(res, ms))

    /** The Recording caption with the space limit: "Keeps recording … Space for about 31 h more." */
    fun spaceHint(res: Resources, ms: Long): String =
        res.getString(
            R.string.ui_join_space,
            res.getString(R.string.ui_recording_hint),
            res.getString(R.string.ui_recording_hint_space, recordingLeft(res, ms)),
        )

    /** The Recording caption when the 2 h cap comes first: "… Stops at 2 hours, in about 1 h 35 min." */
    fun capHint(res: Resources, ms: Long): String =
        res.getString(
            R.string.ui_join_space,
            res.getString(R.string.ui_recording_hint),
            res.getString(R.string.ui_recording_hint_cap, recordingLeft(res, ms)),
        )

    /** "N min" below 60 min, else "N h M min" ("N h" when M is 0). */
    internal fun hoursMinutes(res: Resources, minutes: Long): String {
        val h = (minutes / MINUTES_PER_HOUR).toInt()
        val m = (minutes % MINUTES_PER_HOUR).toInt()
        return when {
            h == 0 -> res.getString(R.string.ui_duration_min, m)
            m == 0 -> res.getString(R.string.ui_duration_h, h)
            else -> res.getString(R.string.ui_duration_h_min, h, m)
        }
    }

    private fun two(value: Long): String = value.toString().padStart(2, '0')

    @Composable
    @ReadOnlyComposable
    fun clockRange(startMs: Long, endMs: Long): String = clockRange(resources(), startMs, endMs)

    @Composable
    @ReadOnlyComposable
    fun processed(doneMs: Long, totalMs: Long): String = processed(resources(), doneMs, totalMs)

    @Composable
    @ReadOnlyComposable
    fun compact(ms: Long): String = compact(resources(), ms)

    @Composable
    @ReadOnlyComposable
    fun roughMinutes(ms: Long): String = roughMinutes(resources(), ms)

    @Composable
    @ReadOnlyComposable
    fun atLeastOneMinute(ms: Long): String = atLeastOneMinute(resources(), ms)

    @Composable
    @ReadOnlyComposable
    fun recordingLeft(ms: Long): String = recordingLeft(resources(), ms)

    @Composable
    @ReadOnlyComposable
    fun speaksFor(ms: Long): String = speaksFor(resources(), ms)

    @Composable
    @ReadOnlyComposable
    fun confirmedSpeech(ms: Long): String = confirmedSpeech(resources(), ms)

    @Composable
    @ReadOnlyComposable
    fun spaceHint(ms: Long): String = spaceHint(resources(), ms)

    @Composable
    @ReadOnlyComposable
    fun capHint(ms: Long): String = capHint(resources(), ms)
}
