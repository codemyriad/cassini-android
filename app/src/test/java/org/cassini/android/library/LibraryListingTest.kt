package org.cassini.android.library

import org.cassini.core.library.AudioKind
import org.cassini.data.db.SearchRow
import org.cassini.ui.components.NoteStatusUi
import org.cassini.ui.format.DayLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

class LibraryListingTest {
    private val cache = LibraryListing()
    private val rows = listOf(libraryRow("a", audioKind = AudioKind.LIVE_CAPTURE))

    @Test fun ticksWithinTheSameDayReuseTheMappedList() {
        val first = cache.map(rows, null, false, RowContext(), NOW, ROME)
        val later = cache.map(rows, null, false, RowContext(), NOW.plusSeconds(60), ROME)
        assertSame(first, later)
    }

    @Test fun recordingTicksAndJobChangesRefreshRows() {
        cache.map(rows, null, false, RowContext(), NOW, ROME)
        val recording = RowContext(activeNoteId = "a", recordingElapsedMs = 12_000)
        val active = cache.map(rows, null, false, recording, NOW, ROME)
        assertEquals(12_000L, active.sections.single().rows.single().durationMs)
        assertEquals(NoteStatusUi.Recording, active.sections.single().rows.single().status)
        val tick = cache.map(rows, null, false, recording.copy(recordingElapsedMs = 13_000), NOW, ROME)
        assertEquals(13_000L, tick.sections.single().rows.single().durationMs)
        val job = cache.map(rows, null, false, RowContext(runningNoteId = "a"), NOW, ROME)
        assertTrue(job.sections.single().rows.single().highlighted)
    }

    @Test fun dayAndZoneChangesRefreshLabelsAndTimes() {
        val first = cache.map(rows, null, false, RowContext(), NOW, ROME)
        assertEquals(DayLabel.Today, first.sections.single().label)
        val tomorrow = cache.map(rows, null, false, RowContext(), NOW.plus(1, ChronoUnit.DAYS), ROME)
        assertEquals(DayLabel.Yesterday, tomorrow.sections.single().label)
        val utc = cache.map(rows, null, false, RowContext(), NOW, ZoneOffset.UTC)
        assertEquals(first.sections.single().rows.single().time.minusHours(2), utc.sections.single().rows.single().time)
    }

    @Test fun rowUpdatesAndSearchResultsReplaceCachedContent() {
        cache.map(rows, null, false, RowContext(), NOW, ROME)
        val changed = listOf(libraryRow("a", title = "Changed"))
        val updated = cache.map(changed, null, false, RowContext(), NOW, ROME)
        assertEquals("Changed", updated.sections.single().rows.single().title)
        assertTrue(updated.audioOnly)
        val found = listOf(SearchRow(changed.single(), "", "\u0002Changed\u0003"))
        val search = cache.map(changed, found, true, RowContext(), NOW, ROME)
        assertTrue(search.sections.isEmpty())
        assertEquals("Changed", search.results!!.single().title)
        assertSame(search, cache.map(changed, found, true, RowContext(), NOW.plusSeconds(1), ROME))
        val empty = cache.map(changed, emptyList(), true, RowContext(), NOW, ROME)
        assertTrue(empty.results!!.isEmpty())
        val cleared = cache.map(changed, null, false, RowContext(), NOW, ROME)
        assertNull(cleared.results)
        assertFalse(cleared.sections.isEmpty())
    }
}
