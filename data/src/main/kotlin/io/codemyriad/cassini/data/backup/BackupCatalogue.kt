package io.codemyriad.cassini.data.backup

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.NoteIds
import io.codemyriad.cassini.data.NoteLocks
import io.codemyriad.cassini.data.db.CassiniDatabase
import io.codemyriad.cassini.data.db.CassiniDatabaseOpener
import io.codemyriad.cassini.data.notes.DocumentRepository
import io.codemyriad.cassini.data.notes.NoteFiles
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

/** Catalogue I/O belongs to backup preparation or recoverable startup, never restore finalization. */
internal class BackupCatalogue(private val context: Context) {
    fun prepare(restored: AppPaths) {
        // A catalogue lost on the source is rebuilt by the normal startup.
        if (Files.notExists(restored.database.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        val version = RestoredCatalogueValidation.version(restored.database)
        require(version <= CassiniDatabase.VERSION) { "Unsupported newer restored catalogue" }
        RestoredCatalogueValidation.checked { prepareSupported(restored) }
    }

    private fun prepareSupported(restored: AppPaths) {
        val opened = CassiniDatabaseOpener(context, restored).open()
        val database = opened.database
        try {
            require(opened.setAside == null) { "Unsupported restored catalogue" }
            database.runInTransaction {
                val sql = database.openHelper.writableDatabase
                sql.execSQL("DELETE FROM jobs")
                sql.execSQL("UPDATE notes SET trashed_job = NULL")
                sql.execSQL(
                    "DELETE FROM variants WHERE note_id IN (SELECT id FROM notes WHERE audio_kind != 'DOCUMENT')"
                )
                sql.execSQL(
                    "UPDATE note_text SET body = '' WHERE note_id IN (SELECT id FROM notes WHERE audio_kind != 'DOCUMENT')"
                )
                sql.execSQL("UPDATE notes SET preview = NULL, selected_variant = NULL WHERE audio_kind != 'DOCUMENT'")
                sql.execSQL(
                    "DELETE FROM note_voice_links WHERE note_id IN (SELECT id FROM notes WHERE audio_kind != 'DOCUMENT')"
                )
                sql.execSQL("UPDATE notes SET document_signature = NULL WHERE audio_kind = 'DOCUMENT'")
            }
            val documents = DocumentRepository(
                database,
                restored,
                CassiniDispatchers(),
                NoteLocks(),
                context.contentResolver
            )
            runBlocking {
                for (note in database.notes().all()) {
                    require(
                        NoteIds.isValid(note.id) && BackupManifest.admits("notes/${note.id}/${note.audioFile}")
                    ) { "Unsafe catalogue audio path" }
                    documents.refreshIfStale(note.id)
                }
            }
        } finally {
            database.close()
        }
        checkpoint(restored.database)
        NoteFiles.syncFile(restored.database)
        NoteFiles.syncDirectory(restored.database.parentFile!!)
    }

    fun checkpoint(file: File) {
        if (Files.notExists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return
        val refuseDeletion = DatabaseErrorHandler { throw SQLiteException("Catalogue corruption during backup") }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, refuseDeletion).use { database ->
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "Damaged backup catalogue" }
            }
            database.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getInt(0) != 0) throw IOException("Catalogue is still in use")
            }
        }
    }
}
