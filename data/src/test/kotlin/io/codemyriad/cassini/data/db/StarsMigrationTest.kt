package io.codemyriad.cassini.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class StarsMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CassiniDatabase::class.java,
    )

    @Test fun addsAnEmptyStarTableAndKeepsEveryNoteAndItsCaches() {
        helper.createDatabase("stars-migration", 3).use { db ->
            val columns = "id,title,created_at,added_at,origin,audio_file,audio_kind,duration_ms," +
                "position_ms,title_dirty,document_warnings,trust_acknowledged,document_signature,updated_at"
            db.execSQL(
                "INSERT INTO notes ($columns) VALUES " +
                    "('doc','Keep',1,2,'IMPORTED_DOCUMENT','document.opus','DOCUMENT',3,0,0,'[]',0,'sig',5)",
            )
        }
        helper.runMigrationsAndValidate("stars-migration", 4, true, CassiniDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT title, document_signature FROM notes").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Keep", rows.getString(0))
                assertEquals("the derived caches stay valid", "sig", rows.getString(1))
            }
            db.query("SELECT COUNT(*) FROM note_stars").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(0, rows.getInt(0))
            }
            db.execSQL("INSERT INTO note_stars (note_id, starred_at) VALUES ('doc', 9)")
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("DELETE FROM notes WHERE id = 'doc'")
            db.query("SELECT COUNT(*) FROM note_stars").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("the star goes with its note", 0, rows.getInt(0))
            }
        }
    }
}
