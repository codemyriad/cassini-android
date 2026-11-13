package io.codemyriad.cassini.ui.note

import android.content.res.Resources
import io.codemyriad.cassini.ui.R
import io.codemyriad.cassini.ui.format.Durations
import io.codemyriad.cassini.ui.format.TimeLeftUi
import java.text.NumberFormat
import java.util.Locale

/** Compact continuation copy uses the shared plural; unknown/sub-minute states retain the existing contract. */
internal fun transcribingEta(resources: Resources, locale: Locale, time: TimeLeftUi): String = when (time) {
    TimeLeftUi.Calculating -> resources.getString(R.string.ui_time_left_line_calculating).continuation(locale)
    TimeLeftUi.Finishing -> resources.getString(R.string.ui_time_left_finishing).continuation(locale)
    is TimeLeftUi.Range -> {
        if (time.highMs < Durations.MINUTE_MS) {
            resources.getString(R.string.ui_time_left_line_under_minute).continuation(locale)
        } else {
            val high = time.highMs / Durations.MINUTE_MS + if (time.highMs % Durations.MINUTE_MS == 0L) 0 else 1
            val low = (time.lowMs / Durations.MINUTE_MS).coerceIn(1, high)
            val numbers = NumberFormat.getIntegerInstance(locale)
            val n = if (low == high) {
                numbers.format(high)
            } else {
                resources.getString(R.string.ui_range, numbers.format(low), numbers.format(high))
            }
            val quantity = if (low == high) high.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else 2
            resources.getQuantityString(R.plurals.ui_tx_eta_minutes, quantity, n)
        }
    }
}

private fun String.continuation(locale: Locale): String = replaceFirstChar { it.toString().lowercase(locale) }
