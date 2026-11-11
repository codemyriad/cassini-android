package io.codemyriad.cassini.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.db.offMain
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class BackupPreparationRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private class PowerCut : Error()

    private fun seed(root: File, value: String, sidecars: Boolean = false) {
        val note = File(root, "notes/n/document.opus")
        Files.createDirectories(note.parentFile!!.toPath())
        note.writeText("$value note")
        val voice = File(root, "voices/people.json")
        Files.createDirectories(voice.parentFile!!.toPath())
        voice.writeText("$value voice")
        offMain {
            SQLiteDatabase.openOrCreateDatabase(File(root, "cassini.db"), null).use {
                it.execSQL("CREATE TABLE marker(value TEXT NOT NULL)")
                it.execSQL("INSERT INTO marker(value) VALUES (?)", arrayOf(value))
            }
        }
        if (sidecars) {
            for (suffix in listOf("-wal", "-shm", "-journal")) File(root, "cassini.db$suffix").writeBytes(byteArrayOf())
        }
    }

    private fun stage(staging: File): File = File(staging, "restore-${UUID.randomUUID()}").also {
        seed(File(it, "incoming"), "new")
    }

    private fun prepareAndAssert(paths: AppPaths, staging: File, value: String) {
        val manifest = offMain { LibraryBackup(context, paths, staging).prepare().first }
        assertEquals("$value note", File(paths.root, "notes/n/document.opus").readText())
        assertEquals("$value voice", paths.people.readText())
        assertEquals(
            BackupManifest.sha256(File(paths.root, "notes/n/document.opus")),
            manifest.files.single { it.path == "notes/n/document.opus" }.sha256,
        )
        offMain {
            SQLiteDatabase.openDatabase(paths.database.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("SELECT value FROM marker", null).use {
                    org.junit.Assert.assertTrue(it.moveToFirst())
                    assertEquals(value, it.getString(0))
                }
            }
        }
        assertFalse(File(staging, "applying.json").exists())
    }

    @Test fun backupImmediatelyAfterEveryInterruptedRestoreMoveRecoversTheWholeOriginalLibraryFirst() {
        for (cut in 1..9) {
            val paths = AppPaths(folder.newFolder(), folder.newFolder())
            val staging = folder.newFolder()
            seed(paths.root, "old", sidecars = true)
            val incoming = stage(staging)
            var moved = 0
            val transaction = LibraryRestoreTransaction(
                paths.root,
                staging,
                { if (++moved == cut) throw PowerCut() },
                {},
            )
            offMain { assertThrows(PowerCut::class.java) { transaction.install(incoming) } }
            prepareAndAssert(paths, staging, "old")
        }
    }

    @Test fun backupAfterAnInterruptedCommittedRestoreKeepsTheCompleteReplacement() {
        val paths = AppPaths(folder.newFolder(), folder.newFolder())
        val staging = folder.newFolder()
        seed(paths.root, "old", sidecars = true)
        val incoming = stage(staging)
        val transaction = LibraryRestoreTransaction(paths.root, staging, {}, { throw PowerCut() })
        offMain { assertThrows(PowerCut::class.java) { transaction.install(incoming) } }
        prepareAndAssert(paths, staging, "new")
    }

    @Test fun failedJournalRecoveryRefusesTheSnapshotBeforePublishingAnyInventory() {
        val paths = AppPaths(folder.newFolder(), folder.newFolder())
        val staging = folder.newFolder()
        seed(paths.root, "old")
        val incoming = stage(staging)
        val transaction = LibraryRestoreTransaction(paths.root, staging, { throw PowerCut() }, {})
        offMain { assertThrows(PowerCut::class.java) { transaction.install(incoming) } }
        val journal = File(staging, "applying.json")
        journal.writeText(JSONObject(journal.readText()).put("payload", "{}").toString())
        val error = offMain {
            assertThrows(IllegalArgumentException::class.java) { LibraryBackup(context, paths, staging).prepare() }
        }
        assertEquals("Damaged restore journal", error.message)
        assertFalse(File(paths.root, BackupManifest.MANIFEST).exists())
        assertEquals("old note", File(incoming, "old/notes/n/document.opus").readText())
    }
}
