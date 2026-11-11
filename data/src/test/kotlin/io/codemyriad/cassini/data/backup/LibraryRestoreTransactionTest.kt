package io.codemyriad.cassini.data.backup

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
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class LibraryRestoreTransactionTest {
    @get:Rule val folder = TemporaryFolder()
    private class PowerCut : Error()

    private fun put(root: File, path: String, text: String) {
        val file = File(root, path)
        Files.createDirectories(file.parentFile.toPath())
        file.writeText(text)
    }

    private fun seed(root: File, prefix: String, sidecars: Boolean = false) {
        put(root, "notes/n/document.opus", "$prefix note")
        put(root, "voices/people.json", "$prefix voice")
        put(root, "cassini.db", "$prefix catalogue")
        if (sidecars) {
            for (suffix in listOf("-wal", "-shm", "-journal")) put(root, "cassini.db$suffix", "$prefix $suffix")
        }
    }

    private fun stage(staging: File): File = File(staging, "restore-${UUID.randomUUID()}").also {
        seed(File(it, "incoming"), "new")
    }

    private fun assertState(root: File, prefix: String, sidecars: Boolean) {
        assertEquals("$prefix note", File(root, "notes/n/document.opus").readText())
        assertEquals("$prefix voice", File(root, "voices/people.json").readText())
        assertEquals("$prefix catalogue", File(root, "cassini.db").readText())
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            if (sidecars) assertEquals("$prefix $suffix", File(root, "cassini.db$suffix").readText())
            else assertFalse(File(root, "cassini.db$suffix").exists())
        }
        assertEquals("existing model", File(root, "models/weights.onnx").readText())
        assertEquals("existing setting", File(root, "settings.preferences_pb").readText())
    }

    @Test fun everyInterruptedMoveCanRollBackNotesCatalogueVoicesAndWalAsAUnit() {
        for (cut in 1..9) {
            val live = folder.newFolder()
            val staging = folder.newFolder()
            seed(live, "old", sidecars = true)
            put(live, "models/weights.onnx", "existing model")
            put(live, "settings.preferences_pb", "existing setting")
            val incoming = stage(staging)
            var moved = 0
            val transaction = LibraryRestoreTransaction(live, staging, { if (++moved == cut) throw PowerCut() }, {})
            offMain { assertThrows(PowerCut::class.java) { transaction.install(incoming) } }
            offMain { LibraryRestoreTransaction(live, staging).recover() }
            assertState(live, "old", sidecars = true)
            assertFalse(File(staging, "applying.json").exists())
        }
    }

    @Test fun anIoFailureRollsBackImmediatelyAndCommitSurvivesAColdStart() {
        val live = folder.newFolder()
        val staging = folder.newFolder()
        seed(live, "old", sidecars = true)
        put(live, "models/weights.onnx", "existing model")
        put(live, "settings.preferences_pb", "existing setting")
        var moved = 0
        val transaction = LibraryRestoreTransaction(live, staging, { if (++moved == 3) throw IOException("injected") }, {})
        val incomplete = stage(staging)
        offMain { assertThrows(IOException::class.java) { transaction.install(incomplete) } }
        assertState(live, "old", sidecars = true)
        val complete = stage(staging)
        val committed = LibraryRestoreTransaction(live, staging, {}, { throw PowerCut() })
        offMain { assertThrows(PowerCut::class.java) { committed.install(complete) } }
        offMain { LibraryRestoreTransaction(live, staging).recover() }
        assertState(live, "new", sidecars = false)
    }

    @Test fun aRecoveryFailureKeepsTheOriginalIoErrorAndRetainsTheJournalForAColdStart() {
        val live = folder.newFolder()
        val staging = folder.newFolder()
        seed(live, "old", sidecars = true)
        put(live, "models/weights.onnx", "existing model")
        put(live, "settings.preferences_pb", "existing setting")
        val journal = File(staging, "applying.json")
        val original = IOException("Synthetic write failure")
        var intactJournal = ""
        val transaction = LibraryRestoreTransaction(live, staging, {
            intactJournal = journal.readText()
            journal.writeText("{}")
            throw original
        }, {})
        val stage = stage(staging)
        val caught = offMain { assertThrows(IOException::class.java) { transaction.install(stage) } }
        org.junit.Assert.assertSame(original, caught)
        assertEquals(1, caught.suppressed.size)
        assertEquals("{}", journal.readText())
        assertEquals("old note", File(stage, "old/notes/n/document.opus").readText())
        journal.writeText(intactJournal)
        offMain { LibraryRestoreTransaction(live, staging).recover() }
        assertState(live, "old", sidecars = true)
    }

    @Test fun aDamagedJournalNeverMovesUserFilesAndAnEmptyDestinationRollsBackToEmpty() {
        val live = folder.newFolder()
        val staging = folder.newFolder()
        put(live, "models/weights.onnx", "existing model")
        val incoming = stage(staging)
        val transaction = LibraryRestoreTransaction(live, staging, { throw PowerCut() }, {})
        offMain { assertThrows(PowerCut::class.java) { transaction.install(incoming) } }
        val journal = File(staging, "applying.json")
        val valid = journal.readText()
        val wrapper = JSONObject(valid).put("payload", "{}")
        journal.writeText(wrapper.toString())
        offMain { assertThrows(IllegalArgumentException::class.java) { LibraryRestoreTransaction(live, staging).recover() } }
        assertEquals("new note", File(live, "notes/n/document.opus").readText())
        journal.writeText(valid)
        offMain { LibraryRestoreTransaction(live, staging).recover() }
        assertFalse(File(live, "notes").exists())
        assertFalse(File(live, "voices").exists())
        assertFalse(File(live, "cassini.db").exists())
        assertEquals("existing model", File(live, "models/weights.onnx").readText())
    }
}
