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

    /** Whether the last [onFullBackup] went to the cloud; null before one ran on this instance. */
    private var cloudRun: Boolean? = null

    override fun onFullBackup(data: FullBackupDataOutput) {
        requireQuiescent()
        val api = Build.VERSION.SDK_INT
        val flags = if (api >= BackupChoices.TRANSPORT_FLAGS_API) data.transportFlags else 0
        val current = choices.readBlocking()
        if (!current.permits(api, flags, BuildConfig.CLOUD_BACKUP_ENABLED)) {
            // Never emit library bytes. Binder exceptions do not guarantee transport failure.
            throw IOException("This backup transport is disabled")
        }
        val cloud = !BackupChoices.isTransfer(api, flags)
        cloudRun = cloud
        // "Include saved voices in backups" off leaves every voiceprint out of both channels (D98).
        val (manifest, inventory) = library.prepare(includeVoices = current.voices)
        // Let fullBackupFile account for every tar entry in the platform's measure-only preflight.
        runBlocking(Dispatchers.IO) { choices.recordQuotaExceeded(false, cloud) }
        for (entry in manifest.files) fullBackupFile(File(filesDir, entry.path), data)
        // Completion metadata is last; an interrupted stream cannot claim a complete dataset.
        fullBackupFile(inventory, data)
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
            // Restore is authorized by Android, independently of this new device's own backup choices.
            library.finishRestore()
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            abandon(error)
            throw IOException("Incomplete library restore refused", error)
        }
    }

    override fun onQuotaExceeded(backupDataBytes: Long, quotaBytes: Long) {
        // Android calls this on the agent that measured the dataset; without a measured run, warn on both channels.
        val cloud = cloudRun
        runBlocking(Dispatchers.IO) {
            if (cloud != false) choices.recordQuotaExceeded(true, cloud = true)
            if (cloud != true) choices.recordQuotaExceeded(true, cloud = false)
        }
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
