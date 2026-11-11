package io.codemyriad.cassini.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDiskIOException
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class CorruptCatalogueRestoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun aValidCataloguePassesTheIntegrityCheck() = runTest {
        val source = NotesHarness(folder)
        source.close()
        offMain { assertEquals(2, RestoredCatalogueValidation.version(source.paths.database)) }
    }

    @Test fun aFailingQuickCheckIsCorruptionEvenWhenSqliteOpensAndItsVersionIsSupported() = offMain {
        val file = folder.newFile("constraint.db")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.version = 2
            database.execSQL("CREATE TABLE sample(value TEXT CHECK(value != 'invalid'))")
            database.execSQL("PRAGMA ignore_check_constraints = ON")
            database.execSQL("INSERT INTO sample VALUES ('invalid')")
        }
        assertThrows(CorruptRestoreException::class.java) { RestoredCatalogueValidation.version(file) }
        Unit
    }

    @Test fun aValidHeaderDoesNotAdmitCorruptSqlitePages() = runTest {
        val source = NotesHarness(folder)
        source.close()
        RandomAccessFile(source.paths.database, "rw").use {
            it.seek(100) // Invalid b-tree page type, past the intact SQLite header.
            it.writeByte(0)
        }
        assertRejected(source.paths.root)
    }

    @Test fun aBadRoomIdentityAtTheSupportedVersionIsCorruptRatherThanNewer() = runTest {
        val source = NotesHarness(folder)
        try {
            source.db.openHelper.writableDatabase.execSQL(
                "UPDATE room_master_table SET identity_hash = 'not-the-schema'",
            )
        } finally {
            source.close()
        }
        assertRejected(source.paths.root)
    }

    @Test fun invalidNoteIdsRejectTheWholeCatalogue() = runTest { assertInvalidRow(true) }

    @Test fun invalidAudioPathsRejectTheWholeCatalogue() = runTest { assertInvalidRow(false) }

    private suspend fun assertInvalidRow(invalidId: Boolean) {
        val source = NotesHarness(folder)
        try {
            val id = if (invalidId) "not-a-uuid" else UUID.randomUUID().toString()
            source.plainNote(id)
            if (!invalidId) {
                source.db.openHelper.writableDatabase.execSQL("UPDATE notes SET audio_file = '../outside.opus'")
            }
        } finally {
            source.close()
        }
        assertRejected(source.paths.root)
    }

    @Test fun storageFailuresDuringCatalogueChecksStayRetryable() {
        val errors = listOf(SQLiteFullException("Synthetic full disk"), SQLiteDiskIOException("Synthetic I/O failure"))
        for (error in errors) {
            val actual = assertThrows(error.javaClass) {
                RestoredCatalogueValidation.checked<Unit> { throw error }
            }
            assertSame(error, actual)
        }
    }

    private fun assertRejected(source: File) = offMain {
        val paths = AppPaths(folder.newFolder(), folder.newFolder())
        val old = File(paths.root, "notes/old/source.opus")
        check(old.parentFile!!.mkdirs())
        old.writeText("Existing audio")
        val staging = folder.newFolder()
        val manifest = BackupManifest.create(source)
        val inventory = File(source, BackupManifest.MANIFEST)
        check(inventory.parentFile!!.mkdirs() || inventory.parentFile!!.isDirectory)
        inventory.writeText(manifest.json())
        val receiver = LibraryBackup(context, paths, staging)
        for (path in manifest.files.map { it.path } + BackupManifest.MANIFEST) {
            val file = File(source, path)
            file.inputStream().use { input ->
                receiver.receive(path, input, file.length(), file.lastModified() / 1000, Long.MAX_VALUE)
            }
        }
        receiver.finishRestore()
        assertNotNull(receiver.recoverRestore())
        assertEquals("Existing audio", old.readText())
        assertFalse(paths.database.exists())
        assertFalse(File(staging, "applying.json").exists())
        assertNull(receiver.recoverRestore())
    }
}
