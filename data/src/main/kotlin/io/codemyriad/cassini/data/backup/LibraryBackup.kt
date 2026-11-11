package io.codemyriad.cassini.data.backup

import android.content.Context
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.AtomicFile
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.CassiniDispatchers
import io.codemyriad.cassini.data.NoteLocks
import io.codemyriad.cassini.data.Threads
import io.codemyriad.cassini.data.db.CassiniDatabaseOpener
import io.codemyriad.cassini.data.notes.DocumentRepository
import io.codemyriad.cassini.data.notes.NoteFiles
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import java.util.UUID

/** Binder-thread backup/restore work on a quiescent app. It never creates the graph or starts a job. */
class LibraryBackup(private val context: Context, private val paths: AppPaths, private val staging: File) {
    private var receiving: File? = null
    private val received = HashSet<String>()
    private var receivedBytes = 0L

    /** XML and this allowlist both omit settings, model receipts/weights, and unfinished inference work. */
    fun prepare(): Pair<BackupManifest, File> {
        Threads.assertOffMain("LibraryBackup.prepare")
        checkpoint(paths.database)
        val manifest = BackupManifest.create(paths.root)
        val file = File(paths.root, BackupManifest.MANIFEST)
        Files.createDirectories(file.parentFile!!.toPath())
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(manifest.json().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: IOException) {
            atomic.failWrite(output)
            throw error
        }
        return manifest to file
    }

    /** Streams a single admitted restore entry into private staging, never its live destination. */
    fun receive(path: String, input: InputStream, size: Long, mtime: Long, availableBytes: Long) {
        Threads.assertOffMain("LibraryBackup.receive")
        require(path == BackupManifest.MANIFEST || BackupManifest.admits(path)) { "Unexpected backup path" }
        require(size in 0..BackupManifest.MAX_FILE_BYTES && received.size < BackupManifest.MAX_ENTRIES + 1)
        if (path == BackupManifest.MANIFEST) require(size <= BackupManifest.MAX_MANIFEST_BYTES)
        if (size > availableBytes - SPACE_RESERVE) throw IOException("Insufficient restore storage")
        val root = receiving ?: begin().also { receiving = it }
        require(received.add(path)) { "Duplicate restore file" }
        val destination = File(File(root, "incoming"), path)
        Files.createDirectories(destination.parentFile!!.toPath())
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = size
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("Truncated restore file")
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
        Files.setLastModifiedTime(destination.toPath(), FileTime.fromMillis(mtime))
        NoteFiles.syncFile(destination)
        receivedBytes = Math.addExact(receivedBytes, size)
    }

    /** Completeness first, then cache repair on the staged database, then a journaled root swap. */
    fun finishRestore() {
        Threads.assertOffMain("LibraryBackup.finishRestore")
        val stage = receiving ?: throw IOException("No complete library was received")
        val incoming = File(stage, "incoming")
        try {
            val manifest = BackupManifest.read(File(incoming, BackupManifest.MANIFEST))
            manifest.verify(incoming)
            require(manifest.files.sumOf { it.bytes } <= receivedBytes) { "Incomplete restore" }
            prepareCatalogue(incoming, File(stage, "cache"))
            LibraryRestoreTransaction(paths.root, staging).install(stage)
            receiving = null
            received.clear()
            receivedBytes = 0
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            // An interrupted transaction owns its old roots until recover() rolls them back.
            if (noTransaction()) LibraryRestoreTransaction.deleteStage(stage)
            receiving = null
            received.clear()
            receivedBytes = 0
            throw error
        }
    }

    fun abandon() {
        Threads.assertOffMain("LibraryBackup.abandon")
        val stage = receiving
        if (stage != null && noTransaction()) LibraryRestoreTransaction.deleteStage(stage)
        receiving = null
        received.clear()
        receivedBytes = 0
    }

    private fun noTransaction(): Boolean = Files.notExists(File(staging, "applying.json").toPath(), LinkOption.NOFOLLOW_LINKS) &&
        Files.notExists(File(staging, "applying.json.bak").toPath(), LinkOption.NOFOLLOW_LINKS)

    private fun begin(): File {
        Files.createDirectories(staging.toPath())
        LibraryRestoreTransaction(paths.root, staging).recover()
        val stage = File(staging, "restore-${UUID.randomUUID()}")
        Files.createDirectories(File(stage, "incoming").toPath())
        return stage
    }

    private fun prepareCatalogue(root: File, cache: File) {
        val restored = AppPaths(root, cache)
        if (Files.notExists(restored.database.toPath(), LinkOption.NOFOLLOW_LINKS)) return // A catalogue lost on the source is rebuilt by the normal startup.
        val opened = CassiniDatabaseOpener(context, restored).open()
        val database = opened.database
        try {
            require(opened.setAside == null) { "Unsupported restored catalogue" }
            database.runInTransaction {
                val sql = database.openHelper.writableDatabase
                sql.execSQL("DELETE FROM jobs")
                sql.execSQL("UPDATE notes SET trashed_job = NULL")
                sql.execSQL("DELETE FROM variants WHERE note_id IN (SELECT id FROM notes WHERE audio_kind != 'DOCUMENT')")
                sql.execSQL("UPDATE note_text SET body = '' WHERE note_id IN (SELECT id FROM notes WHERE audio_kind != 'DOCUMENT')")
                sql.execSQL("UPDATE notes SET preview = NULL, selected_variant = NULL WHERE audio_kind != 'DOCUMENT'")
                sql.execSQL("DELETE FROM note_voice_links WHERE note_id IN (SELECT id FROM notes WHERE audio_kind != 'DOCUMENT')")
                sql.execSQL("UPDATE notes SET document_signature = NULL WHERE audio_kind = 'DOCUMENT'")
            }
            val documents = DocumentRepository(database, restored, CassiniDispatchers(), NoteLocks(), context.contentResolver)
            runBlocking {
                for (note in database.notes().all()) {
                    require(BackupManifest.admits("notes/${note.id}/${note.audioFile}")) { "Unsafe catalogue audio path" }
                    documents.refreshIfStale(note.id)
                }
            }
        } finally {
            database.close()
        }
        checkpoint(restored.database)
    }

    private fun checkpoint(file: File) {
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

    private companion object {
        const val SPACE_RESERVE = 8L * 1024 * 1024
    }
}
