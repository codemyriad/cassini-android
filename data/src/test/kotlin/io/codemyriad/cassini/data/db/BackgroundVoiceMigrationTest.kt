package io.codemyriad.cassini.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Schema 6 (cassini-ux D112 / L-86): the derived background flag, with the document caches derived again. */
@RunWith(RobolectricTestRunner::class)
class BackgroundVoiceMigrationTest {
    @get:Rule val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CassiniDatabase::class.java,
    )

    @Test fun addsAFalseFlagAndRefreshesOnlyDocumentCaches() {
        helper.createDatabase("background-migration", 5).use { db ->
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
            db.execSQL(
                "INSERT INTO variants (note_id,variant_id,ordinal,is_default,language,kind,word_count,speaker_count," +
                    "speakers_told_apart,word_ends_bounded_by_audio,readable) " +
                    "VALUES ('doc','words',0,1,'und','TRANSCRIBED',1,1,1,0,1)",
            )
            db.execSQL(
                "INSERT INTO speakers (note_id,variant_id,speaker_id,ordinal,label,is_placeholder,speaking_ms,word_count," +
                    "turn_count,first_ms) VALUES ('doc','words','a',0,'Anna',0,1,1,1,0)",
            )
        }
        helper.runMigrationsAndValidate("background-migration", 6, true, CassiniDatabase.MIGRATION_5_6).use { db ->
            db.query("SELECT title,document_signature FROM notes ORDER BY id").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Audio", rows.getString(0))
                assertEquals("keep", rows.getString(1))
                assertTrue(rows.moveToNext())
                assertEquals("Keep", rows.getString(0))
                assertTrue("the document caches are derived again", rows.isNull(1))
            }
            db.query("SELECT label, background FROM speakers").use { rows ->
                assertTrue(rows.moveToFirst())
                assertEquals("Anna", rows.getString(0))
                assertEquals(0, rows.getInt(1))
            }
        }
    }
}
