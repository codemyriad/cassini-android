package io.codemyriad.cassini.data.backup

import android.content.Context
import android.database.sqlite.SQLiteFullException
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.db.offMain
import io.codemyriad.cassini.data.notes.NotesHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class RestoreRetryTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun put(root: File, path: String, value: String) = File(root, path).also {
        Files.createDirectories(it.parentFile!!.toPath())
        it.writeText(value)
    }

    private fun receive(source: File, target: AppPaths, staging: File) {
        val manifest = BackupManifest.create(source)
        put(source, BackupManifest.MANIFEST, manifest.json())
        val receiver = LibraryBackup(context, target, staging)
        for (path in manifest.files.map { it.path } + BackupManifest.MANIFEST) {
            val file = File(source, path)
            file.inputStream().use {
                receiver.receive(path, it, file.length(), file.lastModified() / 1000, Long.MAX_VALUE)
            }
        }
        receiver.finishRestore()
    }

    @Test fun aNewRestoreCanSupersedeAnUnappliedReceivedTransaction() = offMain {
        val paths = AppPaths(folder.newFolder(), folder.newFolder())
        val staging = folder.newFolder()
        val first = folder.newFolder()
        put(first, "notes/n/source.opus", "First received audio")
        receive(first, paths, staging)
        val firstStage = staging.listFiles()!!.single { it.isDirectory }
        val second = folder.newFolder()
        put(second, "notes/m/source.opus", "Second received audio")
        receive(second, paths, staging)
        assertTrue(firstStage.exists()) // No library-sized cleanup in the OS callback.
        assertNull(LibraryBackup(context, paths, staging).recoverRestore())
        assertEquals("Second received audio", File(paths.root, "notes/m/source.opus").readText())
        assertFalse(File(paths.root, "notes/n").exists())
        assertFalse(firstStage.exists())
    }

    @Test fun storageFailuresKeepTheReceivedTransactionForAColdStartRetry() = offMain {
        for (error in listOf(IOException("Synthetic storage error"), SQLiteFullException("Synthetic full storage"))) {
            val paths = AppPaths(folder.newFolder(), folder.newFolder())
            val old = put(paths.root, "notes/old/source.opus", "Existing audio")
            val staging = folder.newFolder()
            val source = folder.newFolder()
            put(source, "notes/n/source.opus", "Received audio")
            receive(source, paths, staging)
            val transaction = LibraryRestoreTransaction(paths.root, staging)
            assertThrows(error.javaClass) {
                LibraryBackup(context, paths, staging).recoverRestore(transaction) { throw error }
            }
            assertEquals("Existing audio", old.readText())
            assertNotNull(transaction.receivedStage)
            assertTrue(File(staging, "applying.json").exists())
            assertNull(LibraryBackup(context, paths, staging).recoverRestore())
            assertEquals("Received audio", File(paths.root, "notes/n/source.opus").readText())
        }
    }

    @Test fun anUnrecoveredApplyingTransactionCannotBeSuperseded() = offMain {
        class PowerCut : Error()
        val paths = AppPaths(folder.newFolder(), folder.newFolder())
        put(paths.root, "notes/old/source.opus", "Existing audio")
        val staging = folder.newFolder()
        val stage = File(staging, "restore-${UUID.randomUUID()}")
        put(File(stage, "incoming"), "notes/n/source.opus", "Received audio")
        val transaction = LibraryRestoreTransaction(paths.root, staging, { throw PowerCut() }, {})
        assertThrows(PowerCut::class.java) { transaction.install(stage) }
        val journal = File(staging, "applying.json")
        val before = journal.readText()
        val source = folder.newFolder()
        put(source, "notes/m/source.opus", "A later transfer")
        assertThrows(IllegalArgumentException::class.java) { receive(source, paths, staging) }
        assertEquals(before, journal.readText())
        transaction.recover()
        assertEquals("Existing audio", File(paths.root, "notes/old/source.opus").readText())
    }

    @Test fun aNewerRoomSchemaRemainsReceivedAndCanBeSupersededByACompatibleTransfer() = runTest {
        val source = NotesHarness(folder)
        try {
            source.db.openHelper.writableDatabase.execSQL("PRAGMA user_version = 999")
            source.close()
            val paths = AppPaths(folder.newFolder(), folder.newFolder())
            val old = put(paths.root, "notes/old/source.opus", "Existing audio")
            val staging = folder.newFolder()
            offMain {
                receive(source.paths.root, paths, staging)
                assertThrows(IllegalArgumentException::class.java) {
                    LibraryBackup(context, paths, staging).recoverRestore()
                }
                assertNotNull(LibraryRestoreTransaction(paths.root, staging).receivedStage)
                assertEquals("Existing audio", old.readText())
                val compatible = folder.newFolder()
                put(compatible, "notes/n/source.opus", "Compatible received audio")
                receive(compatible, paths, staging)
                assertNull(LibraryBackup(context, paths, staging).recoverRestore())
                assertEquals("Compatible received audio", File(paths.root, "notes/n/source.opus").readText())
            }
        } finally {
            source.close()
        }
    }
}
