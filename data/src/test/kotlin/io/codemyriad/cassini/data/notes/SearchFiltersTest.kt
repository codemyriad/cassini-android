package io.codemyriad.cassini.data.notes

import io.codemyriad.cassini.core.format.Word
import io.codemyriad.cassini.core.library.Origin
import io.codemyriad.cassini.core.library.SearchDate
import io.codemyriad.cassini.core.library.SearchFilters
import io.codemyriad.cassini.core.library.SearchQuery
import io.codemyriad.cassini.data.db.cache
import io.codemyriad.cassini.data.db.note
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class SearchFiltersTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var h: NotesHarness
    private val zone = ZoneId.of("Europe/Rome")
    private val today = LocalDate.of(2026, 11, 11)
    private val at = today.atStartOfDay(zone).toInstant().toEpochMilli()

    @Before fun setUp() {
        h = NotesHarness(folder)
    }

    @After fun tearDown() = h.close()

    @Suppress("LongParameterList") // Explicit fixture dimensions.
    private suspend fun insert(
        id: String,
        words: List<Word>,
        title: String = "Meeting",
        origin: Origin = Origin.RECORDED,
        createdAt: Long = at,
        labels: Map<String, String> = mapOf("S1" to "Ada", "S2" to "Ben"),
    ) {
        val row = note(id, title, createdAt).copy(origin = origin)
        val cache = cache(row, listOf("v1" to words))
        val speakers = cache.speakers.map {
            labels[it.speakerId]?.let { label -> it.copy(label = label, isPlaceholder = false) } ?: it
        }
        h.db.cache().replaceCache(cache.copy(speakers = speakers))
    }

    private suspend fun search(query: String, filters: SearchFilters = SearchFilters()) =
        h.notes.search(query, filters, zone, today).first()

    @Test fun aNamedSpeakerMustActuallySayTheMatchingWords() = runTest {
        insert("a", listOf(Word("S1", 0, 400, "Nothing"), Word("S2", 1000, 1400, "café")), title = "Café review")
        assertTrue(search("cafe", SearchFilters(speaker = "Ada")).isEmpty())
        val found = search("CAF", SearchFilters(speaker = "bEn")).single()
        assertEquals("café", SearchQuery.highlights(found.snippet).text)
        assertEquals("Café review", found.titleSnippet)
        val highlighted = SearchQuery.highlights(found.snippet)
        assertEquals(listOf("café"), highlighted.ranges.map { highlighted.text.substring(it) })
        assertTrue(search("review", SearchFilters(speaker = "Ben")).isEmpty())
        assertEquals(1, search("review").size)
    }

    @Test fun allTermsMustMatchTheSameAttributedRun() = runTest {
        insert("a", listOf(Word("S1", 0, 400, "delivery"), Word("S2", 1000, 1400, "Friday")))
        assertTrue(search("delivery Friday", SearchFilters(speaker = "Ada")).isEmpty())
        assertEquals(1, search("delivery Friday").size)
    }

    @Test fun dateAndOriginNarrowTheTypedHitsInNewestFirstOrder() = runTest {
        val words = listOf(Word("S1", 0, 400, "delivery"))
        insert("old", words, createdAt = at - 1)
        insert("own", words, createdAt = at + 1)
        insert("received", words, createdAt = at + 2, origin = Origin.IMPORTED_DOCUMENT)
        insert("newer", words, createdAt = at + 3)
        insert("other", listOf(Word("S1", 0, 400, "unrelated")), createdAt = at + 4)
        val filters = SearchFilters(speaker = "Ada", date = SearchDate.TODAY, recordedByMe = true)
        assertEquals(listOf("newer", "own"), search("deliv", filters).map { it.row.id })
        assertEquals(listOf("newer", "received", "own", "old"), search("deliv").map { it.row.id })
    }

    @Test fun namedInventoryExcludesPlaceholdersTrashAndUnselectedVariantsAndDeduplicatesCase() = runTest {
        insert("a", listOf(Word("S1", 0, 400, "first")), labels = mapOf("S1" to "Ben"))
        insert("b", listOf(Word("S1", 0, 400, "second")), labels = mapOf("S1" to "bEN"))
        insert("c", listOf(Word("S1", 0, 400, "third")), labels = mapOf("S1" to "Ada"))
        insert("d", listOf(Word("S1", 0, 400, "unnamed")), labels = emptyMap())
        insert("trashed", listOf(Word("S1", 0, 400, "hidden")), labels = mapOf("S1" to "Chris"))
        h.db.notes().moveToTrash("trashed", at, null)
        assertEquals(listOf("Ada", "Ben"), h.notes.namedSpeakers().first())
        assertTrue(search("hidden", SearchFilters(speaker = "Chris")).isEmpty())
    }

    @Test fun switchingVariantsAndRenamingCannotReuseTheOldSpeakerMatch() = runTest {
        val row = note("variants", createdAt = at)
        val cached = cache(
            row,
            listOf("v1" to listOf(Word("S1", 0, 400, "delivery")), "v2" to listOf(Word("S2", 0, 400, "delivery"))),
        )
        val speakers = cached.speakers.map {
            it.copy(label = if (it.speakerId == "S1") "Ada" else "Ben", isPlaceholder = false)
        }
        h.db.cache().replaceCache(cached.copy(speakers = speakers))
        assertEquals(listOf("Ada"), h.notes.namedSpeakers().first())
        assertEquals(1, search("deliv", SearchFilters(speaker = "Ada")).size)
        val selected = cache(
            row,
            listOf("v1" to listOf(Word("S1", 0, 400, "delivery")), "v2" to listOf(Word("S2", 0, 400, "delivery"))),
            selected = "v2",
        )
        h.db.cache().replaceCache(selected.copy(speakers = speakers))
        assertEquals(listOf("Ben"), h.notes.namedSpeakers().first())
        assertTrue(search("deliv", SearchFilters(speaker = "Ada")).isEmpty())
        assertEquals(1, search("deliv", SearchFilters(speaker = "Ben")).size)
        h.db.cache().replaceCache(selected.copy(speakers = speakers.map { it.copy(label = "Chris") }))
        assertEquals(listOf("Chris"), h.notes.namedSpeakers().first())
        assertTrue(search("deliv", SearchFilters(speaker = "Ben")).isEmpty())
        assertEquals(1, search("deliv", SearchFilters(speaker = "Chris")).size)
    }

    @Test fun anInlineReplyBelongsToItsSpeakerAndItsDisplayLabelIsNotIndexed() = runTest {
        val file = java.io.File(System.getProperty("cassini.interjections"), "acknowledgment.words.json")
        val items = org.json.JSONObject(file.readText()).getJSONArray("items")
        val words = (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            Word(item.getString("speaker"), item.getLong("startMs"), item.getLong("endMs"), item.getString("text"))
        }
        val reading = io.codemyriad.cassini.core.export.ReadingTurns.of(words, true)
        val chip = reading.rows.flatMap { it.members }.first { it.interjectionSpeaker != null }
        val chipWord = words[chip.wordIndices.first()]
        val labels = words.map { it.speaker }.distinct().associateWith {
            if (it == chipWord.speaker) "Reply Person" else "Host Person"
        }
        val cached = cache(note("chip", createdAt = at), listOf("v1" to words))
        h.db.cache().replaceCache(
            cached.copy(
                speakers = cached.speakers.map {
                    it.copy(label = labels.getValue(it.speakerId), isPlaceholder = false)
                },
                // Existing caches contain rendered chips. They remain useful candidates, but never grant a hit.
                body = io.codemyriad.cassini.core.export.ReadingText.body(words, labels::getValue, true),
            ),
        )
        assertTrue(search("Reply Person").isEmpty())
        val found = search(chipWord.text, SearchFilters(speaker = "Reply Person")).single()
        assertTrue(found.snippet.contains(SearchQuery.MATCH_START))
        assertTrue(search("Reply Person", SearchFilters(speaker = "Reply Person")).isEmpty())
        assertFalse(SearchQuery.highlights(found.snippet).text.contains("Reply Person"))
    }
}
