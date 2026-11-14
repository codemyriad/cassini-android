package io.codemyriad.cassini.library

import io.codemyriad.cassini.data.db.LibraryRow
import io.codemyriad.cassini.data.db.SearchRow
import io.codemyriad.cassini.ui.components.NoteRowUi
import io.codemyriad.cassini.ui.components.NoteStatusUi
import io.codemyriad.cassini.ui.library.DaySection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps the mapped list across progress and overlay updates. Used only by the ViewModel's sequential state
 * transform. Room and search publish new lists; identity checks avoid walking the catalogue on every tick.
 */
internal class LibraryListing {
    data class Mapped(
        val sections: ImmutableList<DaySection>,
        val results: ImmutableList<NoteRowUi>?,
        val audioOnly: Boolean,
        /** At least one live note is starred (L-34); computed with the rows, not on every progress tick. */
        val anyStarred: Boolean = false,
    )

    private var rows: List<LibraryRow>? = null
    private var found: List<SearchRow>? = null
    private var context: RowContext? = null
    private var date: LocalDate? = null
    private var zone: ZoneId? = null
    private var searching = false
    private var starredOnly = false
    private var mapped: Mapped? = null

    /** [starredOnly] is the Library's `Starred` choice at rest (L-34); the search results have their own filter. */
    @Suppress("LongParameterList") // All inputs that can change a row or its day label.
    fun map(
        rows: List<LibraryRow>,
        found: List<SearchRow>?,
        searching: Boolean,
        context: RowContext,
        now: Instant,
        zone: ZoneId,
        starredOnly: Boolean = false,
    ): Mapped {
        val date = now.atZone(zone).toLocalDate()
        val previous = mapped
        val sameRows = rows === this.rows && found === this.found && searching == this.searching &&
            starredOnly == this.starredOnly
        val sameContext = context == this.context && date == this.date && zone == this.zone
        if (previous != null && sameRows && sameContext) return previous
        val next = Mapped(
            sections = if (searching) {
                persistentListOf()
            } else {
                LibraryMapping.sections(if (starredOnly) rows.filter { it.starred } else rows, context, now, zone)
            },
            results = if (searching) found?.let { LibraryMapping.results(it, context, now, zone) } ?: persistentListOf() else null,
            audioOnly = rows.any { LibraryMapping.status(it, context) == NoteStatusUi.AudioOnly },
            anyStarred = rows.any { it.starred },
        )
        this.rows = rows
        this.found = found
        this.searching = searching
        this.starredOnly = starredOnly
        this.context = context
        this.date = date
        this.zone = zone
        mapped = next
        return next
    }
}
