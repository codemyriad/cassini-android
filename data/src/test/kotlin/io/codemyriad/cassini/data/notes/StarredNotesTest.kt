package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.SearchDate
import io.codemyriad.cassini.core.library.SearchFilters
import io.codemyriad.cassini.data.db.cache
import io.codemyriad.cassini.data.db.note
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Per-device stars (L-34, D99) in `note_stars`, and the Starred / This year search filters (L-35). */
@RunWith(RobolectricTestRunner::class)
class StarredNotesTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 14)
    private val at = today.atStartOfDay(zone).toInstant().toEpochMilli()

    @Before fun setUp() {
        h = NotesHarness(folder)
    }

    @After fun tearDown() = h.close()

    private suspend fun insert(id: String, createdAt: Long = at) {
        val row = note(id, "Meeting $id", createdAt).copy(recordedAt = Instant.ofEpochMilli(createdAt).toString())
        h.db.cache().replaceCache(cache(row, listOf("v1" to listOf(Word("S1", 0, 400, "delivery")))))
    }

    private suspend fun starred(): Map<String, Boolean> = h.notes.library().first().associate { it.id to it.starred }

    @Test fun starringIsIdempotentAndShowsInTheLibraryAndTheNoteDetails() = runTest {
        insert("a")
        insert("b")
        assertEquals(mapOf("a" to false, "b" to false), starred())
        h.notes.setStarred("a", true)
        h.notes.setStarred("a", true)
        assertEquals(mapOf("a" to true, "b" to false), starred())
        assertTrue(h.notes.note("a").first()!!.starred)
        assertFalse(h.notes.note("b").first()!!.starred)
        h.notes.setStarred("a", false)
        h.notes.setStarred("a", false)
        assertEquals(mapOf("a" to false, "b" to false), starred())
        assertNull(h.notes.note("a").first()!!.star)
    }

    @Test fun aMissingNoteCannotBeStarred() = runTest {
        h.notes.setStarred("ghost", true)
        assertTrue(h.notes.library().first().isEmpty())
        assertEquals(0, countStars())
    }

    @Test fun aStaleWholeRowWriteKeepsTheStar() = runTest {
        insert("a")
        val stale = h.db.notes().note("a")!!
        h.notes.setStarred("a", true)
        // The document re-derivation writes the whole note row it read before the star was set.
        h.db.cache().replaceCache(cache(stale.copy(title = "Renamed"), listOf("v1" to listOf(Word("S1", 0, 400, "x")))))
        h.db.notes().update(stale.copy(positionMs = 5))
        assertEquals(mapOf("a" to true), starred())
    }

    @Test fun deletingTheNoteDeletesItsStar() = runTest {
        insert("a")
        h.notes.setStarred("a", true)
        h.db.notes().delete("a")
        assertEquals(0, countStars())
    }

    @Test fun starredAndThisYearNarrowTheTypedHitsAndTheEmptyQuery() = runTest {
        insert("lastYear", createdAt = LocalDate.of(2025, 12, 31).atStartOfDay(zone).toInstant().toEpochMilli())
        insert("january", createdAt = LocalDate.of(2026, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli())
        insert("now")
        h.notes.setStarred("lastYear", true)
        h.notes.setStarred("now", true)
        for (query in listOf("delivery", "?")) {
            suspend fun ids(filters: SearchFilters) =
                h.notes.search(query, filters, zone, today).first().map { it.row.id }.toSet()
            assertEquals(setOf("lastYear", "january", "now"), ids(SearchFilters()))
            assertEquals(setOf("lastYear", "now"), ids(SearchFilters(starred = true)))
            assertEquals(setOf("january", "now"), ids(SearchFilters(date = SearchDate.YEAR)))
            assertEquals(setOf("now"), ids(SearchFilters(date = SearchDate.YEAR, starred = true)))
        }
    }

    private fun countStars(): Int = h.db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM note_stars").use {
        check(it.moveToFirst())
        it.getInt(0)
    }
}
