package io.codemyriad.cassini.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.db.offMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class RestoreRefusalTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun aRefusedEntryLatchesFailureEvenIfBinderContinuesWithTheRemainingFilesAndFinish() = offMain {
        for (noSpace in listOf(false, true)) {
            val paths = AppPaths(folder.newFolder(), folder.newFolder())
            val staging = folder.newFolder()
            val receiver = LibraryBackup(context, paths, staging)
            assertThrows(IOException::class.java) {
                val input = ByteArrayInputStream(byteArrayOf(1))
                receiver.receive("notes/n/source.opus", input, 2, 1, if (noSpace) 0 else Long.MAX_VALUE)
            }
            val entries = staging.walkTopDown().map { it.relativeTo(staging).path }.toList()
            val inventory = BackupManifest(emptyList()).json().toByteArray()
            val unread = ByteArrayInputStream(inventory)
            assertThrows(IllegalStateException::class.java) {
                receiver.receive(BackupManifest.MANIFEST, unread, inventory.size.toLong(), 1, Long.MAX_VALUE)
            }
            assertEquals(inventory.size, unread.available())
            assertThrows(IllegalStateException::class.java) { receiver.finishRestore() }
            assertEquals(entries, staging.walkTopDown().map { it.relativeTo(staging).path }.toList())
            assertFalse(File(staging, "applying.json").exists())
        }
    }

    @Test fun finalizationRefusesAMissingPayloadInConstantSpaceEvenWhenTheManifestArrived() = offMain {
        val paths = AppPaths(folder.newFolder(), folder.newFolder())
        val staging = folder.newFolder()
        val receiver = LibraryBackup(context, paths, staging)
        val manifest = BackupManifest(listOf(BackupManifest.Entry("notes/n/source.opus", 1, "0".repeat(64))))
            .json().toByteArray()
        receiver.receive(
            BackupManifest.MANIFEST,
            ByteArrayInputStream(manifest),
            manifest.size.toLong(),
            1,
            Long.MAX_VALUE,
        )
        assertThrows(IllegalArgumentException::class.java) { receiver.finishRestore() }
        assertFalse(File(staging, "applying.json").exists())
    }
}
