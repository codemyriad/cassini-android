package io.codemyriad.cassini.data.db

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.codemyriad.cassini.core.library.AudioKind
import io.codemyriad.cassini.core.library.SearchQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Library search over `note_fts` (features.md section 1). The device test NoteFtsDeviceTest repeats the tokenizer
 * cases on a real SQLite and is the authority; these tests FAIL (never skip) when Robolectric's SQLite lacks FTS4,
 * unicode61 or remove_diacritics.
 */
@RunWith(RobolectricTestRunner::class)
class NoteSearchTest {
    @get:Rule val folder = TemporaryFolder()
    private lateinit var db: CassiniDatabase

    @Before fun setUp() {
        db = ScratchCatalogue(folder).open().database
    }

    @After fun tearDown() {
        db.close()
    }

    @Test fun sqliteHasFts4WithUnicode61AndRemoveDiacritics() {
        val raw = SQLiteDatabase.create(null)
        try {
            raw.execSQL("CREATE VIRTUAL TABLE probe USING fts4(body, tokenize=unicode61 \"remove_diacritics=1\")")
            raw.execSQL("INSERT INTO probe(body) VALUES ('café')")
            raw.rawQuery("SELECT COUNT(*) FROM probe WHERE probe MATCH 'cafe'", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals("unicode61 remove_diacritics=1 did not fold 'café'", 1, cursor.getInt(0))
            }
        } catch (error: SQLiteException) {
            fail("This SQLite lacks FTS4 with the unicode61 tokenizer and remove_diacritics=1: ${error.message}")
        } finally {
            raw.close()
        }
    }

    private suspend fun search(input: String): List<SearchRow> =
        db.notes().searchFlow(requireNotNull(SearchQuery.fts(input))).first()

    private suspend fun insertTranscribed(id: String, text: String, title: String = "Note $id", createdAt: Long = 1_000) {
        val row = note(id, title = title, createdAt = createdAt)
        db.notes().insert(row)
        db.cache().replaceCache(cache(row, listOf("v1" to words(text))))
    }

    @Test fun prefixFindsTheWholeWord() = runTest {
        insertTranscribed("a", "the delivery slips to the café")
        assertEquals(listOf("a"), search("deliver").map { it.row.id })
    }

    @Test fun diacriticsAreIgnored() = runTest {
        insertTranscribed("a", "the delivery slips to the café")
        assertEquals(listOf("a"), search("cafe").map { it.row.id })
        assertEquals(listOf("a"), search("CAFÉ").map { it.row.id })
    }

    @Test fun caseIsIgnored() = runTest {
        insertTranscribed("a", "the delivery slips to the café")
        assertEquals(listOf("a"), search("DELIVERY").map { it.row.id })
    }

    @Test fun everyTermMustMatchAndOperatorsAreWords() = runTest {
        insertTranscribed("a", "the delivery slips to the café")
        insertTranscribed("b", "the delivery is on time")
        assertEquals(listOf("a"), search("delivery caf").map { it.row.id })
        assertTrue(search("delivery OR timely").isEmpty())
    }

    @Test fun trashedNotesAreExcluded() = runTest {
        insertTranscribed("a", "the delivery slips", createdAt = 1)
        insertTranscribed("b", "no delivery today", createdAt = 2)
        db.notes().moveToTrash("b", at = 5_000, job = null)
        assertEquals(listOf("a"), search("delivery").map { it.row.id })
        db.notes().restore("b", now = 6_000)
        assertEquals(listOf("b", "a"), search("delivery").map { it.row.id })
    }

    @Test fun snippetMarksTheMatchWithControlCharacters() = runTest {
        insertTranscribed("a", "so the delivery slips to Thursday but the price stays")
        val row = search("deliver").single()
        assertTrue(row.snippet, row.snippet.contains("\u0002delivery\u0003"))
        val highlighted = SearchQuery.highlights(row.snippet)
        assertFalse(highlighted.text.contains('\u0002'))
        assertEquals(listOf("delivery"), highlighted.ranges.map { highlighted.text.substring(it.first, it.last + 1) })
    }

    @Test fun titleOnlyMatchMarksTheTitleAndNotTheBody() = runTest {
        insertTranscribed("a", "nothing relevant here", title = "Supplier meeting")
        val row = search("suppli").single()
        assertTrue(row.titleSnippet, row.titleSnippet.contains("\u0002Supplier\u0003"))
        assertFalse(row.snippet.contains('\u0002'))
    }

    @Test fun renameReindexesTheTitle() = runTest {
        insertTranscribed("a", "nothing relevant", title = "Old name")
        assertTrue(db.notes().rename("a", "Budget meeting", titleDirty = true, now = 2_000))
        assertTrue(search("old").isEmpty())
        assertEquals(listOf("a"), search("budget").map { it.row.id })
        val stored = db.notes().note("a")!!
        assertEquals("Budget meeting", stored.title)
        assertTrue(stored.titleDirty)
        assertFalse(db.notes().rename("missing", "x", titleDirty = false, now = 2_000))
    }

    @Test fun selectingAnotherVariantReindexesTheBody() = runTest {
        val row = note("a")
        db.notes().insert(row)
        db.cache().replaceCache(cache(row, listOf("v1" to words("first versione"), "v2" to words("second stesura"))))
        assertEquals(listOf("a"), search("first").map { it.row.id })
        db.notes().selectVariant("a", "v2", body = "second stesura", preview = null, now = 2_000)
        assertTrue(search("first").isEmpty())
        assertEquals(listOf("a"), search("stesura").map { it.row.id })
    }

    @Test fun replacingTheCacheKeepsTheFtsDocid() = runTest {
        val row = note("a")
        db.notes().insert(row)
        val before = db.notes().text("a")!!.rowId
        db.cache().replaceCache(cache(row, listOf("v1" to words("one two three"))))
        db.cache().replaceCache(cache(row, listOf("v1" to words("four five"))))
        assertEquals(before, db.notes().text("a")!!.rowId)
        assertTrue(search("one").isEmpty())
        assertEquals(listOf("a"), search("five").map { it.row.id })
    }

    @Test fun audioOnlyNotesAreFoundByTitle() = runTest {
        db.notes().insert(note("a", title = "Intervista Marco", audioKind = AudioKind.PLAIN))
        assertEquals(listOf("a"), search("marco").map { it.row.id })
    }
}
