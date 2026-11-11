package io.codemyriad.cassini.backup

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import io.codemyriad.cassini.AppRoots
import io.codemyriad.cassini.BuildConfig
import io.codemyriad.cassini.CassiniApplication
import io.codemyriad.cassini.core.backup.BackupChoices
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.backup.BackupChoicesStore
import io.codemyriad.cassini.data.backup.LibraryBackup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException

/** OS-only entry point. No graph, services, recognizers or model downloads run during backup or restore. */
class CassiniBackupAgent : BackupAgent() {
    private val choices by lazy { BackupChoicesStore(File(noBackupFilesDir, "choices.json"), Dispatchers.IO) }
    private val library by lazy {
        val paths = AppRoots.resolve(filesDir, cacheDir, BuildConfig.DEBUG)
        check(paths.root.canonicalFile == filesDir.canonicalFile) { "Backup cannot use an external debug root" }
        LibraryBackup(this, paths, File(noBackupFilesDir, "restores"))
    }

    override fun onFullBackup(data: FullBackupDataOutput) {
        requireQuiescent()
        val flags = if (Build.VERSION.SDK_INT >= BackupChoices.TRANSPORT_FLAGS_API) data.transportFlags else 0
        if (!choices.readBlocking().permits(Build.VERSION.SDK_INT, flags)) {
            // Refuse before emitting library bytes; a successful empty archive could replace earlier data.
            throw IOException("This backup transport is disabled")
        }
        val (manifest, inventory) = library.prepare()
        fullBackupFile(inventory, data)
        for (entry in manifest.files) fullBackupFile(File(filesDir, entry.path), data)
    }

    override fun onRestoreFile(data: ParcelFileDescriptor, size: Long, destination: File, type: Int, mode: Long, mtime: Long) {
        requireQuiescent()
        try {
            val root = filesDir.canonicalFile.toPath()
            val file = destination.canonicalFile.toPath()
            require(file.startsWith(root)) { "Restore destination is outside the library" }
            val path = root.relativize(file).toString().replace(File.separatorChar, '/')
            when (type) {
                TYPE_DIRECTORY -> require(
                    size == 0L && BackupManifest.directory(path)
                ) { "Unexpected restore directory" }
                TYPE_FILE -> ParcelFileDescriptor.AutoCloseInputStream(
                    ParcelFileDescriptor.dup(data.fileDescriptor)
                ).use { input ->
                    library.receive(path, input, size, mtime, noBackupFilesDir.usableSpace)
                }
                else -> throw IOException("Unexpected restore entry type")
            }
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            abandon(error)
            throw IOException("Library restore entry refused", error)
        }
    }

    override fun onRestoreFinished() {
        requireQuiescent()
        try {
            // Restore is authorized by Android, independently of this new device's default-off cloud choice.
            library.finishRestore()
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            abandon(error)
            throw IOException("Incomplete library restore refused", error)
        }
    }

    override fun onQuotaExceeded(backupDataBytes: Long, quotaBytes: Long) {
        runBlocking(Dispatchers.IO) { choices.recordQuotaExceeded(true) }
    }

    override fun onBackup(oldState: ParcelFileDescriptor?, data: BackupDataOutput, newState: ParcelFileDescriptor) {
        throw IOException("Key-value backup is unsupported")
    }

    override fun onRestore(data: BackupDataInput, appVersionCode: Int, newState: ParcelFileDescriptor) {
        throw IOException("Key-value restore is unsupported")
    }

    private fun requireQuiescent() {
        // Full backup/restore can use the platform's base Application, without our Application.onCreate.
        val app = applicationContext
        if (app is CassiniApplication && app.hasStartedGraph()) throw IOException("Library is in use")
    }

    private fun abandon(original: Exception) {
        try {
            library.abandon()
        } catch (@Suppress("TooGenericExceptionCaught") cleanup: Exception) {
            original.addSuppressed(cleanup)
        }
    }
}
