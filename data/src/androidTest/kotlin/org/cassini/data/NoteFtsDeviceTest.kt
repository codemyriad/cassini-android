package org.cassini.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.cassini.core.library.AudioKind
import org.cassini.core.library.Origin
import org.cassini.core.library.SearchQuery
import org.cassini.data.db.CassiniDatabase
import org.cassini.data.db.CassiniDatabaseOpener
import org.cassini.data.db.NoteCache
import org.cassini.data.db.NoteEntity
import org.cassini.data.db.VariantEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The FTS4 tokenizer on the device's own SQLite (docs/architecture.md, section 3.3): this is the authority for
 * prefix, case and diacritic matching. It opens the catalogue in a scratch root inside the test APK's own files, so it
 * never touches org.cassini.android.
 */
@RunWith(AndroidJUnit4::class)
class NoteFtsDeviceTest {
    private lateinit var root: File
    private lateinit var db: CassiniDatabase

    @Before fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        root = File(context.filesDir, "NoteFtsDeviceTest-${System.nanoTime()}")
        val paths = AppPaths(root = File(root, "files"), cache = File(root, "cache"))
        db = CassiniDatabaseOpener(context, paths).open().database
        runBlocking {
            val note = NoteEntity(
                id = "n1",
                title = "Fornitori",
                createdAt = 1,
                addedAt = 1,
                origin = Origin.RECORDED,
                audioFile = "document.opus",
                audioKind = AudioKind.DOCUMENT,
                updatedAt = 1,
            )
            db.notes().insert(note)
            val variant = VariantEntity("n1", "v1", ordinal = 0, isDefault = true, wordCount = 6)
            val body = "quindi la consegna slitta a giovedì"
            db.cache().replaceCache(NoteCache(note, listOf(variant), emptyList(), emptyList(), body))
        }
    }

    @After fun tearDown() {
        db.close()
        root.deleteRecursively()
    }

    private fun search(input: String): List<String> = runBlocking {
        db.notes().searchFlow(requireNotNull(SearchQuery.fts(input))).first().map { it.row.id }
    }

    @Test fun prefixFindsTheWholeWord() {
        assertEquals(listOf("n1"), search("consegn"))
    }

    @Test fun diacriticsAreIgnored() {
        assertEquals(listOf("n1"), search("giovedi"))
    }

    @Test fun caseIsIgnored() {
        assertEquals(listOf("n1"), search("CONSEGNA"))
    }
}
