package io.codemyriad.cassini.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room catalogue (docs/architecture.md, section 3.3), opened by absolute path at `AppPaths.database` through
 * [CassiniDatabaseOpener]. Version 1 is the pre-public schema (D51); its clean switch adds the pinned job
 * recognizer and D84 measured-word-end provenance cache. After the first public release, a later version needs a
 * written Migration with a MigrationTestHelper
 * test. Destructive fallback migrations are forbidden: a database that cannot be opened is set aside and the
 * catalogue is rebuilt from the files.
 */
@Database(
    entities = [
        NoteEntity::class,
        VariantEntity::class,
        SpeakerEntity::class,
        WordEntity::class,
        NoteTextEntity::class,
        NoteFtsEntity::class,
        JobEntity::class,
        NoteVoiceLinkEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CassiniDatabase : RoomDatabase() {
    companion object {
        /** Preserve authoritative rows/jobs; old document-time caches must be derived again from the file. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN recorded_at TEXT")
                db.execSQL("UPDATE notes SET document_signature = NULL WHERE audio_kind = 'DOCUMENT'")
            }
        }
    }

    abstract fun notes(): NoteDao
    abstract fun cache(): CacheDao
    abstract fun jobs(): JobDao
    abstract fun voiceLinks(): VoiceLinkDao
}
