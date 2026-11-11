package io.codemyriad.cassini.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RecordedTimeMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CassiniDatabase::class.java,
    )

    @Test fun preservesRowsAndInvalidatesOnlyDocumentTimeCaches() {
        helper.createDatabase("recorded-time-migration", 1).use { db ->
            val columns = "id,title,created_at,added_at,origin,audio_file,audio_kind,duration_ms," +
                "position_ms,title_dirty,document_warnings,trust_acknowledged,document_signature,updated_at"
            db.execSQL(
                "INSERT INTO notes ($columns) VALUES " +
                    "('doc','Keep title',1,2,'IMPORTED_DOCUMENT','document.opus','DOCUMENT',3,0,0,'[]',0,'cached',5)",
            )
            db.execSQL(
                "INSERT INTO notes ($columns) VALUES " +
                    "('own','Own recording',6,7,'RECORDED','capture.opus','PLAIN',8,0,0,'[]',0,'keep',10)",
            )
        }
        helper.runMigrationsAndValidate("recorded-time-migration", 2, true, CassiniDatabase.MIGRATION_1_2).use { db ->
            db.query(
                "SELECT title,created_at,document_signature,recorded_at,capture_started_at FROM notes ORDER BY id",
            ).use { rows ->
                rows.moveToFirst()
                assertEquals("Keep title", rows.getString(0))
                assertEquals(1L, rows.getLong(1))
                org.junit.Assert.assertTrue(rows.isNull(2))
                org.junit.Assert.assertTrue(rows.isNull(3))
                org.junit.Assert.assertTrue(rows.isNull(4))
                rows.moveToNext()
                assertEquals("Own recording", rows.getString(0))
                assertEquals("keep", rows.getString(2))
                assertEquals(6L, rows.getLong(4))
                org.junit.Assert.assertTrue(rows.isNull(3))
            }
        }
    }
}
