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
class SpeakerColourMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CassiniDatabase::class.java,
    )

    @Test fun refreshesOnlyDerivedDocumentCachesAndPreservesNotes() {
        helper.createDatabase("speaker-colour-migration", 2).use { db ->
            val columns = "id,title,created_at,added_at,origin,audio_file,audio_kind,duration_ms," +
                "position_ms,title_dirty,document_warnings,trust_acknowledged,document_signature,updated_at"
            db.execSQL(
                "INSERT INTO notes ($columns) VALUES " +
                    "('doc','Keep',1,2,'IMPORTED_DOCUMENT','document.opus','DOCUMENT',3,0,0,'[]',0,'old',5)",
            )
            db.execSQL(
                "INSERT INTO notes ($columns) VALUES " +
                    "('audio','Audio',6,7,'RECORDED','capture.opus','PLAIN',8,0,0,'[]',0,'keep',10)",
            )
        }
        helper.runMigrationsAndValidate("speaker-colour-migration", 3, true, CassiniDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT title,document_signature FROM notes ORDER BY id").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Audio", rows.getString(0))
                assertEquals("keep", rows.getString(1))
                assertTrue(rows.moveToNext())
                assertEquals("Keep", rows.getString(0))
                assertTrue(rows.isNull(1))
            }
        }
    }
}
