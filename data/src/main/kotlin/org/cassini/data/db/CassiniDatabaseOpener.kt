package org.cassini.data.db

import android.content.Context
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.cassini.data.AppPaths
import org.cassini.data.Threads
import java.io.File

/**
 * An open catalogue. [setAside] is the file the previous database was renamed to when it could not be opened (the
 * caller then rebuilds the catalogue from the files, docs/architecture.md section 4.9); null when it opened normally
 * or did not exist.
 */
class OpenedDatabase(val database: CassiniDatabase, val setAside: File?)

/**
 * Opens [CassiniDatabase] at the absolute path `AppPaths.database` (docs/architecture.md, sections 3.3 and 4.8).
 *
 * A database that fails to open (corrupt, a version without a migration, a schema Room cannot verify) is never
 * deleted and never wiped by a destructive migration: its files are renamed to `cassini.db.corrupt-<epoch>` (plus the
 * same suffix on `-wal`, `-shm` and `-journal`) and a new, empty database is created. SQLite's own corruption handler,
 * which deletes the file, is replaced by the same rename. A full disk, an I/O error, a lock or a permission error
 * is thrown as it is and the file stays: these do not show that the file is damaged.
 */
class CassiniDatabaseOpener(
    private val context: Context,
    private val paths: AppPaths,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @WorkerThread
    fun open(): OpenedDatabase {
        Threads.assertOffMain("CassiniDatabaseOpener.open")
        paths.database.parentFile?.mkdirs()
        var setAside: File? = null
        val first = build { setAside = it }
        val failure = try {
            first.openHelper.writableDatabase
            null
        } catch (error: SQLiteException) {
            // A full disk, an I/O error or a lock says nothing about the file: it stays for the next start.
            if (passing(error)) {
                first.close()
                throw error
            }
            error
        } catch (error: IllegalStateException) {
            // Room's missing-migration, downgrade and identity-hash failures.
            error
        }
        if (failure == null) return OpenedDatabase(first, setAside)
        Log.w(TAG, "Cannot open ${paths.database}; setting it aside", failure)
        first.close()
        val moved = setAsideFiles(paths.database)
        val second = build { setAside = it }
        second.openHelper.writableDatabase
        return OpenedDatabase(second, moved ?: setAside)
    }

    private fun passing(error: SQLiteException): Boolean =
        error is SQLiteFullException || error is SQLiteDiskIOException || error is SQLiteDatabaseLockedException ||
            error is SQLiteCantOpenDatabaseException || error is SQLiteAccessPermException ||
            error is SQLiteOutOfMemoryException || error is SQLiteReadOnlyDatabaseException

    private fun build(onSetAside: (File) -> Unit): CassiniDatabase =
        Room.databaseBuilder(context, CassiniDatabase::class.java, paths.database.absolutePath)
            .openHelperFactory(
                SetAsideOnCorruption(FrameworkSQLiteOpenHelperFactory()) { path ->
                    setAsideFiles(File(path))?.let(onSetAside)
                },
            )
            .build()

    /** Renames [database] and its side files to `<name>.corrupt-<epoch>`; returns the new main file, or null. */
    private fun setAsideFiles(database: File): File? {
        val epoch = now()
        var target = File(database.parentFile, "${database.name}.corrupt-$epoch")
        var counter = 1
        while (target.exists()) target = File(database.parentFile, "${database.name}.corrupt-$epoch-${counter++}")
        val suffix = target.name.removePrefix(database.name)
        var moved: File? = null
        for (side in SIDE_FILES) {
            val source = File(database.path + side)
            if (!source.exists()) continue
            val destination = File(database.parentFile, database.name + side + suffix)
            if (source.renameTo(destination)) {
                if (side.isEmpty()) moved = destination
            } else {
                Log.e(TAG, "Cannot rename $source to $destination")
            }
        }
        return moved
    }

    /**
     * Wraps the framework helper so that SQLite's corruption callback renames the database instead of deleting it.
     * Every other callback goes to Room's.
     */
    private class SetAsideOnCorruption(
        private val delegate: SupportSQLiteOpenHelper.Factory,
        private val setAside: (String) -> Unit,
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
            val room = configuration.callback
            val callback = object : SupportSQLiteOpenHelper.Callback(room.version) {
                override fun onConfigure(db: SupportSQLiteDatabase) = room.onConfigure(db)
                override fun onCreate(db: SupportSQLiteDatabase) = room.onCreate(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    room.onUpgrade(db, oldVersion, newVersion)
                override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                    room.onDowngrade(db, oldVersion, newVersion)
                override fun onOpen(db: SupportSQLiteDatabase) = room.onOpen(db)
                override fun onCorruption(db: SupportSQLiteDatabase) {
                    val path = db.path ?: return
                    Log.e(TAG, "SQLite reports $path corrupt; setting it aside")
                    runCatching { db.close() }
                    setAside(path)
                }
            }
            return delegate.create(
                SupportSQLiteOpenHelper.Configuration.builder(configuration.context)
                    .name(configuration.name)
                    .callback(callback)
                    .noBackupDirectory(configuration.useNoBackupDirectory)
                    .allowDataLossOnRecovery(configuration.allowDataLossOnRecovery)
                    .build(),
            )
        }
    }

    private companion object {
        const val TAG = "CassiniDatabase"
        val SIDE_FILES = listOf("", "-wal", "-shm", "-journal")
    }
}
