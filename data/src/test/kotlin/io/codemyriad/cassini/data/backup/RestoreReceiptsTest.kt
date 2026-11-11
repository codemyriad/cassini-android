package io.codemyriad.cassini.data.backup

import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.db.offMain
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
class RestoreReceiptsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun truncatedDuplicateMissingAndOversizedReceiptRowsAreRefused() = offMain<Unit> {
        val stage = folder.newFolder()
        val incoming = File(stage, "incoming")
        Files.createDirectories(File(incoming, "notes/n").toPath())
        val audio = File(incoming, "notes/n/source.opus").also { it.writeText("Received audio") }
        val manifest = BackupManifest.create(incoming)
        val inventory = File(incoming, BackupManifest.MANIFEST)
        Files.createDirectories(inventory.parentFile!!.toPath())
        inventory.writeText(manifest.json())
        for ((path, file) in listOf(BackupManifest.MANIFEST to inventory, "notes/n/source.opus" to audio)) {
            RestoreReceipts.record(stage, BackupManifest.Entry(path, file.length(), BackupManifest.sha256(file)))
        }
        val receipts = File(stage, "receipts.tsv")
        val valid = receipts.readText()
        RestoreReceipts.verify(stage, manifest)
        for (damaged in listOf(valid.dropLast(1), valid + valid.lineSequence().first() + "\n", "", "x".repeat(513))) {
            receipts.writeText(damaged)
            assertThrows(IllegalArgumentException::class.java) { RestoreReceipts.verify(stage, manifest) }
        }
        Files.delete(receipts.toPath())
        val external = folder.newFile().also { it.writeText(valid) }
        Files.createSymbolicLink(receipts.toPath(), external.toPath())
        assertThrows(IllegalArgumentException::class.java) { RestoreReceipts.verify(stage, manifest) }
    }
}
