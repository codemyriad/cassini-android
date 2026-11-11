package io.codemyriad.cassini.data.backup

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteAccessPermException
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteDiskIOException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteFullException
import android.database.sqlite.SQLiteOutOfMemoryException
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import java.io.File

/** Inspect only the prepared copy. Operational failures never prove that received input is corrupt. */
internal object RestoredCatalogueValidation {
    fun version(file: File): Int = checked {
        // FTS4 integrity checks need writes; only the disposable prepared copy is opened here.
        val refuseDeletion = DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Damaged received catalogue") }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, refuseDeletion).use { database ->
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                val result = if (cursor.moveToFirst()) cursor.getString(0) else "No integrity result"
                require(result == "ok" && !cursor.moveToNext()) { "Damaged received catalogue: $result" }
            }
            database.version
        }
    }

    fun <T> checked(block: () -> T): T = try {
        block()
    } catch (error: SQLiteException) {
        if (passing(error)) throw error
        throw CorruptRestoreException(error)
    } catch (error: IllegalStateException) {
        throw CorruptRestoreException(error)
    } catch (error: IllegalArgumentException) {
        throw CorruptRestoreException(error)
    }

    private fun passing(error: SQLiteException): Boolean =
        error is SQLiteFullException || error is SQLiteDiskIOException || error is SQLiteDatabaseLockedException ||
            error is SQLiteCantOpenDatabaseException || error is SQLiteAccessPermException ||
            error is SQLiteOutOfMemoryException || error is SQLiteReadOnlyDatabaseException
}
