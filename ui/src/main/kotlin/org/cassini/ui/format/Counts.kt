package org.cassini.ui.format

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import org.cassini.ui.R
import java.text.NumberFormat

/**
 * Numbers with the locale's grouping ("4,812" / "4.812") and every count of features.md section 0 as a plural.
 * The plural text carries the grouped number, so "4,812 words" and "4.812 parole" come from one call.
 */
@Suppress("TooManyFunctions") // A pure form and a composable form of each formatter.
object Counts {
    private const val SPEED_WHOLE_FROM = 10.0
    private const val HZ_PER_KHZ = 1000.0

    /** [n] with the grouping of the format locale. */
    fun number(n: Long, format: FormatSettings): String = NumberFormat.getIntegerInstance(format.locale).format(n)

    /** The plural [id] for [count], with the grouped number as its only argument. */
    fun plural(res: Resources, @PluralsRes id: Int, count: Int, format: FormatSettings): String =
        res.getQuantityString(id, count, number(count.toLong(), format))

    fun speakers(res: Resources, n: Int, format: FormatSettings) = plural(res, R.plurals.ui_count_speakers, n, format)

    fun notes(res: Resources, n: Int, format: FormatSettings) = plural(res, R.plurals.ui_count_notes, n, format)

    fun people(res: Resources, n: Int, format: FormatSettings) = plural(res, R.plurals.ui_count_people, n, format)

    fun words(res: Resources, n: Int, format: FormatSettings) = plural(res, R.plurals.ui_count_words, n, format)

    fun versions(res: Resources, n: Int, format: FormatSettings) = plural(res, R.plurals.ui_count_versions, n, format)

    fun daysLeft(res: Resources, n: Int, format: FormatSettings) = plural(res, R.plurals.ui_count_days_left, n, format)

    fun moreInQueue(res: Resources, n: Int, format: FormatSettings) =
        plural(res, R.plurals.ui_count_more_in_queue, n, format)

    fun aheadOfNote(res: Resources, n: Int, format: FormatSettings) =
        plural(res, R.plurals.ui_count_ahead_of_note, n, format)

    fun namesFromVoices(res: Resources, n: Int, format: FormatSettings) =
        plural(res, R.plurals.ui_count_names_from_voices, n, format)

    /** "62%". */
    fun percent(res: Resources, percent: Int): String = res.getString(R.string.ui_percent, percent)

    /** "11×" from 10× up, "2.5×" ("2,5×") below. */
    fun speed(res: Resources, factor: Double, format: FormatSettings): String {
        val text = if (factor >= SPEED_WHOLE_FROM) {
            NumberFormat.getIntegerInstance(format.locale).format(Math.round(factor))
        } else {
            NumberFormat.getNumberInstance(format.locale).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }.format(factor)
        }
        return res.getString(R.string.ui_speed_times, text)
    }

    /** The Recording format chip: "Opus 48 kHz", "M4A 44.1 kHz". */
    fun captureFormat(res: Resources, m4a: Boolean, sampleRateHz: Int, format: FormatSettings): String {
        val name = res.getString(if (m4a) R.string.ui_format_m4a else R.string.ui_format_opus)
        val khz = NumberFormat.getNumberInstance(format.locale).apply { maximumFractionDigits = 1 }
            .format(sampleRateHz / HZ_PER_KHZ)
        return res.getString(R.string.ui_format_sample_rate, name, khz)
    }

    /** The placeholder speaker label, "Speaker 3" / "Voce 3" (features.md section 0). */
    fun speakerLabel(res: Resources, n: Int): String = res.getString(R.string.ui_speaker_label, n)

    @Composable
    @ReadOnlyComposable
    fun number(n: Long): String = number(n, format())

    @Composable
    @ReadOnlyComposable
    fun plural(@PluralsRes id: Int, count: Int): String = plural(resources(), id, count, format())

    @Composable
    @ReadOnlyComposable
    fun percent(percent: Int): String = percent(resources(), percent)

    @Composable
    @ReadOnlyComposable
    fun speed(factor: Double): String = speed(resources(), factor, format())

    @Composable
    @ReadOnlyComposable
    fun captureFormat(m4a: Boolean, sampleRateHz: Int): String = captureFormat(resources(), m4a, sampleRateHz, format())

    @Composable
    @ReadOnlyComposable
    fun speakerLabel(n: Int): String = speakerLabel(resources(), n)
}
