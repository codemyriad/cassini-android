package org.cassini.ui.format

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import org.cassini.ui.R
import java.text.NumberFormat

/**
 * Sizes labelled "MB" as the boards and 0.1.0 write them (features.md section 0). The rounding is
 * `ModelCatalog.mebibytes` of `:core`, written again here because `:ui` never depends on `:core`.
 */
object Sizes {
    private const val MIB = 1_048_576.0
    private const val GIB = 1_073_741_824L
    private const val ROUND_TO_TENS_FROM = 100
    private const val TENS = 10

    /** Whole MiB, at least 1, rounded to tens from 100 MiB: the same values as `ModelCatalog.mebibytes`. */
    fun mebibytes(bytes: Long): Long =
        (bytes / MIB).let { if (it < ROUND_TO_TENS_FROM) maxOf(1, Math.round(it)) else Math.round(it / TENS) * TENS }

    /** "640 MB". */
    fun megabytes(res: Resources, bytes: Long, format: FormatSettings): String =
        res.getString(R.string.ui_size_mb, Counts.number(mebibytes(bytes), format))

    /** A total next to its parts: the sum of the rounded parts, so 640 + 62 + 28 reads "730 MB". */
    fun totalMegabytes(res: Resources, parts: List<Long>, format: FormatSettings): String =
        res.getString(R.string.ui_size_mb, Counts.number(parts.sumOf(::mebibytes), format))

    /** The download banner body "296 of 640 MB.": whole MiB done (floored) of the rounded total. */
    fun downloaded(res: Resources, doneBytes: Long, totalBytes: Long, format: FormatSettings): String {
        val total = mebibytes(totalBytes)
        val done = (doneBytes.coerceAtLeast(0) / MIB).toLong().coerceAtMost(total)
        return res.getString(
            R.string.ui_banner_dl_downloading_body,
            Counts.number(done, format),
            megabytes(res, totalBytes, format),
        )
    }

    /** Free-space figures: "860 MB" below 1 GiB, "1.4 GB" ("1,4 GB") from 1 GiB, one decimal. */
    fun storage(res: Resources, bytes: Long, format: FormatSettings): String {
        if (bytes < GIB) return megabytes(res, bytes, format)
        val gb = NumberFormat.getNumberInstance(format.locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(bytes.toDouble() / GIB)
        return res.getString(R.string.ui_size_gb, gb)
    }

    @Composable
    @ReadOnlyComposable
    fun megabytes(bytes: Long): String = megabytes(resources(), bytes, format())

    @Composable
    @ReadOnlyComposable
    fun totalMegabytes(parts: List<Long>): String = totalMegabytes(resources(), parts, format())

    @Composable
    @ReadOnlyComposable
    fun downloaded(doneBytes: Long, totalBytes: Long): String = downloaded(resources(), doneBytes, totalBytes, format())

    @Composable
    @ReadOnlyComposable
    fun storage(bytes: Long): String = storage(resources(), bytes, format())
}
