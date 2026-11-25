package io.codemyriad.cassini.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room catalogue (docs/architecture.md, section 3.3), opened by absolute path at `AppPaths.database` through
 * [CassiniDatabaseOpener]. Version 2 adds the derived D95 recording time; version 3 adds the file-derived
 * speaker colour slot; version 4 adds the per-device stars (`note_stars`, L-34); version 5 adds the start a person
 * set (`notes.started_at`, D112 / L-87) and an import's last-saved time (`notes.source_modified_at`). Each
 * explicit migration preserves authoritative rows and jobs, invalidating legacy document signatures for off-main
 * startup re-derivation, with a MigrationTestHelper test.
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
        NoteStarEntity::class,
    ],
    version = CassiniDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CassiniDatabase : RoomDatabase() {
    companion object {
        const val VERSION = 5

        /** Schema 3: the file-derived speaker colour slot. */
        private const val SPEAKER_COLOUR_VERSION = 3

        /** Schema 4: the per-device stars. */
        private const val STARS_VERSION = 4

        /**
         * Schema 5 adds `started_at` and `source_modified_at` and supersedes the document date reader rules (D112):
         * invalidate cached document signatures so startup refresh re-derives `recorded_at` and `created_at` from each
         * file without touching authoritative rows or jobs.
         */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(STARS_VERSION, VERSION) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE notes ADD COLUMN started_at TEXT")
                db.execSQL("ALTER TABLE notes ADD COLUMN source_modified_at INTEGER")
                db.execSQL("UPDATE notes SET document_signature = NULL WHERE audio_kind = 'DOCUMENT'")
            }
        }

        /** Stars are new per-device state: an empty table, every existing row untouched. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(SPEAKER_COLOUR_VERSION, STARS_VERSION) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_stars` (`note_id` TEXT NOT NULL, `starred_at` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`note_id`), FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
            }
        }

        /** Colour is a file fact; refresh derived rows without touching authoritative notes or jobs. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, SPEAKER_COLOUR_VERSION) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE speakers ADD COLUMN colour_index INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE notes SET document_signature = NULL WHERE audio_kind = 'DOCUMENT'")
            }
        }

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
