package io.codemyriad.cassini.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.codemyriad.cassini.core.backup.BackupManifest
import io.codemyriad.cassini.data.AppPaths
import io.codemyriad.cassini.data.db.offMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class RestoreTimestampTest {
    @get:Rule val folder = TemporaryFolder()
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun tarSecondsPreserveRecordingTimesAndNegativeTimesClampToTheEpoch() = offMain {
        val seconds = Instant.parse("2026-11-11T12:00:00Z").epochSecond
        for (mtime in listOf(seconds, -1L)) {
            val source = folder.newFolder()
            val file = File(source, "notes/n/capture.opus")
            Files.createDirectories(file.parentFile!!.toPath())
            file.writeText("Synthetic audio")
            val manifest = BackupManifest.create(source).json().toByteArray()
            val paths = AppPaths(folder.newFolder(), folder.newFolder())
            val receiver = LibraryBackup(context, paths, folder.newFolder())
            file.inputStream().use {
                receiver.receive("notes/n/capture.opus", it, file.length(), mtime, Long.MAX_VALUE)
            }
            receiver.receive(
                BackupManifest.MANIFEST,
                ByteArrayInputStream(manifest),
                manifest.size.toLong(),
                mtime,
                Long.MAX_VALUE,
            )
            receiver.finishRestore()
            receiver.recoverRestore()
            assertEquals(mtime.coerceAtLeast(0) * 1000, File(paths.root, "notes/n/capture.opus").lastModified())
        }
    }
}
