package io.codemyriad.cassini.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Schema 5 (D112, D-1164): the start a person set and an import's last-saved time, both empty for every old row. */
@RunWith(RobolectricTestRunner::class)
class StartedAtMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CassiniDatabase::class.java,
    )

    @Test fun addsTwoEmptyColumnsAndKeepsEveryNoteItsCachesAndItsStar() {
        helper.createDatabase("started-at-migration", 4).use { db ->
            val columns = "id,title,created_at,added_at,origin,audio_file,audio_kind,duration_ms," +
                "position_ms,title_dirty,document_warnings,trust_acknowledged,document_signature,updated_at,recorded_at"
            db.execSQL(
                "INSERT INTO notes ($columns) VALUES " +
                    "('doc','Keep',1,2,'IMPORTED_AUDIO','source.opus','PLAIN',3,0,0,'[]',0,'sig',5,NULL)",
            )
            db.execSQL("INSERT INTO note_stars (note_id, starred_at) VALUES ('doc', 9)")
        }
        helper.runMigrationsAndValidate("started-at-migration", 5, true, CassiniDatabase.MIGRATION_4_5).use { db ->
            db.query(
                "SELECT title, created_at, document_signature, started_at, source_modified_at FROM notes"
            ).use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Keep", rows.getString(0))
                assertEquals(1, rows.getLong(1))
                assertEquals("the derived caches stay valid", "sig", rows.getString(2))
                assertTrue("no one has set a start", rows.isNull(3))
                assertTrue(rows.isNull(4))
            }
            db.query("SELECT COUNT(*) FROM note_stars").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals(1, rows.getInt(0))
            }
        }
    }
}
