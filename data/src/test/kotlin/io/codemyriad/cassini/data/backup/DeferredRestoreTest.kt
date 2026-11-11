package io.codemyriad.cassini.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.core.library.JobKind
import io.codemyriad.cassini.core.library.JobState
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.db.JobEntity
import io.codemyriad.cassini.data.db.offMain
import io.codemyriad.cassini.data.notes.NotesHarness
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class DeferredRestoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private class PowerCut : Error()

    private fun put(root: File, path: String, text: String): File = File(root, path).also {
        Files.createDirectories(it.parentFile!!.toPath())
        it.writeText(text)
    }

    private fun receive(source: File, target: AppPaths, staging: File): LibraryBackup {
        val manifest = BackupManifest.create(source)
        put(source, BackupManifest.MANIFEST, manifest.json())
        return LibraryBackup(context, target, staging).also { receiver ->
            for (path in listOf(BackupManifest.MANIFEST) + manifest.files.map { it.path }) {
                val file = File(source, path)
                file.inputStream().use {
                    receiver.receive(path, it, file.length(), file.lastModified(), Long.MAX_VALUE)
                }
            }
        }
    }

    @Test fun preparationCopiesOnlyTheCatalogueAndReadsReceivedAudioInPlaceWithoutLinks() = offMain {
        val stage = folder.newFolder()
        val incoming = File(stage, "incoming")
        val database = put(incoming, "cassini.db", "Received catalogue")
        val audio = put(incoming, "notes/n/document.opus", "Received audio")
        put(incoming, "voices/people.json", "Received voices")
        val prepared = RestorePreparation.create(stage)
        assertEquals(incoming, prepared.root)
        assertEquals(File(stage, "prepared/cassini.db"), prepared.database)
        assertTrue(Files.isSameFile(audio.toPath(), File(prepared.note("n"), "document.opus").toPath()))
        assertFalse(File(stage, "prepared/notes").exists())
        assertFalse(File(stage, "prepared/voices").exists())
        prepared.database.writeText("Repaired catalogue")
        assertEquals("Received catalogue", database.readText())
        assertEquals("Received audio", audio.readText())
    }

    @Test fun finalizationOnlyCommitsAPointerEvenWhenPayloadsAndReceiptsCannotBeScanned() = offMain {
        val source = folder.newFolder()
        put(source, "notes/n/source.opus", "Received audio")
        val target = AppPaths(folder.newFolder(), folder.newFolder())
        val old = put(target.root, "notes/old/document.opus", "Existing note")
        val staging = folder.newFolder()
        val orphan = File(staging, "restore-${UUID.randomUUID()}")
        val orphanFile = put(orphan, "incoming/notes/orphan/source.opus", "Incomplete earlier transfer")
        val receiver = receive(source, target, staging)
        val stage = staging.listFiles()!!.single { it != orphan }
        val incoming = File(stage, "incoming")
        val held = File(stage, "withheld")
        Files.move(incoming.toPath(), held.toPath())
        val receipts = File(stage, "receipts.tsv")
        val heldReceipts = File(stage, "withheld-receipts")
        Files.move(receipts.toPath(), heldReceipts.toPath())

        receiver.finishRestore() // No manifest read, payload/hash scan, catalogue rebuild or recursive cleanup.

        assertEquals("Existing note", old.readText())
        assertFalse(File(stage, "prepared").exists())
        assertTrue(orphanFile.isFile)
        assertTrue(File(staging, "applying.json").length() in 1..4096)
        val startup = LibraryBackup(context, target, staging)
        assertThrows(IllegalArgumentException::class.java) { startup.recoverRestore() }
        assertEquals("Existing note", old.readText())
        Files.move(held.toPath(), incoming.toPath())
        Files.move(heldReceipts.toPath(), receipts.toPath())
        startup.recoverRestore()
        assertEquals("Received audio", File(target.root, "notes/n/source.opus").readText())
        assertFalse(old.exists())
        assertFalse(orphan.exists())
        assertFalse(File(staging, "applying.json").exists())
    }

    @Test fun receivedHashesAndPayloadValidationAreDeferredAndRefuseChangesWithoutReplacingLiveData() = offMain {
        val source = folder.newFolder()
        put(source, "notes/n/source.opus", "Received audio")
        for (tamperReceipt in listOf(false, true)) {
            val target = AppPaths(folder.newFolder(), folder.newFolder())
            val old = put(target.root, "notes/old/document.opus", "Existing note")
            val staging = folder.newFolder()
            val receiver = receive(source, target, staging)
            val stage = staging.listFiles()!!.single()
            val receipts = File(stage, "receipts.tsv")
            val original = receipts.readText()
            val hash = BackupManifest.sha256(File(source, "notes/n/source.opus"))
            assertTrue(original.contains("notes/n/source.opus\t14\t$hash\n"))
            if (tamperReceipt) {
                receipts.writeText(original.replace(hash, "0".repeat(64)))
            } else {
                File(stage, "incoming/notes/n/source.opus").writeText("Changed audio!")
            }
            receiver.finishRestore()
            assertThrows(IllegalArgumentException::class.java) {
                LibraryBackup(context, target, staging).recoverRestore()
            }
            assertEquals("Existing note", old.readText())
            assertTrue(File(staging, "applying.json").isFile)
        }
    }

    @Test fun finalizationDoesNotOpenOrRebuildTheReceivedCatalogue() = offMain {
        val source = folder.newFolder()
        put(source, "cassini.db", "Unsupported received catalogue")
        put(source, "notes/n/document.opus", "Unparsed received document")
        val target = AppPaths(folder.newFolder(), folder.newFolder())
        val old = put(target.root, "notes/old/document.opus", "Existing note")
        val staging = folder.newFolder()
        val receiver = receive(source, target, staging)
        val stage = staging.listFiles()!!.single()
        receiver.finishRestore()
        assertFalse(File(stage, "prepared").exists())
        assertEquals("Unsupported received catalogue", File(stage, "incoming/cassini.db").readText())
        assertEquals("Existing note", old.readText())
        assertThrows(IllegalArgumentException::class.java) { LibraryBackup(context, target, staging).recoverRestore() }
        assertEquals("Existing note", old.readText())
        assertFalse(target.database.exists())
        assertEquals("Unsupported received catalogue", File(stage, "incoming/cassini.db").readText())
    }

    @Test fun deferredPromotionSurvivesEveryInterruptedMoveAndRetainsTheUnmodifiedReceivedCatalogue() = runTest {
        val source = NotesHarness(folder)
        try {
            val id = source.notes.createRecording("Received note", 1_700_000_000_000, "opus")
            put(source.paths.root, "notes/$id/capture.opus", "Received audio")
            put(source.paths.root, "voices/people.json", "Received voices")
            source.db.jobs().insert(
                JobEntity(id, 1, JobKind.TRANSCRIBE_AGAIN, JobState.QUEUED, enqueuedAt = 1, updatedAt = 1),
            )
            source.close()
            offMain {
                for (cut in 1..9) {
                    val target = AppPaths(folder.newFolder(), folder.newFolder())
                    put(target.root, "notes/old/document.opus", "Existing note")
                    put(target.root, "voices/people.json", "Existing voices")
                    put(target.root, "cassini.db", "Existing catalogue")
                    for (suffix in listOf("-wal", "-shm", "-journal")) {
                        put(target.root, "cassini.db$suffix", "Old sidecar")
                    }
                    val staging = folder.newFolder()
                    receive(source.paths.root, target, staging).finishRestore()
                    val stage = staging.listFiles()!!.single { it.isDirectory }
                    val incoming = File(stage, "incoming")
                    val originalDatabase = BackupManifest.sha256(File(incoming, "cassini.db"))
                    // Partial catalogue work from an earlier interrupted start is disposable.
                    put(stage, "prepared/cassini.db", "Interrupted cache rebuild")
                    var moved = 0
                    val transaction = LibraryRestoreTransaction(
                        target.root,
                        staging,
                        { if (++moved == cut) throw PowerCut() },
                        {},
                    )
                    val startup = LibraryBackup(context, target, staging)
                    assertThrows(PowerCut::class.java) { startup.recoverRestore(transaction) }
                    assertEquals(cut, moved)
                    assertEquals(originalDatabase, BackupManifest.sha256(File(incoming, "cassini.db")))
                    LibraryBackup(context, target, staging).recoverRestore()
                    assertEquals("Received audio", File(target.root, "notes/$id/capture.opus").readText())
                    assertEquals("Received voices", target.people.readText())
                    assertFalse(File(target.root, "notes/old").exists())
                    assertFalse(File(staging, "applying.json").exists())
                    assertFalse(stage.exists())
                }
            }
        } finally {
            source.close()
        }
    }

    @Test fun committedDeferredPromotionSurvivesAnInterruptionBeforeCleanup() = offMain {
        val source = folder.newFolder()
        put(source, "notes/n/source.opus", "Received audio")
        val target = AppPaths(folder.newFolder(), folder.newFolder())
        put(target.root, "notes/old/document.opus", "Existing note")
        val staging = folder.newFolder()
        receive(source, target, staging).finishRestore()
        val transaction = LibraryRestoreTransaction(target.root, staging, {}, { throw PowerCut() })
        assertThrows(PowerCut::class.java) { LibraryBackup(context, target, staging).recoverRestore(transaction) }
        LibraryBackup(context, target, staging).recoverRestore()
        assertEquals("Received audio", File(target.root, "notes/n/source.opus").readText())
        assertFalse(File(target.root, "notes/old").exists())
        assertFalse(File(staging, "applying.json").exists())
    }

    @Test fun backupRefusesACompletedRestoreUntilStartupHasPublishedIt() = offMain {
        val source = folder.newFolder()
        put(source, "notes/n/source.opus", "Received audio")
        val target = AppPaths(folder.newFolder(), folder.newFolder())
        val old = put(target.root, "notes/old/document.opus", "Existing note")
        val staging = folder.newFolder()
        receive(source, target, staging).finishRestore()
        assertThrows(IOException::class.java) { LibraryBackup(context, target, staging).prepare() }
        assertEquals("Existing note", old.readText())
        assertFalse(File(target.root, BackupManifest.MANIFEST).exists())
        LibraryBackup(context, target, staging).recoverRestore()
        val snapshot = LibraryBackup(context, target, staging).prepare().first
        assertEquals(listOf("notes/n/source.opus"), snapshot.files.map { it.path })
    }
}
