package io.codemyriad.cassini.data.backup

import android.content.Context
import android.util.AtomicFile
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.Threads
import io.codemyriad.cassini.data.notes.NoteFiles
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.FileTime
import java.security.MessageDigest
import java.util.UUID

/** Binder-thread backup/restore work on a quiescent app. It never creates the graph or starts a job. */
class LibraryBackup(context: Context, private val paths: AppPaths, private val staging: File) {
    private var receiving: File? = null
    private var received = HashSet<String>()
    private val catalogue = BackupCatalogue(context)

    /** XML and this allowlist both omit settings, model receipts/weights, and unfinished inference work. */
    fun prepare(): Pair<BackupManifest, File> {
        Threads.assertOffMain("LibraryBackup.prepare")
        val transaction = LibraryRestoreTransaction(paths.root, staging)
        transaction.recover()
        if (transaction.receivedStage() != null) {
            throw IOException("Open Cassini to finish restoring the library before backup")
        }
        BackupVoiceRecovery.recover(paths.root)
        catalogue.checkpoint(paths.database)
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
        val digest = MessageDigest.getInstance("SHA-256")
        FileOutputStream(destination).use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = size
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count < 0) throw IOException("Truncated restore file")
                output.write(buffer, 0, count)
                digest.update(buffer, 0, count)
                remaining -= count
            }
            output.fd.sync()
        }
        Files.setLastModifiedTime(destination.toPath(), FileTime.fromMillis(mtime))
        NoteFiles.syncFile(destination)
        var directory = destination.parentFile!!
        while (directory != root) {
            NoteFiles.syncDirectory(directory)
            directory = directory.parentFile!!
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        RestoreReceipts.record(root, BackupManifest.Entry(path, size, hash))
    }

    /** The deadline-bound OS callback only commits a constant-size pointer. Startup owns all library-wide work. */
    fun finishRestore() {
        Threads.assertOffMain("LibraryBackup.finishRestore")
        val stage = receiving ?: throw IOException("No library was received")
        require(BackupManifest.MANIFEST in received) { "No backup inventory was received" }
        LibraryRestoreTransaction(paths.root, staging).complete(stage)
        abandon()
    }

    /** Incomplete payload cleanup is startup work too, never an OS error/finalization callback. */
    fun abandon() {
        Threads.assertOffMain("LibraryBackup.abandon")
        receiving = null
        received = HashSet()
    }

    /** Must finish before opening the live Room database or making the Library visible. */
    fun recoverRestore() = recoverRestore(LibraryRestoreTransaction(paths.root, staging))

    internal fun recoverRestore(transaction: LibraryRestoreTransaction) {
        Threads.assertOffMain("LibraryBackup.recoverRestore")
        transaction.recover()
        val stage = transaction.receivedStage() ?: return
        val incoming = File(stage, "incoming")
        require(Files.isDirectory(incoming.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Unsafe restore root" }
        val manifest = BackupManifest.read(File(incoming, BackupManifest.MANIFEST))
        RestoreReceipts.verify(stage, manifest)
        manifest.verify(incoming)
        val prepared = RestorePreparation.create(stage, manifest)
        catalogue.prepare(prepared, File(stage, "cache"))
        transaction.install(stage)
        transaction.recover()
    }

    private fun begin(): File {
        Files.createDirectories(staging.toPath())
        LibraryRestoreTransaction(paths.root, staging).requireIdle()
        val stage = File(staging, "restore-${UUID.randomUUID()}")
        Files.createDirectories(File(stage, "incoming").toPath())
        NoteFiles.syncDirectory(stage)
        NoteFiles.syncDirectory(staging)
        return stage
    }

    private companion object {
        const val SPACE_RESERVE = 8L * 1024 * 1024
    }
}
