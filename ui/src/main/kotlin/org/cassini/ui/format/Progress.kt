package org.cassini.ui.format

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import org.cassini.ui.R

/** The progress card headline (features.md section 3.1). */
enum class ProgressHeadline(@StringRes val text: Int) {
    STARTING(R.string.ui_headline_starting),
    WAITING_FOR_PREVIOUS(R.string.ui_headline_waiting),
    READING_AUDIO(R.string.ui_headline_reading_audio),
    PREPARING(R.string.ui_headline_preparing),
    FINDING_WORDS_AND_SPEAKERS(R.string.ui_headline_words_and_speakers),
    FINDING_WORDS(R.string.ui_headline_words),
    MATCHING_VOICES(R.string.ui_headline_matching),
    SAVING(R.string.ui_headline_saving),
}

/** The five stepper columns, with the board label and the spoken name of "Step 3 of 5: Words and voices". */
enum class StageUi(@StringRes val label: Int, @StringRes val spoken: Int) {
    READING(R.string.ui_stage_reading, R.string.ui_stage_reading),
    PREPARING(R.string.ui_stage_preparing, R.string.ui_stage_preparing),
    WORDS_AND_VOICES(R.string.ui_stage_words_and_voices, R.string.ui_stage_words_and_voices_spoken),
    MATCHING(R.string.ui_stage_matching, R.string.ui_stage_matching),
    SAVING(R.string.ui_stage_saving, R.string.ui_stage_saving),
}

/** The state description of one stepper column. */
enum class StageState(@StringRes val text: Int) {
    DONE(R.string.ui_stage_done),
    IN_PROGRESS(R.string.ui_stage_in_progress),
    NOT_STARTED(R.string.ui_stage_not_started),
    SKIPPED(R.string.ui_stage_skipped),
}

/** Time left as the progress tracker reports it (ui.md section 7). */
sealed interface TimeLeftUi {
    data object Calculating : TimeLeftUi

    data object Finishing : TimeLeftUi

    @Immutable
    data class Range(val lowMs: Long, val highMs: Long) : TimeLeftUi
}

/** The battery reading at finish (ui.md section 7); percentages are 0–100. */
sealed interface BatteryUi {
    data object Hidden : BatteryUi

    @Immutable
    data class Measuring(val now: Int) : BatteryUi

    @Immutable
    data class PluggedIn(val now: Int) : BatteryUi

    @Immutable
    data class Projected(val now: Int, val atFinish: Int) : BatteryUi
}

/** Text of the progress card and of the Library progress banner (features.md sections 1 and 3.1). */
@Suppress("TooManyFunctions") // A pure form and a composable form of each formatter.
object Progress {
    private const val MINUTES_PER_HOUR = 60L

    fun headline(res: Resources, headline: ProgressHeadline): String = res.getString(headline.text)

    fun stage(res: Resources, stage: StageUi): String = res.getString(stage.label)

    /** "Step 3 of 5: Words and voices". */
    fun stageDescription(res: Resources, stage: StageUi): String =
        res.getString(
            R.string.ui_stage_description,
            stage.ordinal + 1,
            StageUi.entries.size,
            res.getString(stage.spoken),
        )

    fun stageState(res: Resources, state: StageState): String = res.getString(state.text)

    /**
     * The Time left stat: "6–9 min", "About 6 min", "Under 1 min", "1 h 10 min–1 h 30 min", "Calculating…",
     * "Finishing…". The low end is floored and the high end rounded up to whole minutes.
     */
    fun timeLeft(res: Resources, timeLeft: TimeLeftUi): String =
        when (timeLeft) {
            TimeLeftUi.Calculating -> res.getString(R.string.ui_time_left_calculating)
            TimeLeftUi.Finishing -> res.getString(R.string.ui_time_left_finishing)
            is TimeLeftUi.Range -> when (val span = span(timeLeft)) {
                null -> res.getString(R.string.ui_time_left_under_minute)
                else -> if (span.first == span.last) {
                    res.getString(R.string.ui_time_left_about, Durations.hoursMinutes(res, span.first))
                } else {
                    spanText(res, span)
                }
            }
        }

    /**
     * Line 2 of the Library progress banner: "About 6–9 min left", "About 6 min left", "Under 1 min left",
     * "Calculating time left…", "Finishing…".
     */
    fun timeLeftLine(res: Resources, timeLeft: TimeLeftUi): String =
        when (timeLeft) {
            TimeLeftUi.Calculating -> res.getString(R.string.ui_time_left_line_calculating)
            TimeLeftUi.Finishing -> res.getString(R.string.ui_time_left_finishing)
            is TimeLeftUi.Range -> when (val span = span(timeLeft)) {
                null -> res.getString(R.string.ui_time_left_line_under_minute)
                else -> res.getString(
                    R.string.ui_time_left_line_about,
                    if (span.first == span.last) Durations.hoursMinutes(res, span.first) else spanText(res, span),
                )
            }
        }

    /** The Battery at finish stat: "54% → about 41%", "Plugged in", "Measuring…"; null when hidden. */
    fun battery(res: Resources, battery: BatteryUi): String? =
        when (battery) {
            BatteryUi.Hidden -> null
            is BatteryUi.Measuring -> res.getString(R.string.ui_battery_measuring)
            is BatteryUi.PluggedIn -> res.getString(R.string.ui_battery_plugged_in)
            is BatteryUi.Projected -> res.getString(R.string.ui_battery_projected, battery.now, battery.atFinish)
        }

    /** Whole minutes from the floored low end to the rounded-up high end; null below one minute. */
    private fun span(range: TimeLeftUi.Range): LongRange? {
        if (range.highMs < Durations.MINUTE_MS) return null
        val high = (range.highMs + Durations.MINUTE_MS - 1) / Durations.MINUTE_MS
        val low = (range.lowMs / Durations.MINUTE_MS).coerceIn(1, high)
        return low..high
    }

    private fun spanText(res: Resources, span: LongRange): String =
        if (span.last < MINUTES_PER_HOUR) {
            res.getString(R.string.ui_time_left_minutes_range, span.first.toInt(), span.last.toInt())
        } else {
            res.getString(
                R.string.ui_range,
                Durations.hoursMinutes(res, span.first),
                Durations.hoursMinutes(res, span.last),
            )
        }

    @Composable
    @ReadOnlyComposable
    fun headline(headline: ProgressHeadline): String = headline(resources(), headline)

    @Composable
    @ReadOnlyComposable
    fun stage(stage: StageUi): String = stage(resources(), stage)

    @Composable
    @ReadOnlyComposable
    fun stageDescription(stage: StageUi): String = stageDescription(resources(), stage)

    @Composable
    @ReadOnlyComposable
    fun stageState(state: StageState): String = stageState(resources(), state)

    @Composable
    @ReadOnlyComposable
    fun timeLeft(timeLeft: TimeLeftUi): String = timeLeft(resources(), timeLeft)

    @Composable
    @ReadOnlyComposable
    fun timeLeftLine(timeLeft: TimeLeftUi): String = timeLeftLine(resources(), timeLeft)

    @Composable
    @ReadOnlyComposable
    fun battery(battery: BatteryUi): String? = battery(resources(), battery)
}
