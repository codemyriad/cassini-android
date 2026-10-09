package org.cassini.data.db

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.cassini.core.library.AudioKind
import org.cassini.core.library.SearchQuery
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
            raw.execSQL("INSERT INTO probe(body) VALUES ('giovedì')")
            raw.rawQuery("SELECT COUNT(*) FROM probe WHERE probe MATCH 'giovedi'", null).use { cursor ->
                cursor.moveToFirst()
                assertEquals("unicode61 remove_diacritics=1 did not fold 'giovedì'", 1, cursor.getInt(0))
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
        insertTranscribed("a", "la consegna slitta a giovedì")
        assertEquals(listOf("a"), search("consegn").map { it.row.id })
    }

    @Test fun diacriticsAreIgnored() = runTest {
        insertTranscribed("a", "la consegna slitta a giovedì")
        assertEquals(listOf("a"), search("giovedi").map { it.row.id })
        assertEquals(listOf("a"), search("GIOVEDÌ").map { it.row.id })
    }

    @Test fun caseIsIgnored() = runTest {
        insertTranscribed("a", "la consegna slitta a giovedì")
        assertEquals(listOf("a"), search("CONSEGNA").map { it.row.id })
    }

    @Test fun everyTermMustMatchAndOperatorsAreWords() = runTest {
        insertTranscribed("a", "la consegna slitta a giovedì")
        insertTranscribed("b", "la consegna è puntuale")
        assertEquals(listOf("a"), search("consegna giov").map { it.row.id })
        assertTrue(search("consegna OR puntuale").isEmpty())
    }

    @Test fun trashedNotesAreExcluded() = runTest {
        insertTranscribed("a", "la consegna slitta", createdAt = 1)
        insertTranscribed("b", "nessuna consegna oggi", createdAt = 2)
        db.notes().moveToTrash("b", at = 5_000, job = null)
        assertEquals(listOf("a"), search("consegna").map { it.row.id })
        db.notes().restore("b", now = 6_000)
        assertEquals(listOf("b", "a"), search("consegna").map { it.row.id })
    }

    @Test fun snippetMarksTheMatchWithControlCharacters() = runTest {
        insertTranscribed("a", "quindi la consegna slitta a giovedì but the price stays")
        val row = search("consegn").single()
        assertTrue(row.snippet, row.snippet.contains("\u0002consegna\u0003"))
        val highlighted = SearchQuery.highlights(row.snippet)
        assertFalse(highlighted.text.contains('\u0002'))
        assertEquals(listOf("consegna"), highlighted.ranges.map { highlighted.text.substring(it.first, it.last + 1) })
    }

    @Test fun titleOnlyMatchMarksTheTitleAndNotTheBody() = runTest {
        insertTranscribed("a", "nothing relevant here", title = "Riunione fornitori")
        val row = search("fornit").single()
        assertTrue(row.titleSnippet, row.titleSnippet.contains("\u0002fornitori\u0003"))
        assertFalse(row.snippet.contains('\u0002'))
    }

    @Test fun renameReindexesTheTitle() = runTest {
        insertTranscribed("a", "nothing relevant", title = "Old name")
        assertTrue(db.notes().rename("a", "Riunione budget", titleDirty = true, now = 2_000))
        assertTrue(search("old").isEmpty())
        assertEquals(listOf("a"), search("budget").map { it.row.id })
        val stored = db.notes().note("a")!!
        assertEquals("Riunione budget", stored.title)
        assertTrue(stored.titleDirty)
        assertFalse(db.notes().rename("missing", "x", titleDirty = false, now = 2_000))
    }

    @Test fun selectingAnotherVariantReindexesTheBody() = runTest {
        val row = note("a")
        db.notes().insert(row)
        db.cache().replaceCache(cache(row, listOf("v1" to words("prima versione"), "v2" to words("seconda stesura"))))
        assertEquals(listOf("a"), search("prima").map { it.row.id })
        db.notes().selectVariant("a", "v2", body = "seconda stesura", preview = null, now = 2_000)
        assertTrue(search("prima").isEmpty())
        assertEquals(listOf("a"), search("stesura").map { it.row.id })
    }

    @Test fun replacingTheCacheKeepsTheFtsDocid() = runTest {
        val row = note("a")
        db.notes().insert(row)
        val before = db.notes().text("a")!!.rowId
        db.cache().replaceCache(cache(row, listOf("v1" to words("uno due tre"))))
        db.cache().replaceCache(cache(row, listOf("v1" to words("quattro cinque"))))
        assertEquals(before, db.notes().text("a")!!.rowId)
        assertTrue(search("uno").isEmpty())
        assertEquals(listOf("a"), search("cinque").map { it.row.id })
    }

    @Test fun audioOnlyNotesAreFoundByTitle() = runTest {
        db.notes().insert(note("a", title = "Intervista Marco", audioKind = AudioKind.PLAIN))
        assertEquals(listOf("a"), search("marco").map { it.row.id })
    }
}
