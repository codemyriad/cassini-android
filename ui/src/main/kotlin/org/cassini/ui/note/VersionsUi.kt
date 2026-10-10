package org.cassini.ui.note

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import java.time.LocalDate
import java.time.LocalTime

/** The Versions sheet (no board; features.md section 3.4): one row per variant in manifest order. */
@Immutable
data class VersionsUi(val rows: ImmutableList<VersionRowUi>, val selected: Int)

/** How a variant came to be; it also picks the version chip suffix (features.md section 3.3). */
enum class VersionKindUi {
    /** The first transcription: no suffix. */
    ORIGINAL,

    /** "· speakers merged". */
    MERGED,

    /** "· transcribed again". */
    TRANSCRIBED_AGAIN,
}

/**
 * One row: "Version [number]" and its detail, "Transcribed 8 Oct, 15:40 · 4,812 words" or "Speakers merged · from
 * version [fromVersion] · 8 Oct, 16:02", then [language] (a BCP 47 tag, shown in the app language; null when
 * empty). An unreadable variant ([readable] false) is disabled with "Can't be read". [withYear] adds the year to the
 * date; the ViewModel sets it with its clock when [date] is not in the current year (ui.md section 2).
 */
@Immutable
data class VersionRowUi(
    val index: Int,
    val number: Int,
    val kind: VersionKindUi,
    val date: LocalDate,
    val time: LocalTime,
    val words: Int,
    val fromVersion: Int? = null,
    val language: String? = null,
    val readable: Boolean = true,
    val withYear: Boolean = false,
)
