package org.cassini.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * The Room catalogue (docs/architecture.md, section 3.3), opened by absolute path at `AppPaths.database` through
 * [CassiniDatabaseOpener]. Version 1 ships; a later version needs a written Migration with a MigrationTestHelper
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
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CassiniDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun cache(): CacheDao
    abstract fun jobs(): JobDao
    abstract fun voiceLinks(): VoiceLinkDao
}
