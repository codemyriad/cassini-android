package io.codemyriad.cassini.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room catalogue (docs/architecture.md, section 3.3), opened by absolute path at `AppPaths.database` through
 * [CassiniDatabaseOpener]. Version 2 adds the derived D95 recording time. Its explicit migration preserves authoritative
 * rows and jobs, invalidating legacy document signatures for lazy re-derivation, with a MigrationTestHelper test.
 * Destructive fallback migrations are forbidden: a database that cannot be opened is set aside and the catalogue
 * is rebuilt from the files.
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
                db.execSQL("ALTER TABLE notes ADD COLUMN capture_started_at INTEGER")
                db.execSQL("ALTER TABLE notes ADD COLUMN capture_audio_sha256 TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN legacy_capture_candidate INTEGER")
                // Only an own non-document row's created_at still means capture start, never a cached seal time.
                db.execSQL(
                    "UPDATE notes SET capture_started_at = created_at " +
                        "WHERE origin = 'RECORDED' AND audio_kind != 'DOCUMENT'",
                )
                // V1 adopt retained the capture clock; rebuild used createdAtUtc. Resolve against the file off-main.
                db.execSQL(
                    "UPDATE notes SET legacy_capture_candidate = created_at " +
                        "WHERE origin = 'RECORDED' AND audio_kind = 'DOCUMENT' AND document_state = 'OK'",
                )
                db.execSQL("UPDATE notes SET document_signature = NULL WHERE audio_kind = 'DOCUMENT'")
            }
        }
    }

    abstract fun notes(): NoteDao
    abstract fun cache(): CacheDao
    abstract fun jobs(): JobDao
    abstract fun voiceLinks(): VoiceLinkDao
}
