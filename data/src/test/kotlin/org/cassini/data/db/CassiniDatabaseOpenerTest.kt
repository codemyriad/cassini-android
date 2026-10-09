package org.cassini.data.db

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A catalogue that cannot be opened is set aside as `cassini.db.corrupt-<epoch>`, never deleted or wiped. */
@RunWith(RobolectricTestRunner::class)
class CassiniDatabaseOpenerTest {
    @get:Rule val folder = TemporaryFolder()
    private val catalogue by lazy { ScratchCatalogue(folder) }

    private fun corruptFiles(): List<String> =
        catalogue.paths.root.list().orEmpty().filter { it.contains(".corrupt-") }.sorted()

    @Test fun opensByAbsolutePathAndKeepsRowsAcrossReopen() = runTest {
        val first = catalogue.open()
        assertNull(first.setAside)
        first.database.notes().insert(note("a"))
        first.database.close()
        assertTrue(catalogue.paths.database.isFile)

        val second = catalogue.open()
        assertNull(second.setAside)
        assertEquals("Note a", second.database.notes().note("a")?.title)
        second.database.close()
        assertTrue(corruptFiles().isEmpty())
    }

    @Test fun aFileThatIsNotADatabaseIsSetAsideAndRecreated() = runTest {
        catalogue.paths.database.writeGarbage()
        val garbage = catalogue.paths.database.readBytes()
        catalogue.clock = 1_234
        val opened = catalogue.open()

        val setAside = opened.setAside!!
        assertEquals("cassini.db.corrupt-1234", setAside.name)
        assertTrue(garbage.contentEquals(setAside.readBytes()))
        opened.database.notes().insert(note("fresh"))
        assertEquals("Note fresh", opened.database.notes().note("fresh")?.title)
        opened.database.close()
    }

    @Test fun aNewerSchemaVersionIsSetAsideNotDestroyed() = runTest {
        catalogue.paths.database.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(catalogue.paths.database, null).use { raw ->
            raw.execSQL("CREATE TABLE future (x TEXT)")
            raw.execSQL("INSERT INTO future VALUES ('keep me')")
            raw.version = 2
        }
        catalogue.clock = 42
        val opened = catalogue.open()

        val setAside = opened.setAside!!
        assertEquals("cassini.db.corrupt-42", setAside.name)
        SQLiteDatabase.openDatabase(setAside.path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            raw.rawQuery("SELECT x FROM future", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("keep me", cursor.getString(0))
            }
        }
        assertTrue(opened.database.notes().all().isEmpty())
        opened.database.close()
    }

    @Test fun aSchemaRoomCannotVerifyIsSetAside() = runTest {
        catalogue.paths.database.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(catalogue.paths.database, null).use { raw ->
            raw.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
            raw.execSQL("INSERT INTO room_master_table VALUES (42, 'not-this-schema')")
            raw.version = 1
        }
        val opened = catalogue.open()
        assertNotNull(opened.setAside)
        val sideFiles = listOf("-wal", "-shm", "-journal")
        assertEquals(1, corruptFiles().count { name -> sideFiles.none { name.contains(it) } })
        opened.database.notes().insert(note("a"))
        assertEquals("Note a", opened.database.notes().note("a")?.title)
        opened.database.close()
    }
}
